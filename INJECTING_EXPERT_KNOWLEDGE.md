# Injecting Expert Knowledge into RCA Agents

## The LLM Knowledge Paradox

**Question:** "LLMs already have lots of knowledge. Why inject more?"

**Answer:** Yes, but there's a critical difference:

### What LLMs Already Know (General Knowledge)
```
✅ Concepts: "The 5 Whys methodology helps find root causes"
✅ Definitions: "RED method = Rate, Errors, Duration"
✅ Theory: "Check logs when troubleshooting"
✅ Best practices: "Network issues often cause timeouts"
```

### What LLMs DON'T Reliably Do (Without Guidance)
```
❌ Systematic application: Following a methodology step-by-step
❌ Context-specific: Knowing YOUR system's architecture
❌ Consistent execution: Applying the same approach each time
❌ Structured output: Returning results in required format
❌ Tool orchestration: When to use which tool in your stack
❌ Domain patterns: Common failure modes in YOUR environment
```

## The Problem with Relying on General LLM Knowledge

### Example: Generic Response
```
User: "Service X is slow"

LLM (without guidance):
"You should check CPU, memory, network, database,
and application logs. Also consider recent deployments."

Problems:
- Too generic
- No specific order
- Doesn't leverage your monitoring tools
- Misses system-specific patterns
- No structured investigation
```

### Example: With Injected Knowledge
```
User: "Service X is slow"

LLM (with injected knowledge):
"Following the RED Method for Service X:

Step 1: Check RATE (requests/sec)
→ Tool: SearchIndexTool on metrics-* index
→ Query: service:X AND metric:request_rate [last 1h]

Step 2: Check ERRORS (error %)
→ Tool: SearchIndexTool on logs-* index
→ Query: service:X AND level:ERROR [last 1h]

Step 3: Check DURATION (latency)
→ Tool: DataDistributionTool
→ Field: response_time_ms, service:X

Based on results, proceed to specific subsystem..."

Benefits:
- Systematic approach
- Uses YOUR tools
- Specific to YOUR indices
- Follows proven methodology
- Reproducible investigation
```

## Types of Expert Knowledge to Inject

### 1. Troubleshooting Methodologies

Well-established frameworks that guide investigation:

#### The USE Method (Utilization, Saturation, Errors)
```yaml
methodology: "USE Method"
description: "For every resource, check Utilization, Saturation, and Errors"
applies_to: "Performance issues"
resources_to_check:
  - resource: "CPU"
    metrics:
      utilization: "cpu_percent"
      saturation: "load_average"
      errors: "cpu_errors"
    tools:
      - "SearchIndexTool on system-metrics"

  - resource: "Memory"
    metrics:
      utilization: "memory_percent"
      saturation: "swap_usage"
      errors: "oom_errors"
    tools:
      - "SearchIndexTool on system-metrics"
      - "LogPatternAnalysisTool for OOM"

  - resource: "Network"
    metrics:
      utilization: "network_throughput"
      saturation: "tcp_retransmits"
      errors: "network_errors"
    tools:
      - "SearchIndexTool on network-metrics"
      - "DataDistributionTool for packet analysis"

  - resource: "Disk"
    metrics:
      utilization: "disk_usage_percent"
      saturation: "disk_queue_length"
      errors: "disk_io_errors"
    tools:
      - "SearchIndexTool on disk-metrics"

investigation_order: ["CPU", "Memory", "Network", "Disk"]
```

