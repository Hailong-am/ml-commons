# Connection Pool Troubleshooting Guide

## Overview

This guide helps you troubleshoot connection pool exhaustion issues, particularly the error:
```
Acquire operation took longer than the configured maximum time.
This indicates that a request cannot get a connection from the pool within the specified maximum time.
This can be due to high request rate.
```

## Enhanced Logging Features

The ML-Commons HTTP client now includes comprehensive logging to help diagnose connection pool issues:

### 1. **Pool Saturation Warnings** (Always Active - WARN level)
Automatically logs when the connection pool reaches 80% capacity:

```
WARN  MLMetricsInstrumentedHttpClient - CONNECTION POOL SATURATION WARNING [a1b2c3d4] - Request #42 for https://api.example.com: Pool is 85.0% full.
ActiveRequests: 17/20, AvailableConcurrency: 3.
If you see 'Acquire operation took longer than configured maximum time' errors, consider increasing max_connection in connector configuration.
PeakConcurrency: 19, SlowRequestCount: 5
```

**Key Information:**
- **Pool utilization percentage** - How full the pool is
- **ActiveRequests** - Current concurrent requests
- **AvailableConcurrency** - Free connections remaining
- **PeakConcurrency** - Highest concurrency seen
- **SlowRequestCount** - Number of slow requests detected

### 2. **Slow Request Detection** (Always Active - WARN level)
Identifies requests holding connections for extended periods:

**Slow Request (>5 seconds):**
```
WARN  MLMetricsInstrumentedHttpClient - Slow request detected [a1b2c3d4] - Request #42: POST https://api.example.com/predict took 6500ms (>5000ms threshold).
ActiveRequests: 15, AvailableConcurrency: 5
```

**Very Slow Request (>30 seconds):**
```
WARN  MLMetricsInstrumentedHttpClient - VERY SLOW REQUEST DETECTED [a1b2c3d4] - Request #42: POST https://api.example.com/predict took 35000ms (>30000ms threshold).
This request held a connection for an extended time.
ActiveRequests: 15, AvailableConcurrency: 5
```

**Why This Matters:**
- Slow requests hold connections longer
- Reduces available connections for other requests
- Can lead to pool exhaustion

### 3. **Peak Concurrency Tracking** (INFO level)
Logs when new concurrency peaks are reached:

```
INFO  MLMetricsInstrumentedHttpClient - New peak concurrency [a1b2c3d4]: 18 concurrent requests (max: 20)
```

**Helps Identify:**
- Maximum concurrent request load
- Whether pool size is appropriate for your workload

### 4. **Detailed Request Metrics** (INFO level - requires metrics enabled)
When metrics are enabled (`-Dml.http.client.metrics.enabled=true`), you get detailed per-request metrics:

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

## Diagnostic Steps

### Step 1: Check for Pool Saturation Warnings

**Look for:**
```bash
grep "CONNECTION POOL SATURATION WARNING" logs/opensearch.log
```

**If found:**
- Note the pool utilization percentage
- Check PeakConcurrency vs MaxConcurrency
- If consistently > 80%, increase `max_connection` in connector config

### Step 2: Identify Slow Requests

**Find slow requests:**
```bash
grep "Slow request detected\|VERY SLOW REQUEST" logs/opensearch.log
```

**Analyze:**
- Which endpoints/URIs are slow?
- How long are they taking?
- Are they consistent or sporadic?

**Count by URI:**
```bash
grep "Slow request" logs/opensearch.log | awk -F'for ' '{print $2}' | awk -F' took' '{print $1}' | sort | uniq -c | sort -rn
```

### Step 3: Check Peak Concurrency

**Find peak:**
```bash
grep "New peak concurrency" logs/opensearch.log | tail -1
```

**Compare to max_connection:**
- If peak approaches max frequently, increase pool size
- If peak is much lower than max, you may have slow requests

### Step 4: Enable Detailed Metrics (if needed)

**Enable:**
```bash
-Dml.http.client.metrics.enabled=true
```

**Analyze request patterns:**
```bash
# See all completed requests with timing
grep "REQUEST_END" logs/opensearch.log

# Find requests by duration
grep "Duration:" logs/opensearch.log | awk '{print $NF}' | sort
```

## Common Scenarios & Solutions

### Scenario 1: Pool Constantly Saturated

**Symptoms:**
- Frequent saturation warnings
- Peak concurrency = max concurrency
- Many pending requests

