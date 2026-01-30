# RCA Tools Implementation - Complete

## Summary

Successfully implemented and registered 2 critical RCA tools to complete the MVP tool set for root cause analysis in the ml-commons plugin.

## What Was Implemented

### 1. TimeSeriesSearchTool
**Location**: `ml-algorithms/src/main/java/org/opensearch/ml/engine/tools/TimeSeriesSearchTool.java`

**Purpose**: Identifies WHEN an issue started by analyzing data over time

**Key Features**:
- Date histogram aggregations with auto-selected intervals
- Baseline calculation from first 50% of data points
- Spike detection (>3x baseline threshold)
- Trend analysis (INCREASING/DECREASING/STABLE)
- Auto-detects timestamp field (defaults to @timestamp)
- Time range parsing (1h, 24h, 7d, etc.)

**Example Usage**:
```json
{
  "index": "app-logs-*",
  "query": "{\"match\":{\"level\":\"ERROR\"}}",
  "time_range": "24h"
}
```

**Returns**:
- Timeline showing when issue started
- Spike detection with timestamps
- Baseline comparison
- Trend analysis

### 2. CompareTimeWindowsTool
**Location**: `ml-algorithms/src/main/java/org/opensearch/ml/engine/tools/CompareTimeWindowsTool.java`

**Purpose**: Identifies WHAT changed by comparing problem vs baseline periods

**Key Features**:
- Parallel query execution for both windows
- Extended statistics (min, max, avg, stdDev)
- Percentile analysis (p50, p95, p99)
- Percent change calculations
- Significance detection (>20% avg or >30% p95)
- Direction classification (INCREASED/DECREASED/STABLE)

**Example Usage**:
```json
{
  "index": "app-metrics-*",
  "field": "response_time_ms",
  "problem_start": "2024-01-15T10:00:00Z",
  "problem_end": "2024-01-15T11:00:00Z",
  "baseline_start": "2024-01-15T08:00:00Z",
  "baseline_end": "2024-01-15T09:00:00Z"
}
```

**Returns**:
- Statistical comparison table
- Percent changes for all metrics
- Significance indicator
- Overall direction

## Registration Complete

Both tools have been registered in `MachineLearningPlugin.java`:

### Added Imports (lines 282-283, 293):
```java
import org.opensearch.ml.engine.tools.CompareTimeWindowsTool;
import org.opensearch.ml.engine.tools.TimeSeriesSearchTool;
```

### Factory Initialization (lines 845-846):
```java
TimeSeriesSearchTool.Factory.getInstance().init(client);
CompareTimeWindowsTool.Factory.getInstance().init(client);
```

### Factory Registration (lines 861-862):
```java
toolFactories.put(TimeSeriesSearchTool.TYPE, TimeSeriesSearchTool.Factory.getInstance());
toolFactories.put(CompareTimeWindowsTool.TYPE, CompareTimeWindowsTool.Factory.getInstance());
```

## Complete MVP Tool Set

Your RCA agent now has access to all 6 essential tools:

### ✅ Existing Tools (4)
1. **ListIndexTool** - Discover available indices
2. **SearchIndexTool** - Generic search with DSL queries
3. **IndexInsightTool** - Detect fields and index types
4. **MCP LogPatternAnalysisTool** - Analyze log patterns and anomalies
5. **MCP DataDistributionTool** - Analyze data distributions

### ✅ New Tools (2)
6. **TimeSeriesSearchTool** - Find WHEN issues started
7. **CompareTimeWindowsTool** - Find WHAT changed

## Build Status

✅ Code formatted with `./gradlew spotlessApply`
✅ Compilation successful for both modules
✅ All tools registered in MachineLearningPlugin
✅ Ready for integration testing

## Next Steps

### 1. Integration Testing
Test the new tools with real data:

```bash
# Build the plugin
./gradlew assemble

# Run integration tests
./gradlew integTest --tests="*TimeSeriesSearchTool*"
./gradlew integTest --tests="*CompareTimeWindowsTool*"
```

