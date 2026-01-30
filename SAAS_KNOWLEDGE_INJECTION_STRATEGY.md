# Knowledge Injection for SaaS RCA Agent

## The Challenge

You're building a SaaS product where:
- **You** provide: ML-Commons RCA agent as a service
- **Customers** provide: Their own data in OpenSearch
- **Privacy**: You cannot see customer data
- **Variability**: Each customer has different systems, services, architectures

**Problem**: How do you inject domain knowledge when you don't know the customer's domain?

## Solution: Layered Knowledge Architecture

```
┌─────────────────────────────────────────────────────┐
│ Layer 1: Universal Methodologies (You Provide)     │
│ - USE Method, RED Method, 5 Whys                   │
│ - Generic troubleshooting patterns                  │
│ - Independent of customer domain                    │
└─────────────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────────────┐
│ Layer 2: Auto-Discovered Knowledge (Automated)     │
│ - Discover customer's indices/fields                │
│ - Learn service names from data                     │
│ - Detect metric patterns                            │
└─────────────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────────────┐
│ Layer 3: Customer-Provided Knowledge (Optional)    │
│ - Customer documents their architecture             │
│ - Custom failure patterns                           │
│ - Investigation shortcuts                           │
└─────────────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────────────┐
│ Layer 4: Learned Knowledge (Per-Tenant Isolated)   │
│ - Learn from validated RCAs (per customer)          │
│ - Build customer-specific patterns                  │
│ - Privacy-preserving learning                       │
└─────────────────────────────────────────────────────┘
```

## Layer 1: Universal Methodologies (Ship in Product)

**What you CAN provide**: Domain-agnostic troubleshooting frameworks

### Implementation

```java
/**
 * Built-in methodologies shipped with the product
 * Works for ANY customer, any domain
 */
public class UniversalTroubleshootingMethodologies {

    public static final String USE_METHOD = """
        # USE Method (For Performance Issues)

        For every resource type, systematically check:
        1. **Utilization**: How busy is the resource (0-100%)?
        2. **Saturation**: How much work is queued/waiting?
        3. **Errors**: Are there any errors reported?

        ## Resource Investigation Order
        1. CPU → 2. Memory → 3. Network → 4. Disk

        ## How to Apply
        - Use DataDistributionTool to check utilization metrics
        - Look for values >80% (high utilization)
        - Compare current vs baseline (last 24h avg)
        - Check for sudden spikes or gradual increases

        ## Generic Metric Names to Look For
        CPU: cpu_usage, cpu_percent, cpu_utilization, processor_usage
        Memory: memory_usage, mem_percent, memory_utilization, heap_used
        Network: network_throughput, bytes_sent, packets_dropped
        Disk: disk_usage, io_utilization, disk_percent
        """;

    public static final String RED_METHOD = """
        # RED Method (For Service Issues)

        For any service/endpoint, check:
        1. **Rate**: Request rate (requests/second) - is it unusual?
        2. **Errors**: Error rate (%) - what's failing?
        3. **Duration**: Response time - how slow?

        ## How to Apply
        - Identify the service/component with issues
        - Use SearchIndexTool to find relevant logs/metrics
        - Use LogPatternAnalysisTool to identify error patterns
        - Use DataDistributionTool to analyze latency distribution

        ## Generic Fields to Look For
        Rate: request_count, request_rate, throughput, requests_per_second
        Errors: error_count, error_rate, status_code, log_level
        Duration: response_time, latency, duration, elapsed_time
        """;

    public static final String FIVE_WHYS = """
        # Five Whys Method (Root Cause Deep Dive)

        Ask "why" repeatedly to drill down to root cause:

        ## Template
        Symptom: [Initial problem statement]

        Why 1: [Immediate cause]
        Evidence: [Tool result/data supporting this]

        Why 2: [What caused Why 1?]
        Evidence: [Tool result/data supporting this]

        Why 3: [What caused Why 2?]
        Evidence: [Tool result/data supporting this]

        Why 4: [What caused Why 3?]
        Evidence: [Tool result/data supporting this]

        Why 5: [What caused Why 4?]
        Evidence: [Tool result/data supporting this]

        Root Cause: [Actionable root cause you can fix]

        ## Rules
        - Each "why" must be backed by concrete evidence from tools
        - Stop when you reach something actionable
        - You may need fewer or more than 5 whys
        """;

    public static final String COMMON_PATTERNS = """
        # Common Failure Patterns (Generic)

        These patterns appear across many systems:

        ## "Timeout" Symptoms
        Common causes:
        - Resource exhaustion (connection pools, threads)
        - Slow dependencies (database, external API)
        - Network issues

        Investigation approach:
        1. Check resource pool sizes (connections, threads)
        2. Identify slow operations (queries, API calls)
        3. Check network latency metrics

        ## "High Error Rate" Symptoms
        Common causes:
        - Recent deployment/config change
        - Dependency failure
        - Data validation issues

        Investigation approach:
        1. Check timeline: when did errors start?
        2. Correlate with changes (deployments, config)
        3. Analyze error messages for patterns

        ## "Performance Degradation" Symptoms
        Common causes:
        - Resource saturation (CPU, memory, disk)
        - Increased load
        - Inefficient code paths

        Investigation approach:
        1. Apply USE Method to identify bottleneck
        2. Check if load increased
        3. Compare with baseline performance
        """;
}
```

