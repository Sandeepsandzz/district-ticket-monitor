package com.district.monitor.dto;

import java.util.List;

public record AvailabilityCheckResult(
        boolean isAvailable,
        List<String> matchedTheatres,
        List<String> matchedScreens,
        List<String> matchedShowtimes,
        String matchedDate,
        String matchedLanguage,
        String matchedFormat,
        String bookingUrl
) {
}
