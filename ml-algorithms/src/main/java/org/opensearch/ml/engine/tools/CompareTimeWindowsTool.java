/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.tools;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.ml.common.spi.tools.Tool;
import org.opensearch.ml.common.spi.tools.ToolAnnotation;
import org.opensearch.search.aggregations.AggregationBuilders;
import org.opensearch.search.aggregations.metrics.ExtendedStats;
import org.opensearch.search.aggregations.metrics.ExtendedStatsAggregationBuilder;
import org.opensearch.search.aggregations.metrics.Percentiles;
import org.opensearch.search.aggregations.metrics.PercentilesAggregationBuilder;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.transport.client.Client;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * Tool for comparing metrics between two time windows
 *
 * Critical for RCA to identify what changed between normal and problem periods
 */
@Getter
@Setter
@Log4j2
@ToolAnnotation(CompareTimeWindowsTool.TYPE)
public class CompareTimeWindowsTool implements Tool {

    public static final String TYPE = "CompareTimeWindowsTool";

    private static final String DEFAULT_DESCRIPTION =
        "Compares metrics between problem window and baseline window to identify what changed. "
            + "Use this when you know WHEN a problem occurred and want to find WHAT changed. "
            + "\n\nInput parameters:"
            + "\n- index (required): Index pattern with metrics (e.g., 'metrics-*')"
            + "\n- field (required): Numeric field to compare (e.g., 'response_time', 'cpu_percent')"
            + "\n- problem_start (required): Problem window start time (ISO-8601 or epoch millis)"
            + "\n- problem_end (required): Problem window end time (ISO-8601 or epoch millis)"
            + "\n- baseline_start (required): Baseline window start time (ISO-8601 or epoch millis)"
            + "\n- baseline_end (required): Baseline window end time (ISO-8601 or epoch millis)"
            + "\n- timestamp_field (optional): Timestamp field name (default: auto-detect)"
            + "\n- additional_query (optional): Additional query filter as JSON string"
            + "\n\nReturns: Statistical comparison showing what changed (avg, p95, p99, min, max) with percent changes."
            + "\n\nExample: {\"index\":\"app-metrics-*\", \"field\":\"response_time_ms\", \"problem_start\":\"2024-01-15T10:00:00Z\", \"problem_end\":\"2024-01-15T11:00:00Z\", \"baseline_start\":\"2024-01-15T08:00:00Z\", \"baseline_end\":\"2024-01-15T09:00:00Z\"}";

    private String name = TYPE;
    private String description = DEFAULT_DESCRIPTION;
    private Map<String, Object> attributes;
    private Client client;