### Agent Configuration (Built-in)

```json
{
  "default_system_prompt": "You are an expert troubleshooting agent. Use these methodologies:\n\n{{USE_METHOD}}\n\n{{RED_METHOD}}\n\n{{FIVE_WHYS}}\n\nAdapt these methods to the customer's data structure.",
  "methodologies": {
    "use_method": "{{USE_METHOD}}",
    "red_method": "{{RED_METHOD}}",
    "five_whys": "{{FIVE_WHYS}}",
    "common_patterns": "{{COMMON_PATTERNS}}"
  }
}
```

## Layer 2: Auto-Discovery (Automated Per Customer)

**What you CAN do**: Automatically discover customer's data structure

### Index Discovery Tool

```java
/**
 * Automatically discovers customer's OpenSearch indices and structure
 * Runs during agent initialization or on-demand
 */
public class CustomerDataDiscoveryTool implements Tool {

    @Override
    public String getDescription() {
        return "Discovers available indices and their structure in the customer's OpenSearch. " +
               "Helps the agent understand what data is available for investigation.";
    }

    @Override
    public <T> void run(Map<String, String> parameters, ActionListener<T> listener) {
        String tenantId = parameters.get("tenant_id");

        // Discover indices
        CatIndicesRequest request = new CatIndicesRequest();
        client.cat().indices(request, ActionListener.wrap(response -> {

            List<IndexInfo> indices = parseIndices(response);

            // For each index, get mapping and sample documents
            Map<String, IndexStructure> structures = new HashMap<>();

            for (IndexInfo index : indices) {
                GetMappingsRequest mappingReq = new GetMappingsRequest()
                    .indices(index.getName());

                client.indices().getMapping(mappingReq, ActionListener.wrap(mappingResp -> {

                    // Sample a few documents to understand data patterns
                    SearchRequest sampleReq = new SearchRequest(index.getName())
                        .source(SearchSourceBuilder.searchSource().size(10));

                    client.search(sampleReq, ActionListener.wrap(sampleResp -> {

                        IndexStructure structure = analyzeStructure(
                            index.getName(),
                            mappingResp,
                            sampleResp
                        );

                        structures.put(index.getName(), structure);

                        // Store discovery results for this tenant
                        storeDiscoveryResults(tenantId, structures);

                        listener.onResponse((T) formatDiscoveryResults(structures));

                    }, listener::onFailure));

                }, listener::onFailure));
            }

        }, listener::onFailure));
    }

    private IndexStructure analyzeStructure(
        String indexName,
        GetMappingsResponse mapping,
        SearchResponse sample
    ) {
        IndexStructure structure = new IndexStructure();
        structure.setIndexName(indexName);

        // Categorize index based on name patterns
        structure.setCategory(categorizeIndex(indexName));

        // Extract field types
        Map<String, String> fields = extractFields(mapping);
        structure.setFields(fields);

        // Identify key fields
        structure.setTimestampField(findTimestampField(fields));
        structure.setServiceField(findServiceField(fields, sample));
        structure.setErrorFields(findErrorFields(fields, sample));
        structure.setMetricFields(findMetricFields(fields));

        // Detect common patterns in data
        structure.setDataPatterns(detectPatterns(sample));

        return structure;
    }

    private String categorizeIndex(String indexName) {
        String lower = indexName.toLowerCase();

        if (lower.contains("log") || lower.contains("event")) {
            return "logs";
        } else if (lower.contains("metric") || lower.contains("monitoring")) {
            return "metrics";
        } else if (lower.contains("trace") || lower.contains("span")) {
            return "traces";
        } else if (lower.contains("alert") || lower.contains("incident")) {
            return "alerts";
        }

        return "unknown";
    }

    private String findTimestampField(Map<String, String> fields) {
        // Look for common timestamp field names
        List<String> candidates = Arrays.asList(
            "@timestamp", "timestamp", "time", "created_at",
            "event_time", "log_time", "datetime"
        );

        for (String candidate : candidates) {
            if (fields.containsKey(candidate) &&
                fields.get(candidate).equals("date")) {
                return candidate;
            }
        }

        return null;
    }

    private String findServiceField(Map<String, String> fields, SearchResponse sample) {
        // Look for common service/component field names
        List<String> candidates = Arrays.asList(
            "service", "service_name", "component", "application",
            "app", "service.name", "kubernetes.pod.name"
        );

        for (String candidate : candidates) {
            if (fields.containsKey(candidate)) {
                // Verify this field has values in sample data
                if (hasValuesInSample(candidate, sample)) {
                    return candidate;
                }
            }
        }

        return null;
    }

    private List<String> findErrorFields(Map<String, String> fields, SearchResponse sample) {
        List<String> errorFields = new ArrayList<>();

        List<String> candidates = Arrays.asList(
            "level", "log_level", "severity", "error", "error_message",
            "exception", "status", "status_code", "response_code"
        );

        for (String candidate : candidates) {
            if (fields.containsKey(candidate)) {
                errorFields.add(candidate);
            }
        }

        return errorFields;
    }

    private String formatDiscoveryResults(Map<String, IndexStructure> structures) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Customer Data Structure Discovery\n\n");

        // Group by category
        Map<String, List<IndexStructure>> byCategory = structures.values()
            .stream()
            .collect(Collectors.groupingBy(IndexStructure::getCategory));

        for (Map.Entry<String, List<IndexStructure>> entry : byCategory.entrySet()) {
            sb.append(String.format("## %s Indices\n\n",
                entry.getKey().toUpperCase()));

            for (IndexStructure structure : entry.getValue()) {
                sb.append(String.format("### %s\n", structure.getIndexName()));
                sb.append(String.format("- Timestamp field: %s\n",
                    structure.getTimestampField()));

                if (structure.getServiceField() != null) {
                    sb.append(String.format("- Service field: %s\n",
                        structure.getServiceField()));
                }

                if (!structure.getErrorFields().isEmpty()) {
                    sb.append(String.format("- Error fields: %s\n",
                        String.join(", ", structure.getErrorFields())));
                }

                if (!structure.getMetricFields().isEmpty()) {
                    sb.append("- Key metrics: ");
                    sb.append(String.join(", ",
                        structure.getMetricFields().stream()
                            .limit(5)
                            .collect(Collectors.toList())));
                    sb.append("\n");
                }

                sb.append("\n");
            }
        }

        sb.append("\n## Investigation Guidance\n\n");
        sb.append("Use these indices for:\n");
        sb.append("- Logs: Use LogPatternAnalysisTool\n");
        sb.append("- Metrics: Use DataDistributionTool\n");
        sb.append("- Errors: Search error fields with SearchIndexTool\n");

        return sb.toString();
    }
}
```

