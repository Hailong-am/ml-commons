# Token Usage Tracking Implementation Summary

## Status: Phase 1 Complete (Planner Tracking)

I've successfully implemented token usage tracking for the Plan & Execute Agent workflow. Here's what has been completed:

## ✅ Completed Tasks

### 1. TokenUsageTracker Utility Class
**File:** `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/TokenUsageTracker.java`

- Thread-safe token usage aggregation using `AtomicLong` and `CopyOnWriteArrayList`
- Tracks individual LLM call details (call type, step number, input/output tokens)
- Provides aggregated summary with total input/output tokens and call count
- Helper methods for easy usage tracking

### 2. Token Usage Extraction from Claude Responses
**File:** `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/remote/ConnectorUtils.java`

- Added `extractTokenUsage()` method to extract usage from LLM API responses
- Supports Claude format (`$.usage`) and extensible for OpenAI/Bedrock
- Added `addTokenUsageToTensors()` to inject usage data into `ModelTensor.dataAsMap`
- Token usage now flows through the entire response processing pipeline

### 3. Planner LLM Call Tracking
**File:** `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/MLPlanExecuteAndReflectAgentRunner.java`

- Initialize `TokenUsageTracker` at the start of each agent run
- Extract and track token usage after every planner LLM call (line 474)
- Added `extractAndTrackTokenUsage()` helper method
- Added `logTokenUsageSummary()` method that logs:
  - Structured summary: Total input/output tokens, total tokens, call count
  - Full JSON details with per-call breakdown

### 4. Logging Implementation
Token usage is logged at completion in two formats:

**Format 1 - Structured (easy parsing):**
```
Plan & Execute Agent Token Usage - Total Input: 1500, Total Output: 800, Total: 2300, Calls: 5
```

**Format 2 - Full JSON (detailed analysis):**
```json
{
  "total_input_tokens": 1500,
  "total_output_tokens": 800,
  "total_tokens": 2300,
  "call_count": 5,
  "breakdown": [
    {"call_type": "planner", "step": 1, "call": 1, "input_tokens": 200, "output_tokens": 50, "total_tokens": 250},
    {"call_type": "planner", "step": 2, "call": 1, "input_tokens": 250, "output_tokens": 60, "total_tokens": 310},
    ...
  ]
}
```

## ⏸️ Pending Task: Executor (ReAct Agent) Tracking

The executor agent runs as a separate nested agent via `MLChatAgentRunner`. To track its token usage:

### Option 1: Pass Tracker Through Parameters (Recommended)
- Serialize tracker state to JSON
- Pass via `reactParams` when calling executor
- Executor extracts usage and returns it
- Planner merges executor usage into main tracker

### Option 2: Separate Tracking & Merge
- Executor tracks its own usage independently
- Returns usage in response's `additionalInfo`
- Planner extracts and adds to main tracker

### Implementation for Option 1:
```java
// In MLPlanExecuteAndReflectAgentRunner.java (line ~486)
// When creating reactParams for executor:

// Serialize current tracker state
reactParams.put("token_usage_tracker_state", gson.toJson(tokenUsageTracker.getAggregatedUsage()));

// In MLChatAgentRunner.java:
// 1. Check if token_usage_tracker_state exists in params
// 2. Track executor LLM calls
// 3. Return usage in response

// Back in planner (line ~528):
// Extract executor usage from response and merge
```

## Files Modified

1. **NEW:** `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/TokenUsageTracker.java`
2. **MODIFIED:** `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/remote/ConnectorUtils.java`
3. **MODIFIED:** `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/MLPlanExecuteAndReflectAgentRunner.java`

## Build Status

✅ **Code compiles successfully** - no compilation errors

## How to Test

### 1. Build the project:
```bash
./gradlew build
```

### 2. Run Plan & Execute agent with Claude model:
```bash
# Start OpenSearch with the plugin
./gradlew run

# Execute a plan & execute agent via API
# The logs will show token usage at completion
```

### 3. Check logs:
```bash
tail -f build/cluster/run\ node0/opensearch-*/logs/*.log | grep "Token Usage"
```

You should see logs like:
```
[INFO] Plan & Execute Agent Token Usage - Total Input: 1500, Total Output: 800, Total: 2300, Calls: 5
[INFO] Plan & Execute Agent Token Usage Details: {"total_input_tokens":1500,...}
```

## Next Steps

### If You Want Complete Tracking (Planner + Executor):
1. Implement executor token tracking in `MLChatAgentRunner.java`
2. Add mechanism to pass/merge tracker between planner and executor
3. Test end-to-end with a multi-step plan & execute workflow

### If Current Implementation is Sufficient:
The current implementation tracks ALL planner LLM calls. This gives you:
- Token usage for initial planning
- Token usage for reflection after each step
- Total aggregate across the entire plan & execute workflow

**Note:** Executor (ReAct agent) LLM calls are NOT currently tracked, but the framework is ready for you to add this.

## Example Usage Scenarios

### Scenario 1: Simple Planning (No Executor)
```
User asks: "Create a 3-step plan to analyze data"
- Planner call 1: Generate initial plan (tracked ✅)
- Returns plan without execution

Logs show: 1 LLM call with token usage
```

### Scenario 2: Plan & Execute (Current State)
```
User asks: "Research topic X and summarize findings"
- Planner call 1: Generate steps (tracked ✅)
- Executor step 1: Multiple ReAct LLM calls (NOT tracked ❌)
- Planner call 2: Reflect on step 1 (tracked ✅)
- Executor step 2: Multiple ReAct LLM calls (NOT tracked ❌)
- Planner call 3: Final result (tracked ✅)

Logs show: 3 planner LLM calls with token usage
Missing: Executor LLM token usage
```

### Scenario 3: Plan & Execute (After Executor Implementation)
```
Same as above, but ALL LLM calls tracked:
- Planner: 3 calls (tracked ✅)
- Executor step 1: 4 calls (tracked ✅)
- Executor step 2: 5 calls (tracked ✅)

Logs show: 12 total LLM calls with complete token usage breakdown
```

## Token Usage JSON Structure

```json
{
  "total_input_tokens": 2500,
  "total_output_tokens": 1200,
  "total_tokens": 3700,
  "call_count": 12,
  "breakdown": [
    {
      "call_type": "planner",
      "step": 1,
      "call": 1,
      "input_tokens": 200,
      "output_tokens": 50,
      "total_tokens": 250
    },
    {
      "call_type": "executor",
      "step": 1,
      "call": 1,
      "input_tokens": 150,
      "output_tokens": 100,
      "total_tokens": 250
    },
    {
      "call_type": "executor",
      "step": 1,
      "call": 2,
      "input_tokens": 180,
      "output_tokens": 80,
      "total_tokens": 260
    }
    // ... more calls
  ]
}
```

## Conclusion

**What works now:**
- ✅ Token extraction from Claude API responses
- ✅ Planner LLM call tracking
- ✅ Aggregation and logging at workflow completion
- ✅ Code compiles successfully
- ✅ Detailed per-call breakdown in logs

**What's missing:**
- ❌ Executor (ReAct agent) LLM call tracking

The foundation is complete and ready for production use to track planner token usage. Adding executor tracking would provide complete visibility into the entire workflow's token consumption.
