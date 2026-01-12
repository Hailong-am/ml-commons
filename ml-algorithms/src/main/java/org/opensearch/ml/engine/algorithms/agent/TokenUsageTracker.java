/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.algorithms.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.log4j.Log4j2;

/**
 * Thread-safe tracker for aggregating token usage across multiple LLM calls in agent workflows.
 * Tracks both individual call details and cumulative totals for input/output tokens.
 */
@Log4j2
public class TokenUsageTracker {
    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    private final List<Map<String, Object>> callDetails = new CopyOnWriteArrayList<>();
    private final AtomicInteger callCounter = new AtomicInteger(0);

    /**
     * Add token usage from a single LLM call
     *
     * @param callType Type of call (e.g., "planner", "executor")
     * @param step Step number in the workflow
     * @param callNumber Call number within the step
     * @param usage Map containing token usage data (input_tokens, output_tokens)
     */
    public void addUsage(String callType, int step, int callNumber, Map<String, Object> usage) {
        addUsage(callType, step, callNumber, null, usage);
    }

    /**
     * Add token usage from a single LLM call with step description
     *
     * @param callType Type of call (e.g., "planner", "executor")
     * @param step Step number in the workflow
     * @param callNumber Call number within the step
     * @param stepDescription Description of what this step does
     * @param usage Map containing token usage data (input_tokens, output_tokens)
     */
    public void addUsage(String callType, int step, int callNumber, String stepDescription, Map<String, Object> usage) {
        if (usage == null || usage.isEmpty()) {
            log.debug("No token usage data provided for call type: {}, step: {}, call: {}", callType, step, callNumber);
            return;
        }

        try {
            // Try both camelCase and snake_case formats
            long inputTokens = getTokenValue(usage, "inputTokens");
            if (inputTokens == 0) {
                inputTokens = getTokenValue(usage, "input_tokens");
            }

            long outputTokens = getTokenValue(usage, "outputTokens");
            if (outputTokens == 0) {
                outputTokens = getTokenValue(usage, "output_tokens");
            }

            if (inputTokens == 0 && outputTokens == 0) {
                log.debug("Zero token usage for call type: {}, step: {}, call: {}", callType, step, callNumber);
                return;
            }

            totalInputTokens.addAndGet(inputTokens);
            totalOutputTokens.addAndGet(outputTokens);

            Map<String, Object> callDetail = new HashMap<>();
            callDetail.put("call_type", callType);
            callDetail.put("step", step);
            callDetail.put("call", callNumber);
            if (stepDescription != null && !stepDescription.isEmpty()) {
                callDetail.put("step_description", stepDescription);
            }
            callDetail.put("input_tokens", inputTokens);
            callDetail.put("output_tokens", outputTokens);
            callDetail.put("total_tokens", inputTokens + outputTokens);

            callDetails.add(callDetail);
            callCounter.incrementAndGet();

            log
                .debug(
                    "Added token usage - Type: {}, Step: {}, Call: {}, Input: {}, Output: {}, Total: {}",
                    callType,
                    step,
                    callNumber,
                    inputTokens,
                    outputTokens,
                    inputTokens + outputTokens
                );
        } catch (Exception e) {
            log.warn("Failed to track token usage for call type: {}, step: {}, call: {}", callType, step, callNumber, e);
        }
    }

    /**
     * Simplified method for adding usage without call number tracking
     */
    public void addUsage(String callType, int step, Map<String, Object> usage) {
        addUsage(callType, step, 1, null, usage);
    }

    /**
     * Simplified method for adding usage with step description but without call number tracking
     */
    public void addUsage(String callType, int step, String stepDescription, Map<String, Object> usage) {
        addUsage(callType, step, 1, stepDescription, usage);
    }

    /**
     * Extract token value from usage map, handling various numeric types
     */
    private long getTokenValue(Map<String, Object> usage, String key) {
        Object value = usage.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                log.warn("Failed to parse token value for key {}: {}", key, value);
            }
        }
        return 0L;
    }

    /**
     * Get aggregated token usage summary
     *
     * @return Map containing total_input_tokens, total_output_tokens, total_tokens, call_count, and breakdown
     */
    public Map<String, Object> getAggregatedUsage() {
        Map<String, Object> result = new HashMap<>();
        result.put("total_input_tokens", totalInputTokens.get());
        result.put("total_output_tokens", totalOutputTokens.get());
        result.put("total_tokens", totalInputTokens.get() + totalOutputTokens.get());
        result.put("call_count", callCounter.get());
        result.put("breakdown", new ArrayList<>(callDetails));
        return result;
    }

    /**
     * Get simple summary without detailed breakdown
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> result = new HashMap<>();
        result.put("total_input_tokens", totalInputTokens.get());
        result.put("total_output_tokens", totalOutputTokens.get());
        result.put("total_tokens", totalInputTokens.get() + totalOutputTokens.get());
        result.put("call_count", callCounter.get());
        return result;
    }

    /**
     * Reset all tracked data
     */
    public void reset() {
        totalInputTokens.set(0);
        totalOutputTokens.set(0);
        callDetails.clear();
        callCounter.set(0);
        log.debug("Token usage tracker reset");
    }

    /**
     * Check if any token usage has been tracked
     */
    public boolean hasUsage() {
        return callCounter.get() > 0;
    }

    /**
     * Get total input tokens
     */
    public long getTotalInputTokens() {
        return totalInputTokens.get();
    }

    /**
     * Get total output tokens
     */
    public long getTotalOutputTokens() {
        return totalOutputTokens.get();
    }

    /**
     * Get total tokens (input + output)
     */
    public long getTotalTokens() {
        return totalInputTokens.get() + totalOutputTokens.get();
    }

    /**
     * Get number of LLM calls tracked
     */
    public int getCallCount() {
        return callCounter.get();
    }
}