### Service Name Discovery

```java
/**
 * Discovers services/components from customer data
 */
public class ServiceDiscoveryTool implements Tool {

    @Override
    public String getDescription() {
        return "Discovers all services/components in the customer's system by " +
               "analyzing their data. Returns list of services with their indices.";
    }

    @Override
    public <T> void run(Map<String, String> parameters, ActionListener<T> listener) {
        String tenantId = parameters.get("tenant_id");

        // Get discovered data structure
        IndexStructure logIndex = getDiscoveredStructure(tenantId, "logs");

        if (logIndex == null || logIndex.getServiceField() == null) {
            listener.onResponse((T) "No service field found in log data");
            return;
        }

        // Aggregate to find all unique services
        SearchRequest request = new SearchRequest(logIndex.getIndexName());
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

        // Terms aggregation on service field
        TermsAggregationBuilder agg = AggregationBuilders
            .terms("services")
            .field(logIndex.getServiceField() + ".keyword")
            .size(100);

        sourceBuilder.aggregation(agg);
        sourceBuilder.size(0);
        request.source(sourceBuilder);

        client.search(request, ActionListener.wrap(response -> {
            Terms services = response.getAggregations().get("services");

            List<String> serviceList = services.getBuckets()
                .stream()
                .map(bucket -> bucket.getKeyAsString())
                .collect(Collectors.toList());

            // Store for this tenant
            storeDiscoveredServices(tenantId, serviceList);

            String result = formatServiceList(serviceList, logIndex);
            listener.onResponse((T) result);

        }, listener::onFailure));
    }

    private String formatServiceList(List<String> services, IndexStructure logIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Discovered Services\n\n");
        sb.append(String.format("Found %d services in your system:\n\n", services.size()));

        for (String service : services) {
            sb.append(String.format("- %s\n", service));
        }

        sb.append(String.format("\n## How to Investigate\n\n"));
        sb.append(String.format("To investigate a specific service, use:\n"));
        sb.append(String.format("- Index: %s\n", logIndex.getIndexName()));
        sb.append(String.format("- Filter: %s:<service_name>\n", logIndex.getServiceField()));
        sb.append(String.format("- Example query: %s:payment-service AND level:ERROR\n",
            logIndex.getServiceField()));

        return sb.toString();
    }
}
```

