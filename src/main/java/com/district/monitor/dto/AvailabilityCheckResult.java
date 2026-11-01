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
        String bookingUrl,
        String checkStatus,
        String checkMessage
) {
    public AvailabilityCheckResult(boolean isAvailable, List<String> matchedTheatres, List<String> matchedScreens,
                                   List<String> matchedShowtimes, String matchedDate, String matchedLanguage,
                                   String matchedFormat, String bookingUrl) {
        this(isAvailable, matchedTheatres, matchedScreens, matchedShowtimes, matchedDate, matchedLanguage,
                matchedFormat, bookingUrl, isAvailable ? "AVAILABLE" : "NO_MATCH",
                isAvailable ? "Matching tickets found" : "No matching screenings found");
    }
}
