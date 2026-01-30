/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.httpclient;

import static org.opensearch.secure_sm.AccessController.doPrivileged;

import java.time.Duration;

import lombok.extern.log4j.Log4j2;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.metrics.LoggingMetricPublisher;
import software.amazon.awssdk.metrics.MetricPublisher;

@Log4j2
public class MLHttpClientFactory {

    // private static final boolean ENABLE_METRICS = Boolean.parseBoolean(System.getProperty("ml.http.client.metrics.enabled", "true"));
    private static final boolean ENABLE_METRICS = true;

    public static SdkAsyncHttpClient getAsyncHttpClient(
        Duration connectionTimeout,
        Duration readTimeout,
        int maxConnections,
        boolean connectorPrivateIpEnabled
    ) {
        return doPrivileged(() -> {
            log
                .info(
                    "Creating MLHttpClient with connectionTimeout: {}, readTimeout: {}, maxConnections: {}, metricsEnabled: {}",
                    connectionTimeout,
                    readTimeout,
                    maxConnections,
                    ENABLE_METRICS
                );

            SdkAsyncHttpClient delegate = NettyNioAsyncHttpClient
                .builder()
                .connectionTimeout(connectionTimeout)
                .readTimeout(readTimeout)
                .maxConcurrency(maxConnections)
                .build();

            SdkAsyncHttpClient validatedClient = new MLValidatableAsyncHttpClient(delegate, connectorPrivateIpEnabled);

            // Wrap with metrics instrumentation if enabled
            if (ENABLE_METRICS) {
                log.info("Wrapping HTTP client with metrics instrumentation");
                return new MLMetricsInstrumentedHttpClient(validatedClient, maxConnections);
            }

            return validatedClient;
        });
    }

    /**
     * Creates a MetricPublisher for logging HTTP client metrics in the format:
     * <pre>
     * INFO  LoggingMetricPublisher - [4e6f2bb5]         HttpClient
     * INFO  LoggingMetricPublisher - [4e6f2bb5]         ┌───────────────────────────────────────┐
     * INFO  LoggingMetricPublisher - [4e6f2bb5]         │ MaxConcurrency=50                     │
     * INFO  LoggingMetricPublisher - [4e6f2bb5]         │ AvailableConcurrency=0                │
     * INFO  LoggingMetricPublisher - [4e6f2bb5]         │ LeasedConcurrency=1                   │
     * INFO  LoggingMetricPublisher - [4e6f2bb5]         │ ConcurrencyAcquireDuration=PT0.00004S │
     * INFO  LoggingMetricPublisher - [4e6f2bb5]         │ PendingConcurrencyAcquires=0          │
     * INFO  LoggingMetricPublisher - [4e6f2bb5]         │ HttpClientName=Apache                 │
     * INFO  LoggingMetricPublisher - [4e6f2bb5]         └────────────────────────
     * </pre>
     *
     * This should be used when building AWS SDK service clients (e.g., BedrockRuntimeAsyncClient):
     * <pre>
     * BedrockRuntimeAsyncClient.builder()
     *     .httpClient(httpClient)
     *     .overrideConfiguration(ClientOverrideConfiguration.builder()
     *         .addMetricPublisher(MLHttpClientFactory.createMetricPublisher())
     *         .build())
     *     .build();
     * </pre>
     *
     * Can be enabled via system property: -Dml.http.client.metrics.enabled=true
     *
     * @return MetricPublisher for logging HTTP client metrics, or null if metrics are disabled
     */
    public static MetricPublisher createMetricPublisher() {
        if (ENABLE_METRICS) {
            log.info("HTTP client metrics logging enabled");
            return LoggingMetricPublisher.create();
        }
        return null;
    }

    /**
     * Checks if HTTP client metrics are enabled.
     *
     * @return true if metrics are enabled, false otherwise
     */
    public static boolean isMetricsEnabled() {
        return ENABLE_METRICS;
    }
}
