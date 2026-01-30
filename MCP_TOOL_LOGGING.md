# MCP Tool Request/Response Logging

## Overview

Added comprehensive logging for MCP (Model Context Protocol) tool calls to help debug and monitor tool execution. This provides visibility into what tools are being called, with what inputs, and what they return.

## Implementation

### Files Modified

1. **McpStreamableHttpTool.java** (`ml-algorithms/src/main/java/org/opensearch/ml/engine/tools/McpStreamableHttpTool.java`)
2. **McpSseTool.java** (`ml-algorithms/src/main/java/org/opensearch/ml/engine/tools/McpSseTool.java`)

### What's Logged

For each MCP tool call, the following information is logged at INFO level:

**Request (Before Tool Execution):**
- Tool name
- Input parameters (JSON formatted)

**Response (After Tool Execution):**
- Tool name
- Result content (JSON formatted)

**Errors:**
- Tool name
- Exception details (ERROR level)

## Log Format

### MCP Streamable HTTP Tool

**Request Log:**
```
INFO  McpStreamableHttpTool - MCP Streamable HTTP Tool Call - Tool: {tool_name}, Input: {json_input}
```

**Response Log:**
```
INFO  McpStreamableHttpTool - MCP Streamable HTTP Tool Response - Tool: {tool_name}, Result: {json_result}
```

**Example:**
```
INFO  McpStreamableHttpTool - MCP Streamable HTTP Tool Call - Tool: get_weather, Input: {"location":"San Francisco","units":"celsius"}
INFO  McpStreamableHttpTool - MCP Streamable HTTP Tool Response - Tool: get_weather, Result: [{"type":"text","text":"Temperature: 18°C, Condition: Partly Cloudy"}]
```

### MCP SSE Tool

**Request Log:**
```
INFO  McpSseTool - MCP SSE Tool Call - Tool: {tool_name}, Input: {json_input}
```

**Response Log:**
```
INFO  McpSseTool - MCP SSE Tool Response - Tool: {tool_name}, Result: {json_result}
```

**Example:**
```
INFO  McpSseTool - MCP SSE Tool Call - Tool: search_database, Input: {"query":"users with email domain example.com","limit":10}
INFO  McpSseTool - MCP SSE Tool Response - Tool: search_database, Result: [{"type":"text","text":"Found 5 users"}]
```

## Use Cases

### 1. Debugging Tool Execution

When tools don't behave as expected, you can see:
- Exactly what inputs were sent to the tool
- What the tool returned
- Any errors that occurred

### 2. Monitoring Tool Usage

Track which tools are being used and how frequently:
```bash
# Count tool calls by tool name
grep "MCP.*Tool Call" logs/opensearch.log | awk -F'Tool: ' '{print $2}' | awk -F',' '{print $1}' | sort | uniq -c

# Example output:
#  45 get_weather
#  23 search_database
#  12 send_email
```

### 3. Performance Analysis

Correlate request and response logs to understand tool execution time:
```bash
# Find all tool calls with their responses
grep -E "MCP.*(Tool Call|Tool Response)" logs/opensearch.log
```

### 4. Troubleshooting Agent Workflows

When using agents with MCP tools, understand the complete tool execution flow:
- Which tools were called
- In what order
- What data was passed between tools
- Where failures occurred

## Log Filtering

### Filter by Transport Type

**Streamable HTTP only:**
```bash
grep "MCP Streamable HTTP Tool" logs/opensearch.log
```

**SSE only:**
```bash
grep "MCP SSE Tool" logs/opensearch.log
```

### Filter by Tool Name

```bash
# All calls to a specific tool
grep "Tool: get_weather" logs/opensearch.log
```

### Filter by Phase

**Requests only:**
```bash
grep "Tool Call -" logs/opensearch.log
```

**Responses only:**
```bash
grep "Tool Response -" logs/opensearch.log
```

**Errors only:**
```bash
grep "Failed to call MCP" logs/opensearch.log
```

