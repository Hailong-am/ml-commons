/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.tools;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.ml.common.spi.tools.Tool;
import org.opensearch.ml.common.spi.tools.ToolAnnotation;
import org.opensearch.search.aggregations.AggregationBuilders;
import org.opensearch.search.aggregations.bucket.histogram.DateHistogramAggregationBuilder;
import org.opensearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.opensearch.search.aggregations.bucket.histogram.Histogram;
import org.opensearch.search.aggregations.bucket.histogram.ParsedDateHistogram;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.transport.client.Client;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * Tool for time-series analysis - finds when an issue started
 *
 * Critical for RCA to identify the exact time a problem began
 */
@Getter
@Setter
@Log4j2
@ToolAnnotation(TimeSeriesSearchTool.TYPE)
public class TimeSeriesSearchTool implements Tool {

    public static final String TYPE = "TimeSeriesSearchTool";
    private static final String DEFAULT_TIME_RANGE = "24h";
    private static final String DEFAULT_INTERVAL = "auto";

    private static final String DEFAULT_DESCRIPTION = "Analyzes data over time to identify when an issue started. "
        + "Use this to find: (1) When errors/issues began, (2) Timeline of events, "
        + "(3) Spikes or anomalies in data. "
        + "\n\nInput parameters:"
        + "\n- index (required): Index pattern to search (e.g., 'logs-*', 'metrics-*')"
        + "\n- query (required): OpenSearch query DSL as JSON string to filter events"
        + "\n- timestamp_field (optional): Timestamp field name (default: auto-detect)"
        + "\n- time_range (optional): How far back to look (e.g., '1h', '24h', '7d', default: '24h')"
        + "\n- interval (optional): Time bucket size (e.g., '1m', '5m', '1h', default: auto)"
        + "\n\nReturns: Timeline showing when issue started, spike detection, and baseline comparison."
        + "\n\nExample: {\"index\":\"app-logs-*\", \"query\":\"{\\\"match\\\":{\\\"level\\\":\\\"ERROR\\\"}}\", \"time_range\":\"24h\"}";

    private String name = TYPE;
    private String description = DEFAULT_DESCRIPTION;
    private Map<String, Object> attributes;
    private Client client;

