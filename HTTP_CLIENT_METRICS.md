# HTTP Client Metrics and Connection Pool Diagnostics

## Overview

ML-Commons provides comprehensive HTTP client metrics and connection pool diagnostics to troubleshoot performance issues, particularly the connection pool exhaustion error:

```
Acquire operation took longer than the configured maximum time.
This indicates that a request cannot get a connection from the pool within the specified maximum time.
```

This document covers:
- Architecture and connection pool isolation
- Automatic diagnostics (always active)
- Detailed metrics (opt-in)
- Bedrock-specific diagnostics
- Troubleshooting workflow

## Architecture Understanding

### Connection Pool Isolation

**Key Finding**: Each ML-Commons connector creates its own HTTP client instance with independent connection pools.

```
Connector A → MLHttpClient A → NettyClient A → Pool Map A
                                                └─ Pool("https://bedrock...us-east-1:443") = 20 connections

Connector B → MLHttpClient B → NettyClient B → Pool Map B
                                                └─ Pool("https://bedrock...us-east-1:443") = 20 connections
```

**Implications**:
- Even if two connectors point to the same destination (same host, port, protocol), they use **separate pools**
- `max_connections` in connector config applies **per connector**, not globally
- Pool exhaustion affects only one connector at a time
- No pool contention between connectors

See: **UNDERSTANDING_POOL_KEY.md** for details on how poolKey determines pool assignment.

## Implementation Approaches

### 1. Raw HTTP Client Metrics (AwsConnectorExecutor, HttpJsonConnectorExecutor)

**Files**:
- `MLHttpClientFactory.java` - Wraps HTTP clients with metrics instrumentation
- `MLMetricsInstrumentedHttpClient.java` - Custom wrapper providing pool diagnostics

**Affected Connectors**:
- `AwsConnectorExecutor` - AWS SigV4 signed requests
- `HttpJsonConnectorExecutor` - Standard HTTP/HTTPS requests

### 2. AWS Service Client Metrics (BedrockStreamingHandler)

**Files**:
- `BedrockStreamingHandler.java` - Bedrock-specific request tracking

**Affected Connectors**:
- Bedrock Converse streaming operations

### 3. MCP Tool Logging (McpStreamableHttpTool, McpSseTool)

**Note**: MCP connectors use Java's native HttpClient created internally by the MCP library. Connection pool metrics are not available for MCP connectors.

**Files**:
- `McpStreamableHttpTool.java` - MCP HTTP tool call logging
- `McpSseTool.java` - MCP SSE tool call logging

See: **MCP_CONNECTORS_METRICS_NOTE.md** for details.

## Enabling Metrics

### System Property

To enable detailed HTTP client metrics:

```bash
-Dml.http.client.metrics.enabled=true
```

Examples:

```bash
# When running locally
./gradlew run -Dml.http.client.metrics.enabled=true

# In jvm.options
-Dml.http.client.metrics.enabled=true
```

### What Gets Logged

**With metrics DISABLED (default)**:
- Pool saturation warnings (>80% full) - WARN level
- Slow request warnings (>5s, >30s) - WARN level
- Peak concurrency tracking - INFO level
- Bedrock diagnostics - INFO/WARN/ERROR levels
- MCP tool calls - INFO level

**With metrics ENABLED**:
- All of the above, PLUS:
- Detailed per-request metrics boxes - INFO level
- Request/response timing - INFO level

## Automatic Diagnostics (Always Active)

These diagnostics are **always active** at WARN level, regardless of metrics flag.

### 1. Pool Saturation Warnings

**Triggers**: When pool reaches 80% capacity

```
WARN  MLMetricsInstrumentedHttpClient - CONNECTION POOL SATURATION WARNING [a1b2c3d4] - Request #42 for https://api.example.com: Pool is 85.0% full.
ActiveRequests: 17/20, AvailableConcurrency: 3.
If you see 'Acquire operation took longer than configured maximum time' errors, consider increasing max_connection in connector configuration.
PeakConcurrency: 19, SlowRequestCount: 5
```

**Information Provided**:
- Client ID (`[a1b2c3d4]`) - unique identifier for this HTTP client instance
- Pool utilization percentage
- Active vs available connections
- Peak concurrency reached
- Number of slow requests detected

