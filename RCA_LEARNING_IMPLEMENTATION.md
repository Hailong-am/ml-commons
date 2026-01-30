# RCA Learning Implementation - Practical Examples

This document provides concrete implementation examples for learning from your RCA processes stored in OpenSearch.

## Use Case: Enhancing Future RCAs with Historical Knowledge

### Problem
Your Plan-Execute-Reflect agent is investigating the same types of issues repeatedly without leveraging past learnings.

### Solution
Implement a Context Manager that injects relevant historical RCA cases into the agent's context before planning.

## Implementation Example 1: RCA Knowledge Retrieval Tool

### Step 1: Create a Tool to Search Historical RCAs

```java
package org.opensearch.ml.engine.tools;

import org.opensearch.ml.common.spi.tools.Tool;
import org.opensearch.client.Client;

/**
 * Tool that searches historical RCA cases to provide context for current investigation
 */
public class HistoricalRCASearchTool implements Tool {

    private final Client client;
    private static final String RCA_MEMORY_INDEX = ".plugins-ml-memory-message";

    public HistoricalRCASearchTool(Client client) {
        this.client = client;
    }

    @Override
    public String getType() {
        return "HistoricalRCASearchTool";
    }

    @Override
    public String getName() {
        return "HistoricalRCASearch";
    }

    @Override
    public String getDescription() {
        return "Searches past RCA investigations for similar symptoms and returns " +
               "successful investigation patterns, root causes found, and resolution steps. " +
               "Input: JSON with 'symptom' (error description) and 'context' (service, timeframe). " +
               "Example: {\"symptom\": \"connection timeout errors\", \"context\": \"payment service\"}";
    }

    @Override
    public <T> void run(Map<String, String> parameters, ActionListener<T> listener) {
        String symptom = parameters.get("symptom");
        String context = parameters.getOrDefault("context", "");

        // Build OpenSearch query to find similar past RCAs
        SearchRequest searchRequest = new SearchRequest(RCA_MEMORY_INDEX);
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

        // Use More Like This query to find similar symptoms
        MoreLikeThisQueryBuilder mltQuery = QueryBuilders.moreLikeThisQuery(
            new String[]{"input", "response"},
            new String[]{symptom + " " + context},
            null
        );
        mltQuery.minTermFreq(1);
        mltQuery.maxQueryTerms(12);

        // Only get root interactions (not traces)
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery()
            .must(mltQuery)
            .mustNot(QueryBuilders.existsQuery("parent_message_id"))
            .must(QueryBuilders.regexpQuery("response", ".*[Rr]oot [Cc]ause.*"));

        sourceBuilder.query(boolQuery);
        sourceBuilder.size(5);
        sourceBuilder.sort("create_time", SortOrder.DESC);

        searchRequest.source(sourceBuilder);

        client.search(searchRequest, ActionListener.wrap(response -> {
            List<Map<String, Object>> similarCases = new ArrayList<>();

            for (SearchHit hit : response.getHits().getHits()) {
                Map<String, Object> source = hit.getSourceAsMap();
                String conversationId = (String) source.get("conversation_id");

                // Get full investigation trace for this conversation
                getFullInvestigation(conversationId, ActionListener.wrap(investigation -> {
                    Map<String, Object> caseData = new HashMap<>();
                    caseData.put("symptom", source.get("input"));
                    caseData.put("root_cause", extractRootCause(source.get("response")));
                    caseData.put("investigation_steps", investigation.get("steps"));
                    caseData.put("tools_used", investigation.get("tools"));
                    caseData.put("resolution_time", investigation.get("duration"));

                    similarCases.add(caseData);

                    if (similarCases.size() == response.getHits().getHits().length) {
                        // All cases retrieved, format and return
                        String result = formatRCAKnowledge(similarCases);
                        listener.onResponse((T) result);
                    }
                }, listener::onFailure));
            }

            if (response.getHits().getHits().length == 0) {
                listener.onResponse((T) "No similar past RCA cases found.");
            }
        }, listener::onFailure));
    }

    private void getFullInvestigation(String conversationId, ActionListener<Map<String, Object>> listener) {
        // Query all traces for this conversation
        SearchRequest request = new SearchRequest(RCA_MEMORY_INDEX);
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

        sourceBuilder.query(QueryBuilders.termQuery("conversation_id", conversationId));
        sourceBuilder.sort("trace_number", SortOrder.ASC);
        sourceBuilder.size(100);

        request.source(sourceBuilder);

        client.search(request, ActionListener.wrap(response -> {
            List<String> steps = new ArrayList<>();
            Set<String> tools = new HashSet<>();
            Instant startTime = null;
            Instant endTime = null;

            for (SearchHit hit : response.getHits().getHits()) {
                Map<String, Object> source = hit.getSourceAsMap();

                // Extract step information
                String input = (String) source.get("input");
                if (input != null && !input.isEmpty()) {
                    steps.add(input);
                }

                // Extract tool usage
                Map<String, Object> additionalInfo = (Map<String, Object>) source.get("additional_info");
                if (additionalInfo != null && additionalInfo.containsKey("tool_name")) {
                    tools.add((String) additionalInfo.get("tool_name"));
                }

                // Track timing
                String createTimeStr = (String) source.get("create_time");
                Instant createTime = Instant.parse(createTimeStr);
                if (startTime == null || createTime.isBefore(startTime)) {
                    startTime = createTime;
                }
                if (endTime == null || createTime.isAfter(endTime)) {
                    endTime = createTime;
                }
            }

            Map<String, Object> investigation = new HashMap<>();
            investigation.put("steps", steps);
            investigation.put("tools", new ArrayList<>(tools));
            if (startTime != null && endTime != null) {
                investigation.put("duration", Duration.between(startTime, endTime).getSeconds());
            }

            listener.onResponse(investigation);
        }, listener::onFailure));
    }

    private String extractRootCause(Object response) {
        String responseStr = response.toString();
        // Simple extraction - look for "root cause: " or "Root Cause:"
        Pattern pattern = Pattern.compile("[Rr]oot [Cc]ause:?\\s*([^\\n.]+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(responseStr);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return responseStr;
    }

    private String formatRCAKnowledge(List<Map<String, Object>> cases) {
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(cases.size()).append(" similar past RCA cases:\n\n");

        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> case_ = cases.get(i);
            sb.append(String.format("Case %d:\n", i + 1));
            sb.append(String.format("  Symptom: %s\n", case_.get("symptom")));
            sb.append(String.format("  Root Cause Found: %s\n", case_.get("root_cause")));

            List<String> steps = (List<String>) case_.get("investigation_steps");
            sb.append("  Investigation Steps:\n");
            for (int j = 0; j < Math.min(steps.size(), 5); j++) {
                sb.append(String.format("    %d. %s\n", j + 1, steps.get(j)));
            }

            List<String> tools = (List<String>) case_.get("tools_used");
            sb.append(String.format("  Tools Used: %s\n", String.join(", ", tools)));

            Object duration = case_.get("resolution_time");
            if (duration != null) {
                sb.append(String.format("  Resolution Time: %s seconds\n", duration));
            }
            sb.append("\n");
        }

        sb.append("Recommendation: Consider investigating similar root causes and using similar investigation approaches.");

        return sb.toString();
    }

    @Override
    public boolean validate(Map<String, String> parameters) {
        return parameters.containsKey("symptom") && !parameters.get("symptom").isEmpty();
    }
}
```

