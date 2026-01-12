# Token Usage Tracking Implementation Plan

## Goal
Track and aggregate token usage across all LLM calls in a Plan & Execute Agent workflow (including planner calls and all executor ReAct agent steps), and make this data available via API.

## Current Architecture Analysis

### Plan & Execute Agent Flow
```
User Request
    ↓
MLPlanExecuteAndReflectAgentRunner.run()
    ↓
    ├─ [PLANNER] LLM Call #1 (initial planning) → generates steps
    │   ↓
    ├─ [EXECUTOR] ReAct Agent Step 1 execution (MLChatAgentRunner)
    │   ├─ LLM Call #2 (reasoning)
    │   ├─ LLM Call #3 (tool selection)
    │   └─ LLM Call #4 (response generation)
    │   ↓
    ├─ [PLANNER] LLM Call #5 (reflect on step 1) → generates next steps or result
    │   ↓
    ├─ [EXECUTOR] ReAct Agent Step 2 execution (MLChatAgentRunner)
    │   ├─ LLM Call #6-8 (multiple reasoning iterations)
    │   └─ ...
    │   ↓
    ├─ [PLANNER] LLM Call #9 (reflect on step 2)
    │   ↓
    └─ ... (continues until max_steps or final result)
        ↓
Final Response with aggregated token usage
```

### Key Files
1. **MLPlanExecuteAndReflectAgentRunner.java** - Main orchestrator
   - Line 451-460: Creates planner LLM prediction requests
   - Line 634: Executes planner calls via `MLPredictionTaskAction`
   - Line 520-627: Executes ReAct executor agent via `MLExecuteTaskAction`

2. **MLChatAgentRunner.java** - ReAct agent executor
   - Multiple LLM calls per step (reasoning → tool call → response)
   - Line 370: Initializes `additionalInfo` map for metadata

3. **ConnectorUtils.java** - Response processing
   - Line 233-308: `processOutput()` method
   - Converts LLM API responses into `ModelTensors`

4. **Interaction.java** - Memory storage
   - Line 62: `additionalInfo` field (Map<String, String>)

## Implementation Strategy

### Phase 1: Token Usage Extraction from Claude Responses

**Claude API Response Format:**
```json
{
  "content": [{"text": "response text"}],
  "usage": {
    "input_tokens": 100,
    "output_tokens": 50
  }
}
```

**What to do:**
1. Modify `ConnectorUtils.processOutput()` to extract usage data
2. Add usage info to `ModelTensor.dataAsMap` alongside response content

**Files to modify:**
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/remote/ConnectorUtils.java`

### Phase 2: Token Usage Aggregation Utility

**Create a new utility class:**
```java
// ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/TokenUsageTracker.java

public class TokenUsageTracker {
    private AtomicLong totalInputTokens = new AtomicLong(0);
    private AtomicLong totalOutputTokens = new AtomicLong(0);
    private List<Map<String, Object>> callDetails = new CopyOnWriteArrayList<>();

    public void addUsage(String callType, Map<String, Object> usage) {
        // Aggregate tokens
        // Track individual call details
    }

    public Map<String, Object> getAggregatedUsage() {
        // Return summary: total_input_tokens, total_output_tokens, total_tokens, call_count, breakdown
    }
}
```

**What to do:**
1. Create thread-safe token usage tracker
2. Support adding usage from individual LLM calls
3. Provide aggregation methods

**Files to create:**
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/TokenUsageTracker.java`

### Phase 3: Track Planner LLM Calls

**Modify MLPlanExecuteAndReflectAgentRunner:**

1. Initialize `TokenUsageTracker` at the start of `run()` method
2. Extract usage after each planner call (line 464-467)
3. Pass tracker through `executePlanningLoop()` recursive calls

**Locations to modify:**
```java
// Line 293: run() method - initialize tracker
private TokenUsageTracker tokenUsageTracker;

// Line 464: After planner response - extract usage
planListener.whenComplete(llmOutput -> {
    ModelTensorOutput modelTensorOutput = (ModelTensorOutput) llmOutput.getOutput();

    // NEW: Extract token usage
    extractAndTrackUsage(modelTensorOutput, "planner", tokenUsageTracker);

    Map<String, Object> parseLLMOutput = parseLLMOutput(allParams, modelTensorOutput);
    // ... rest of logic
});
```

