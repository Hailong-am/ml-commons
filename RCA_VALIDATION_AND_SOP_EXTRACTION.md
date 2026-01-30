# RCA Validation and SOP Extraction

## The Core Problem

**Question:** Can we extract RCA processes into SOPs/Skills when we don't know if the RCA is valid?

**Answer:** Yes, BUT only with proper validation mechanisms. Extracting SOPs from unvalidated RCAs is dangerous - it codifies potentially incorrect procedures.

## Validation Pyramid

```
Level 4: Proven Correct ✅✅✅✅    → Extract to SOPs
         ↑
Level 3: Highly Confident ✅✅✅    → Suggest as best practices
         ↑
Level 2: Likely Correct ✅✅       → Use for agent context
         ↑
Level 1: Unvalidated ❓           → Track but don't learn from
         ↑
Level 0: Proven Incorrect ❌      → Learn what NOT to do
```

## Validation Strategies

### Strategy 1: Human-in-the-Loop Validation (Most Reliable)

After each RCA completes, collect human feedback:

```json
{
  "conversation_id": "rca-123",
  "validation": {
    "root_cause_correct": true/false,
    "investigation_efficient": 1-5,
    "missing_steps": ["Should have checked X first"],
    "unnecessary_steps": ["Step 3 was redundant"],
    "would_recommend_approach": true/false,
    "time_to_validate": "2025-01-15T10:45:00Z",
    "validator": "john.doe@company.com",
    "actual_resolution": "Restarted service X, issue resolved"
  }
}
```

**Implementation:**

```java
/**
 * Tool to collect RCA validation feedback
 */
public class RCAValidationTool implements Tool {

    @Override
    public String getDescription() {
        return "Records validation feedback for a completed RCA investigation. " +
               "Input: conversation_id, root_cause_correct (boolean), investigation_rating (1-5), " +
               "comments (optional). This helps improve future investigations.";
    }

    @Override
    public <T> void run(Map<String, String> parameters, ActionListener<T> listener) {
        String conversationId = parameters.get("conversation_id");
        boolean rootCauseCorrect = Boolean.parseBoolean(parameters.get("root_cause_correct"));
        int investigationRating = Integer.parseInt(parameters.getOrDefault("investigation_rating", "3"));
        String comments = parameters.getOrDefault("comments", "");

        Map<String, Object> validation = new HashMap<>();
        validation.put("conversation_id", conversationId);
        validation.put("root_cause_correct", rootCauseCorrect);
        validation.put("investigation_rating", investigationRating);
        validation.put("comments", comments);
        validation.put("validated_at", Instant.now().toString());
        validation.put("validation_source", "human");

        // Index to validation index
        IndexRequest request = new IndexRequest("rca-validations")
            .source(validation, XContentType.JSON);

        client.index(request, ActionListener.wrap(response -> {
            // Update the analytics record with validation status
            updateAnalyticsWithValidation(conversationId, rootCauseCorrect, investigationRating);
            listener.onResponse((T) "Validation recorded successfully");
        }, listener::onFailure));
    }

    private void updateAnalyticsWithValidation(String conversationId, boolean correct, int rating) {
        UpdateRequest updateRequest = new UpdateRequest("rca-analytics", conversationId)
            .doc(Map.of(
                "validated", true,
                "root_cause_correct", correct,
                "investigation_rating", rating,
                "confidence_level", calculateConfidenceLevel(correct, rating)
            ))
            .docAsUpsert(true);

        client.update(updateRequest, ActionListener.wrap(
            response -> log.info("Updated analytics for {}", conversationId),
            e -> log.error("Failed to update analytics", e)
        ));
    }

    private int calculateConfidenceLevel(boolean correct, int rating) {
        if (!correct) return 0; // Proven incorrect
        if (rating >= 4) return 4; // Proven correct
        if (rating == 3) return 2; // Likely correct
        return 1; // Unvalidated/low confidence
    }
}
```

### Strategy 2: Outcome-Based Validation (Automated)

Track whether the proposed fix actually resolved the issue:

```json
{
  "conversation_id": "rca-123",
  "proposed_fix": "Increase connection pool size",
  "fix_applied_at": "2025-01-15T11:00:00Z",
  "outcome_validation": {
    "issue_recurred": false,
    "metrics_improved": true,
    "error_rate_before": 15.2,
    "error_rate_after": 0.3,
    "validation_period_hours": 24,
    "confidence": "high"
  }
}
```

**Implementation:**

```python
class OutcomeBasedValidator:
    """
    Automatically validates RCAs by monitoring if the issue recurs
    """

    def validate_rca_outcome(self, conversation_id, fix_applied_time, validation_hours=24):
        """
        Check if the issue recurred after the fix was applied
        """
        # Get original RCA details
        rca = self.get_rca_details(conversation_id)
        symptom_signature = self.extract_symptom_signature(rca['symptom'])

        # Query for similar issues after fix was applied
        end_time = fix_applied_time + timedelta(hours=validation_hours)

        query = {
            "query": {
                "bool": {
                    "must": [
                        {"range": {"timestamp": {
                            "gte": fix_applied_time,
                            "lte": end_time
                        }}},
                        {"match": {"error_message": symptom_signature}}
                    ]
                }
            }
        }

        response = self.client.search(index="application-logs", body=query)
        issue_recurred = response['hits']['total']['value'] > 0

        # Check if metrics improved
        metrics_before = self.get_metrics(
            start=fix_applied_time - timedelta(hours=validation_hours),
            end=fix_applied_time
        )
        metrics_after = self.get_metrics(
            start=fix_applied_time,
            end=end_time
        )

        metrics_improved = self.compare_metrics(metrics_before, metrics_after)

        # Calculate confidence
        if not issue_recurred and metrics_improved:
            confidence = "high"
            root_cause_correct = True
        elif issue_recurred:
            confidence = "low"
            root_cause_correct = False
        else:
            confidence = "medium"
            root_cause_correct = None  # Uncertain

        # Store validation
        validation = {
            "conversation_id": conversation_id,
            "issue_recurred": issue_recurred,
            "metrics_improved": metrics_improved,
            "validation_period_hours": validation_hours,
            "confidence": confidence,
            "root_cause_correct": root_cause_correct,
            "validated_at": datetime.now().isoformat(),
            "validation_source": "automated_outcome"
        }

        self.client.index(
            index="rca-validations",
            body=validation
        )

        return validation

    def extract_symptom_signature(self, symptom):
        """Extract key patterns from symptom for matching"""
        # Remove specific values (IDs, timestamps, etc)
        import re
        signature = re.sub(r'\d+', 'N', symptom)  # Replace numbers
        signature = re.sub(r'\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b', 'UUID', signature)  # UUIDs
        return signature

    def compare_metrics(self, before, after):
        """Compare metrics to determine if situation improved"""
        improvements = 0
        total_metrics = 0

        for metric_name in ['error_rate', 'latency_p95', 'cpu_usage']:
            if metric_name in before and metric_name in after:
                total_metrics += 1
                # Lower is better for these metrics
                if after[metric_name] < before[metric_name] * 0.8:  # 20% improvement
                    improvements += 1

        return improvements / total_metrics > 0.5 if total_metrics > 0 else False
```

### Strategy 3: Consensus-Based Validation

If multiple RCAs for similar symptoms converge on the same root cause, that increases confidence:

```python
def calculate_consensus_confidence(symptom, root_cause):
    """
    Calculate confidence based on how many similar RCAs reached same conclusion
    """
    # Find similar RCAs
    similar_rcas = find_similar_rcas(symptom)

    if len(similar_rcas) < 2:
        return "low"  # Only one case, can't validate via consensus

    # Count how many identified the same root cause
    root_cause_category = categorize_root_cause(root_cause)
    same_conclusion = [
        rca for rca in similar_rcas
        if categorize_root_cause(rca['root_cause']) == root_cause_category
    ]

    consensus_ratio = len(same_conclusion) / len(similar_rcas)

    if consensus_ratio >= 0.8:
        return "high"    # Strong consensus
    elif consensus_ratio >= 0.5:
        return "medium"  # Moderate consensus
    else:
        return "low"     # No consensus
```

