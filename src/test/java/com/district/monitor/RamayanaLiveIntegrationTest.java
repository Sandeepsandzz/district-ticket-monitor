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
    void cityCatalogueTheatreMatchesCurrentMovieSessions() {
        var scraper = new DistrictScraperService("https://www.district.in/movies");
        var catalogue = scraper.fetchVizagTheatres();
        assertEquals("live", catalogue.source());
        assertTrue(catalogue.items().size() > 1);
        String theatre = catalogue.items().stream().filter(t -> t.startsWith("AAA Cinemas")).findFirst().orElseThrow();
        String movie = scraper.fetchCurrentVizagMovies().stream().map(m -> m.get("url"))
                .filter(url -> url.contains("MV176276")).findFirst().orElseThrow();
        assertFalse(scraper.getAvailableScreensForTheatre(movie, theatre).isEmpty(),
                "A city catalogue theatre should match its movie sessions");
    }
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
