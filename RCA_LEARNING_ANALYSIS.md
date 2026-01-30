# Learning from RCA Processes - Analysis Guide

## Overview

When using the Plan-Execute-Reflect Agent for Root Cause Analysis (RCA), the entire investigation process is stored in OpenSearch's `.plugins-ml-memory-message` index. This creates a rich dataset containing:

- **Planning decisions**: What the agent decided to investigate
- **Execution traces**: Which tools were used and what data was queried
- **Results**: What was discovered at each step
- **Final conclusions**: The identified root causes
- **Token usage**: Cost metrics for each LLM call
- **Temporal data**: Timing of investigations

## Data Structure Stored

### Interaction Schema

Each interaction in the index contains:

```json
{
  "id": "interaction-uuid",
  "create_time": "2025-01-15T10:30:00Z",
  "updated_time": "2025-01-15T10:32:00Z",
  "conversation_id": "memory-id",
  "parent_message_id": "parent-interaction-id",  // Links to parent step
  "trace_number": 3,                              // Sequence in workflow
  "origin": "PlanExecuteReflect Agent",
  "input": "Search for error patterns in logs between 10:00-10:30",
  "response": "Found 150 errors with pattern 'ConnectionTimeout'",
  "additional_info": {
    "tool_name": "SearchIndexTool",
    "query": "...",
    "results_count": "150",
    "execution_time_ms": "234"
  }
}
```

### Hierarchical Structure

For Plan-Execute-Reflect agents, interactions form a tree:

```
Root Interaction (User Question: "Why did service X fail?")
├── Trace 1: Planner decides to check logs
│   └── Executor Step 1: Search logs for errors
│       ├── Tool Call: SearchIndexTool (logs)
│       └── Result: "Found 200 errors"
├── Trace 2: Planner analyzes error patterns
│   └── Executor Step 2: Aggregate errors by type
│       ├── Tool Call: DataDistributionTool
│       └── Result: "80% are ConnectionTimeout"
├── Trace 3: Planner investigates network
│   └── Executor Step 3: Check network metrics
│       ├── Tool Call: SearchIndexTool (metrics)
│       └── Result: "Network latency spiked at 10:15"
└── Final Result: "Root cause: Network switch failure at 10:15"
```

## Learning Opportunities

### 1. Pattern Mining for Common Root Causes

**What to analyze:**
- Cluster similar RCA final results to identify recurring root causes
- Extract patterns from the investigation paths that led to specific root causes

**OpenSearch Query Example:**
```json
{
  "query": {
    "bool": {
      "must": [
        { "term": { "origin": "PlanExecuteReflect Agent" }},
        { "exists": { "field": "parent_message_id" }},
        { "match": { "response": "root cause" }}
      ]
    }
  },
  "aggs": {
    "root_cause_patterns": {
      "terms": {
        "field": "response.keyword",
        "size": 50
      }
    }
  }
}
```

**Insights to gain:**
- Top 10 most common root causes in your system
- Seasonal patterns (do certain failures occur at specific times?)
- Correlation between symptoms and root causes

### 2. Investigation Strategy Optimization

**What to analyze:**
- Successful vs unsuccessful RCA paths
- Average number of steps to resolution
- Which investigation steps are most informative

**Analysis Approach:**
```
For each conversation_id:
1. Extract all traces ordered by trace_number
2. Identify if RCA was successful (final response contains definitive root cause)
3. Compare investigation paths:
   - Successful: ["check logs" → "analyze errors" → "check network"] → 3 steps
   - Unsuccessful: ["check logs" → "check CPU" → "check memory" → ...] → 8 steps
4. Learn: Checking network early is more effective for connection errors
```

**Metrics to track:**
- Average steps to resolution by root cause type
- Most efficient investigation sequences
- Dead-end investigation paths to avoid

### 3. Tool Effectiveness Analysis

**What to analyze:**
- Which tools provide the most valuable information
- Tool usage patterns in successful vs failed RCAs
- Tool execution times and costs

**OpenSearch Aggregation:**
```json
{
  "query": {
    "exists": { "field": "additional_info.tool_name" }
  },
  "aggs": {
    "tools_by_effectiveness": {
      "terms": { "field": "additional_info.tool_name.keyword" },
      "aggs": {
        "avg_result_quality": {
          "avg": { "field": "additional_info.results_count" }
        },
        "avg_execution_time": {
          "avg": { "field": "additional_info.execution_time_ms" }
        }
      }
    }
  }
}
```

**Questions to answer:**
- Which tools are used most frequently in successful RCAs?
- Are there tools that are called but rarely provide useful information?
- What's the cost-benefit ratio of each tool?

### 4. LLM Planning Quality Analysis

**What to analyze:**
- Compare planner's initial plan vs actual execution path
- Identify cases where reflection led to better outcomes
- Measure planning accuracy