### Strategy 4: Statistical Validation

Track which investigation patterns lead to successful resolutions:

```sql
-- Calculate success rate for each investigation pattern
SELECT
    investigation_path,
    COUNT(*) as times_used,
    AVG(CASE WHEN root_cause_correct = true THEN 1 ELSE 0 END) as success_rate,
    AVG(investigation_steps) as avg_steps,
    AVG(duration_seconds) as avg_duration
FROM rca_analytics
WHERE validated = true
GROUP BY investigation_path
HAVING COUNT(*) >= 5  -- Only patterns used at least 5 times
ORDER BY success_rate DESC, times_used DESC
```

## Confidence Scoring System

Combine multiple validation signals into a confidence score:

```python
class RCAConfidenceScorer:
    """
    Calculate confidence score for an RCA based on multiple signals
    """

    WEIGHTS = {
        'human_validation': 0.40,
        'outcome_validation': 0.30,
        'consensus': 0.15,
        'statistical_pattern': 0.10,
        'agent_uncertainty': 0.05
    }

    def calculate_confidence(self, conversation_id):
        scores = {}

        # 1. Human validation (if available)
        human_val = self.get_human_validation(conversation_id)
        if human_val:
            if human_val['root_cause_correct']:
                scores['human_validation'] = human_val['investigation_rating'] / 5.0
            else:
                scores['human_validation'] = 0.0

        # 2. Outcome validation
        outcome_val = self.get_outcome_validation(conversation_id)
        if outcome_val:
            if outcome_val['confidence'] == 'high':
                scores['outcome_validation'] = 1.0
            elif outcome_val['confidence'] == 'medium':
                scores['outcome_validation'] = 0.5
            else:
                scores['outcome_validation'] = 0.0

        # 3. Consensus validation
        rca = self.get_rca_details(conversation_id)
        consensus = self.calculate_consensus_confidence(
            rca['symptom'],
            rca['root_cause']
        )
        scores['consensus'] = {
            'high': 1.0,
            'medium': 0.6,
            'low': 0.3
        }.get(consensus, 0.3)

        # 4. Statistical pattern match
        pattern_success = self.get_pattern_success_rate(rca['investigation_path'])
        scores['statistical_pattern'] = pattern_success

        # 5. Agent's own uncertainty signals
        agent_confidence = self.extract_agent_confidence(rca)
        scores['agent_uncertainty'] = agent_confidence

        # Calculate weighted average
        total_score = sum(
            scores.get(key, 0.5) * weight
            for key, weight in self.WEIGHTS.items()
        )

        return {
            'confidence_score': total_score,
            'confidence_level': self.score_to_level(total_score),
            'contributing_factors': scores,
            'recommendation': self.get_recommendation(total_score)
        }

    def score_to_level(self, score):
        if score >= 0.8:
            return 4  # Proven correct
        elif score >= 0.6:
            return 3  # Highly confident
        elif score >= 0.4:
            return 2  # Likely correct
        elif score >= 0.2:
            return 1  # Unvalidated
        else:
            return 0  # Likely incorrect

    def get_recommendation(self, score):
        if score >= 0.8:
            return "EXTRACT_TO_SOP"
        elif score >= 0.6:
            return "SUGGEST_AS_BEST_PRACTICE"
        elif score >= 0.4:
            return "USE_FOR_CONTEXT"
        elif score >= 0.2:
            return "MONITOR_ONLY"
        else:
            return "MARK_AS_UNRELIABLE"
```

## SOP Extraction Process (With Validation)

### Step 1: Filter for High-Confidence RCAs