### 2. Agent Configuration
Create an agent configuration that includes all 6 tools plus universal troubleshooting methodologies (USE Method, RED Method, 5 Whys).

### 3. End-to-End RCA Testing
Test the complete RCA workflow:
1. User reports "service is slow"
2. Agent uses TimeSeriesSearchTool to identify when slowness started
3. Agent uses CompareTimeWindowsTool to identify what metric changed
4. Agent uses LogPatternAnalysisTool to find error patterns
5. Agent concludes root cause with evidence

### 4. Optional Enhancements
- **Auto-discovery Tool**: Automatically detect customer's indices, fields, services at initialization
- **Universal Methodologies**: Inject USE Method, RED Method, 5 Whys into agent prompts
- **Customer Knowledge API**: Allow customers to upload their own domain knowledge

## Usage Example

Complete RCA investigation flow:

```
User: "Payment service has high error rate"

Agent Step 1: Uses TimeSeriesSearchTool
→ Finds: Errors spiked at 2024-01-15T10:15:00Z (5x increase)

Agent Step 2: Uses CompareTimeWindowsTool
→ Compares: 10:00-11:00 (problem) vs 08:00-09:00 (baseline)
→ Finds: response_time increased by 450% (from 50ms to 275ms)

Agent Step 3: Uses LogPatternAnalysisTool
→ Finds: "Database connection timeout" pattern increased 10x

Agent Conclusion:
"Root Cause: Database connection timeouts started at 10:15 AM causing
response time to increase from 50ms to 275ms (450% increase). Error
rate increased 5x baseline. Evidence: 'Database connection timeout'
log pattern shows 10x increase during problem window."
```

## File Structure

```
ml-commons/
├── ml-algorithms/src/main/java/org/opensearch/ml/engine/tools/
│   ├── TimeSeriesSearchTool.java          ✅ NEW
│   ├── CompareTimeWindowsTool.java        ✅ NEW
│   ├── ListIndexTool.java                 ✅ Existing
│   ├── SearchIndexTool.java               ✅ Existing
│   └── IndexInsightTool.java              ✅ Existing
├── plugin/src/main/java/org/opensearch/ml/plugin/
│   └── MachineLearningPlugin.java         ✅ Modified (registration)
└── documentation/
    ├── MVP_TOOL_SET.md                    ✅ Design document
    ├── SAAS_KNOWLEDGE_INJECTION_STRATEGY.md  ✅ Strategy document
    ├── KNOWLEDGE_INJECTION_COMPARISON.md  ✅ Before/after examples
    └── RCA_TOOLS_IMPLEMENTATION_COMPLETE.md  ✅ This file
```

## Technical Details

### TimeSeriesSearchTool Implementation
- Uses DateHistogramAggregationBuilder for time bucketing
- Auto-selects interval: 1m (≤1h), 5m (≤6h), 15m (≤24h), 1h (≤7d), 4h (>7d)
- Calculates baseline from first 50% of buckets
- Detects spikes using 3x baseline threshold
- Handles missing data with minDocCount(0)
- Supports custom timestamp field or auto-detects @timestamp

### CompareTimeWindowsTool Implementation
- Parallel execution using CountDownLatch for efficiency
- ExtendedStats aggregation for comprehensive metrics
- Percentiles aggregation at 50th, 95th, 99th percentiles
- Significance detection: >20% average change or >30% p95 change
- Formats results as markdown tables with status icons
- Supports additional query filters for focusing analysis

## Performance Considerations

- **TimeSeriesSearchTool**: Single aggregation query, O(buckets) memory
- **CompareTimeWindowsTool**: Two parallel queries, O(1) memory (only aggregations)
- Both tools use `size: 0` to avoid fetching documents (only aggregations)
- Efficient for large indices (millions of documents)

## Troubleshooting

If you encounter build issues:

```bash
# Clean and rebuild
./gradlew clean build

# Format code
./gradlew spotlessApply

# Check specific module
./gradlew :opensearch-ml-algorithms:check
```

---

**Status**: ✅ COMPLETE - Ready for testing and deployment

**Date**: 2026-01-15

**Next Milestone**: Integration testing and agent configuration