**Solution:**
Increase `max_connection` in connector configuration:

```json
{
  "connector": {
    "client_config": {
      "max_connections": 50  // Increase from 20 (example)
    }
  }
}
```

### Scenario 2: Slow Downstream Service

**Symptoms:**
- Many slow request warnings (>5s)
- Pool utilization high
- Long request durations

**Solutions:**
1. **Optimize downstream service** - Fix performance issues
2. **Increase timeouts** - If requests are legitimately slow:
   ```json
   {
     "client_config": {
       "read_timeout": 60  // Increase if needed
     }
   }
   ```
3. **Increase pool size** - More connections to handle slow requests

### Scenario 3: Request Spikes

**Symptoms:**
- Intermittent saturation warnings
- Peak concurrency varies widely
- Slow requests during spikes only

**Solutions:**
1. **Increase pool size** - Handle burst traffic
2. **Implement rate limiting** - Smooth out request spikes
3. **Add request queuing** - Queue requests when pool is full

### Scenario 4: Connection Leaks

**Symptoms:**
- ActiveRequests doesn't decrease
- Pool gradually fills up
- No slow request warnings

**Investigation:**
1. Check for connection leaks in code
2. Ensure all responses are properly consumed
3. Check for exceptions preventing connection release

**Enable detailed logging:**
```bash
# Track when connections are acquired/released
grep "REQUEST_START\|REQUEST_END" logs/opensearch.log | grep "RequestNumber: 42"
```

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

**Low Traffic (< 10 req/s):**
```json
{
  "max_connections": 10,
  "connection_timeout": 10,
  "read_timeout": 30
}
```

**Medium Traffic (10-50 req/s):**
```json
{
  "max_connections": 30,
  "connection_timeout": 10,
  "read_timeout": 30
}
```

**High Traffic (> 50 req/s):**
```json
{
  "max_connections": 50,
  "connection_timeout": 10,
  "read_timeout": 30
}
```

**Slow Downstream Service:**
```json
{
  "max_connections": 50,    // More connections for concurrent slow requests
  "connection_timeout": 10,
  "read_timeout": 60        // Longer read timeout
}
```

## Log Analysis Commands

### Find all saturation events
```bash
grep "SATURATION WARNING" logs/opensearch.log | wc -l
```

### Find slow requests grouped by duration
```bash
grep "Slow request" logs/opensearch.log | awk -F'took ' '{print $2}' | awk -F'ms' '{print $1}' | sort -n
```

### Track pool utilization over time
```bash
grep "Pool is" logs/opensearch.log | awk -F'Pool is ' '{print $2}' | awk -F'%' '{print $1}'
```

### Find most problematic URIs
```bash
grep "Slow request\|SATURATION" logs/opensearch.log | grep -o 'https://[^ ]*' | sort | uniq -c | sort -rn
```

### Check request rate
```bash
grep "REQUEST_START" logs/opensearch.log | awk '{print $1, $2}' | uniq -c
```

## Monitoring Best Practices

### 1. Set Up Alerts

Alert on:
- **High pool utilization** (>80%)
- **Frequent slow requests** (>10/minute)
- **Connection acquisition errors**

### 2. Track Metrics

Monitor:
- Peak concurrency trends
- Slow request counts
- Pool saturation frequency
- Request duration percentiles (p50, p95, p99)

### 3. Regular Reviews

Periodically review:
- Peak concurrency vs max_connection
- Slow request patterns
- Connection pool settings

### 4. Load Testing

Before production:
1. Load test with realistic traffic
2. Monitor saturation warnings
3. Adjust max_connection accordingly
4. Test failure scenarios

## Related Documentation

- **HTTP_CLIENT_METRICS.md** - How to enable and use metrics
- **METRICS_IMPLEMENTATION_SUMMARY.md** - Implementation details
- **MCP_TOOL_LOGGING.md** - MCP tool execution logging

## Quick Reference

### Enable Metrics
```bash
-Dml.http.client.metrics.enabled=true
```

### Increase Pool Size
```json
{"client_config": {"max_connections": 50}}
```

### Find Saturation Warnings
```bash
grep "SATURATION WARNING" logs/opensearch.log
```

### Find Slow Requests
```bash
grep "Slow request\|VERY SLOW" logs/opensearch.log
```

### Check Peak Concurrency
```bash
grep "peak concurrency" logs/opensearch.log | tail -1
```