### Auto-Discovery at Agent Initialization

```java
/**
 * Run discovery when customer first uses RCA agent
 */
public class RCAAgentInitializer {

    public void initializeForCustomer(String tenantId, ActionListener<String> listener) {
        // Step 1: Discover data structure
        Map<String, String> params = Map.of("tenant_id", tenantId);

        dataDiscoveryTool.run(params, ActionListener.wrap(discoveryResult -> {

            // Step 2: Discover services
            serviceDiscoveryTool.run(params, ActionListener.wrap(services -> {

                // Step 3: Build customer-specific knowledge context
                String knowledgeContext = buildKnowledgeContext(
                    discoveryResult,
                    services
                );

                // Step 4: Store for this tenant
                storeCustomerKnowledge(tenantId, knowledgeContext);

                listener.onResponse("Agent initialized for customer: " + tenantId);

            }, listener::onFailure));

        }, listener::onFailure));
    }

    private String buildKnowledgeContext(String discovery, String services) {
        return String.format("""
            # Your System Structure

            %s

            %s

            ## Investigation Tips

            When troubleshooting:
            1. Use the discovered indices above
            2. Filter by service name when investigating specific services
            3. Use timestamp field for time-based analysis
            4. Check error fields for failure patterns
            """, discovery, services);
    }
}
```

## Layer 3: Customer-Configurable Knowledge

**What customers CAN provide**: Their own domain knowledge (optional)

### Knowledge Configuration Schema

```yaml
# customer-knowledge.yaml (customer provides this optionally)

architecture:
  description: "E-commerce platform on AWS"

  services:
    - name: "api-gateway"
      description: "Main API entry point"
      dependencies: ["auth-service", "product-service", "payment-service"]
      indices: ["api-gateway-logs-*"]
      normal_behavior:
        request_rate: "5000-10000 req/s"
        error_rate: "< 1%"
        p95_latency: "< 200ms"

    - name: "payment-service"
      description: "Handles payment processing"
      dependencies: ["postgres-db", "stripe-api"]
      indices: ["payment-logs-*", "payment-metrics-*"]
      known_issues:
        - symptom: "timeout errors"
          usual_cause: "Database connection pool exhaustion"
          first_check: "connection_pool_active metric"
          normal_value: "< 40 connections"
          max_value: "50 connections"

investigation_shortcuts:
  - symptom_pattern: "payment.*timeout"
    recommended_steps:
      - "Check database connection pool (payment-metrics-*)"
      - "Check Stripe API health (external)"
      - "Check network latency to database"

  - symptom_pattern: "slow.*api"
    recommended_steps:
      - "Apply RED Method to api-gateway"
      - "Check backend service health"
      - "Check database query performance"

incident_history:
  - date: "2024-12-01"
    summary: "Payment outage due to DB pool exhaustion"
    root_cause: "Connection pool size insufficient"
    lessons:
      - "Always check connection pool first for payment timeouts"
      - "Normal usage is 20-30 connections, alert at 40"
```

