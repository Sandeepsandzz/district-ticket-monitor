package com.district.monitor.dto;

public record CreateWatchRequest(
        String chatId,
        String city,
        String movieName,
        String theatreName,
        String screenFilter,
        String targetDate,
        String languageFilter,
        String formatFilter,
        String movieUrl
) {
}