**Data to extract:**
```python
# Pseudo-analysis
for conversation in conversations:
    planner_steps = get_traces_by_origin("planner")
    executor_steps = get_traces_by_origin("executor")

    # Compare planned vs executed
    initial_plan = extract_steps_from_response(planner_steps[0])
    actual_execution = [step.input for step in executor_steps]

    plan_adherence = calculate_similarity(initial_plan, actual_execution)

    # Did reflection improve the investigation?
    if len(planner_steps) > 1:
        reflection_triggered = True
        outcome_improved = compare_trajectory_quality(...)
```

**Insights:**
- How often does the planner need to revise its plan?
- What triggers reflection (unexpected results, errors)?
- Does reflection improve RCA success rate?

### 5. Knowledge Base Construction

**Goal:** Build a searchable knowledge base of past RCAs

**Structure:**
```json
{
  "symptom": "Service returning 500 errors",
  "investigation_path": [
    "Check error logs",
    "Analyze error distribution",
    "Check upstream dependencies"
  ],
  "root_cause": "Database connection pool exhausted",
  "resolution": "Increased connection pool size from 50 to 100",
  "similar_cases": ["conv-1", "conv-2", "conv-3"],
  "frequency": 12,
  "avg_resolution_time": "8 minutes"
}
```

**Use cases:**
- Given a new symptom, suggest likely root causes
- Recommend investigation paths based on similar past cases
- Estimate time to resolution

### 6. Agent Performance Benchmarking

**Metrics to track over time:**

```sql
-- Average investigation efficiency
SELECT
  DATE_TRUNC('day', create_time) as date,
  AVG(step_count) as avg_steps,
  AVG(total_time_seconds) as avg_time,
  AVG(token_usage) as avg_cost,
  COUNT(DISTINCT conversation_id) as rca_count
FROM rca_summary
GROUP BY date
ORDER BY date DESC
```

**Questions to answer:**
- Is the agent getting better over time (fewer steps, faster resolution)?
- Which types of failures are hardest to diagnose (most steps)?
- How does performance vary by time of day or system load?

### 7. Temporal Pattern Analysis

**What to analyze:**
- Time between symptom and RCA completion
- Investigation patterns during incidents vs normal operation
- Correlation between investigation complexity and incident severity

**Analysis:**
```python
# Extract temporal patterns
for conversation in conversations:
    start_time = conversation.create_time
    end_time = conversation.updated_time
    investigation_duration = end_time - start_time

    # Correlate with incident severity
    if investigation_duration > threshold:
        classify_as_complex()

    # Time-of-day patterns
    hour_of_day = start_time.hour
    day_of_week = start_time.weekday()

    patterns[hour_of_day][day_of_week].append(conversation)
```

**Insights:**
- Do RCAs take longer during peak hours?
- Are certain root causes more common at specific times?
- Can we predict investigation complexity from initial symptoms?

## Implementation Strategies

### Strategy 1: Build an RCA Analytics Pipeline

```
1. Extract Phase
   └── Query .plugins-ml-memory-message index
   └── Group by conversation_id
   └── Reconstruct investigation trees using parent_message_id

2. Transform Phase
   └── Parse additional_info for structured data
   └── Extract tool usage, results, and costs
   └── Classify RCA outcomes (success/failure)
   └── Calculate metrics (steps, time, cost)

3. Load Phase
   └── Store in dedicated analytics index
   └── Create visualization dashboards
   └── Build ML models for prediction

4. Learn Phase
   └── Train models on successful patterns
   └── Generate investigation recommendations
   └── Update agent prompts based on learnings
```

### Strategy 2: Real-time Learning System

```
1. After each RCA completion:
   └── Trigger analysis workflow
   └── Extract investigation pattern
   └── Compare to known patterns
   └── Update knowledge base if novel

2. Use learnings to improve future RCAs:
   └── Inject relevant past cases into context
   └── Suggest investigation steps based on similar symptoms
   └── Warn about known dead-end paths
```

### Strategy 3: Feedback Loop for Agent Improvement

```
1. Collect human feedback on RCA quality:
   └── Was the root cause correct?
   └── Were investigation steps efficient?
   └── What could be improved?

2. Correlate feedback with investigation patterns:
   └── Identify which patterns lead to accurate results
   └── Learn which tools are most valuable
   └── Detect when agent is going in circles

3. Update agent configuration:
   └── Refine prompt templates based on learnings
   └── Adjust tool selection priorities
   └── Modify reflection triggers
```

## Practical OpenSearch Queries

### Query 1: Find all completed RCAs with root causes

```json
{
  "query": {
    "bool": {
      "must": [
        { "term": { "origin.keyword": "PlanExecuteReflect Agent" }},
        { "regexp": { "response": ".*[Rr]oot [Cc]ause.*" }}
      ],
      "must_not": [
        { "exists": { "field": "parent_message_id" }}
      ]
    }
  },
  "sort": [{ "create_time": "desc" }]
}
```

### Query 2: Analyze tool usage patterns

```json
{
  "size": 0,
  "query": {
    "exists": { "field": "additional_info.tool_name" }
  },
  "aggs": {
    "tools": {
      "terms": {
        "field": "additional_info.tool_name.keyword",
        "size": 20
      },
      "aggs": {
        "by_origin": {
          "terms": { "field": "origin.keyword" }
        },
        "avg_trace_position": {
          "avg": { "field": "trace_number" }
        }
      }
    }
  }
}
```