#### The RED Method (Rate, Errors, Duration)
```yaml
methodology: "RED Method"
description: "For every service, check Rate, Errors, and Duration"
applies_to: "Service performance issues"
steps:
  1:
    metric: "Rate"
    description: "Request rate - is traffic unusual?"
    query_template: "service:{{service_name}} AND metric:request_rate"
    index: "metrics-*"
    tool: "SearchIndexTool"
    analysis: "Compare to baseline, check for spikes or drops"

  2:
    metric: "Errors"
    description: "Error rate - are there failures?"
    query_template: "service:{{service_name}} AND (level:ERROR OR status:5xx)"
    index: "logs-*"
    tool: "LogPatternAnalysisTool"
    analysis: "Identify error patterns, group by error type"

  3:
    metric: "Duration"
    description: "Response time - is it slow?"
    query_template: "service:{{service_name}} AND metric:response_time"
    index: "metrics-*"
    tool: "DataDistributionTool"
    analysis: "Check p50, p95, p99 latencies"

follow_up_rules:
  - if: "rate_dropped_significantly"
    then: "investigate_upstream_dependencies"
  - if: "error_rate_high"
    then: "investigate_error_patterns_first"
  - if: "duration_increased"
    then: "investigate_slow_components"
```

#### The 5 Whys
```yaml
methodology: "5 Whys"
description: "Ask 'why' 5 times to get to root cause"
applies_to: "All RCA scenarios"
template: |
  Symptom: {{initial_symptom}}

  Why 1: {{reason_1}}
  Evidence: {{evidence_1}}

  Why 2: {{reason_2}}
  Evidence: {{evidence_2}}

  Why 3: {{reason_3}}
  Evidence: {{evidence_3}}

  Why 4: {{reason_4}}
  Evidence: {{evidence_4}}

  Why 5: {{reason_5}}
  Evidence: {{evidence_5}}

  Root Cause: {{root_cause}}

rules:
  - "Each 'why' must be backed by concrete evidence from tools"
  - "Stop when you reach something you can fix (actionable root cause)"
  - "If you can't find evidence, use tools to gather it"
```

### 2. Common Failure Patterns

Document well-known failure modes:

```yaml
failure_patterns:

  - pattern: "Connection Pool Exhaustion"
    symptoms:
      - "Connection timeout errors"
      - "Slow database queries"
      - "503 errors from service"
    investigation_steps:
      1: "Check active connection count vs pool size"
      2: "Identify slow queries holding connections"
      3: "Check for connection leaks"
    typical_root_causes:
      - "Pool size too small for load"
      - "Long-running queries not timing out"
      - "Connection leak in application code"
    tools_to_use:
      - "SearchIndexTool: database.connections.active"
      - "SearchIndexTool: database.query.duration"
    resolution_patterns:
      - "Increase pool size"
      - "Add query timeout"
      - "Fix connection leak"

  - pattern: "Memory Leak"
    symptoms:
      - "Gradual memory increase over time"
      - "OOM errors"
      - "GC taking longer"
    investigation_steps:
      1: "Check memory usage trend over time"
      2: "Correlate with application metrics (objects created)"
      3: "Check for heap dump patterns"
    typical_root_causes:
      - "Objects not being garbage collected"
      - "Cache growing unbounded"
      - "Event listeners not removed"
    tools_to_use:
      - "DataDistributionTool: memory_usage over time"
      - "SearchIndexTool: gc_duration"

  - pattern: "Cascading Failure"
    symptoms:
      - "Multiple services failing simultaneously"
      - "Errors propagating upstream"
      - "Timeout errors across services"
    investigation_steps:
      1: "Identify first service that failed (timeline)"
      2: "Map dependency graph"
      3: "Check for retry storms"
    typical_root_causes:
      - "One service failure triggers retries"
      - "Circuit breakers not configured"
      - "Synchronous dependencies"
    tools_to_use:
      - "SearchIndexTool: service errors by timestamp"
      - "DataDistributionTool: request rates"

  - pattern: "DNS Resolution Failure"
    symptoms:
      - "Connection timeout to external services"
      - "Intermittent failures"
      - "No network errors in local system"
    investigation_steps:
      1: "Check DNS resolution times"
      2: "Test DNS from affected hosts"
      3: "Check DNS server health"
    typical_root_causes:
      - "DNS server overloaded"
      - "DNS cache expired"
      - "Network path to DNS blocked"
    tools_to_use:
      - "SearchIndexTool: dns logs"
      - "SearchIndexTool: network connectivity"
```

