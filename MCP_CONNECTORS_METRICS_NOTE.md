# MCP Connectors and Metrics Support

## Overview

This document explains why MCP (Model Context Protocol) connectors don't currently support HTTP client metrics and what would be needed to add support.

## Current State

**MCP Connectors:**
- `McpConnectorExecutor` - Uses SSE (Server-Sent Events) transport
- `McpStreamableHttpConnectorExecutor` - Uses Streamable HTTP transport

**Status:** ❌ Metrics not supported

## Technical Limitation

### Why Metrics Aren't Supported

MCP connectors use the Model Context Protocol client library (`io.modelcontextprotocol.client`), which:

1. **Creates HttpClient Internally**: The MCP library's transport builders (`HttpClientSseClientTransport`, `HttpClientStreamableHttpTransport`) create Java's native `HttpClient` internally

2. **No Wrapping Hook**: The library doesn't expose a method to wrap or intercept the created `HttpClient`

3. **Limited Customization**: The only customization point is `customizeClient()`, which provides access to `HttpClient.Builder` before the client is built, but not the final client instance

### Code Example

```java
// Current implementation in McpStreamableHttpConnectorExecutor
McpClientTransport transport = HttpClientStreamableHttpTransport
    .builder(mcpServerUrl)
    .endpoint(endpoint)
    .customizeClient(clientBuilder -> {
        // We can configure the builder
        clientBuilder.connectTimeout(connectionTimeout);
        clientBuilder.followRedirects(HttpClient.Redirect.NORMAL);
        // But we cannot wrap the final HttpClient
    })
    .customizeRequest(headerConfig)
    .build();
```

### What We Tried

1. **Builder Wrapping**: Attempted to wrap the builder, but the MCP library still creates the client internally
2. **Post-Build Wrapping**: No access to the created client after `.build()` is called
3. **Custom Transport**: Would require reimplementing the entire MCP transport layer

## Documentation Added

To document this limitation, code comments have been added to both MCP connector executors:

**McpConnectorExecutor.java:99**
```java
// Create transport
// Note: MCP library creates HttpClient internally, metrics not currently supported
McpClientTransport transport = HttpClientSseClientTransport.builder(...)
```

**McpStreamableHttpConnectorExecutor.java:95**
```java
// Create streamable HTTP transport
// Note: MCP library creates HttpClient internally, metrics not currently supported
McpClientTransport transport = HttpClientStreamableHttpTransport.builder(...)
```

## Future Options

### Option 1: Upstream MCP Library Enhancement

**Recommended Approach**: Contribute to the MCP library to add an HTTP client wrapper hook.

**Proposed API:**
```java
HttpClientSseClientTransport.builder(mcpServerUrl)
    .sseEndpoint(sseEndpoint)
    .customizeClient(clientBuilder -> { ... })
    .httpClientWrapper(client -> new InstrumentedHttpClient(client))  // NEW
    .build();
```

**Benefits:**
- Clean API
- Benefits all MCP library users
- No workarounds needed

### Option 2: Custom Transport Implementation

Create custom transport implementations that extend or replace the MCP library's transports.

**Challenges:**
- Requires understanding MCP protocol details
- Need to maintain compatibility with upstream changes
- Significant development effort

### Option 3: Network-Level Instrumentation

Use network-level instrumentation (e.g., Java agent, bytecode manipulation).

**Challenges:**
- Complex setup
- May impact performance
- Harder to maintain

## Impact

### What's Missing Without MCP Metrics

Without metrics for MCP connectors, you cannot observe:
- Request count and throughput
- Request latency and duration
- Active/concurrent requests
- Connection failures
- HTTP status codes

### Workarounds

1. **Application-Level Logging**: Add custom logging around MCP tool invocations
2. **External Monitoring**: Use network monitoring tools to observe MCP traffic
3. **MCP Server Logs**: Check logs on the MCP server side

## Comparison with Other Connectors

### Connectors WITH Metrics ✅

**AwsConnectorExecutor & HttpJsonConnectorExecutor:**
- Use AWS SDK's `SdkAsyncHttpClient`
- Created via `MLHttpClientFactory.getAsyncHttpClient()`
- Automatically wrapped with `MLMetricsInstrumentedHttpClient`
- Full metrics support

**BedrockStreamingHandler:**
- Uses AWS SDK's `BedrockRuntimeAsyncClient`
- Uses AWS SDK's native `LoggingMetricPublisher`
- Full metrics support

### Connectors WITHOUT Metrics ❌

**MCP Connectors:**
- Use Java's native `HttpClient` via MCP library
- Client created internally by MCP library
- No wrapping hooks available
- Metrics not supported

**HttpStreamingHandler:**
- Uses OkHttp library for OpenAI-compatible streaming
- Different HTTP client library
- Not in scope for current metrics implementation

## Recommendation

**Short Term:**
- Document the limitation (✅ Done)
- Use workarounds as needed (application logging, external monitoring)

**Long Term:**
- Propose enhancement to MCP library maintainers
- Contribute implementation if accepted
- Update ML-Commons once MCP library supports it

## References

- MCP Library: `io.modelcontextprotocol:client`
- ML-Commons Metrics Implementation: See `HTTP_CLIENT_METRICS.md`
- AWS SDK Metrics: `software.amazon.awssdk.metrics.LoggingMetricPublisher`