### Step 2: Register the Tool

Add to `MachineLearningPlugin.java`:

```java
@Override
public Collection<Object> createComponents(...) {
    // ... existing code ...

    // Register Historical RCA Search Tool
    HistoricalRCASearchTool historicalRCASearchTool = new HistoricalRCASearchTool(client);
    Map<String, Tool.Factory> toolFactories = new HashMap<>();
    toolFactories.put("HistoricalRCASearchTool", (params, encryptor) -> historicalRCASearchTool);

    // ... rest of code ...
}
```

### Step 3: Update Agent Configuration

Modify your RCA agent configuration to include the historical search tool:

```json
{
  "name": "RCA_Agent_with_History",
  "type": "plan_execute_reflect",
  "llm": {
    "model_id": "your-llm-model-id",
    "parameters": {}
  },
  "tools": [
    {
      "type": "HistoricalRCASearchTool",
      "name": "historical_rca_search",
      "description": "Search past RCA investigations for similar issues"
    },
    {
      "type": "SearchIndexTool",
      "name": "search_logs"
    },
    {
      "type": "DataDistributionTool",
      "name": "analyze_distribution"
    }
  ],
  "parameters": {
    "planner_prompt": "You are an expert at root cause analysis. Before creating your investigation plan, ALWAYS use the historical_rca_search tool to learn from past similar cases. This will help you create a more efficient investigation plan.",
    "max_steps": "15"
  }
}
```