### 3. Investigation Decision Trees

Guide the agent through decision points:

```yaml
decision_tree:
  root:
    question: "What type of issue is this?"
    options:
      - label: "Performance (slow)"
        next: "performance_tree"
      - label: "Availability (down/errors)"
        next: "availability_tree"
      - label: "Data quality (incorrect data)"
        next: "data_quality_tree"

  performance_tree:
    question: "Is the issue affecting all users or specific users?"
    options:
      - label: "All users"
        next: "check_infrastructure"
      - label: "Specific users/requests"
        next: "check_application_logic"

  check_infrastructure:
    question: "Which resource is constrained?"
    steps:
      - "Apply USE method to all resources"
      - "Use DataDistributionTool on system metrics"
    options:
      - label: "CPU saturated"
        conclusion: "CPU bottleneck"
        recommendations:
          - "Scale horizontally"
          - "Optimize hot code paths"
      - label: "Memory saturated"
        conclusion: "Memory bottleneck"
        recommendations:
          - "Increase memory"
          - "Check for memory leaks"
      - label: "Network saturated"
        conclusion: "Network bottleneck"
        recommendations:
          - "Optimize data transfer"
          - "Add caching layer"

  availability_tree:
    question: "Can you reach the service at all?"
    options:
      - label: "No response (timeout)"
        next: "check_network_path"
      - label: "Returns errors (5xx)"
        next: "check_application_errors"

  check_network_path:
    steps:
      - "Check service health endpoint"
      - "Check network connectivity"
      - "Check DNS resolution"
      - "Check firewall rules"
    tools:
      - "SearchIndexTool: service.health"
      - "SearchIndexTool: network.connectivity"
```

### 4. System-Specific Knowledge

Document YOUR system's peculiarities:

```yaml
system_knowledge:
  architecture:
    description: "Microservices architecture on Kubernetes"
    components:
      - name: "api-gateway"
        type: "nginx"
        indices: ["api-gateway-logs-*", "api-gateway-metrics-*"]
        common_issues:
          - "Rate limiting triggers at 10k req/s"
          - "Connection pool exhaustion at 1k concurrent connections"

      - name: "payment-service"
        type: "java-spring-boot"
        indices: ["payment-logs-*", "payment-metrics-*"]
        dependencies: ["database", "redis", "external-payment-api"]
        common_issues:
          - "Database connection pool: max 50 connections"
          - "External API timeout: 30 seconds"
          - "Redis cache miss causes 10x slower response"

  known_incidents:
    - incident: "2024-12-01 payment outage"
      root_cause: "Database connection pool exhausted"
      symptom: "Payment timeouts"
      lessons_learned:
        - "Check connection pool first for payment timeouts"
        - "Database has max_connections=100"
      prevention: "Added connection pool monitoring"

    - incident: "2024-11-15 API slowdown"
      root_cause: "Redis cache cluster failover"
      symptom: "API latency increased 10x"
      lessons_learned:
        - "Check Redis health for sudden slowdowns"
        - "Cache miss causes database load spike"

  investigation_shortcuts:
    - symptom: "payment-service timeout"
      first_checks:
        - "Database connection pool usage"
        - "External payment API health"
        - "Redis availability"

    - symptom: "api-gateway 503"
      first_checks:
        - "Backend service health"
        - "Rate limit hit"
        - "Connection pool status"
```

## Implementation Strategies

### Strategy 1: Enhanced System Prompt

Inject knowledge directly into the agent's system prompt:

```java
public class EnhancedRCAPromptBuilder {

    private static final String BASE_SYSTEM_PROMPT = """
        You are an expert Site Reliability Engineer performing Root Cause Analysis.

        ## Your Investigation Framework

        When investigating issues, follow these proven methodologies:

        ### 1. Issue Classification
        First, categorize the issue:
        - Performance: Service is slow but functional
        - Availability: Service is down or returning errors
        - Data Quality: Service works but produces incorrect results

        ### 2. Apply Appropriate Methodology

        For PERFORMANCE issues, use the USE Method:
        - Utilization: How busy is the resource? (CPU%, memory%, etc.)
        - Saturation: How much work is queued? (load average, queue depth)
        - Errors: Are there any errors? (hardware errors, dropped packets)

        Check resources in this order: CPU → Memory → Network → Disk
        Use DataDistributionTool to analyze metric distributions.

        For SERVICE issues, use the RED Method:
        - Rate: Request rate (req/s) - is traffic normal?
        - Errors: Error rate (%) - what's failing?
        - Duration: Response time (ms) - how slow?

        Use LogPatternAnalysisTool to identify error patterns.

        ### 3. Apply the 5 Whys
        For each finding, ask "why" and gather evidence:
        - Why 1: State immediate cause + tool evidence
        - Why 2: What caused that? + tool evidence
        - ...continue until you reach an actionable root cause

        ### 4. Check Common Patterns First

        Before deep investigation, check these common failure modes:

        **"Connection timeout" symptoms:**
        ✓ Check: Connection pool exhaustion
        ✓ Check: Network latency spike
        ✓ Check: DNS resolution issues
        ✓ Tool: SearchIndexTool on connection metrics

        **"Memory errors" symptoms:**
        ✓ Check: Memory leak (gradual increase)
        ✓ Check: Sudden traffic spike
        ✓ Check: Large object allocation
        ✓ Tool: DataDistributionTool on memory_usage over time

        **"Slow database queries" symptoms:**
        ✓ Check: Missing indexes
        ✓ Check: Lock contention
        ✓ Check: Full table scans
        ✓ Tool: SearchIndexTool on slow query logs

        ### 5. System-Specific Knowledge

        %s

        ## Investigation Rules

        1. ALWAYS gather evidence before concluding
        2. Use tools, don't guess
        3. Check most likely causes first (based on symptoms)
        4. Follow methodologies systematically
        5. Stop when you reach an actionable root cause
        6. Document your reasoning at each step

        ## Output Format

        Structure your investigation as:
        ```json
        {
          "steps": ["step 1", "step 2", ...],
          "result": "final conclusion with root cause"
        }
        ```
        """;

    public static String buildSystemPrompt(MLAgent agent) {
        // Load system-specific knowledge
        String systemKnowledge = loadSystemKnowledge(agent);

        return String.format(BASE_SYSTEM_PROMPT, systemKnowledge);
    }

    private static String loadSystemKnowledge(MLAgent agent) {
        // This could come from agent parameters or external config
        StringBuilder kb = new StringBuilder();

        kb.append("### Your System Architecture\n\n");
        kb.append("Services:\n");
        kb.append("- api-gateway: Entry point, nginx-based\n");
        kb.append("  Indices: api-gateway-logs-*, api-gateway-metrics-*\n");
        kb.append("  Rate limit: 10,000 req/s\n");
        kb.append("  Connection pool: 1,000 max\n\n");

        kb.append("- payment-service: Handles payments, Java Spring Boot\n");
        kb.append("  Indices: payment-logs-*, payment-metrics-*\n");
        kb.append("  Dependencies: postgres-db, redis-cache, stripe-api\n");
        kb.append("  DB connection pool: 50 max\n");
        kb.append("  Common issue: Pool exhaustion causes timeouts\n\n");

        kb.append("### Known Shortcuts\n\n");
        kb.append("If symptom contains 'payment timeout':\n");
        kb.append("→ First check: Database connection pool usage\n");
        kb.append("→ Second check: External API (Stripe) health\n");
        kb.append("→ Third check: Redis cache hit rate\n\n");

        return kb.toString();
    }
}
```

### Strategy 2: Knowledge Base Tool

Create a tool that provides methodology guidance:

```java
public class TroubleshootingKnowledgeBaseTool implements Tool {

    private final Map<String, MethodologyGuide> methodologies;
    private final Map<String, FailurePattern> failurePatterns;

    public TroubleshootingKnowledgeBaseTool() {
        this.methodologies = loadMethodologies();
        this.failurePatterns = loadFailurePatterns();
    }

    @Override
    public String getDescription() {
        return "Provides expert troubleshooting guidance based on proven methodologies. " +
               "Input: symptom description or question about investigation approach. " +
               "Returns: Recommended methodology, investigation steps, common patterns, and tools to use. " +
               "Example: {\"symptom\": \"service is slow\"} → Returns USE/RED method guidance";
    }

    @Override
    public <T> void run(Map<String, String> parameters, ActionListener<T> listener) {
        String symptom = parameters.get("symptom");
        String question = parameters.get("question");

        StringBuilder guidance = new StringBuilder();

        if (symptom != null) {
            // Provide methodology based on symptom
            IssueType issueType = classifyIssue(symptom);
            MethodologyGuide methodology = methodologies.get(issueType.getMethodology());

            guidance.append("## Recommended Investigation Approach\n\n");
            guidance.append(String.format("Issue Type: %s\n", issueType.getName()));
            guidance.append(String.format("Methodology: %s\n\n", methodology.getName()));
            guidance.append(methodology.getDescription()).append("\n\n");

            guidance.append("### Investigation Steps:\n");
            for (int i = 0; i < methodology.getSteps().size(); i++) {
                MethodologyStep step = methodology.getSteps().get(i);
                guidance.append(String.format("%d. %s\n", i + 1, step.getDescription()));
                guidance.append(String.format("   Tool: %s\n", step.getRecommendedTool()));
                guidance.append(String.format("   Query: %s\n\n", step.getQueryTemplate()));
            }

            // Check for matching failure patterns
            List<FailurePattern> matchingPatterns = findMatchingPatterns(symptom);
            if (!matchingPatterns.isEmpty()) {
                guidance.append("\n### Common Failure Patterns to Check:\n\n");
                for (FailurePattern pattern : matchingPatterns) {
                    guidance.append(String.format("**%s**\n", pattern.getName()));
                    guidance.append(String.format("- Typical cause: %s\n", pattern.getTypicalCause()));
                    guidance.append(String.format("- Quick check: %s\n", pattern.getQuickCheck()));
                    guidance.append(String.format("- Resolution: %s\n\n", pattern.getTypicalResolution()));
                }
            }
        }

        if (question != null) {
            // Answer methodology questions
            String answer = answerMethodologyQuestion(question);
            guidance.append(answer);
        }

        listener.onResponse((T) guidance.toString());
    }

    private IssueType classifyIssue(String symptom) {
        String lower = symptom.toLowerCase();

        if (lower.contains("slow") || lower.contains("latency") || lower.contains("performance")) {
            return new IssueType("Performance", "USE_METHOD");
        } else if (lower.contains("error") || lower.contains("fail") || lower.contains("down")) {
            return new IssueType("Availability", "RED_METHOD");
        } else if (lower.contains("incorrect") || lower.contains("wrong") || lower.contains("corrupt")) {
            return new IssueType("Data Quality", "FIVE_WHYS");
        }

        return new IssueType("General", "FIVE_WHYS");
    }

    private List<FailurePattern> findMatchingPatterns(String symptom) {
        return failurePatterns.values().stream()
            .filter(pattern -> pattern.matches(symptom))
            .sorted(Comparator.comparingInt(FailurePattern::getMatchScore).reversed())
            .limit(3)
            .collect(Collectors.toList());
    }

    private Map<String, MethodologyGuide> loadMethodologies() {
        Map<String, MethodologyGuide> methods = new HashMap<>();

        // USE Method
        MethodologyGuide useMethod = MethodologyGuide.builder()
            .name("USE Method")
            .description("For every resource, check Utilization, Saturation, and Errors")
            .applicableFor("Performance issues")
            .steps(Arrays.asList(
                new MethodologyStep(
                    "Check CPU Utilization",
                    "DataDistributionTool",
                    "index:system-metrics AND metric:cpu_percent"
                ),
                new MethodologyStep(
                    "Check CPU Saturation (load average)",
                    "SearchIndexTool",
                    "index:system-metrics AND metric:load_average"
                ),
                new MethodologyStep(
                    "Check Memory Utilization",
                    "DataDistributionTool",
                    "index:system-metrics AND metric:memory_percent"
                ),
                new MethodologyStep(
                    "Check Network Utilization",
                    "DataDistributionTool",
                    "index:network-metrics AND metric:throughput"
                )
            ))
            .build();

        methods.put("USE_METHOD", useMethod);

        // RED Method
        MethodologyGuide redMethod = MethodologyGuide.builder()
            .name("RED Method")
            .description("For every service, check Rate, Errors, and Duration")
            .applicableFor("Service issues")
            .steps(Arrays.asList(
                new MethodologyStep(
                    "Check Request Rate",
                    "DataDistributionTool",
                    "index:metrics-* AND service:{{service}} AND metric:request_rate"
                ),
                new MethodologyStep(
                    "Check Error Rate",
                    "LogPatternAnalysisTool",
                    "index:logs-* AND service:{{service}} AND level:ERROR"
                ),
                new MethodologyStep(
                    "Check Response Duration",
                    "DataDistributionTool",
                    "index:metrics-* AND service:{{service}} AND metric:response_time"
                )
            ))
            .build();

        methods.put("RED_METHOD", redMethod);

        return methods;
    }

    private Map<String, FailurePattern> loadFailurePatterns() {
        Map<String, FailurePattern> patterns = new HashMap<>();

        patterns.put("connection_pool_exhaustion", FailurePattern.builder()
            .name("Connection Pool Exhaustion")
            .symptoms(Arrays.asList("timeout", "connection", "slow database"))
            .typicalCause("Database connection pool size too small for current load")
            .quickCheck("Check active connections vs pool max size")
            .investigationQuery("index:database-metrics AND metric:connection_pool_active")
            .typicalResolution("Increase connection pool size or reduce connection hold time")
            .build());

        patterns.put("memory_leak", FailurePattern.builder()
            .name("Memory Leak")
            .symptoms(Arrays.asList("oom", "out of memory", "gradual memory increase"))
            .typicalCause("Objects not being garbage collected")
            .quickCheck("Check memory usage trend over last 24 hours")
            .investigationQuery("index:system-metrics AND metric:memory_usage")
            .typicalResolution("Identify and fix object retention issue")
            .build());

        patterns.put("dns_failure", FailurePattern.builder()
            .name("DNS Resolution Failure")
            .symptoms(Arrays.asList("timeout", "unreachable", "intermittent"))
            .typicalCause("DNS server overloaded or misconfigured")
            .quickCheck("Check DNS resolution times")
            .investigationQuery("index:dns-logs AND resolution_time > 1000")
            .typicalResolution("Fix DNS configuration or scale DNS servers")
            .build());

        return patterns;
    }
}
```