```json
GET /rca-analytics/_search
{
  "query": {
    "bool": {
      "must": [
        {"term": {"validated": true}},
        {"term": {"root_cause_correct": true}},
        {"range": {"confidence_score": {"gte": 0.8}}}
      ]
    }
  },
  "aggs": {
    "by_symptom_category": {
      "terms": {"field": "symptom_category"},
      "aggs": {
        "common_patterns": {
          "terms": {
            "field": "investigation_path.keyword",
            "min_doc_count": 5,
            "order": {"avg_rating": "desc"}
          },
          "aggs": {
            "avg_rating": {"avg": {"field": "investigation_rating"}},
            "avg_steps": {"avg": {"field": "investigation_steps"}},
            "success_rate": {"avg": {"field": "root_cause_correct"}}
          }
        }
      }
    }
  }
}
```

### Step 2: Extract SOP Template

```python
class SOPExtractor:
    """
    Extract Standard Operating Procedures from validated RCAs
    """

    def extract_sop_for_symptom_category(self, symptom_category, min_confidence=0.8):
        """
        Extract SOP for a specific symptom category
        """
        # Get all high-confidence RCAs for this category
        query = {
            "query": {
                "bool": {
                    "must": [
                        {"term": {"symptom_category": symptom_category}},
                        {"term": {"validated": True}},
                        {"term": {"root_cause_correct": True}},
                        {"range": {"confidence_score": {"gte": min_confidence}}}
                    ]
                }
            },
            "size": 100
        }

        response = self.client.search(index="rca-analytics", body=query)
        rcas = [hit['_source'] for hit in response['hits']['hits']]

        if len(rcas) < 5:
            return None  # Not enough data to create SOP

        # Extract common investigation pattern
        investigation_steps = self.extract_common_steps(rcas)

        # Extract common root causes
        root_causes = self.extract_common_root_causes(rcas)

        # Extract recommended tools
        recommended_tools = self.extract_recommended_tools(rcas)

        # Build SOP
        sop = {
            "category": symptom_category,
            "title": f"RCA Procedure for {symptom_category.title()} Issues",
            "confidence": self.calculate_sop_confidence(rcas),
            "based_on_cases": len(rcas),
            "last_updated": datetime.now().isoformat(),
            "procedure": {
                "overview": self.generate_overview(symptom_category, rcas),
                "prerequisites": self.extract_prerequisites(rcas),
                "investigation_steps": investigation_steps,
                "common_root_causes": root_causes,
                "recommended_tools": recommended_tools,
                "average_duration": statistics.mean([r['duration_seconds'] for r in rcas]),
                "success_rate": statistics.mean([r.get('root_cause_correct', 0) for r in rcas])
            },
            "examples": self.extract_example_cases(rcas),
            "warnings": self.extract_warnings(rcas),
            "metadata": {
                "creation_date": datetime.now().isoformat(),
                "version": "1.0",
                "review_status": "auto_generated_pending_review"
            }
        }

        return sop

    def extract_common_steps(self, rcas):
        """
        Extract the most common investigation steps sequence
        """
        # Collect all investigation paths
        paths = [rca['investigation_path'].split(' -> ') for rca in rcas]

        # Find common prefixes (early steps that most investigations share)
        common_steps = []
        step_position = 0

        while True:
            steps_at_position = {}
            for path in paths:
                if len(path) > step_position:
                    step = self.normalize_step(path[step_position])
                    steps_at_position[step] = steps_at_position.get(step, 0) + 1

            if not steps_at_position:
                break

            # Find most common step at this position
            most_common = max(steps_at_position.items(), key=lambda x: x[1])
            if most_common[1] / len(paths) >= 0.6:  # 60% of cases use this step
                common_steps.append({
                    "step_number": step_position + 1,
                    "description": most_common[0],
                    "frequency": most_common[1] / len(paths),
                    "typical_outcome": self.get_typical_outcome(most_common[0], rcas)
                })
                step_position += 1
            else:
                # No clear consensus, stop looking for common steps
                break

        return common_steps

    def normalize_step(self, step):
        """Normalize step description to group similar steps"""
        import re
        # Remove specific values
        normalized = re.sub(r'\d+', 'N', step)
        normalized = re.sub(r'\b[0-9a-f-]{36}\b', 'UUID', normalized)
        # Simplify to key action
        normalized = normalized.lower()
        return normalized

    def extract_common_root_causes(self, rcas):
        """Extract most common root causes with their frequencies"""
        root_cause_counts = {}

        for rca in rcas:
            category = rca.get('root_cause_category', 'unknown')
            root_cause_counts[category] = root_cause_counts.get(category, 0) + 1

        total = len(rcas)
        return [
            {
                "category": category,
                "frequency": count / total,
                "description": self.get_category_description(category),
                "typical_resolution": self.get_typical_resolution(category, rcas)
            }
            for category, count in sorted(
                root_cause_counts.items(),
                key=lambda x: x[1],
                reverse=True
            )
        ]

    def extract_recommended_tools(self, rcas):
        """Extract tools that were most effective"""
        tool_effectiveness = {}

        for rca in rcas:
            for tool in rca.get('tools_used', []):
                if tool not in tool_effectiveness:
                    tool_effectiveness[tool] = {
                        'usage_count': 0,
                        'total_steps': [],
                        'success_cases': 0
                    }

                tool_effectiveness[tool]['usage_count'] += 1
                tool_effectiveness[tool]['total_steps'].append(rca['investigation_steps'])
                if rca.get('root_cause_correct'):
                    tool_effectiveness[tool]['success_cases'] += 1

        # Calculate effectiveness score
        recommendations = []
        for tool, stats in tool_effectiveness.items():
            avg_steps = statistics.mean(stats['total_steps'])
            success_rate = stats['success_cases'] / stats['usage_count']

            recommendations.append({
                "tool": tool,
                "usage_frequency": stats['usage_count'] / len(rcas),
                "success_rate": success_rate,
                "avg_investigation_length": avg_steps,
                "recommendation": "REQUIRED" if success_rate > 0.8 else "OPTIONAL"
            })

        return sorted(recommendations, key=lambda x: x['success_rate'], reverse=True)
```