**Files to modify:**
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/MLPlanExecuteAndReflectAgentRunner.java`

### Phase 4: Track Executor ReAct Agent LLM Calls

**Modify MLChatAgentRunner:**

1. Accept `TokenUsageTracker` as parameter (passed from planner)
2. Extract usage after each LLM call in the ReAct loop
3. Update `additionalInfo` with per-step token usage

**Locations to modify:**
```java
// In run() method or wherever LLM prediction happens
// After LLM response is received, extract usage:
extractAndTrackUsage(modelTensorOutput, "executor_step_N", tokenUsageTracker);

// Store in additionalInfo
additionalInfo.put("executor_step_N_tokens", usage);
```

**Challenge:** Need to pass tracker from plan & execute to executor agent
- Pass via `AgentMLInput` parameters
- Serialize/deserialize tracker state
- Or: Retrieve and merge after executor completes

**Files to modify:**
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/MLChatAgentRunner.java`
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/MLAgentExecutor.java`

### Phase 5: Log Aggregated Token Usage

**In MLPlanExecuteAndReflectAgentRunner:**

After all steps complete (in `saveAndReturnFinalResult()` and `handleMaxStepsReached()`):

```java
// Line 755-811: saveAndReturnFinalResult()
// Before returning final result, log aggregated token usage

Map<String, Object> aggregatedUsage = tokenUsageTracker.getAggregatedUsage();
log.info("Plan & Execute Agent Token Usage Summary: {}", gson.toJson(aggregatedUsage));

// Also log in structured format for easy parsing
log.info("Total Input Tokens: {}, Total Output Tokens: {}, Total Tokens: {}, Call Count: {}",
         aggregatedUsage.get("total_input_tokens"),
         aggregatedUsage.get("total_output_tokens"),
         aggregatedUsage.get("total_tokens"),
         aggregatedUsage.get("call_count"));

// Then continue with normal flow
Map<String, Object> updateContent = new HashMap<>();
updateContent.put(INTERACTIONS_RESPONSE_FIELD, finalResult);
memory.update(parentInteractionId, updateContent, ...);
```

**Token Usage Log Output Example:**
```
[INFO] Plan & Execute Agent Token Usage Summary: {"total_input_tokens":1500,"total_output_tokens":800,"total_tokens":2300,"call_count":9,"breakdown":[{"call_type":"planner","step":1,"input_tokens":200,"output_tokens":50},{"call_type":"executor_step_1","call":1,"input_tokens":150,"output_tokens":100},...]}

[INFO] Total Input Tokens: 1500, Total Output Tokens: 800, Total Tokens: 2300, Call Count: 9
```

**Files to modify:**
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/MLPlanExecuteAndReflectAgentRunner.java`

## Detailed Implementation Steps

### Step 1: Extract Token Usage from Claude Response

**File:** `ConnectorUtils.java`

**Location:** Line 233-308 in `processOutput()` method

**Changes:**
```java
public static ModelTensors processOutput(...) {
    // ... existing code ...

    // NEW: Extract token usage if available
    Map<String, Object> tokenUsage = extractTokenUsage(modelResponse);

    // ... process response as before ...

    // NEW: Add usage to first tensor's dataAsMap
    if (!modelTensors.isEmpty() && tokenUsage != null) {
        ModelTensor firstTensor = modelTensors.get(0);
        Map<String, Object> dataAsMap = firstTensor.getDataAsMap();
        if (dataAsMap != null) {
            Map<String, Object> enhancedMap = new HashMap<>(dataAsMap);
            enhancedMap.put("token_usage", tokenUsage);
            firstTensor.setDataAsMap(enhancedMap);
        }
    }

    return ModelTensors.builder().mlModelTensors(modelTensors).build();
}

private static Map<String, Object> extractTokenUsage(String modelResponse) {
    try {
        if (org.opensearch.ml.common.utils.StringUtils.isJson(modelResponse)) {
            Object usageObj = JsonPath.read(modelResponse, "$.usage");
            if (usageObj instanceof Map) {
                return (Map<String, Object>) usageObj;
            }
        }
    } catch (Exception e) {
        log.debug("No token usage found in response", e);
    }
    return null;
}
```

### Step 2: Create TokenUsageTracker

**File:** `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/TokenUsageTracker.java` (new)

