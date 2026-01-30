/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.httpclient;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.log4j.Log4j2;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;

/**
 * Instrumented HTTP client wrapper that logs connection pool metrics.
 * This wrapper provides visibility into HTTP client behavior for raw SdkAsyncHttpClient usage
 * (as opposed to AWS service clients which use MetricPublisher).
 */
@Log4j2
public class MLMetricsInstrumentedHttpClient implements SdkAsyncHttpClient {
    private final SdkAsyncHttpClient delegate;
    private final int maxConcurrency;
    private final AtomicLong requestCounter = new AtomicLong(0);
    private final AtomicLong activeRequests = new AtomicLong(0);
    private final AtomicLong peakConcurrentRequests = new AtomicLong(0);
    private final AtomicLong slowRequestCount = new AtomicLong(0);
    private final String clientId;

    // Thresholds for warnings
    private static final double POOL_SATURATION_THRESHOLD = 0.8; // 80% full
    private static final long SLOW_REQUEST_THRESHOLD_MS = 5000; // 5 seconds
    private static final long VERY_SLOW_REQUEST_THRESHOLD_MS = 30000; // 30 seconds

    public MLMetricsInstrumentedHttpClient(SdkAsyncHttpClient delegate, int maxConcurrency) {
        this.delegate = delegate;
        this.maxConcurrency = maxConcurrency;
        this.clientId = String.format("%08x", System.identityHashCode(this));
        log.info("Created HTTP client [{}] with maxConcurrency={}", clientId, maxConcurrency);
    }

    @Override
    public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
        long requestNum = requestCounter.incrementAndGet();
        long active = activeRequests.incrementAndGet();
        long startTime = System.nanoTime();

        // Track peak concurrency
        updatePeakConcurrency(active);

        // Extract request info for better debugging
        String uri = request.request() != null ? request.request().getUri().toString() : "unknown";
        String method = request.request() != null ? request.request().method().name() : "unknown";

        // Check for pool saturation
        checkPoolSaturation(active, requestNum, uri);

        if (MLHttpClientFactory.isMetricsEnabled()) {
            logMetrics(requestNum, active, startTime, "REQUEST_START", 0, false, uri, method);
        }