**Action**: Increase `max_connections` in connector configuration if saturation is frequent.

### 2. Slow Request Detection

**Slow Request (>5 seconds)**:
```
WARN  MLMetricsInstrumentedHttpClient - Slow request detected [a1b2c3d4] - Request #42: POST https://api.example.com/predict took 6500ms (>5000ms threshold).
ActiveRequests: 15, AvailableConcurrency: 5
```

**Very Slow Request (>30 seconds)**:
```
WARN  MLMetricsInstrumentedHttpClient - VERY SLOW REQUEST DETECTED [a1b2c3d4] - Request #42: POST https://api.example.com/predict took 35000ms (>30000ms threshold).
This request held a connection for an extended time.
ActiveRequests: 15, AvailableConcurrency: 5
```

**Why This Matters**:
- Slow requests hold connections longer
- Reduces available connections for other requests
- Can lead to pool exhaustion even with moderate traffic

**Action**: Investigate slow endpoints. Consider increasing pool size or adding timeouts.

### 3. Peak Concurrency Tracking

```
INFO  MLMetricsInstrumentedHttpClient - New peak concurrency [a1b2c3d4]: 18 concurrent requests (max: 20)
```

**What it tells you**:
- Maximum concurrent requests reached for this client
- How close you are to pool capacity

**Action**: If peak consistently approaches max, increase pool size.

### 4. Client Creation Logs

```
INFO  MLMetricsInstrumentedHttpClient - Created HTTP client [a1b2c3d4] with maxConcurrency=20
```

**Use**: Correlate client ID to connector by finding creation logs.

## Detailed Metrics (When Enabled)

When `-Dml.http.client.metrics.enabled=true`, detailed per-request metrics are logged:

```
INFO  MLMetricsInstrumentedHttpClient - ┌────────────────────────────────────────────────────────────────┐
INFO  MLMetricsInstrumentedHttpClient - │ HttpClient Metrics - [a1b2c3d4]                               │
INFO  MLMetricsInstrumentedHttpClient - ├────────────────────────────────────────────────────────────────┤
INFO  MLMetricsInstrumentedHttpClient - │ Phase: REQUEST_END                                            │
INFO  MLMetricsInstrumentedHttpClient - │ RequestNumber: 42                                             │
INFO  MLMetricsInstrumentedHttpClient - │ Method: POST                                                  │
INFO  MLMetricsInstrumentedHttpClient - │ URI: https://api.example.com/predict                          │
INFO  MLMetricsInstrumentedHttpClient - │ Status: SUCCESS                                               │
INFO  MLMetricsInstrumentedHttpClient - │ Duration: 1.25s                                               │
INFO  MLMetricsInstrumentedHttpClient - │ MaxConcurrency: 20                                            │
INFO  MLMetricsInstrumentedHttpClient - │ ActiveRequests: 15                                            │
INFO  MLMetricsInstrumentedHttpClient - │ AvailableConcurrency: 5                                       │
INFO  MLMetricsInstrumentedHttpClient - │ PoolUtilization: 75.0%                                        │
INFO  MLMetricsInstrumentedHttpClient - │ PeakConcurrency: 18                                           │
INFO  MLMetricsInstrumentedHttpClient - │ SlowRequestCount: 3                                           │
INFO  MLMetricsInstrumentedHttpClient - │ HttpClientName: Netty                                         │
INFO  MLMetricsInstrumentedHttpClient - └────────────────────────────────────────────────────────────────┘
```

**Metrics Explained**:
- **Phase**: `REQUEST_START` or `REQUEST_END`
- **RequestNumber**: Sequential request number for this client instance
- **Method**: HTTP method (GET, POST, etc.)
- **URI**: Request URI
- **Status**: `SUCCESS` or `FAILED`
- **Duration**: Request duration (ns/μs/ms/s format)
- **MaxConcurrency**: Maximum concurrent connections allowed
- **ActiveRequests**: Currently executing requests
- **AvailableConcurrency**: Free connections
- **PoolUtilization**: Percentage of pool in use
- **PeakConcurrency**: Highest concurrency seen
- **SlowRequestCount**: Total slow requests detected
- **HttpClientName**: HTTP client implementation (Netty)

