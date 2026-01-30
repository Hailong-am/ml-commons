# MVP Tool Set for SaaS RCA Agent

## Core Principle

**Provide tools that work for ANY customer data, not domain-specific tools.**

Tools should be:
- ✅ Generic (work on any OpenSearch index)
- ✅ Powerful (cover 80% of RCA scenarios)
- ✅ Simple (easy for LLM to use correctly)
- ✅ Safe (read-only, can't harm customer data)

## Minimal Tool Set (6 Tools)

### Category 1: Discovery Tools (Know What's There)

#### 1. **ListIndicesAndFieldsTool**
**Purpose**: Discover customer's data structure
**Already exists**: Partially (as `ListIndexTool` in MCP)
**What it does**:
```
Input: None (uses tenant context)
Output:
  - List of indices
  - Key fields in each index
  - Suggested index categories (logs, metrics, traces)

Example output:
"Found 5 indices:
- app-logs-2025-01 (type: logs)
  Fields: @timestamp, service.name, level, message
- app-metrics-2025-01 (type: metrics)
  Fields: @timestamp, service.name, cpu_usage, memory_usage
- payment-logs-2025-01 (type: logs)
  Fields: timestamp, service, status_code, error_message"
```

**Why essential**: Agent needs to know what indices exist and what fields to query

**Enhancement needed**:
```java
@Override
public <T> void run(Map<String, String> parameters, ActionListener<T> listener) {
    // Get all indices for this tenant
    CatIndicesRequest request = new CatIndicesRequest();

    client.cat().indices(request, ActionListener.wrap(indicesResponse -> {
        List<IndexInfo> indices = parseIndices(indicesResponse);

        // For each index, get mapping to understand fields
        Map<String, IndexMetadata> metadata = new HashMap<>();

        for (IndexInfo index : indices) {
            GetMappingsRequest mappingReq = new GetMappingsRequest()
                .indices(index.getName());

            client.indices().getMapping(mappingReq, ActionListener.wrap(mappingResp -> {
                IndexMetadata meta = extractMetadata(index, mappingResp);

                // Categorize index (logs, metrics, traces, etc.)
                meta.setCategory(inferCategory(index.getName(), meta.getFields()));

                // Identify key fields
                meta.setTimestampField(findTimestampField(meta.getFields()));
                meta.setCommonFields(identifyCommonFields(meta.getFields()));

                metadata.put(index.getName(), meta);

                if (metadata.size() == indices.size()) {
                    // All done, format and return
                    String result = formatDiscoveryResult(metadata);
                    listener.onResponse((T) result);
                }
            }, listener::onFailure));
        }
    }, listener::onFailure));
}

private String formatDiscoveryResult(Map<String, IndexMetadata> metadata) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Available Indices and Fields\n\n");

    // Group by category
    Map<String, List<IndexMetadata>> byCategory = metadata.values().stream()
        .collect(Collectors.groupingBy(IndexMetadata::getCategory));

    for (Map.Entry<String, List<IndexMetadata>> entry : byCategory.entrySet()) {
        sb.append(String.format("## %s Indices\n", entry.getKey().toUpperCase()));

        for (IndexMetadata meta : entry.getValue()) {
            sb.append(String.format("### %s\n", meta.getIndexName()));
            sb.append(String.format("- Timestamp field: `%s`\n", meta.getTimestampField()));
            sb.append("- Key fields: ");
            sb.append(meta.getCommonFields().stream()
                .map(f -> String.format("`%s` (%s)", f.getName(), f.getType()))
                .collect(Collectors.joining(", ")));
            sb.append("\n\n");
        }
    }

    return sb.toString();
}
```

---

### Category 2: Search & Query Tools (Find Relevant Data)

#### 2. **SearchIndexTool**
**Purpose**: Search for specific events/logs
**Already exists**: Yes (in MCP `SearchIndexTool`)
**What it does**:
```
Input:
  - index: "app-logs-*"
  - query: {"match": {"level": "ERROR"}}
  - timeRange: "last 1 hour"
  - size: 100

Output:
  - Matching documents
  - Total count
  - Sample results

Example:
"Found 234 ERROR logs in last 1 hour.
Top errors:
- 'Connection timeout' (150 occurrences)
- 'Database unavailable' (50 occurrences)
- 'Memory exceeded' (34 occurrences)"
```

**Why essential**: Core tool for finding specific issues

**Keep as-is**: Should already work well for generic searches

---

#### 3. **TimeSeriesSearchTool** (NEW)
**Purpose**: Find when something started happening
**What it does**:
```
Input:
  - index: "app-logs-*"
  - query: {"match": {"error_type": "timeout"}}
  - timeRange: "last 24 hours"
  - interval: "1h" or "5m"

Output:
  - Timeline of occurrences
  - When spike/change happened
  - Comparison to baseline

Example:
"Timeout errors timeline (last 24h):
- 00:00-06:00: 2-5 errors/hour (normal)
- 06:00-08:00: 5-8 errors/hour (normal)
- 08:15: SPIKE to 150 errors/hour ⚠️
- 08:15-10:00: 120-150 errors/hour (ongoing)

Conclusion: Issue started at 08:15"
```

**Implementation**:
```java
public class TimeSeriesSearchTool implements Tool {

    @Override
    public String getDescription() {
        return "Searches data over time to identify when an issue started. " +
               "Input: index, query, timeRange, interval. " +
               "Returns: Timeline showing when issue began, spike detection, comparison to baseline.";
    }

    @Override
    public <T> void run(Map<String, String> parameters, ActionListener<T> listener) {
        String index = parameters.get("index");
        String query = parameters.get("query");
        String timeRange = parameters.getOrDefault("timeRange", "last 24 hours");
        String interval = parameters.getOrDefault("interval", "1h");

        SearchRequest request = new SearchRequest(index);
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

        // Parse and apply query
        QueryBuilder queryBuilder = parseQuery(query);
        sourceBuilder.query(queryBuilder);

        // Add date histogram aggregation
        DateHistogramAggregationBuilder histogram = AggregationBuilders
            .dateHistogram("timeline")
            .field(getTimestampField(index))
            .fixedInterval(new DateHistogramInterval(interval))
            .minDocCount(0); // Show zeros

        sourceBuilder.aggregation(histogram);
        sourceBuilder.size(0); // Only need aggregations

        // Apply time range
        RangeQueryBuilder timeFilter = QueryBuilders.rangeQuery(getTimestampField(index))
            .gte(parseTimeRange(timeRange));
        sourceBuilder.postFilter(timeFilter);

        request.source(sourceBuilder);

        client.search(request, ActionListener.wrap(response -> {
            ParsedDateHistogram agg = response.getAggregations().get("timeline");

            // Analyze timeline
            TimelineAnalysis analysis = analyzeTimeline(agg.getBuckets());

            listener.onResponse((T) formatTimelineResult(analysis));
        }, listener::onFailure));
    }

    private TimelineAnalysis analyzeTimeline(List<? extends Histogram.Bucket> buckets) {
        TimelineAnalysis analysis = new TimelineAnalysis();

        List<Long> counts = buckets.stream()
            .map(Histogram.Bucket::getDocCount)
            .collect(Collectors.toList());

        // Calculate baseline (first 50% of data)
        int halfPoint = counts.size() / 2;
        double baseline = counts.subList(0, halfPoint).stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0);

        // Find spikes (>3x baseline)
        for (int i = 0; i < buckets.size(); i++) {
            Histogram.Bucket bucket = buckets.get(i);
            if (bucket.getDocCount() > baseline * 3) {
                analysis.addSpike(bucket.getKeyAsString(), bucket.getDocCount(), baseline);
            }
        }

        // Identify trend
        if (counts.get(counts.size() - 1) > baseline * 2) {
            analysis.setTrend("INCREASING");
        } else if (counts.get(counts.size() - 1) < baseline * 0.5) {
            analysis.setTrend("DECREASING");
        } else {
            analysis.setTrend("STABLE");
        }

        return analysis;
    }
}
```

**Why essential**: Critical for identifying "when did this start?"

---

### Category 3: Analysis Tools (Understand Patterns)

#### 4. **LogPatternAnalysisTool**
**Purpose**: Identify common error patterns
**Already exists**: Yes (in MCP)
**What it does**:
```
Input:
  - index: "app-logs-*"
  - logField: "message"
  - timeRange: "last 1 hour"
  - minOccurrences: 5

Output:
  - Clustered log patterns
  - Frequency of each pattern
  - Sample logs

Example:
"Found 3 error patterns:

Pattern 1 (150 occurrences):
'Connection timeout to database at <IP>'
Samples:
- Connection timeout to database at 10.0.1.5
- Connection timeout to database at 10.0.1.6

Pattern 2 (50 occurrences):
'Failed to deserialize response: <ERROR>'

Pattern 3 (34 occurrences):
'Memory allocation failed'"
```

**Why essential**: Turns thousands of logs into actionable patterns

**Keep as-is**: Should work well for generic log analysis

---

#### 5. **DataDistributionTool**
**Purpose**: Analyze metric distributions (percentiles, anomalies)
**Already exists**: Yes (in MCP)
**What it does**:
```
Input:
  - index: "app-metrics-*"
  - field: "response_time_ms"
  - timeRange: "last 1 hour"

Output:
  - Min, Max, Avg, Median, P95, P99
  - Distribution visualization
  - Anomaly detection

Example:
"Response time distribution (last 1 hour):
- Min: 10ms
- P50: 120ms
- P95: 450ms ⚠️ (baseline: 200ms)
- P99: 1200ms ⚠️ (baseline: 350ms)
- Max: 3500ms

Conclusion: High percentiles significantly elevated"
```

**Why essential**: Understanding if metrics are abnormal

**Keep as-is**: Good for statistical analysis

---

#### 6. **CompareTimeWindowsTool** (NEW)
**Purpose**: Compare "problem window" vs "normal window"
**What it does**:
```
Input:
  - index: "app-metrics-*"
  - field: "cpu_usage"
  - problemWindow: "2025-01-15 08:00 to 10:00"
  - baselineWindow: "2025-01-14 08:00 to 10:00"

Output:
  - Side-by-side comparison
  - What changed significantly
  - Statistical significance

Example:
"Comparing problem vs baseline:

CPU Usage:
- Baseline: avg 45%, p95 60%
- Problem: avg 85%, p95 95% ⚠️ +89% increase

Memory Usage:
- Baseline: avg 60%, p95 70%
- Problem: avg 62%, p95 72% ✓ No significant change

Request Rate:
- Baseline: avg 5000/s
- Problem: avg 12000/s ⚠️ +140% increase

Conclusion: CPU spiked due to 2.4x traffic increase"
```

**Implementation**:
```java
public class CompareTimeWindowsTool implements Tool {

    @Override
    public String getDescription() {
        return "Compares metrics between problem window and baseline window. " +
               "Input: index, field, problemWindow, baselineWindow. " +
               "Returns: Side-by-side comparison showing what changed significantly.";
    }

    @Override
    public <T> void run(Map<String, String> parameters, ActionListener<T> listener) {
        String index = parameters.get("index");
        String field = parameters.get("field");
        String problemWindow = parameters.get("problemWindow");
        String baselineWindow = parameters.get("baselineWindow");

        // Query baseline
        StepListener<MetricStats> baselineListener = new StepListener<>();
        queryMetricStats(index, field, baselineWindow, baselineListener);

        // Query problem window
        baselineListener.whenComplete(baselineStats -> {
            StepListener<MetricStats> problemListener = new StepListener<>();
            queryMetricStats(index, field, problemWindow, problemListener);

            problemListener.whenComplete(problemStats -> {
                // Compare
                Comparison comparison = compare(baselineStats, problemStats);
                listener.onResponse((T) formatComparison(field, comparison));
            }, listener::onFailure);
        }, listener::onFailure);
    }

    private Comparison compare(MetricStats baseline, MetricStats problem) {
        Comparison comp = new Comparison();

        // Calculate percent changes
        double avgChange = ((problem.getAvg() - baseline.getAvg()) / baseline.getAvg()) * 100;
        double p95Change = ((problem.getP95() - baseline.getP95()) / baseline.getP95()) * 100;

        comp.setAvgChange(avgChange);
        comp.setP95Change(p95Change);

        // Determine significance
        comp.setSignificant(Math.abs(avgChange) > 20 || Math.abs(p95Change) > 30);

        return comp;
    }
}
```

**Why essential**: Critical for answering "what changed?"

---

## Tool Usage Patterns

### Pattern 1: "Service X is slow"

```
Step 1: ListIndicesAndFieldsTool
→ Discover which indices have data for "Service X"
→ Find: "service-x-logs-*" and "service-x-metrics-*"

Step 2: TimeSeriesSearchTool
→ Query: service-x-logs-* where level=ERROR
→ Find: Errors started spiking at 08:15

Step 3: CompareTimeWindowsTool
→ Compare metrics: 08:00-08:15 vs 08:15-09:00
→ Find: CPU usage doubled, request rate normal

Step 4: LogPatternAnalysisTool
→ Analyze error logs from 08:15 onwards
→ Find: "Database connection timeout" pattern

Conclusion: Database connection issue started at 08:15
```

### Pattern 2: "High error rate"

```
Step 1: ListIndicesAndFieldsTool
→ Find log indices

Step 2: SearchIndexTool
→ Find all errors in last 1 hour
→ 500 errors found

Step 3: LogPatternAnalysisTool
→ Cluster errors into patterns
→ Pattern 1: "Authentication failed" (400 occurrences)
→ Pattern 2: "Service unavailable" (100 occurrences)

Step 4: TimeSeriesSearchTool
→ When did "Authentication failed" errors start?
→ Started at 09:00, ongoing

Step 5: CompareTimeWindowsTool (on auth service metrics)
→ Compare before/after 09:00
→ Find: Auth service response time increased 10x

Conclusion: Auth service degradation at 09:00 causing failures
```

### Pattern 3: "Memory issue"

```
Step 1: ListIndicesAndFieldsTool
→ Find metric indices

Step 2: DataDistributionTool
→ Field: memory_usage_percent
→ Find: P95 at 95%, P99 at 98%

Step 3: TimeSeriesSearchTool
→ When did memory usage increase?
→ Gradual increase over 6 hours

Step 4: CompareTimeWindowsTool
→ Compare 6 hours ago vs now
→ Memory: 60% → 95%
→ Object count: 1M → 5M objects

Step 5: SearchIndexTool
→ Look for OOM errors
→ Find: 50 OOM errors in last hour

Conclusion: Memory leak, gradual buildup over 6 hours
```

---

## Optional Tools (Nice to Have)

### 7. **CorrelationFinderTool**
Find what changed at the same time as the issue
```
"Issue started at 08:15. What else changed at 08:15?"
→ Deployment at 08:14
→ Traffic spike at 08:15
→ Database connections increased at 08:15
```

### 8. **DependencyCheckTool**
Check health of dependent services
```
"Payment service failing. Check dependencies:"
→ Database: healthy ✓
→ Redis: high latency ⚠️
→ External API: timeout errors ⚠️
```

### 9. **BaselineCompareTool**
Compare to same time yesterday/last week
```
"Is current CPU usage normal?"
→ Now: 75%
→ Same time yesterday: 45%
→ Same time last week: 40%
→ Conclusion: Abnormally high
```

---

## Tool Implementation Priority

### Week 1 (MVP)
1. ✅ ListIndicesAndFieldsTool (enhance existing)
2. ✅ SearchIndexTool (use existing)
3. ✅ TimeSeriesSearchTool (NEW - critical)

### Week 2
4. ✅ LogPatternAnalysisTool (use existing)
5. ✅ DataDistributionTool (use existing)
6. ✅ CompareTimeWindowsTool (NEW - critical)

### Future
7. CorrelationFinderTool
8. DependencyCheckTool
9. BaselineCompareTool

---

## Tool Design Principles

### 1. **Self-Describing**
Tool descriptions must be clear enough for LLM to use correctly:
```java
@Override
public String getDescription() {
    return "Compares metrics between two time windows. " +
           "Use this to identify what changed between normal and problem periods. " +
           "Input: index (string), field (string), " +
           "problemWindow (e.g., '2025-01-15 08:00 to 10:00'), " +
           "baselineWindow (e.g., '2025-01-14 08:00 to 10:00'). " +
           "Returns: Comparison showing significant changes. " +
           "Example: {\"index\":\"metrics-*\", \"field\":\"cpu_usage\", " +
           "\"problemWindow\":\"last 1 hour\", \"baselineWindow\":\"same time yesterday\"}";
}
```

### 2. **Intelligent Defaults**
Make tools work with minimal input:
```java
// If timeRange not specified, default to "last 1 hour"
String timeRange = parameters.getOrDefault("timeRange", "last 1 hour");

// If interval not specified, choose based on time range
String interval = parameters.get("interval");
if (interval == null) {
    interval = autoSelectInterval(timeRange); // 1h range → 1m interval
}
```

### 3. **Actionable Output**
Return insights, not just data:
```
❌ Bad: "Found 234 errors"
✅ Good: "Found 234 errors, primarily 'Connection timeout' (150x).
         Started at 08:15. Compare to normal: 2-5 errors/hour."
```

### 4. **Error Handling**
Gracefully handle missing data:
```java
if (timestampField == null) {
    return "Cannot perform time-series analysis: no timestamp field found. " +
           "Available fields: " + String.join(", ", fields);
}
```

---

## Configuration Example

```json
{
  "agent": "RCA_Agent",
  "type": "plan_execute_reflect",
  "tools": [
    {
      "type": "ListIndicesAndFieldsTool",
      "name": "discover_data_structure",
      "description": "Discover available indices and fields",
      "auto_run_on_first_use": true
    },
    {
      "type": "SearchIndexTool",
      "name": "search_logs",
      "description": "Search for specific events/logs"
    },
    {
      "type": "TimeSeriesSearchTool",
      "name": "find_when_started",
      "description": "Find when issue started"
    },
    {
      "type": "LogPatternAnalysisTool",
      "name": "analyze_error_patterns",
      "description": "Identify common error patterns"
    },
    {
      "type": "DataDistributionTool",
      "name": "analyze_metric_distribution",
      "description": "Analyze metric distributions and anomalies"
    },
    {
      "type": "CompareTimeWindowsTool",
      "name": "compare_before_after",
      "description": "Compare problem vs normal periods"
    }
  ],
  "parameters": {
    "system_prompt": "{{UNIVERSAL_METHODOLOGIES}}",
    "planner_prompt": "First use discover_data_structure to understand available data. Then apply appropriate methodology.",
    "max_steps": 15
  }
}
```

---

## Summary

**Essential 6 Tools for MVP:**

| Tool | Status | Priority | Purpose |
|------|--------|----------|---------|
| ListIndicesAndFieldsTool | Enhance existing | P0 | Discovery |
| SearchIndexTool | Use existing | P0 | Basic search |
| TimeSeriesSearchTool | Build new | P0 | Timeline analysis |
| LogPatternAnalysisTool | Use existing | P1 | Error clustering |
| DataDistributionTool | Use existing | P1 | Metric analysis |
| CompareTimeWindowsTool | Build new | P1 | Before/after comparison |

**These 6 tools cover:**
- ✅ Discovery (what data exists)
- ✅ Search (find specific things)
- ✅ Timeline (when did it start)
- ✅ Patterns (what's common)
- ✅ Distribution (is it abnormal)
- ✅ Comparison (what changed)

This enables 80%+ of RCA scenarios!