### Customer Knowledge API

```java
/**
 * API for customers to provide their domain knowledge
 */
@RestController
@RequestMapping("/api/ml/rca/knowledge")
public class CustomerKnowledgeController {

    @PostMapping
    public Response uploadKnowledge(
        @RequestHeader("tenant-id") String tenantId,
        @RequestBody CustomerKnowledge knowledge
    ) {
        // Validate structure
        validateKnowledge(knowledge);

        // Store in tenant-specific index
        storeCustomerKnowledge(tenantId, knowledge);

        // Update agent configuration for this tenant
        updateAgentWithKnowledge(tenantId, knowledge);

        return Response.ok("Knowledge uploaded successfully");
    }

    @GetMapping
    public CustomerKnowledge getKnowledge(
        @RequestHeader("tenant-id") String tenantId
    ) {
        return retrieveCustomerKnowledge(tenantId);
    }

    @PutMapping("/shortcuts")
    public Response addInvestigationShortcut(
        @RequestHeader("tenant-id") String tenantId,
        @RequestBody InvestigationShortcut shortcut
    ) {
        addShortcut(tenantId, shortcut);
        return Response.ok("Shortcut added");
    }
}
```

### UI for Knowledge Configuration

```javascript
// Customer-facing UI to document their system
class KnowledgeConfigurationUI {

    renderServiceForm() {
        return (
            <Form>
                <Input
                    label="Service Name"
                    help="E.g., payment-service, api-gateway"
                />
                <TextArea
                    label="Description"
                    help="What does this service do?"
                />
                <TagInput
                    label="Dependencies"
                    help="Other services this depends on"
                />
                <Input
                    label="Log Index Pattern"
                    help="E.g., payment-logs-*"
                />

                <Subsection title="Normal Behavior">
                    <Input label="Normal Request Rate" placeholder="1000-5000 req/s" />
                    <Input label="Normal Error Rate" placeholder="< 1%" />
                    <Input label="Normal Latency" placeholder="< 200ms" />
                </Subsection>

                <Subsection title="Known Issues">
                    <RepeatingGroup>
                        <Input label="Symptom" />
                        <Input label="Usual Cause" />
                        <Input label="First Check" />
                    </RepeatingGroup>
                </Subsection>
            </Form>
        );
    }

    renderInvestigationShortcut() {
        return (
            <Form>
                <Input
                    label="Symptom Pattern"
                    help="Regex or keywords, e.g., 'timeout.*payment'"
                />
                <OrderedList
                    label="Investigation Steps"
                    help="What to check first, second, third..."
                />
                <TagInput
                    label="Tools to Use"
                    options={availableTools}
                />
            </Form>
        );
    }
}
```

## Layer 4: Tenant-Isolated Learning

**What you CAN do**: Learn separately per customer

### Per-Tenant Learning System

```java
/**
 * Learns from RCAs but keeps knowledge isolated per tenant
 */
public class TenantIsolatedLearningSystem {

    public void processRCA(String tenantId, String conversationId) {
        // All learning is stored per-tenant
        String tenantIndex = String.format(".ml-rca-analytics-%s", tenantId);

        // Extract and store analytics (isolated)
        RCAAnalytics analytics = extractAnalytics(conversationId);

        IndexRequest request = new IndexRequest(tenantIndex)
            .source(analytics.toMap(), XContentType.JSON);

        client.index(request, ActionListener.wrap(response -> {

            // Check if we have enough data to create patterns (per tenant)
            checkForPatterns(tenantId);

        }, e -> log.error("Failed to store analytics for tenant {}", tenantId, e)));
    }

    private void checkForPatterns(String tenantId) {
        // Query tenant-specific analytics
        String tenantIndex = String.format(".ml-rca-analytics-%s", tenantId);

        SearchRequest request = new SearchRequest(tenantIndex);
        // ... find patterns in this tenant's data only

        // If patterns found, suggest to customer
        if (hasSignificantPatterns(tenantId)) {
            notifyCustomerOfLearnings(tenantId);
        }
    }

    private void notifyCustomerOfLearnings(String tenantId) {
        // Generate suggestion for customer
        List<Pattern> patterns = extractPatterns(tenantId);

        String suggestion = String.format("""
            We've analyzed your RCA history and found these patterns:

            %s

            Would you like to add these as investigation shortcuts?
            [Accept] [Review] [Dismiss]
            """, formatPatterns(patterns));

        // Send notification to customer
        notificationService.send(tenantId, suggestion);
    }
}
```

