package com.district.monitor.dto;

import java.time.Instant;

public record ScraperHealth(
        String status,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        int consecutiveFailures,
        String lastFailureMessage,
        Instant lastRequestAt,
        long lastRequestDurationMs,
        long totalRequests,
        long failedRequests,
        long averageLatencyMs
) {
    public ScraperHealth(String status, Instant lastSuccessAt, Instant lastFailureAt,
                         int consecutiveFailures, String lastFailureMessage) {
        this(status, lastSuccessAt, lastFailureAt, consecutiveFailures, lastFailureMessage,
                null, 0, 0, 0, 0);
    }
}