### Query 3: Reconstruct full investigation for a specific RCA

```json
{
  "query": {
    "term": { "conversation_id": "specific-memory-id" }
  },
  "sort": [
    { "trace_number": "asc" },
    { "create_time": "asc" }
  ],
  "_source": [
    "trace_number",
    "input",
    "response",
    "additional_info",
    "parent_message_id"
  ]
}
```

### Query 4: Find RCAs with similar symptoms

```json
{
  "query": {
    "more_like_this": {
      "fields": ["input", "response"],
      "like": "Service returning connection timeout errors",
      "min_term_freq": 1,
      "max_query_terms": 12
    }
  },
  "size": 10
}
```

## Advanced: ML-Powered Enhancements

### 1. Investigation Path Prediction

Train a sequence model to predict next best investigation step:

```
Input: [symptom, completed_steps, tool_results]
Output: recommended_next_step

Example:
Input: ["500 errors", ["check logs"], ["found connection errors"]]
Output: "Check database connection pool status"
```

### 2. Root Cause Classification

Train a classifier to predict root cause category from symptoms:

```
Features:
- Error message patterns
- Affected service
- Time of day
- Recent deployments
- Metric anomalies

Labels:
- Network issues
- Database problems
- Code bugs
- Configuration errors
- Infrastructure failures
```

### 3. Anomaly Detection in Investigation Patterns

Detect unusual investigation patterns that might indicate:
- Agent getting stuck in loops
- Missing critical investigation steps
- Inefficient tool usage
- Novel failure modes

## Action Items

### Immediate (Week 1)
1. ✅ Confirm data is being stored in `.plugins-ml-memory-message`
2. Query a sample RCA and verify data structure
3. Build a simple dashboard showing:
   - Number of RCAs per day
   - Average investigation steps
   - Most common root causes

### Short-term (Month 1)
1. Implement RCA analytics pipeline
2. Create knowledge base of successful RCA patterns
3. Build tool effectiveness report
4. Identify top 5 investigation best practices

### Long-term (Quarter 1)
1. Train ML models for root cause prediction
2. Build recommendation system for investigation steps
3. Create automated RCA quality scoring
4. Implement feedback loop to improve agent performance

## Example Analysis Script

```python
from opensearchpy import OpenSearch

# Connect to OpenSearch
client = OpenSearch([{'host': 'localhost', 'port': 9200}])

# Extract all RCA conversations
def get_rca_conversations(start_date, end_date):
    query = {
        "query": {
            "bool": {
                "must": [
                    {"term": {"origin.keyword": "PlanExecuteReflect Agent"}},
                    {"range": {"create_time": {"gte": start_date, "lte": end_date}}}
                ]
            }
        },
        "size": 10000,
        "sort": [{"create_time": "asc"}]
    }

    response = client.search(index=".plugins-ml-memory-message", body=query)
    return response['hits']['hits']

# Reconstruct investigation tree
def reconstruct_investigation(conversation_id):
    interactions = get_rca_conversations(None, None)
    conv_interactions = [i for i in interactions
                        if i['_source']['conversation_id'] == conversation_id]

    # Build tree structure
    tree = {}
    root = None
    for interaction in conv_interactions:
        source = interaction['_source']
        node = {
            'id': interaction['_id'],
            'trace_num': source.get('trace_number'),
            'input': source.get('input'),
            'response': source.get('response'),
            'additional_info': source.get('additional_info'),
            'children': []
        }

        parent_id = source.get('parent_message_id')
        if parent_id:
            if parent_id in tree:
                tree[parent_id]['children'].append(node)
        else:
            root = node

        tree[interaction['_id']] = node

    return root

# Analyze investigation patterns
def analyze_patterns(conversations):
    patterns = {
        'avg_steps': 0,
        'common_tools': {},
        'success_rate': 0,
        'avg_duration': 0
    }

    for conv_id in conversations:
        tree = reconstruct_investigation(conv_id)
        # Analyze tree structure...
        # Extract patterns...

    return patterns

# Generate recommendations
def generate_recommendations(symptom, past_rcas):
    # Use similarity search to find similar past cases
    similar_cases = find_similar_rcas(symptom)

    # Extract common investigation paths
    common_paths = extract_common_paths(similar_cases)

    # Rank by success rate
    ranked_paths = rank_by_success(common_paths)

    return {
        'recommended_steps': ranked_paths[0],
        'likely_root_causes': extract_root_causes(similar_cases),
        'estimated_duration': calculate_avg_duration(similar_cases)
    }
```

## Conclusion

Your RCA process data is a goldmine for learning and improvement. By systematically analyzing this data, you can:

1. **Improve agent performance** through pattern learning
2. **Reduce investigation time** by learning from successful paths
3. **Build organizational knowledge** of system failure modes
4. **Predict and prevent** future incidents
5. **Optimize costs** by identifying most effective tools
6. **Train new team members** with successful RCA examples

The key is to treat your RCA data as a continuously growing knowledge base that feeds back into agent improvement, creating a virtuous cycle of learning and optimization.