## Implementation Example 2: RCA Pattern Learning Aggregation

### Create an Analytics Index

```json
PUT /rca-analytics
{
  "mappings": {
    "properties": {
      "conversation_id": { "type": "keyword" },
      "symptom": { "type": "text", "fields": {"keyword": {"type": "keyword"}} },
      "symptom_category": { "type": "keyword" },
      "root_cause": { "type": "text", "fields": {"keyword": {"type": "keyword"}} },
      "root_cause_category": { "type": "keyword" },
      "investigation_steps": { "type": "integer" },
      "tools_used": { "type": "keyword" },
      "investigation_path": { "type": "text" },
      "success": { "type": "boolean" },
      "duration_seconds": { "type": "integer" },
      "tokens_used": { "type": "integer" },
      "timestamp": { "type": "date" },
      "service_affected": { "type": "keyword" },
      "time_of_day": { "type": "keyword" },
      "similar_case_ids": { "type": "keyword" }
    }
  }
}
```

### Create ETL Pipeline

```python
from opensearchpy import OpenSearch, helpers
import json
from datetime import datetime
from collections import defaultdict

class RCAAnalyticsPipeline:
    def __init__(self, opensearch_client):
        self.client = opensearch_client
        self.memory_index = ".plugins-ml-memory-message"
        self.analytics_index = "rca-analytics"

    def process_rca_batch(self, start_date, end_date):
        """
        Process all RCA conversations in date range and extract analytics
        """
        conversations = self._get_conversations(start_date, end_date)

        for conv_id in conversations:
            try:
                analytics = self._extract_analytics(conv_id)
                if analytics:
                    self._index_analytics(analytics)
            except Exception as e:
                print(f"Error processing {conv_id}: {e}")

    def _get_conversations(self, start_date, end_date):
        """Get all unique conversation IDs in date range"""
        query = {
            "query": {
                "bool": {
                    "must": [
                        {"term": {"origin.keyword": "PlanExecuteReflect Agent"}},
                        {"range": {"create_time": {"gte": start_date, "lte": end_date}}}
                    ],
                    "must_not": [
                        {"exists": {"field": "parent_message_id"}}
                    ]
                }
            },
            "size": 10000,
            "_source": ["conversation_id"]
        }

        response = self.client.search(index=self.memory_index, body=query)
        return [hit['_source']['conversation_id'] for hit in response['hits']['hits']]

    def _extract_analytics(self, conv_id):
        """Extract analytics data for a single RCA conversation"""
        # Get all interactions for this conversation
        query = {
            "query": {"term": {"conversation_id": conv_id}},
            "sort": [{"trace_number": "asc"}, {"create_time": "asc"}],
            "size": 1000
        }

        response = self.client.search(index=self.memory_index, body=query)
        interactions = [hit['_source'] for hit in response['hits']['hits']]

        if not interactions:
            return None

        # Extract root interaction (user question)
        root = next((i for i in interactions if not i.get('parent_message_id')), None)
        if not root:
            return None

        # Get final response (last interaction)
        final = interactions[-1]

        # Extract investigation steps (traces)
        traces = [i for i in interactions if i.get('trace_number')]

        # Calculate metrics
        analytics = {
            "conversation_id": conv_id,
            "symptom": root.get('input', ''),
            "symptom_category": self._categorize_symptom(root.get('input', '')),
            "root_cause": self._extract_root_cause(final.get('response', '')),
            "root_cause_category": self._categorize_root_cause(final.get('response', '')),
            "investigation_steps": len(traces),
            "tools_used": self._extract_tools_used(traces),
            "investigation_path": self._extract_investigation_path(traces),
            "success": self._is_successful(final.get('response', '')),
            "duration_seconds": self._calculate_duration(interactions),
            "tokens_used": self._extract_token_usage(interactions),
            "timestamp": root.get('create_time'),
            "service_affected": self._extract_service(root.get('input', '')),
            "time_of_day": self._get_time_category(root.get('create_time'))
        }

        return analytics

    def _categorize_symptom(self, symptom):
        """Categorize symptom into predefined categories"""
        symptom_lower = symptom.lower()

        categories = {
            "errors": ["error", "exception", "fail", "crash"],
            "performance": ["slow", "timeout", "latency", "delay"],
            "availability": ["down", "unavailable", "not responding", "unreachable"],
            "data": ["corrupt", "missing", "incorrect", "inconsistent"]
        }

        for category, keywords in categories.items():
            if any(kw in symptom_lower for kw in keywords):
                return category

        return "other"

    def _categorize_root_cause(self, response):
        """Categorize root cause from response"""
        response_lower = response.lower()

        categories = {
            "network": ["network", "connection", "dns", "timeout", "latency"],
            "database": ["database", "sql", "query", "connection pool", "deadlock"],
            "code": ["bug", "null pointer", "logic error", "exception"],
            "infrastructure": ["disk", "memory", "cpu", "resource", "capacity"],
            "configuration": ["config", "setting", "parameter", "environment"]
        }

        for category, keywords in categories.items():
            if any(kw in response_lower for kw in keywords):
                return category

        return "unknown"

    def _extract_tools_used(self, traces):
        """Extract unique list of tools used"""
        tools = set()
        for trace in traces:
            additional_info = trace.get('additional_info', {})
            if additional_info and 'tool_name' in additional_info:
                tools.add(additional_info['tool_name'])
        return list(tools)

    def _extract_investigation_path(self, traces):
        """Create a text representation of investigation path"""
        steps = []
        for trace in traces:
            input_text = trace.get('input', '')
            if input_text:
                # Simplify step description
                step = input_text[:100] + "..." if len(input_text) > 100 else input_text
                steps.append(step)
        return " -> ".join(steps)

    def _is_successful(self, final_response):
        """Determine if RCA was successful"""
        response_lower = final_response.lower()
        success_indicators = ["root cause", "identified", "found the issue", "determined"]
        failure_indicators = ["unable to", "could not", "no clear", "max steps"]

        has_success = any(ind in response_lower for ind in success_indicators)
        has_failure = any(ind in response_lower for ind in failure_indicators)

        return has_success and not has_failure

    def _calculate_duration(self, interactions):
        """Calculate investigation duration in seconds"""
        if not interactions:
            return 0

        first_time = datetime.fromisoformat(interactions[0]['create_time'].replace('Z', '+00:00'))
        last_time = datetime.fromisoformat(interactions[-1]['create_time'].replace('Z', '+00:00'))

        return int((last_time - first_time).total_seconds())

    def _extract_token_usage(self, interactions):
        """Extract total token usage if available"""
        # This assumes token usage is stored in additional_info
        total_tokens = 0
        for interaction in interactions:
            additional_info = interaction.get('additional_info', {})
            if additional_info and 'tokens' in additional_info:
                total_tokens += int(additional_info['tokens'])
        return total_tokens

    def _extract_service(self, symptom):
        """Extract service name from symptom description"""
        # Simple pattern matching - customize based on your naming
        import re
        match = re.search(r'(service|api|component|system)[\s:-]+(\w+)', symptom.lower())
        if match:
            return match.group(2)
        return "unknown"

    def _get_time_category(self, timestamp):
        """Categorize time of day"""
        dt = datetime.fromisoformat(timestamp.replace('Z', '+00:00'))
        hour = dt.hour

        if 6 <= hour < 12:
            return "morning"
        elif 12 <= hour < 18:
            return "afternoon"
        elif 18 <= hour < 24:
            return "evening"
        else:
            return "night"

    def _extract_root_cause(self, response):
        """Extract root cause statement from response"""
        import re
        response_lower = response.lower()

        # Look for common root cause patterns
        patterns = [
            r'root cause[:\s]+([^.\n]+)',
            r'identified[:\s]+([^.\n]+)',
            r'the issue is[:\s]+([^.\n]+)',
            r'determined that[:\s]+([^.\n]+)'
        ]

        for pattern in patterns:
            match = re.search(pattern, response_lower)
            if match:
                return match.group(1).strip()

        # If no pattern match, return first sentence
        sentences = response.split('.')
        if sentences:
            return sentences[0].strip()

        return response[:200]  # Fallback to first 200 chars

    def _index_analytics(self, analytics):
        """Index analytics document"""
        self.client.index(
            index=self.analytics_index,
            body=analytics,
            refresh=True
        )

# Usage
client = OpenSearch([{'host': 'localhost', 'port': 9200}])
pipeline = RCAAnalyticsPipeline(client)

# Process last 7 days
from datetime import datetime, timedelta
end_date = datetime.now().isoformat()
start_date = (datetime.now() - timedelta(days=7)).isoformat()

pipeline.process_rca_batch(start_date, end_date)
```