### Step 3: Create Agent Skill from SOP

```json
{
  "skill_name": "network_issue_rca_sop",
  "description": "Standard Operating Procedure for investigating network-related issues",
  "confidence": 0.85,
  "based_on_validated_cases": 47,
  "trigger_conditions": {
    "symptom_patterns": [
      "connection timeout",
      "network unreachable",
      "dns resolution failed"
    ],
    "symptom_category": "network"
  },
  "execution_plan": {
    "steps": [
      {
        "step": 1,
        "action": "Check recent network topology changes",
        "tool": "SearchIndexTool",
        "parameters": {
          "index": "network-changes",
          "timeframe": "last 2 hours"
        },
        "expected_outcome": "List of recent changes",
        "if_found_issues": "proceed_to_step_5",
        "if_no_issues": "proceed_to_step_2"
      },
      {
        "step": 2,
        "action": "Analyze network latency patterns",
        "tool": "DataDistributionTool",
        "parameters": {
          "index": "network-metrics",
          "field": "latency_ms",
          "timeframe": "last 1 hour"
        },
        "expected_outcome": "Latency distribution",
        "threshold_for_concern": "p95 > 500ms"
      },
      {
        "step": 3,
        "action": "Check DNS resolution times",
        "tool": "SearchIndexTool",
        "parameters": {
          "index": "dns-logs",
          "query": "resolution_time > 1000"
        }
      }
    ]
  },
  "common_root_causes": [
    {
      "cause": "Network switch failure",
      "probability": 0.35,
      "typical_resolution": "Failover to backup switch"
    },
    {
      "cause": "DNS server overload",
      "probability": 0.28,
      "typical_resolution": "Scale DNS replicas"
    }
  ],
  "validation_metadata": {
    "success_rate": 0.89,
    "avg_investigation_time": "8 minutes",
    "last_validated": "2025-01-15T10:00:00Z",
    "requires_human_review": false
  }
}
```

## Continuous Validation and Improvement Loop

```
1. RCA Execution
   ↓
2. Automatic Outcome Tracking
   ↓
3. Human Validation (periodic sampling)
   ↓
4. Confidence Calculation
   ↓
5. SOP Extraction (for high-confidence patterns)
   ↓
6. SOP Deployment to Agent
   ↓
7. Monitor SOP Effectiveness
   ↓
8. Update/Retire SOPs based on new data
   ↓
   (back to step 1)
```