## Complete Implementation: Agent with Multi-Layer Knowledge

```java
public class MultiLayerRCAAgent {

    public void run(
        String tenantId,
        String question,
        ActionListener<Object> listener
    ) {
        // Layer 1: Universal methodologies (always available)
        String universalKnowledge = UniversalTroubleshootingMethodologies.getAll();

        // Layer 2: Auto-discovered knowledge (cached per tenant)
        String discoveredKnowledge = getDiscoveredKnowledge(tenantId);
        if (discoveredKnowledge == null) {
            // First time - discover now
            runDiscovery(tenantId, ActionListener.wrap(discovered -> {
                continueWithKnowledge(
                    tenantId,
                    question,
                    universalKnowledge,
                    discovered,
                    listener
                );
            }, listener::onFailure));
            return;
        }

        // Layer 3: Customer-provided knowledge (if exists)
        String customerKnowledge = getCustomerKnowledge(tenantId);

        // Layer 4: Learned patterns (tenant-specific)
        String learnedPatterns = getLearnedPatterns(tenantId);

        // Combine all knowledge layers
        String systemPrompt = buildSystemPrompt(
            universalKnowledge,
            discoveredKnowledge,
            customerKnowledge,
            learnedPatterns
        );

        // Run agent with combined knowledge
        agent.run(question, systemPrompt, listener);
    }

    private String buildSystemPrompt(
        String universal,
        String discovered,
        String customer,
        String learned
    ) {
        return String.format("""
            You are an expert RCA agent. Use this knowledge:

            # Universal Methodologies
            %s

            # Your Customer's System (Auto-Discovered)
            %s

            %s

            %s

            Apply methodologies systematically using the discovered indices and fields.
            """,
            universal,
            discovered,
            customer != null ? "# Customer Documentation\n" + customer : "",
            learned != null ? "# Learned Patterns\n" + learned : ""
        );
    }
}
```

## Customer Onboarding Flow

```
Step 1: Customer signs up
    ↓
Step 2: Agent auto-discovers their data structure
    ↓
Step 3: Show discovery results to customer
    ↓
Step 4: [Optional] Customer adds domain knowledge
    ↓
Step 5: Agent ready to use with:
        - Universal methodologies ✓
        - Discovered structure ✓
        - Customer knowledge (if provided) ✓
    ↓
Step 6: As agent is used, learn patterns (per tenant)
    ↓
Step 7: Suggest learned patterns to customer for validation
```

## Benefits of This Approach

✅ **Privacy**: No cross-tenant data sharing
✅ **Works immediately**: Universal methodologies work for everyone
✅ **Adapts automatically**: Discovers customer's data structure
✅ **Improves over time**: Learns from each customer's usage
✅ **Customer control**: Can add their own knowledge
✅ **Scalable**: Same product works for all customers

## Implementation Priority

### Phase 1 (Launch)
1. ✅ Ship universal methodologies
2. ✅ Implement auto-discovery
3. ✅ Basic agent with discovered knowledge

### Phase 2 (Enhancement)
1. ✅ Customer knowledge configuration UI
2. ✅ Per-tenant learning system
3. ✅ Learning suggestions to customers

### Phase 3 (Advanced)
1. ✅ Cross-tenant aggregated insights (privacy-preserving)
2. ✅ Benchmarking (anonymous comparison)
3. ✅ Best practice recommendations

This gives you a SaaS product that works out-of-the-box but gets better with use!