### Strategy 3: Pre-Investigation Context Manager Hook

Inject knowledge before investigation starts:

```java
public class KnowledgeInjectionContextManager implements ContextManager {

    @Override
    public void preProcess(
        Map<String, String> parameters,
        List<String> interactions,
        Memory memory,
        ActionListener<ContextManagerContext> listener
    ) {
        String question = parameters.get("question");

        // Analyze question to determine what knowledge to inject
        KnowledgeRecommendation recommendation = analyzeAndRecommend(question);

        // Build enhanced context
        StringBuilder enhancedInteractions = new StringBuilder();
        enhancedInteractions.append("## Investigation Guidance\n\n");
        enhancedInteractions.append(recommendation.getMethodologyGuidance());
        enhancedInteractions.append("\n\n");
        enhancedInteractions.append(recommendation.getRelevantPatterns());
        enhancedInteractions.append("\n\n");
        enhancedInteractions.append("## Your Investigation\n\n");
        enhancedInteractions.append(String.join("\n", interactions));

        // Return enhanced context
        ContextManagerContext context = new ContextManagerContext();
        context.setToolInteractions(Arrays.asList(enhancedInteractions.toString()));
        context.getParameters().put("enhanced_context", "true");

        listener.onResponse(context);
    }

    private KnowledgeRecommendation analyzeAndRecommend(String question) {
        // Simple keyword-based recommendation
        // In production, this could use embeddings for semantic matching

        String lower = question.toLowerCase();
        KnowledgeRecommendation rec = new KnowledgeRecommendation();

        if (lower.contains("slow") || lower.contains("performance")) {
            rec.setMethodologyGuidance(USE_METHOD_GUIDE);
            rec.setRelevantPatterns(Arrays.asList(
                PATTERN_CPU_BOTTLENECK,
                PATTERN_MEMORY_LEAK,
                PATTERN_DISK_IO
            ));
        } else if (lower.contains("error") || lower.contains("fail")) {
            rec.setMethodologyGuidance(RED_METHOD_GUIDE);
            rec.setRelevantPatterns(Arrays.asList(
                PATTERN_CONNECTION_POOL,
                PATTERN_CASCADE_FAILURE,
                PATTERN_EXTERNAL_API_FAILURE
            ));
        } else {
            rec.setMethodologyGuidance(FIVE_WHYS_GUIDE);
            rec.setRelevantPatterns(Collections.emptyList());
        }

        return rec;
    }
}
```