## Implementation: Gradual Learning System

```python
class GradualLearningSystem:
    """
    Safely learn from RCAs with increasing confidence levels
    """

    CONFIDENCE_THRESHOLDS = {
        'suggest_to_human': 0.4,      # Show to humans for review
        'use_as_context': 0.6,        # Inject into agent context
        'recommend_strongly': 0.8,     # Recommend in agent planning
        'extract_to_sop': 0.9         # Create formal SOP
    }

    def process_new_rca(self, conversation_id):
        """
        Process a newly completed RCA through validation pipeline
        """
        # Wait for outcome validation (24 hours)
        self.schedule_outcome_validation(conversation_id, delay_hours=24)

        # Request human validation for sample cases
        if self.should_request_human_validation():
            self.request_human_validation(conversation_id)

        # Calculate initial confidence (without outcome)
        confidence = self.calculate_confidence(conversation_id)

        # Take action based on confidence
        if confidence >= self.CONFIDENCE_THRESHOLDS['suggest_to_human']:
            self.flag_for_human_review(conversation_id)

        return confidence

    def update_after_validation(self, conversation_id, validation_result):
        """
        Update confidence and take actions after validation
        """
        # Recalculate confidence with new validation data
        confidence = self.calculate_confidence(conversation_id)

        if confidence >= self.CONFIDENCE_THRESHOLDS['extract_to_sop']:
            # Check if we have enough similar cases
            similar_count = self.count_similar_validated_cases(conversation_id)
            if similar_count >= 5:
                self.trigger_sop_extraction(conversation_id)

        elif confidence >= self.CONFIDENCE_THRESHOLDS['recommend_strongly']:
            self.add_to_recommended_patterns(conversation_id)

        elif confidence >= self.CONFIDENCE_THRESHOLDS['use_as_context']:
            self.add_to_context_pool(conversation_id)

        # If confidence is low, mark for review
        if confidence < 0.3:
            self.flag_as_potentially_incorrect(conversation_id)

    def should_request_human_validation(self):
        """
        Determine if we should request human validation
        Sample more when we have less data
        """
        total_rcas = self.count_total_rcas()
        validated_rcas = self.count_validated_rcas()

        validation_rate = validated_rcas / total_rcas if total_rcas > 0 else 0

        # Sample more aggressively when we have less validated data
        if validated_rcas < 20:
            return random.random() < 0.5  # 50% sampling rate
        elif validated_rcas < 100:
            return random.random() < 0.2  # 20% sampling rate
        else:
            return random.random() < 0.05  # 5% sampling rate
```

## Answer to Original Question

**Q: Can we extract RCA processes to SOPs/skills? Is that valid when we don't know if the RCA is valid?**

**A: Yes, but with these requirements:**

1. ✅ **Implement validation mechanisms** (human feedback, outcome tracking, consensus)
2. ✅ **Use confidence scoring** to filter high-quality RCAs
3. ✅ **Start conservatively** - use validated patterns for context, not SOPs initially
4. ✅ **Gradual deployment** - monitor effectiveness before full adoption
5. ✅ **Continuous feedback** - keep validating even after SOP extraction
6. ✅ **Human oversight** - periodic review of auto-generated SOPs
7. ✅ **Version control** - track SOP changes and be ready to rollback

**Don't extract SOPs from:**
- ❌ Unvalidated RCAs
- ❌ Single occurrence patterns
- ❌ RCAs with conflicting resolutions
- ❌ Cases where the issue recurred
- ❌ Low confidence investigations

**The learning pyramid:**
```
Level 1 (Day 1-30):   Collect RCAs, no learning yet
Level 2 (Day 31-60):  Use validated patterns for agent context
Level 3 (Day 61-90):  Recommend investigation approaches
Level 4 (Day 91+):    Extract formal SOPs for proven patterns
```

This approach ensures you're learning from **good practices**, not encoding **bad habits** into your system.