## Bedrock-Specific Diagnostics

Bedrock connectors have additional diagnostics to identify model performance issues.

### Request Lifecycle Logs

**1. Request Start (INFO)**:
```
INFO  BedrockStreamingHandler - BEDROCK_REQUEST_START - Model: anthropic.claude-3-sonnet-20240229-v1:0, Region: us-east-1, Action: predict, Payload size: 1234 bytes
```

**2. First Byte Received (DEBUG)**:
```
DEBUG BedrockStreamingHandler - BEDROCK_FIRST_BYTE - Model: anthropic.claude-3-sonnet-20240229-v1:0, TTFB: 1250ms
```

**3. Request Complete (INFO)**:
```
INFO  BedrockStreamingHandler - BEDROCK_REQUEST_COMPLETE - Model: anthropic.claude-3-sonnet-20240229-v1:0, TotalDuration: 15000ms, TimeToFirstByte: 1250ms, EventCount: 342, State: COMPLETED
```

### Bedrock Warnings

**Throttling Detection (WARN)**:
```
WARN  BedrockStreamingHandler - BEDROCK_THROTTLED - Model: anthropic.claude-3-sonnet-20240229-v1:0, Duration: 5000ms - Bedrock throttling detected
```

**Slow Request (WARN)**:
```
WARN  BedrockStreamingHandler - BEDROCK_SLOW_REQUEST - Model: anthropic.claude-3-sonnet-20240229-v1:0 took 35000ms to complete. This may be holding connections and causing pool exhaustion. Consider: 1) Using a faster model, 2) Increasing connection pool size, 3) Adding request timeout
```

**Slow Time to First Byte (WARN)**:
```
WARN  BedrockStreamingHandler - BEDROCK_SLOW_TTFB - Model: anthropic.claude-3-sonnet-20240229-v1:0 took 8000ms for first byte. Slow model initialization may cause connection pool buildup.
```

**Client Error 4xx (WARN)**:
```
WARN  BedrockStreamingHandler - BEDROCK_CLIENT_ERROR - Model: anthropic.claude-3-sonnet-20240229-v1:0, Duration: 2000ms, Status: 4xx
```

**Server Error 5xx (ERROR)**:
```
ERROR BedrockStreamingHandler - BEDROCK_SERVER_ERROR - Model: anthropic.claude-3-sonnet-20240229-v1:0, Duration: 3000ms, Status: 5xx
```

See: **BEDROCK_DIAGNOSTICS.md** for detailed Bedrock troubleshooting.

## MCP Tool Logging

MCP tool calls are logged for debugging:

```
INFO  McpStreamableHttpTool - MCP Streamable HTTP Tool Call - Tool: search_documents, Input: {"query":"OpenSearch","limit":10}
INFO  McpStreamableHttpTool - MCP Streamable HTTP Tool Response - Tool: search_documents, Result: [{"content":[...]}]
```

## Diagnostic Workflow

### Step 1: Identify the Problem Connector

When you see "Acquire operation took longer than configured maximum time":

1. **Look for recent saturation warnings**:
   ```bash
   grep "SATURATION WARNING" logs/opensearch.log | tail -5
   ```

2. **Find which connector created that client**:
   ```bash
   grep "Created HTTP client \[a1b2c3d4\]" logs/opensearch.log
   ```

### Step 2: Determine Root Cause

**Check for Pool Saturation**:
```bash
grep "SATURATION WARNING" logs/opensearch.log | grep "\[a1b2c3d4\]" | wc -l
```
- Frequent warnings → Pool too small for traffic

**Check for Slow Requests**:
```bash
grep "Slow request\|VERY SLOW REQUEST" logs/opensearch.log | grep "\[a1b2c3d4\]"
```
- Many slow requests → Downstream service or model is slow

**Check Peak Concurrency**:
```bash
grep "New peak concurrency" logs/opensearch.log | grep "\[a1b2c3d4\]" | tail -1
```
- Peak near max → Need larger pool

### Step 3: For Bedrock Connectors

**Identify Slow Models**:
```bash
grep "BEDROCK_SLOW_REQUEST" logs/opensearch.log | awk -F'Model: ' '{print $2}' | awk -F' took' '{print $1}' | sort | uniq -c | sort -rn
```

