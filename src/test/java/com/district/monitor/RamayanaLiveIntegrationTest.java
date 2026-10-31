package com.district.monitor;

import com.district.monitor.entity.WatchRule;
import com.district.monitor.service.DistrictScraperService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/** A time-sensitive live regression check, run explicitly while these shows are listed. */
@EnabledIfSystemProperty(named = "district.live.ramayana", matches = "true")
class RamayanaLiveIntegrationTest {
    @Test
    void findsMovieAndNovemberSixScreenSevenShow() {
        var scraper = new DistrictScraperService("https://www.district.in/movies");
        var movie = scraper.fetchCurrentVizagMovies().stream()
                .filter(m -> m.get("url").contains("MV176276"))
                .findFirst().orElseThrow(() -> new AssertionError("Ramayana missing from Vizag catalogue"));
        var rule = new WatchRule("123", "vizag", movie.get("name"), "AAA Cinemas",
                "Screen 7", "2026-11-06", "ANY", "ANY", movie.get("url"));
        var result = scraper.checkWatchRule(rule);
        assertTrue(result.isAvailable(), "Expected available November 6 Screen 7 sessions at AAA");
        assertTrue(result.matchedTheatres().stream().anyMatch(t -> t.contains("AAA")));
        assertTrue(result.matchedScreens().stream().anyMatch(s -> s.contains("7")));
        assertTrue(result.matchedShowtimes().contains("08:25 AM"), "Expected 08:25 AM IST show");
    }
}
