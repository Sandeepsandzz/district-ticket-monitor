package com.district.monitor.dto;

import java.time.Instant;

public record ScraperHealth(
        String status,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        int consecutiveFailures,
        String lastFailureMessage
) {
}