## Implementation Example 3: Dashboard Queries

### Query 1: Top Root Causes

```json
GET /rca-analytics/_search
{
  "size": 0,
  "aggs": {
    "top_root_causes": {
      "terms": {
        "field": "root_cause_category",
        "size": 10
      },
      "aggs": {
        "avg_resolution_time": {
          "avg": { "field": "duration_seconds" }
        },
        "avg_steps": {
          "avg": { "field": "investigation_steps" }
        },
        "success_rate": {
          "avg": { "field": "success" }
        }
      }
    }
  }
}
```

### Query 2: Investigation Efficiency Trends

```json
GET /rca-analytics/_search
{
  "size": 0,
  "aggs": {
    "by_day": {
      "date_histogram": {
        "field": "timestamp",
        "calendar_interval": "day"
      },
      "aggs": {
        "avg_steps": {"avg": {"field": "investigation_steps"}},
        "avg_duration": {"avg": {"field": "duration_seconds"}},
        "success_rate": {"avg": {"field": "success"}},
        "total_rcas": {"value_count": {"field": "conversation_id"}}
      }
    }
  }
}
```

### Query 3: Tool Effectiveness by Root Cause

```json
GET /rca-analytics/_search
{
  "size": 0,
  "aggs": {
    "by_root_cause": {
      "terms": {"field": "root_cause_category"},
      "aggs": {
        "tools_breakdown": {
          "terms": {"field": "tools_used"},
          "aggs": {
            "avg_steps": {"avg": {"field": "investigation_steps"}}
          }
        }
      }
    }
  }
}
```

## Next Steps

1. **Deploy the HistoricalRCASearchTool** to your ML-Commons instance
2. **Run the ETL pipeline** daily to populate the analytics index
3. **Create dashboards** in OpenSearch Dashboards to visualize patterns
4. **Iterate on categories** based on your specific domain
5. **Build ML models** to predict root causes from symptoms
6. **Implement feedback loops** to continuously improve agent performance

## Expected Improvements

After implementing these learning mechanisms, you should see:

- ✅ **20-30% reduction** in investigation steps for known issue types
- ✅ **Faster time to resolution** by leveraging historical knowledge
- ✅ **Better first-step selection** by the planner agent
- ✅ **Reduced repeated investigations** of the same root causes
- ✅ **Improved agent confidence** with historical context
- ✅ **Cost savings** from more efficient LLM usage

Happy learning from your RCA data!
