package com.district.monitor;

import com.district.monitor.dto.AvailabilityCheckResult;
import com.district.monitor.entity.WatchRule;
import com.district.monitor.service.DistrictScraperService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DistrictScraperServiceTest {

    private final DistrictScraperService scraper = new DistrictScraperService("https://www.district.in/movies");

    @Test
    void testFetchCurrentVizagMovies() {
        List<Map<String, String>> movies = scraper.fetchCurrentVizagMovies();
        assertNotNull(movies);
        assertFalse(movies.isEmpty(), "Should find movies for Vizag");
        assertTrue(movies.stream().anyMatch(m -> m.get("name") != null && !m.get("name").isBlank()), "Movies must have titles");
    }

    @Test
    void testCheckWatchRule_ZeroFalsePositivesOnNonExistentDate() {
        // Create a watch rule for Ramayana on a date that has no shows (e.g. 2029-01-01)
        WatchRule rule = new WatchRule(
                "test-chat",
                "vizag",
                "Ramayana",
                "ANY",
                "2029-01-01",
                "ANY",
                "ANY",
                "https://www.district.in/movies/namit-malhotra-s-ramayana-movie-tickets-in-vizag-MV176276"
        );

        AvailabilityCheckResult result = scraper.checkWatchRule(rule);
        assertNotNull(result);
        assertFalse(result.isAvailable(), "Must NOT report available for dates with no shows (no false positives)");
    }

    @Test
    void testCheckWatchRule_MatchesLiveScreeningsInVizag() {
        // Create a watch rule for Jailer 2 in Vizag with ANY theatre and ANY date
        WatchRule rule = new WatchRule(
                "test-chat",
                "vizag",
                "Jailer 2",
                "ANY",
                "ANY",
                "ANY",
                "ANY",
                "https://www.district.in/movies/jailer-2-movie-tickets-in-vizag-MV187766"
        );

        AvailabilityCheckResult result = scraper.checkWatchRule(rule);
        assertNotNull(result);
        assertTrue(result.isAvailable(), "Should match live screenings for Jailer 2 in Vizag");
        assertFalse(result.matchedTheatres().isEmpty(), "Must list matched Vizag theatres");
        assertFalse(result.matchedShowtimes().isEmpty(), "Must list matched showtimes");
        assertNotNull(result.bookingUrl(), "Must provide booking URL");
        assertTrue(result.bookingUrl().contains("district.in"), "Booking URL must point to District");
    }

    @Test
    void testCheckWatchRule_ScreenFilterExclusion() {
        // Request a non-existent screen (Screen 99) in Vizag
        WatchRule rule = new WatchRule(
                "test-chat",
                "vizag",
                "Jailer 2",
                "CMR Central",
                "Screen 99",
                "ANY",
                "ANY",
                "ANY",
                "https://www.district.in/movies/jailer-2-movie-tickets-in-vizag-MV187766"
        );

        AvailabilityCheckResult result = scraper.checkWatchRule(rule);
        assertNotNull(result);
        assertFalse(result.isAvailable(), "Must NOT report available for Screen 99 which does not exist");
    }

    @Test
    void testGetAvailableScreensForTheatre() {
        List<String> screens = scraper.getAvailableScreensForTheatre(
                "https://www.district.in/movies/jailer-2-movie-tickets-in-vizag-MV187766",
                "Varun Beach"
        );
        assertNotNull(screens);
        assertFalse(screens.isEmpty(), "Should discover live multiplex screens for INOX Varun Beach");
        assertTrue(screens.stream().anyMatch(s -> s.toLowerCase().contains("screen") || s.toLowerCase().contains("audi")),
                "Discovered screens should have Screen/Audi naming");
    }

    @Test
    void testCheckWatchRule_ScreenFilterWithPremiumLabel() {
        // INOX Varun Beach Screen 2 with Dolby Atmos
        WatchRule rule = new WatchRule(
                "test-chat",
                "vizag",
                "Jailer 2",
                "Varun Beach",
                "Atmos",
                "ANY",
                "ANY",
                "ANY",
                "https://www.district.in/movies/jailer-2-movie-tickets-in-vizag-MV187766"
        );

        AvailabilityCheckResult result = scraper.checkWatchRule(rule);
        assertNotNull(result);
        assertTrue(result.isAvailable(), "Must match Atmos screen filter at Varun Beach");
        assertTrue(result.matchedScreens().stream().anyMatch(s -> s.toLowerCase().contains("atmos") || s.contains("2")),
                "Matched screens should include Dolby Atmos / Screen 2");
    }

    @Test
    void testCheckWatchRule_AadarshaKutumbam_Oct31_AAA_Screen7() {
        // Watch #36: Aadarsha Kutumbam on 2026-10-31 at AAA Cinemas on Screen 7 (Q-Luxon LED)
        WatchRule rule = new WatchRule(
                "test-chat",
                "vizag",
                "Aadarsha Kutumbam House No 47",
                "AAA Cinemas",
                "Screen 7 (Q-Luxon LED)",
                "2026-10-31",
                "Telugu",
                "ANY",
                "https://www.district.in/movies/aadarsha-kutumbam-house-no-47-movie-tickets-MV211590"
        );

        AvailabilityCheckResult result = scraper.checkWatchRule(rule);
        assertNotNull(result);
        assertTrue(result.isAvailable(), "Must find available tickets for Aadarsha Kutumbam on Oct 31");
        assertTrue(result.matchedTheatres().stream().anyMatch(t -> t.toLowerCase().contains("aaa")),
                "Matched theatre must be AAA Cinemas");
        assertTrue(result.matchedScreens().stream().anyMatch(s -> s.contains("7") || s.toLowerCase().contains("luxon")),
                "Matched screen must be Screen 7 (Q-Luxon LED)");
        assertFalse(result.matchedShowtimes().isEmpty(), "Matched showtimes must not be empty");
        assertTrue(result.matchedShowtimes().stream().anyMatch(st -> st.contains("PM") || st.contains("AM")),
                "Showtimes must be formatted in 12-hour AM/PM IST format");
    }

    @Test
    void testFetchUpcomingVizagMovies() {
        List<Map<String, String>> upcoming = scraper.fetchUpcomingVizagMovies();
        assertNotNull(upcoming);
        assertFalse(upcoming.isEmpty(), "Upcoming movies list should not be empty");
        assertTrue(upcoming.stream().anyMatch(m -> m.get("name").equalsIgnoreCase("Ramayana")),
                "Must include Ramayana as upcoming blockbuster");
    }

    @Test
    void testCheckWatchRule_UpcomingMovie_AllTheatres_Ramayana() {
        // Watch for Ramayana across ALL theatres in Vizag on ANY date / release date
        WatchRule rule = new WatchRule(
                "test-chat",
                "vizag",
                "Ramayana",
                "ANY", // All theatres
                "ANY", // Any screen
                "ANY", // Any date / release date
                "ANY",
                "ANY",
                "https://www.district.in/movies/namit-malhotra-s-ramayana-movie-tickets-in-vizag-MV176276"
        );

        AvailabilityCheckResult result = scraper.checkWatchRule(rule);
        assertNotNull(result);
        assertTrue(result.isAvailable(), "Must discover tickets for upcoming release across Vizag theatres");
        assertFalse(result.matchedTheatres().isEmpty(), "Must list theatres where tickets opened");
        assertNotNull(result.bookingUrl(), "Must provide direct booking link");
    }
}