**Check Time to First Byte Distribution**:
```bash
grep "BEDROCK_REQUEST_COMPLETE" logs/opensearch.log | awk -F'TimeToFirstByte: ' '{print $2}' | awk -F'ms' '{print $1}' | sort -n
```

**Check for Throttling**:
```bash
grep "BEDROCK_THROTTLED" logs/opensearch.log | wc -l
```

### Step 4: Apply Solution

**If pool is saturated** → Increase `max_connections`:
```json
{
  "connector": {
    "client_config": {
      "max_connections": 50
    }
  }
}
```

**If requests are slow** → Use faster model or increase timeout:
```json
{
  "connector": {
    "parameters": {
      "model": "anthropic.claude-3-haiku-20240307-v1:0"
    },
    "client_config": {
      "read_timeout": 60
    }
  }
}
```

**If Bedrock throttling** → Reduce request rate or request quota increase from AWS

## Configuration Tuning

### Connector Client Configuration

```json
{
  "connector": {
    "client_config": {
      "max_connections": 20,        // Connection pool size
      "connection_timeout": 10,     // Seconds to establish connection
      "read_timeout": 30,           // Seconds to read response
      "retry_backoff_millis": 100,
      "retry_backoff_policy": "constant",
      "retry_timeout_seconds": 30,
      "max_retry_times": 3
    }
  }
}
```

### Recommended Values by Workload

**Low Traffic (< 10 req/s)**:
```json
{"max_connections": 10, "read_timeout": 30}
```

**Medium Traffic (10-50 req/s)**:
```json
{"max_connections": 30, "read_timeout": 30}
```

**High Traffic (> 50 req/s)**:
```json
{"max_connections": 50, "read_timeout": 30}
```

**Slow Downstream Service**:
```json
{"max_connections": 50, "read_timeout": 60}
```

**Bedrock by Model**:
- Haiku (fast): `max_connections: 20-30`
- Sonnet (medium): `max_connections: 30-50`
- Opus (slow): `max_connections: 50-100`

See: **CONNECTION_POOL_TROUBLESHOOTING.md** for detailed tuning guidance.

## Related Documentation

- **CONNECTION_POOL_TROUBLESHOOTING.md** - General pool troubleshooting guide
- **BEDROCK_DIAGNOSTICS.md** - Bedrock-specific diagnostics and solutions
- **UNDERSTANDING_POOL_KEY.md** - How connection pools are organized
- **MCP_CONNECTORS_METRICS_NOTE.md** - Limitations of MCP connector metrics

## Implementation Files

- `common/src/main/java/org/opensearch/ml/common/httpclient/MLHttpClientFactory.java`
- `common/src/main/java/org/opensearch/ml/common/httpclient/MLMetricsInstrumentedHttpClient.java`
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/remote/streaming/BedrockStreamingHandler.java`
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/remote/AwsConnectorExecutor.java`
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/remote/HttpJsonConnectorExecutor.java`
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/tools/McpStreamableHttpTool.java`
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/tools/McpSseTool.java`

## Quick Reference Commands

```bash
# Enable detailed metrics
-Dml.http.client.metrics.enabled=true

# Find saturation warnings
grep "SATURATION WARNING" logs/opensearch.log

# Find slow requests
grep "Slow request\|VERY SLOW" logs/opensearch.log

# Check peak concurrency
grep "peak concurrency" logs/opensearch.log | tail -1

# Identify slow Bedrock models
grep "BEDROCK_SLOW_REQUEST" logs/opensearch.log | awk -F'Model: ' '{print $2}' | awk -F' took' '{print $1}' | sort | uniq -c | sort -rn

# Check Bedrock throttling
grep "BEDROCK_THROTTLED" logs/opensearch.log | wc -l

# Find most problematic URIs
grep "Slow request\|SATURATION" logs/opensearch.log | grep -o 'https://[^ ]*' | sort | uniq -c | sort -rn
```

## Performance Impact

The metrics collection has minimal performance impact:
- **Disabled (default)**: No overhead for detailed metrics, only lightweight counters for warnings
- **Enabled**: Small overhead for formatting detailed metric boxes
- Warnings (saturation, slow requests) are always active with negligible overhead