## Configuration Example

Add knowledge to your agent configuration:

```json
{
  "name": "RCA_Agent_With_Expert_Knowledge",
  "type": "plan_execute_reflect",
  "llm": {
    "model_id": "claude-sonnet-4.5",
    "parameters": {}
  },
  "tools": [
    {
      "type": "TroubleshootingKnowledgeBaseTool",
      "name": "get_methodology_guidance",
      "description": "Get expert troubleshooting guidance"
    },
    {
      "type": "SearchIndexTool",
      "name": "search_logs"
    },
    {
      "type": "DataDistributionTool",
      "name": "analyze_metrics"
    },
    {
      "type": "LogPatternAnalysisTool",
      "name": "analyze_log_patterns"
    }
  ],
  "parameters": {
    "system_prompt": "You are an expert SRE. ALWAYS start by using get_methodology_guidance tool with the symptom to get investigation guidance. Follow the recommended methodology systematically.",
    "planner_prompt": "Before creating your plan, use get_methodology_guidance to understand the best investigation approach. Structure your plan based on the recommended methodology.",
    "max_steps": "15"
  }
}
```

## Expected Benefits

With injected expert knowledge:

✅ **Consistent investigations** - Same approach every time
✅ **Systematic coverage** - Don't miss obvious checks
✅ **Tool orchestration** - Knows which tool for what
✅ **Pattern recognition** - Checks common issues first
✅ **Reduced investigation time** - Follows proven paths
✅ **Better success rate** - Uses established methodologies
✅ **Explainable reasoning** - Clear methodology followed
✅ **No human validation needed** - Based on proven practices

## Why This Works Better Than Generic LLM Knowledge

1. **Structured Application**: LLMs know concepts but need guidance to apply them systematically
2. **Tool Integration**: LLM doesn't know YOUR tools and indices
3. **System-Specific**: LLM doesn't know YOUR architecture and common issues
4. **Consistent Output**: Ensures same approach every time, not random exploration
5. **Actionable Steps**: Provides concrete queries and tools, not generic advice

## Next Steps

1. **Start with methodologies** - Inject USE/RED/5 Whys guidance
2. **Add common patterns** - Document your system's frequent issues
3. **Create knowledge base tool** - Make it queryable by agent
4. **Test and refine** - See which knowledge helps most
5. **Keep updating** - Add new patterns as you discover them

This approach is **immediately usable** without waiting for validation data!
