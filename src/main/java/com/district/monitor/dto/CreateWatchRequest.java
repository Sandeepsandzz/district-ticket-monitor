package com.district.monitor.dto;

import java.math.BigDecimal;

public record CreateWatchRequest(
        String chatId,
        String city,
        String movieName,
        String theatreName,
        String screenFilter,
        String targetDate,
        String languageFilter,
        String formatFilter,
        String movieUrl,
        BigDecimal maxPrice,
        Integer minAvailableSeats
) {
}