```java
package org.opensearch.ml.engine.algorithms.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class TokenUsageTracker {
    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    private final List<Map<String, Object>> callDetails = new CopyOnWriteArrayList<>();

    public void addUsage(String callType, int step, int callNumber, Map<String, Object> usage) {
        if (usage == null || usage.isEmpty()) {
            return;
        }

        try {
            long inputTokens = getTokenValue(usage, "input_tokens");
            long outputTokens = getTokenValue(usage, "output_tokens");

            totalInputTokens.addAndGet(inputTokens);
            totalOutputTokens.addAndGet(outputTokens);

            Map<String, Object> callDetail = new HashMap<>();
            callDetail.put("call_type", callType);
            callDetail.put("step", step);
            callDetail.put("call", callNumber);
            callDetail.put("input_tokens", inputTokens);
            callDetail.put("output_tokens", outputTokens);
            callDetail.put("total_tokens", inputTokens + outputTokens);

            callDetails.add(callDetail);

            log.debug("Added token usage - Type: {}, Step: {}, Call: {}, Input: {}, Output: {}",
                     callType, step, callNumber, inputTokens, outputTokens);
        } catch (Exception e) {
            log.warn("Failed to track token usage", e);
        }
    }

    private long getTokenValue(Map<String, Object> usage, String key) {
        Object value = usage.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    public Map<String, Object> getAggregatedUsage() {
        Map<String, Object> result = new HashMap<>();
        result.put("total_input_tokens", totalInputTokens.get());
        result.put("total_output_tokens", totalOutputTokens.get());
        result.put("total_tokens", totalInputTokens.get() + totalOutputTokens.get());
        result.put("call_count", callDetails.size());
        result.put("breakdown", new ArrayList<>(callDetails));
        return result;
    }

    public void reset() {
        totalInputTokens.set(0);
        totalOutputTokens.set(0);
        callDetails.clear();
    }
}
```

### Step 3: Modify MLPlanExecuteAndReflectAgentRunner

**Key Changes:**

1. Add instance variable for tracker
2. Initialize tracker in `run()` method
3. Extract usage after planner calls
4. Pass tracker to executor
5. Store aggregated usage in final interaction

**Specific code locations and changes documented in Phase 3 above.**

### Step 4: Modify MLChatAgentRunner (Executor)

**Challenge:** The executor runs as a separate agent via `MLExecuteTaskAction`. Need to:
- Either pass tracker state through API parameters
- Or track executor usage separately and merge later

**Recommended approach:** Track separately and merge
- Executor returns token usage in response's `additionalInfo`
- Planner extracts and adds to its tracker

### Step 5: Store and Retrieve

**Storage:** Update `Interaction.additionalInfo` with JSON string of aggregated usage

**Retrieval:** Use existing GET interaction API, parse `additionalInfo` client-side

## Testing Strategy

1. **Unit Tests**
   - TokenUsageTracker add/aggregate logic
   - Token extraction from Claude response formats

2. **Integration Tests**
   - Full plan & execute workflow with mocked Claude responses
   - Verify usage aggregation across multiple steps
   - Test error handling when usage data is missing

3. **Manual Testing**
   - Deploy to test cluster
   - Execute real plan & execute agent with Claude
   - Verify token counts match Claude API logs

## Documentation

**Create:** `docs/token-usage-tracking.md`

**Contents:**
- Feature overview
- How to enable/configure
- API examples for retrieving usage
- Usage data format
- Troubleshooting

## Timeline Estimate

- Phase 1 (Extract usage): 2-3 hours
- Phase 2 (Tracker utility): 1-2 hours
- Phase 3 (Planner tracking): 2-3 hours
- Phase 4 (Executor tracking): 3-4 hours
- Phase 5 (Log aggregated usage): 1 hour

**Total:** 9-13 hours (simplified - no storage, no API, no tests)

## Open Questions

1. **Should we track usage for tool calls?** (Tools might also use LLMs)
2. **Log level?** Should we use INFO or DEBUG for the detailed breakdown?
3. **Other LLM providers?** Design should support OpenAI, Bedrock, etc. with different response formats

## Next Steps

1. ✅ Review and approve this plan
2. Implement Phase 1 (extract token usage from Claude responses)
3. Implement Phase 2 (create TokenUsageTracker utility)
4. Implement Phase 3 (track planner LLM calls)
5. Implement Phase 4 (track executor LLM calls)
6. Implement Phase 5 (log aggregated results)
7. Test manually with real Claude API calls
8. Verify logs show correct token usage

## Implementation Order

**Step 1:** Create TokenUsageTracker utility class
**Step 2:** Extract token usage in ConnectorUtils.processOutput()
**Step 3:** Add tracking to MLPlanExecuteAndReflectAgentRunner (planner calls)
**Step 4:** Add tracking to MLChatAgentRunner (executor calls)
**Step 5:** Add logging at completion