    public CompareTimeWindowsTool(Client client) {
        this.client = client;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public <T> void run(Map<String, String> parameters, ActionListener<T> listener) {
        try {
            // Extract parameters
            String index = parameters.get("index");
            String field = parameters.get("field");
            String problemStart = parameters.get("problem_start");
            String problemEnd = parameters.get("problem_end");
            String baselineStart = parameters.get("baseline_start");
            String baselineEnd = parameters.get("baseline_end");
            String timestampField = parameters.get("timestamp_field");
            String additionalQuery = parameters.get("additional_query");

            // Validation
            if (index == null || index.isEmpty()) {
                listener.onFailure(new IllegalArgumentException("Missing required parameter: index (e.g., 'metrics-*')"));
                return;
            }

            if (field == null || field.isEmpty()) {
                listener.onFailure(new IllegalArgumentException("Missing required parameter: field (numeric field to compare)"));
                return;
            }

            if (problemStart == null || problemEnd == null || baselineStart == null || baselineEnd == null) {
                listener
                    .onFailure(
                        new IllegalArgumentException(
                            "Missing required parameters: problem_start, problem_end, baseline_start, baseline_end"
                        )
                    );
                return;
            }

            // If timestamp field not provided, use common default
            if (timestampField == null || timestampField.isEmpty()) {
                timestampField = "@timestamp";
            }

            log
                .info(
                    "CompareTimeWindowsTool: Comparing {} in {} between problem [{} to {}] and baseline [{} to {}]",
                    field,
                    index,
                    problemStart,
                    problemEnd,
                    baselineStart,
                    baselineEnd
                );

            // Query both windows in parallel
            AtomicReference<WindowStats> problemStatsRef = new AtomicReference<>();
            AtomicReference<WindowStats> baselineStatsRef = new AtomicReference<>();
            AtomicReference<Exception> errorRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(2);

            // Query problem window
            queryWindowStats(index, field, timestampField, problemStart, problemEnd, additionalQuery, ActionListener.wrap(stats -> {
                problemStatsRef.set(stats);
                latch.countDown();
            }, e -> {
                errorRef.set(e);
                latch.countDown();
            }));

            // Query baseline window
            queryWindowStats(index, field, timestampField, baselineStart, baselineEnd, additionalQuery, ActionListener.wrap(stats -> {
                baselineStatsRef.set(stats);
                latch.countDown();
            }, e -> {
                errorRef.set(e);
                latch.countDown();
            }));

            // Wait for both queries to complete (in separate thread to avoid blocking)
            new Thread(() -> {
                try {
                    latch.await();

                    if (errorRef.get() != null) {
                        listener.onFailure(errorRef.get());
                        return;
                    }

                    WindowStats problemStats = problemStatsRef.get();
                    WindowStats baselineStats = baselineStatsRef.get();

                    if (problemStats == null || baselineStats == null) {
                        listener.onFailure(new IllegalStateException("Failed to retrieve statistics for one or both windows"));
                        return;
                    }

                    // Compare and format results
                    Comparison comparison = compareWindows(problemStats, baselineStats, field);
                    String result = formatResult(comparison, problemStart, problemEnd, baselineStart, baselineEnd);
                    listener.onResponse((T) result);

                } catch (Exception e) {
                    log.error("CompareTimeWindowsTool failed", e);
                    listener.onFailure(e);
                }
            }).start();

        } catch (Exception e) {
            log.error("CompareTimeWindowsTool error", e);
            listener.onFailure(e);
        }
    }

    private void queryWindowStats(
        String index,
        String field,
        String timestampField,
        String startTime,
        String endTime,
        String additionalQuery,
        ActionListener<WindowStats> listener
    ) {
        try {
            SearchRequest request = new SearchRequest(index);
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

            // Build query
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

            // Add time range filter
            boolQuery
                .filter(
                    QueryBuilders.rangeQuery(timestampField).gte(startTime).lte(endTime).format("strict_date_optional_time||epoch_millis")
                );

            // Add additional query if provided
            if (additionalQuery != null && !additionalQuery.isEmpty()) {
                try {
                    boolQuery.must(QueryBuilders.wrapperQuery(additionalQuery));
                } catch (Exception e) {
                    log.warn("Failed to parse additional query as JSON, using as query string", e);
                    boolQuery.must(QueryBuilders.queryStringQuery(additionalQuery));
                }
            }

            sourceBuilder.query(boolQuery);

            // Add extended stats aggregation for comprehensive metrics
            ExtendedStatsAggregationBuilder extendedStats = AggregationBuilders.extendedStats("stats").field(field);

            // Add percentiles aggregation
            PercentilesAggregationBuilder percentiles = AggregationBuilders.percentiles("percentiles").field(field).percentiles(50, 95, 99);

            sourceBuilder.aggregation(extendedStats);
            sourceBuilder.aggregation(percentiles);
            sourceBuilder.size(0); // We only need aggregations

            request.source(sourceBuilder);

            // Execute search
            client.search(request, ActionListener.wrap(response -> {
                WindowStats stats = extractStats(response, field);
                listener.onResponse(stats);
            }, listener::onFailure));

        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    private WindowStats extractStats(SearchResponse response, String field) {
        WindowStats stats = new WindowStats();

        ExtendedStats extendedStats = response.getAggregations().get("stats");
        Percentiles percentiles = response.getAggregations().get("percentiles");

        stats.setCount(extendedStats.getCount());
        stats.setMin(extendedStats.getMin());
        stats.setMax(extendedStats.getMax());
        stats.setAvg(extendedStats.getAvg());
        stats.setStdDev(extendedStats.getStdDeviation());
        stats.setP50(percentiles.percentile(50));
        stats.setP95(percentiles.percentile(95));
        stats.setP99(percentiles.percentile(99));

        return stats;
    }

    private Comparison compareWindows(WindowStats problem, WindowStats baseline, String field) {
        Comparison comparison = new Comparison();
        comparison.setField(field);
        comparison.setProblemStats(problem);
        comparison.setBaselineStats(baseline);

        // Calculate percent changes (handle division by zero)
        if (baseline.getAvg() != 0) {
            comparison.setAvgChange(((problem.getAvg() - baseline.getAvg()) / baseline.getAvg()) * 100);
        }
        if (baseline.getP95() != 0) {
            comparison.setP95Change(((problem.getP95() - baseline.getP95()) / baseline.getP95()) * 100);
        }
        if (baseline.getP99() != 0) {
            comparison.setP99Change(((problem.getP99() - baseline.getP99()) / baseline.getP99()) * 100);
        }
        if (baseline.getMax() != 0) {
            comparison.setMaxChange(((problem.getMax() - baseline.getMax()) / baseline.getMax()) * 100);
        }

        // Determine significance (>20% change in avg or >30% in p95)
        boolean significant = Math.abs(comparison.getAvgChange()) > 20 || Math.abs(comparison.getP95Change()) > 30;
        comparison.setSignificant(significant);

        // Determine direction
        if (comparison.getAvgChange() > 10) {
            comparison.setDirection("INCREASED");
        } else if (comparison.getAvgChange() < -10) {
            comparison.setDirection("DECREASED");
        } else {
            comparison.setDirection("STABLE");
        }

        // Generate conclusion
        StringBuilder conclusion = new StringBuilder();
        if (significant) {
            conclusion
                .append(
                    String
                        .format(
                            "Significant change detected in %s: average %s by %.1f%% (from %.2f to %.2f), p95 %s by %.1f%%",
                            field,
                            comparison.getAvgChange() > 0 ? "increased" : "decreased",
                            Math.abs(comparison.getAvgChange()),
                            baseline.getAvg(),
                            problem.getAvg(),
                            comparison.getP95Change() > 0 ? "increased" : "decreased",
                            Math.abs(comparison.getP95Change())
                        )
                );
        } else {
            conclusion
                .append(
                    String
                        .format(
                            "No significant change in %s (average change: %.1f%%, p95 change: %.1f%%)",
                            field,
                            comparison.getAvgChange(),
                            comparison.getP95Change()
                        )
                );
        }

        comparison.setConclusion(conclusion.toString());
        return comparison;
    }

    private String formatResult(Comparison comparison, String problemStart, String problemEnd, String baselineStart, String baselineEnd) {
        StringBuilder result = new StringBuilder();
        result.append("# Time Window Comparison\n\n");
        result.append(String.format("**Field**: %s\n", comparison.getField()));
        result.append(String.format("**Problem Window**: %s to %s\n", problemStart, problemEnd));
        result.append(String.format("**Baseline Window**: %s to %s\n\n", baselineStart, baselineEnd));

        // Conclusion first
        result.append("## Conclusion\n");
        String statusIcon = comparison.isSignificant() ? "⚠️" : "✓";
        result.append(String.format("%s %s\n\n", statusIcon, comparison.getConclusion()));

        // Comparison table
        result.append("## Statistical Comparison\n\n");
        result.append("| Metric | Baseline | Problem | Change |\n");
        result.append("|--------|----------|---------|--------|\n");

        WindowStats baseline = comparison.getBaselineStats();
        WindowStats problem = comparison.getProblemStats();

        result.append(String.format("| Count | %d | %d | - |\n", baseline.getCount(), problem.getCount()));
        result
            .append(String.format("| Average | %.2f | %.2f | %+.1f%% |\n", baseline.getAvg(), problem.getAvg(), comparison.getAvgChange()));
        result.append(String.format("| P50 (Median) | %.2f | %.2f | - |\n", baseline.getP50(), problem.getP50()));
        result.append(String.format("| P95 | %.2f | %.2f | %+.1f%% |\n", baseline.getP95(), problem.getP95(), comparison.getP95Change()));
        result.append(String.format("| P99 | %.2f | %.2f | %+.1f%% |\n", baseline.getP99(), problem.getP99(), comparison.getP99Change()));
        result.append(String.format("| Min | %.2f | %.2f | - |\n", baseline.getMin(), problem.getMin()));
        result.append(String.format("| Max | %.2f | %.2f | %+.1f%% |\n", baseline.getMax(), problem.getMax(), comparison.getMaxChange()));
        result.append(String.format("| Std Dev | %.2f | %.2f | - |\n", baseline.getStdDev(), problem.getStdDev()));

        result.append(String.format("\n**Overall Direction**: %s\n", comparison.getDirection()));

        return result.toString();
    }

    @Override
    public boolean validate(Map<String, String> parameters) {
        return parameters.containsKey("index")
            && !parameters.get("index").isEmpty()
            && parameters.containsKey("field")
            && !parameters.get("field").isEmpty()
            && parameters.containsKey("problem_start")
            && parameters.containsKey("problem_end")
            && parameters.containsKey("baseline_start")
            && parameters.containsKey("baseline_end");
    }

    // Data classes
    private static class WindowStats {
        private long count;
        private double min;
        private double max;
        private double avg;
        private double stdDev;
        private double p50;
        private double p95;
        private double p99;

        public long getCount() {
            return count;
        }

        public void setCount(long count) {
            this.count = count;
        }

        public double getMin() {
            return min;
        }

        public void setMin(double min) {
            this.min = min;
        }

        public double getMax() {
            return max;
        }

        public void setMax(double max) {
            this.max = max;
        }

        public double getAvg() {
            return avg;
        }

        public void setAvg(double avg) {
            this.avg = avg;
        }

        public double getStdDev() {
            return stdDev;
        }

        public void setStdDev(double stdDev) {
            this.stdDev = stdDev;
        }

        public double getP50() {
            return p50;
        }

        public void setP50(double p50) {
            this.p50 = p50;
        }

        public double getP95() {
            return p95;
        }

        public void setP95(double p95) {
            this.p95 = p95;
        }

        public double getP99() {
            return p99;
        }

        public void setP99(double p99) {
            this.p99 = p99;
        }
    }

    private static class Comparison {
        private String field;
        private WindowStats problemStats;
        private WindowStats baselineStats;
        private double avgChange;
        private double p95Change;
        private double p99Change;
        private double maxChange;
        private boolean significant;
        private String direction;
        private String conclusion;

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }

        public WindowStats getProblemStats() {
            return problemStats;
        }

        public void setProblemStats(WindowStats problemStats) {
            this.problemStats = problemStats;
        }

        public WindowStats getBaselineStats() {
            return baselineStats;
        }

        public void setBaselineStats(WindowStats baselineStats) {
            this.baselineStats = baselineStats;
        }

        public double getAvgChange() {
            return avgChange;
        }

        public void setAvgChange(double avgChange) {
            this.avgChange = avgChange;
        }

        public double getP95Change() {
            return p95Change;
        }

        public void setP95Change(double p95Change) {
            this.p95Change = p95Change;
        }

        public double getP99Change() {
            return p99Change;
        }

        public void setP99Change(double p99Change) {
            this.p99Change = p99Change;
        }

        public double getMaxChange() {
            return maxChange;
        }

        public void setMaxChange(double maxChange) {
            this.maxChange = maxChange;
        }

        public boolean isSignificant() {
            return significant;
        }

        public void setSignificant(boolean significant) {
            this.significant = significant;
        }

        public String getDirection() {
            return direction;
        }

        public void setDirection(String direction) {
            this.direction = direction;
        }

        public String getConclusion() {
            return conclusion;
        }

        public void setConclusion(String conclusion) {
            this.conclusion = conclusion;
        }
    }

    // Factory for tool registration
    public static class Factory implements Tool.Factory<CompareTimeWindowsTool> {
        private Client client;
        private static Factory INSTANCE;

        /**
         * Create or return the singleton factory instance
         */
        public static Factory getInstance() {
            if (INSTANCE != null) {
                return INSTANCE;
            }
            synchronized (CompareTimeWindowsTool.class) {
                if (INSTANCE != null) {
                    return INSTANCE;
                }
                INSTANCE = new Factory();
                return INSTANCE;
            }
        }

        public void init(Client client) {
            this.client = client;
        }

        @Override
        public CompareTimeWindowsTool create(Map<String, Object> params) {
            return new CompareTimeWindowsTool(client);
        }

        @Override
        public String getDefaultDescription() {
            return DEFAULT_DESCRIPTION;
        }

        @Override
        public String getDefaultType() {
            return TYPE;
        }

        @Override
        public String getDefaultVersion() {
            return "1.0.0";
        }

        @Override
        public Map<String, Object> getDefaultAttributes() {
            return Map.of();
        }
    }
}