        return delegate.execute(request).whenComplete((result, error) -> {
            long endTime = System.nanoTime();
            long activeAfter = activeRequests.decrementAndGet();
            long durationNanos = endTime - startTime;
            long durationMs = durationNanos / 1_000_000;

            // Detect and log slow requests
            if (durationMs >= VERY_SLOW_REQUEST_THRESHOLD_MS) {
                slowRequestCount.incrementAndGet();
                log
                    .warn(
                        "VERY SLOW REQUEST DETECTED [{}] - Request #{}: {} {} took {}ms (>{}ms threshold). "
                            + "This request held a connection for an extended time. "
                            + "ActiveRequests: {}, AvailableConcurrency: {}",
                        clientId,
                        requestNum,
                        method,
                        uri,
                        durationMs,
                        VERY_SLOW_REQUEST_THRESHOLD_MS,
                        activeAfter,
                        maxConcurrency - activeAfter
                    );
            } else if (durationMs >= SLOW_REQUEST_THRESHOLD_MS) {
                slowRequestCount.incrementAndGet();
                log
                    .warn(
                        "Slow request detected [{}] - Request #{}: {} {} took {}ms (>{}ms threshold). "
                            + "ActiveRequests: {}, AvailableConcurrency: {}",
                        clientId,
                        requestNum,
                        method,
                        uri,
                        durationMs,
                        SLOW_REQUEST_THRESHOLD_MS,
                        activeAfter,
                        maxConcurrency - activeAfter
                    );
            }

            if (MLHttpClientFactory.isMetricsEnabled()) {
                logMetrics(requestNum, activeAfter, endTime, "REQUEST_END", durationNanos, error != null, uri, method);
            }
        });
    }

    private void updatePeakConcurrency(long currentActive) {
        long peak;
        do {
            peak = peakConcurrentRequests.get();
            if (currentActive <= peak) {
                break;
            }
        } while (!peakConcurrentRequests.compareAndSet(peak, currentActive));

        if (currentActive > peak) {
            log.info("New peak concurrency [{}]: {} concurrent requests (max: {})", clientId, currentActive, maxConcurrency);
        }
    }

    private void checkPoolSaturation(long activeCount, long requestNum, String uri) {
        double utilizationPct = (double) activeCount / maxConcurrency;

        if (utilizationPct >= POOL_SATURATION_THRESHOLD) {
            long available = maxConcurrency - activeCount;
            log
                .warn(
                    "CONNECTION POOL SATURATION WARNING [{}] - Request #{} for {}: Pool is {}% full. "
                        + "ActiveRequests: {}/{}, AvailableConcurrency: {}. "
                        + "If you see 'Acquire operation took longer than configured maximum time' errors, "
                        + "consider increasing max_connection in connector configuration. "
                        + "PeakConcurrency: {}, SlowRequestCount: {}",
                    clientId,
                    requestNum,
                    uri,
                    String.format("%.1f", utilizationPct * 100),
                    activeCount,
                    maxConcurrency,
                    available,
                    peakConcurrentRequests.get(),
                    slowRequestCount.get()
                );
        }
    }

    private void logMetrics(long requestNum, long activeCount, long timestamp, String phase) {
        logMetrics(requestNum, activeCount, timestamp, phase, 0, false, "unknown", "unknown");
    }

    private void logMetrics(
        long requestNum,
        long activeCount,
        long timestamp,
        String phase,
        long durationNanos,
        boolean hasError,
        String uri,
        String method
    ) {
        String status = hasError ? "FAILED" : "SUCCESS";
        long availableConcurrency = maxConcurrency - activeCount;
        double poolUtilization = (double) activeCount / maxConcurrency * 100;

        log.info("┌────────────────────────────────────────────────────────────────┐");
        log.info("│ HttpClient Metrics - [{}]                               │", clientId);
        log.info("├────────────────────────────────────────────────────────────────┤");
        log.info("│ Phase: {}                                                      │", truncate(phase, 54));
        log.info("│ RequestNumber: {}                                              │", requestNum);
        log.info("│ Method: {}                                                     │", truncate(method, 53));
        log.info("│ URI: {}                                                        │", truncate(uri, 56));

        if ("REQUEST_END".equals(phase)) {
            log.info("│ Status: {}                                                     │", status);
            log.info("│ Duration: {}                                                   │", formatDuration(durationNanos));
        }

        log.info("│ MaxConcurrency: {}                                             │", maxConcurrency);
        log.info("│ ActiveRequests: {}                                             │", activeCount);
        log.info("│ AvailableConcurrency: {}                                       │", availableConcurrency);
        log.info("│ PoolUtilization: {}                                            │", String.format("%.1f%%", poolUtilization));
        log.info("│ PeakConcurrency: {}                                            │", peakConcurrentRequests.get());
        log.info("│ SlowRequestCount: {}                                           │", slowRequestCount.get());
        log.info("│ HttpClientName: {}                                             │", getHttpClientName());
        log.info("└────────────────────────────────────────────────────────────────┘");
    }

    private String truncate(String str, int maxLength) {
        if (str == null) {
            return "null";
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }

    private String formatDuration(long nanos) {
        if (nanos < 1_000) {
            return nanos + "ns";
        } else if (nanos < 1_000_000) {
            return String.format("%.2fμs", nanos / 1_000.0);
        } else if (nanos < 1_000_000_000) {
            return String.format("%.2fms", nanos / 1_000_000.0);
        } else {
            return String.format("%.2fs", nanos / 1_000_000_000.0);
        }
    }

    private String getHttpClientName() {
        // Try to extract the actual client name from delegate
        String className = delegate.getClass().getSimpleName();
        if (className.contains("Netty")) {
            return "Netty";
        } else if (className.contains("Apache")) {
            return "Apache";
        } else if (className.contains("Validatable")) {
            // For MLValidatableAsyncHttpClient, look at its delegate
            try {
                Field delegateField = delegate.getClass().getDeclaredField("delegate");
                delegateField.setAccessible(true);
                Object innerDelegate = delegateField.get(delegate);
                String innerClassName = innerDelegate.getClass().getSimpleName();
                if (innerClassName.contains("Netty")) {
                    return "Netty";
                }
            } catch (Exception e) {
                // Ignore, fall through to default
            }
        }
        return className;
    }

    @Override
    public void close() {
        if (MLHttpClientFactory.isMetricsEnabled()) {
            log.info("Closing HTTP client [{}]", clientId);
        }
        delegate.close();
    }

    @Override
    public String clientName() {
        return delegate.clientName();
    }
}