## JSON Formatting

The input and result are logged as JSON strings, making it easy to:
- Parse with tools like `jq`
- Extract specific fields
- Format for readability

### Pretty Print with jq

```bash
# Extract and pretty-print inputs
grep "MCP.*Tool Call" logs/opensearch.log | sed 's/.*Input: //' | jq '.'

# Extract and pretty-print results
grep "MCP.*Tool Response" logs/opensearch.log | sed 's/.*Result: //' | jq '.'
```

## Performance Impact

**Log Level:** INFO
- Logs are only written when INFO level is enabled for the tool classes
- No overhead when INFO logging is disabled

**Log Size:**
- Input/output JSON is included in full
- May be large for tools with extensive inputs or outputs
- Consider log rotation policies

**Recommendations:**
- Keep INFO logging enabled in development/testing
- In production, enable based on monitoring needs
- Use log filtering to reduce noise if needed

## Configuration

### Enable/Disable MCP Tool Logging

MCP tool logging uses standard Log4j2 configuration. To adjust:

**Disable MCP tool logging:**
```xml
<!-- In log4j2.properties or similar -->
logger.mcptool_sse.name = org.opensearch.ml.engine.tools.McpSseTool
logger.mcptool_sse.level = warn

logger.mcptool_http.name = org.opensearch.ml.engine.tools.McpStreamableHttpTool
logger.mcptool_http.level = warn
```

**Enable detailed logging:**
```xml
logger.mcptool_sse.name = org.opensearch.ml.engine.tools.McpSseTool
logger.mcptool_sse.level = debug

logger.mcptool_http.name = org.opensearch.ml.engine.tools.McpStreamableHttpTool
logger.mcptool_http.level = debug
```

## Integration with HTTP Client Metrics

When combined with HTTP client metrics (see `HTTP_CLIENT_METRICS.md`), you get complete visibility:

**MCP Tool Logs:**
- Application-level view
- Tool name, inputs, outputs
- Business logic perspective

**HTTP Client Metrics:**
- Network-level view (for non-MCP connectors)
- Connection pools, latency, errors
- Infrastructure perspective

**Note:** MCP connectors don't currently support HTTP client metrics due to library limitations (see `MCP_CONNECTORS_METRICS_NOTE.md`), but tool-level logging provides complementary visibility.

## Example: Complete Tool Execution Flow

```
# Agent receives user request
INFO  Agent - Processing request: "What's the weather in SF?"

# Agent decides to use get_weather tool
INFO  Agent - Selected tool: get_weather

# Tool execution begins
INFO  McpSseTool - MCP SSE Tool Call - Tool: get_weather, Input: {"location":"San Francisco","units":"fahrenheit"}

# Tool execution completes
INFO  McpSseTool - MCP SSE Tool Response - Tool: get_weather, Result: [{"type":"text","text":"Temperature: 64°F, Condition: Sunny"}]

# Agent processes result
INFO  Agent - Tool result: Temperature is 64°F
```

## Troubleshooting

### Common Issues

**1. No logs appearing**
- Check log level configuration
- Verify MCP tools are actually being executed
- Check log file location

**2. JSON parsing errors in logs**
- Input may contain special characters
- Check for quote escaping issues
- Tool may be returning invalid JSON

**3. Large log files**
- MCP tools with large inputs/outputs will create large logs
- Implement log rotation
- Consider summarizing large payloads

## Related Documentation

- **HTTP_CLIENT_METRICS.md** - HTTP client metrics for non-MCP connectors
- **MCP_CONNECTORS_METRICS_NOTE.md** - Why MCP connectors lack HTTP metrics
- **METRICS_IMPLEMENTATION_SUMMARY.md** - Overall metrics implementation

## Build Status

✅ Code compiles successfully
✅ Formatting (spotlessApply) passed
✅ No new warnings introduced
✅ Backward compatible (logging at INFO level)