    public TimeSeriesSearchTool(Client client) {
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
            String queryStr = parameters.get("query");
            String timestampField = parameters.get("timestamp_field");
            String timeRange = parameters.getOrDefault("time_range", DEFAULT_TIME_RANGE);
            String intervalStr = parameters.getOrDefault("interval", DEFAULT_INTERVAL);

            // Validation
            if (index == null || index.isEmpty()) {
                listener.onFailure(new IllegalArgumentException("Missing required parameter: index (e.g., 'logs-*')"));
                return;
            }

            if (queryStr == null || queryStr.isEmpty()) {
                listener.onFailure(new IllegalArgumentException("Missing required parameter: query (OpenSearch query DSL as JSON string)"));
                return;
            }

            // Parse time range to calculate start time
            Instant endTime = Instant.now();
            Instant startTime = parseTimeRange(timeRange, endTime);

            // Auto-select interval if not specified
            DateHistogramInterval interval;
            if (DEFAULT_INTERVAL.equals(intervalStr)) {
                interval = autoSelectInterval(startTime, endTime);
            } else {
                interval = new DateHistogramInterval(intervalStr);
            }

            log.info("TimeSeriesSearchTool: Analyzing {} from {} to {} with interval {}", index, startTime, endTime, interval);

            // Build search request
            SearchRequest searchRequest = buildTimeSeriesQuery(index, queryStr, timestampField, startTime, endTime, interval);

            // Execute search
            client.search(searchRequest, ActionListener.wrap(response -> {
                TimelineAnalysis analysis = analyzeTimeline(response, interval);
                String result = formatResult(analysis, timeRange);
                listener.onResponse((T) result);
            }, e -> {
                log.error("TimeSeriesSearchTool failed for index: {}", index, e);
                listener.onFailure(e);
            }));

        } catch (Exception e) {
            log.error("TimeSeriesSearchTool error", e);
            listener.onFailure(e);
        }
    }

    private SearchRequest buildTimeSeriesQuery(
        String index,
        String queryStr,
        String timestampField,
        Instant startTime,
        Instant endTime,
        DateHistogramInterval interval
    ) {
        SearchRequest request = new SearchRequest(index);
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

        // Parse user query
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

        try {
            // Try to parse as JSON query
            boolQuery.must(QueryBuilders.wrapperQuery(queryStr));
        } catch (Exception e) {
            // If parsing fails, try as simple query string
            log.warn("Failed to parse query as JSON, using as query string", e);
            boolQuery.must(QueryBuilders.queryStringQuery(queryStr));
        }

        // If timestamp field not provided, try common field names
        if (timestampField == null || timestampField.isEmpty()) {
            timestampField = "@timestamp"; // Most common default
        }

        // Add time range filter
        boolQuery
            .filter(
                QueryBuilders.rangeQuery(timestampField).gte(startTime.toEpochMilli()).lte(endTime.toEpochMilli()).format("epoch_millis")
            );

        sourceBuilder.query(boolQuery);

        // Add date histogram aggregation
        DateHistogramAggregationBuilder histogram = AggregationBuilders
            .dateHistogram("timeline")
            .field(timestampField)
            .fixedInterval(interval)
            .minDocCount(0); // Include zero-count buckets

        sourceBuilder.aggregation(histogram);
        sourceBuilder.size(0); // We only need aggregations, not individual docs

        request.source(sourceBuilder);
        return request;
    }

    private TimelineAnalysis analyzeTimeline(SearchResponse response, DateHistogramInterval interval) {
        ParsedDateHistogram histogram = response.getAggregations().get("timeline");
        List<? extends Histogram.Bucket> buckets = histogram.getBuckets();

        TimelineAnalysis analysis = new TimelineAnalysis();
        analysis.setTotalHits(response.getHits().getTotalHits().value());
        analysis.setInterval(interval.toString());

        if (buckets.isEmpty()) {
            analysis.setConclusion("No data found in the specified time range");
            return analysis;
        }

        // Extract counts
        List<TimePoint> timeline = new ArrayList<>();
        for (Histogram.Bucket bucket : buckets) {
            TimePoint point = new TimePoint();
            point.setTimestamp(bucket.getKeyAsString());
            point.setCount(bucket.getDocCount());
            timeline.add(point);
        }
        analysis.setTimeline(timeline);

        // Calculate baseline (first 50% of data points)
        int halfPoint = Math.max(1, timeline.size() / 2);
        double baseline = timeline.subList(0, halfPoint).stream().mapToLong(TimePoint::getCount).average().orElse(0);

        analysis.setBaseline(baseline);

        // Detect spikes (>3x baseline)
        double spikeThreshold = baseline * 3;
        List<Spike> spikes = new ArrayList<>();

        for (int i = 0; i < timeline.size(); i++) {
            TimePoint point = timeline.get(i);
            if (point.getCount() > spikeThreshold && baseline > 0) {
                Spike spike = new Spike();
                spike.setTimestamp(point.getTimestamp());
                spike.setCount(point.getCount());
                spike.setBaselineMultiplier(point.getCount() / baseline);
                spikes.add(spike);
            }
        }
        analysis.setSpikes(spikes);

        // Determine trend
        long recent = timeline.get(timeline.size() - 1).getCount();
        if (baseline > 0) {
            if (recent > baseline * 2) {
                analysis.setTrend("INCREASING");
            } else if (recent < baseline * 0.5) {
                analysis.setTrend("DECREASING");
            } else {
                analysis.setTrend("STABLE");
            }
        } else {
            analysis.setTrend("NO_BASELINE");
        }

        // Generate conclusion
        StringBuilder conclusion = new StringBuilder();
        if (!spikes.isEmpty()) {
            Spike firstSpike = spikes.get(0);
            conclusion
                .append(
                    String
                        .format(
                            "Issue started at %s (spike from %.0f to %.0f occurrences, %.1fx increase)",
                            firstSpike.getTimestamp(),
                            baseline,
                            firstSpike.getCount(),
                            firstSpike.getBaselineMultiplier()
                        )
                );
        } else if (baseline > 0 && recent > baseline * 1.5) {
            conclusion.append(String.format("Gradual increase detected (baseline: %.0f, current: %d)", baseline, recent));
        } else if (analysis.getTotalHits() == 0) {
            conclusion.append("No matching events found in specified time range");
        } else {
            conclusion.append(String.format("Consistent pattern (avg: %.0f occurrences per interval)", baseline));
        }

        analysis.setConclusion(conclusion.toString());
        return analysis;
    }

    private String formatResult(TimelineAnalysis analysis, String timeRange) {
        StringBuilder result = new StringBuilder();
        result.append("# Timeline Analysis\n\n");
        result.append(String.format("**Time Range**: Last %s\n", timeRange));
        result.append(String.format("**Interval**: %s\n", analysis.getInterval()));
        result.append(String.format("**Total Events**: %d\n\n", analysis.getTotalHits()));

        // Conclusion first
        result.append("## Conclusion\n");
        result.append(analysis.getConclusion()).append("\n\n");

        // Baseline
        if (analysis.getBaseline() > 0) {
            result.append("## Baseline\n");
            result.append(String.format("Normal rate: %.1f occurrences per %s\n\n", analysis.getBaseline(), analysis.getInterval()));
        }

        // Spikes
        if (!analysis.getSpikes().isEmpty()) {
            result.append("## Detected Spikes\n");
            for (Spike spike : analysis.getSpikes()) {
                result
                    .append(
                        String
                            .format(
                                "- **%s**: %d occurrences (%.1fx baseline)\n",
                                spike.getTimestamp(),
                                spike.getCount(),
                                spike.getBaselineMultiplier()
                            )
                    );
            }
            result.append("\n");
        }

        // Timeline summary (show high points)
        result.append("## Timeline Summary\n");
        List<TimePoint> highPoints = analysis
            .getTimeline()
            .stream()
            .filter(p -> p.getCount() > 0)
            .sorted(Comparator.comparingLong(TimePoint::getCount).reversed())
            .limit(5)
            .collect(Collectors.toList());

        if (!highPoints.isEmpty()) {
            result.append("Highest activity periods:\n");
            for (TimePoint point : highPoints) {
                result.append(String.format("- %s: %d occurrences\n", point.getTimestamp(), point.getCount()));
            }
        }

        // Trend
        result.append(String.format("\n**Current Trend**: %s\n", analysis.getTrend()));

        return result.toString();
    }

    private Instant parseTimeRange(String timeRange, Instant endTime) {
        try {
            // Parse formats like "1h", "24h", "7d", "30m"
            String value = timeRange.replaceAll("[^0-9]", "");
            String unit = timeRange.replaceAll("[0-9]", "").toLowerCase();

            long amount = Long.parseLong(value);

            return switch (unit) {
                case "m", "min", "minutes" -> endTime.minus(amount, ChronoUnit.MINUTES);
                case "h", "hour", "hours" -> endTime.minus(amount, ChronoUnit.HOURS);
                case "d", "day", "days" -> endTime.minus(amount, ChronoUnit.DAYS);
                default -> endTime.minus(24, ChronoUnit.HOURS); // Default to 24h
            };
        } catch (Exception e) {
            log.warn("Failed to parse time range: {}, using default 24h", timeRange);
            return endTime.minus(24, ChronoUnit.HOURS);
        }
    }

    private DateHistogramInterval autoSelectInterval(Instant start, Instant end) {
        long durationMinutes = ChronoUnit.MINUTES.between(start, end);

        // Select interval based on total duration
        if (durationMinutes <= 60) {
            return new DateHistogramInterval("1m");
        } else if (durationMinutes <= 360) {
            return new DateHistogramInterval("5m");
        } else if (durationMinutes <= 1440) {
            return new DateHistogramInterval("15m");
        } else if (durationMinutes <= 10080) {
            return new DateHistogramInterval("1h");
        } else {
            return new DateHistogramInterval("4h");
        }
    }

    @Override
    public boolean validate(Map<String, String> parameters) {
        return parameters.containsKey("index")
            && !parameters.get("index").isEmpty()
            && parameters.containsKey("query")
            && !parameters.get("query").isEmpty();
    }

    // Data classes
    private static class TimelineAnalysis {
        private long totalHits;
        private String interval;
        private double baseline;
        private List<TimePoint> timeline;
        private List<Spike> spikes;
        private String trend;
        private String conclusion;

        public TimelineAnalysis() {
            this.timeline = new ArrayList<>();
            this.spikes = new ArrayList<>();
        }

        // Getters and setters
        public long getTotalHits() {
            return totalHits;
        }

        public void setTotalHits(long totalHits) {
            this.totalHits = totalHits;
        }

        public String getInterval() {
            return interval;
        }

        public void setInterval(String interval) {
            this.interval = interval;
        }

        public double getBaseline() {
            return baseline;
        }

        public void setBaseline(double baseline) {
            this.baseline = baseline;
        }

        public List<TimePoint> getTimeline() {
            return timeline;
        }

        public void setTimeline(List<TimePoint> timeline) {
            this.timeline = timeline;
        }

        public List<Spike> getSpikes() {
            return spikes;
        }

        public void setSpikes(List<Spike> spikes) {
            this.spikes = spikes;
        }

        public String getTrend() {
            return trend;
        }

        public void setTrend(String trend) {
            this.trend = trend;
        }

        public String getConclusion() {
            return conclusion;
        }

        public void setConclusion(String conclusion) {
            this.conclusion = conclusion;
        }
    }

    private static class TimePoint {
        private String timestamp;
        private long count;

        public String getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(String timestamp) {
            this.timestamp = timestamp;
        }

        public long getCount() {
            return count;
        }

        public void setCount(long count) {
            this.count = count;
        }
    }

    private static class Spike {
        private String timestamp;
        private long count;
        private double baselineMultiplier;

        public String getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(String timestamp) {
            this.timestamp = timestamp;
        }

        public long getCount() {
            return count;
        }

        public void setCount(long count) {
            this.count = count;
        }

        public double getBaselineMultiplier() {
            return baselineMultiplier;
        }

        public void setBaselineMultiplier(double baselineMultiplier) {
            this.baselineMultiplier = baselineMultiplier;
        }
    }

    // Factory for tool registration
    public static class Factory implements Tool.Factory<TimeSeriesSearchTool> {
        private Client client;
        private static Factory INSTANCE;

        /**
         * Create or return the singleton factory instance
         */
        public static Factory getInstance() {
            if (INSTANCE != null) {
                return INSTANCE;
            }
            synchronized (TimeSeriesSearchTool.class) {
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
        public TimeSeriesSearchTool create(Map<String, Object> params) {
            return new TimeSeriesSearchTool(client);
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
