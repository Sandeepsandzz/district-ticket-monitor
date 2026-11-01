package com.district.monitor.service;

import com.district.monitor.dto.AvailabilityCheckResult;
import com.district.monitor.dto.ScraperHealth;
import com.district.monitor.entity.WatchRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DistrictScraperService {

    private static final Logger log = LoggerFactory.getLogger(DistrictScraperService.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    );

    private static final Pattern VIZAG_MOVIE_URL_PATTERN =
            Pattern.compile("https://www\\.district\\.in/movies/([a-z0-9\\-]+)-movie-tickets-(?:in-[a-z0-9\\-]+-)?(MV\\d+)");

    private static final Pattern SESSION_PATTERN = Pattern.compile(
            "\"name\":\"([^\"]+)\",\"address\":[^}]+}.*?\"showTime\":\"([^\"]+)\".*?\"audi\":\"([^\"]+)\"(.*?\"scrnFmt\":\"([^\"]+)\".*?\"encSessionId\":\"([^\"]+)\".*?\"mcd\":\"([^\"]+)\")"
    );

    private static final Pattern PREMIUM_LABEL_PATTERN = Pattern.compile(
            "\"premiumLabel\":\"([^\"]+)\""
    );

    private static final Pattern LANGUAGE_PATTERN = Pattern.compile(
            "\"(?:language|lang|audioLanguage)\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE
    );

    private final String baseUrl;
    private final int maxAttempts;
    private final long retryBackoffMs;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cache of discovered movies in Vizag: title -> url
    private volatile List<Map<String, String>> cachedVizagMovies = new ArrayList<>();
    private volatile long lastMoviesCacheTime = 0;
    private volatile List<Map<String, String>> cachedUpcomingMovies = List.of();
    private volatile long lastUpcomingCacheTime;
    private volatile TheatreCatalogue cachedTheatres;
    private volatile long lastTheatresCacheTime;
    private volatile List<String> lastKnownTheatres = List.of();
    private volatile Instant lastSuccessAt;
    private volatile Instant lastFailureAt;
    private volatile int consecutiveFailures;
    private volatile String lastFailureMessage;

    public record ScreeningRecord(
            String title,
            String dateIso,
            String timeStr,
            String cinemaName,
            String format,
            String audi,
            String language,
            String bookingUrl,
            BigDecimal price,
            Integer availableSeats
    ) {}

    public record TheatreCatalogue(List<String> items, String source) {
        public boolean suggested() { return "fallback".equals(source); }
    }

    @Autowired
    public DistrictScraperService(
            @Value("${district.base-url:https://www.district.in/movies}") String baseUrl,
            @Value("${district.scraper.max-attempts:3}") int maxAttempts,
            @Value("${district.scraper.retry-backoff-ms:750}") long retryBackoffMs
    ) {
        this.baseUrl = baseUrl;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
    }

    public DistrictScraperService(String baseUrl) {
        this(baseUrl, 3, 750);
    }

    public ScraperHealth getHealth() {
        String status = consecutiveFailures == 0 ? "UP" : (lastSuccessAt == null ? "DOWN" : "DEGRADED");
        return new ScraperHealth(status, lastSuccessAt, lastFailureAt, consecutiveFailures, lastFailureMessage);
    }

    /**
     * Dynamically scrapes all featured & now-showing movies in Vizag from District homepage.
     */
    public synchronized List<Map<String, String>> fetchCurrentVizagMovies() {
        long now = System.currentTimeMillis();
        if (now - lastMoviesCacheTime < 300_000) { // 5-minute cache, including an empty result after a fetch failure
            return cachedVizagMovies;
        }

        Map<String, Map<String, String>> movieMap = new LinkedHashMap<>();
        try {
            String vizagUrl = baseUrl.endsWith("/movies") ? baseUrl + "/vizag-movie-tickets" : baseUrl + "/movies/vizag-movie-tickets";
            Document doc = fetchDocument(vizagUrl, 15_000, "movie catalogue");

            String rawHtml = doc.html();
            long nowEpochSec = System.currentTimeMillis() / 1000L;

            // 1. Match explicit Vizag movie links in HTML
            Matcher vm = VIZAG_MOVIE_URL_PATTERN.matcher(rawHtml);
            while (vm.find()) {
                String slug = vm.group(1);
                String fullUrl = normalizeToVizagUrl(vm.group(0));
                String formattedTitle = formatSlugToTitle(slug);
                movieMap.putIfAbsent(formattedTitle.toLowerCase(Locale.ROOT), Map.of(
                        "name", formattedTitle,
                        "url", fullUrl,
                        "slug", slug
                ));
            }

            // 2. Parse JSON-LD ItemList schema for movies
            for (Element script : doc.select("script[type=application/ld+json]")) {
                try {
                    JsonNode root = objectMapper.readTree(script.html());
                    if ("ItemList".equals(root.path("@type").asText()) && root.has("itemListElement")) {
                        for (JsonNode item : root.path("itemListElement")) {
                            JsonNode movieNode = item.has("item") ? item.path("item") : item;
                            String name = movieNode.path("name").asText().trim();
                            String url = normalizeToVizagUrl(movieNode.path("url").asText().trim());
                            if (!name.isEmpty() && !url.isEmpty() && !name.equalsIgnoreCase("Home") && !name.equalsIgnoreCase("Movies")) {
                                movieMap.putIfAbsent(name.toLowerCase(Locale.ROOT), Map.of(
                                        "name", name,
                                        "url", url,
                                        "slug", name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                                ));
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 3. Parse MovieData embedded in Next.js state
            Matcher mm = Pattern.compile("\"movie_id\":\"([^\"]+)\",\"name\":\"([^\"]+)\".*?\"release_date\":\"?([0-9]+)\"?").matcher(rawHtml);
            while (mm.find()) {
                String id = mm.group(1);
                String name = mm.group(2).trim();
                long releaseEpoch = Long.parseLong(mm.group(3));
                if (releaseEpoch > nowEpochSec + 86400) continue; // Future release date
                String url = "https://www.district.in/movies/" + name.toLowerCase().replaceAll("[^a-z0-9]+", "-") + "-movie-tickets-in-vizag-MV" + id;
                String key = name.toLowerCase();
                if (!movieMap.containsKey(key)) {
                    movieMap.put(key, Map.of(
                            "name", name,
                            "url", url,
                            "slug", name.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch dynamic Vizag movies from District: {}", e.getMessage());
            // Keep the last known-good catalogue rather than replacing it with an empty result.
            lastMoviesCacheTime = now;
            return cachedVizagMovies;
        }

        cachedVizagMovies = new ArrayList<>(movieMap.values());
        lastMoviesCacheTime = now;
        return cachedVizagMovies;
    }

    /**
     * Curated and dynamic upcoming blockbusters list for Vizag.
     */
    public synchronized List<Map<String, String>> fetchUpcomingVizagMovies() {
        long now = System.currentTimeMillis();
        if (now - lastUpcomingCacheTime < 300_000) return cachedUpcomingMovies;
        try {
            Document doc = fetchDocument("https://www.district.in/movies/upcoming-movies", 15_000, "upcoming catalogue");
            List<Map<String, String>> movies = parseUpcomingMovies(doc);
            if (!movies.isEmpty()) cachedUpcomingMovies = movies;
            lastUpcomingCacheTime = now;
        } catch (IOException e) {
            log.warn("Could not fetch upcoming movies: {}", e.getMessage());
            lastUpcomingCacheTime = now;
        }
        return cachedUpcomingMovies;
    }

    public List<Map<String, String>> parseUpcomingMovies(Document doc) {
        Map<String, Map<String, String>> movies = new LinkedHashMap<>();
        for (Element link : doc.select("a[href*=-movie-tickets-MV]")) {
            Element heading = link.selectFirst("h5");
            if (heading == null || heading.text().isBlank()) continue;
            String href = link.absUrl("href");
            if (href.isBlank() && link.attr("href").startsWith("/movies/")) href = "https://www.district.in" + link.attr("href");
            if (!isAllowedMoviePageUrl(href) || !href.matches("https://www\\.district\\.in/movies/[^?#]+-movie-tickets-MV\\d+")) continue;
            Element date = link.selectFirst("span");
            movies.putIfAbsent(href, Map.of("name", heading.text(), "url", normalizeToVizagUrl(href),
                    "releaseDate", date == null ? "Coming soon" : date.text()));
        }
        return List.copyOf(movies.values());
    }

    /**
     * Ensures any generic District movie link is converted to Vizag city-specific URL.
     */
    public String normalizeToVizagUrl(String url) {
        if (url == null || url.isBlank()) return url;
        if (!url.contains("-in-vizag-")) {
            url = url.replaceFirst("-movie-tickets-(MV\\d+)", "-movie-tickets-in-vizag-$1");
        }
        return url;
    }

    public boolean isAllowedMoviePageUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value.trim());
            String path = uri.getPath();
            return isAllowedDistrictUrl(uri)
                    && path != null
                    && path.startsWith("/movies/")
                    && !path.startsWith("/movies/seat-layout/")
                    && !path.contains("..");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isAllowedDistrictUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            return isAllowedDistrictUrl(URI.create(value.trim()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isAllowedDistrictUrl(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && "www.district.in".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getUserInfo() == null
                && uri.getPath() != null
                && uri.getPath().startsWith("/movies/")
                && uri.getFragment() == null;
    }

    /**
     * Inspects a movie page and returns distinct screen/audi names playing for a specific theatre in Vizag.
     */
    public List<String> getAvailableScreensForTheatre(String movieUrl, String theatreFilter) {
        if (movieUrl == null || movieUrl.isBlank() || movieUrl.endsWith("/movies")) {
            return List.of();
        }
        try {
            String targetUrl = normalizeToVizagUrl(movieUrl);
            if (!isAllowedMoviePageUrl(targetUrl)) return List.of();
            Document doc = fetchDocument(targetUrl, 10_000, "screen discovery");

            String rawHtml = doc.html();
            List<ScreeningRecord> allScreenings = extractScreeningsFromPage(doc, rawHtml, "", extractDateFromUrl(targetUrl));
            Set<String> screens = new LinkedHashSet<>();
            for (ScreeningRecord r : allScreenings) {
                if (matchesTheatre(r, theatreFilter) && r.audi() != null && !r.audi().isBlank()) {
                    screens.add(r.audi().trim());
                }
            }
            return new ArrayList<>(screens);
        } catch (Exception e) {
            log.debug("Could not extract live screens for theatre {}: {}", theatreFilter, e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns theatres currently present in District's movie-session data for
     * the selected Vizag movie. This is deliberately movie-specific: the
     * watcher must alert only for the theatre chosen by the user.
     */
    public List<String> getAvailableTheatresForMovie(String movieUrl) {
        if (movieUrl == null || movieUrl.isBlank() || movieUrl.endsWith("/movies")) {
            return List.of();
        }
        try {
            String targetUrl = normalizeToVizagUrl(movieUrl);
            if (!isAllowedMoviePageUrl(targetUrl)) return List.of();
            Document doc = fetchDocument(targetUrl, 15_000, "theatre discovery");
            List<ScreeningRecord> screenings = extractScreeningsFromPage(
                    doc, doc.html(), "", extractDateFromUrl(targetUrl));
            return screenings.stream()
                    .map(ScreeningRecord::cinemaName)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.debug("Could not extract live theatres for movie {}: {}", movieUrl, e.getMessage());
            return List.of();
        }
    }

    /** District's city-wide cinema catalogue, independent of any movie's current sessions. */
    public synchronized TheatreCatalogue fetchVizagTheatres() {
        long now = System.currentTimeMillis();
        if (cachedTheatres != null && now - lastTheatresCacheTime <
                ("live".equals(cachedTheatres.source()) ? 300_000 : 60_000)) return cachedTheatres;
        try {
            Document doc = fetchDocument("https://www.district.in/movies/cinemas-in-vizag", 15_000, "theatre catalogue");
            List<String> theatres = parseVizagTheatres(doc);
            if (!theatres.isEmpty()) {
                lastKnownTheatres = theatres;
                cachedTheatres = new TheatreCatalogue(theatres, "live");
                lastTheatresCacheTime = now;
                return cachedTheatres;
            }
            log.warn("District's Vizag theatre catalogue was empty");
        } catch (Exception e) {
            log.warn("Could not load District's Vizag theatre catalogue: {}", e.getMessage());
        }
        cachedTheatres = lastKnownTheatres.isEmpty()
                ? new TheatreCatalogue(getKnownVizagTheatres(), "fallback")
                : new TheatreCatalogue(lastKnownTheatres, "cached");
        lastTheatresCacheTime = now;
        return cachedTheatres;
    }

    public List<String> parseVizagTheatres(Document doc) {
        Map<String, String> theatres = new LinkedHashMap<>();
        for (Element link : doc.select("a[href]")) {
            try {
                URI uri = URI.create(link.absUrl("href"));
                String path = uri.getPath();
                if (!"https".equalsIgnoreCase(uri.getScheme()) || !"www.district.in".equalsIgnoreCase(uri.getHost())
                        || path == null || !path.matches("/movies/[a-zA-Z0-9-]+-CD[0-9]+")) continue;
                Element label = link.selectFirst("span");
                String name = (label == null ? link.text() : label.text()).trim();
                if (!name.isBlank()) theatres.putIfAbsent(path.substring(path.lastIndexOf("-CD")), name);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed links elsewhere on the page.
            }
        }
        return theatres.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /** Partial suggestions used when the live catalogue cannot be reached. */
    public List<String> getKnownVizagTheatres() {
        return List.of(
                "AAA Cinemas",
                "INOX Varun Beach",
                "INOX CMR Central, Gajuwaka, Vizag",
                "INOX CMR Central, Maddilapalem, Vizag",
                "INOX Chitralayaa Mall",
                "Cinepolis Sreekanya Cineglitz",
                "Asian Mukta A2 Cinemas",
                "STBL Cinemas Multiplex SL Nagar",
                "Miraj Cinemas Bhupathi Surya Central",
                "Kameswari & Kinnera A/C Laser 4K Projection Dolby Atmos & 7.1 Ultrasound, Vizag"
        );
    }

    public AvailabilityCheckResult checkWatchRule(WatchRule rule) {
        return checkWatchRule(rule, new HashMap<>());
    }

    public AvailabilityCheckResult checkWatchRule(WatchRule rule, Map<String, Document> pageCache) {
        try {
            String targetUrl = rule.getMovieUrl();
            if (targetUrl == null || targetUrl.isBlank() || targetUrl.endsWith("/movies") || targetUrl.endsWith("/movies/")) {
                targetUrl = discoverMovieUrlInCity("vizag", rule.getMovieName());
            }

            if (targetUrl == null) {
                log.debug("No movie URL resolved for '{}' in Vizag", rule.getMovieName());
                return new AvailabilityCheckResult(
                        false, List.of(), List.of(), List.of(), rule.getTargetDate(), rule.getLanguageFilter(), rule.getFormatFilter(), baseUrl
                );
            }

            targetUrl = normalizeToVizagUrl(targetUrl);
            if (!isAllowedMoviePageUrl(targetUrl)) {
                log.warn("Rejected non-District movie URL for watch id={}", rule.getId());
                return new AvailabilityCheckResult(false, List.of(), List.of(), List.of(), rule.getTargetDate(),
                        rule.getLanguageFilter(), rule.getFormatFilter(), baseUrl, "ERROR", "Invalid District movie URL");
            }

            // Append fromdate if specific target date is requested (e.g. 2026-10-31)
            String targetDate = rule.getTargetDate();
            if (targetDate != null && !targetDate.isBlank() && !"ANY".equalsIgnoreCase(targetDate.trim())) {
                String sep = targetUrl.contains("?") ? "&" : "?";
                targetUrl = targetUrl + sep + "fromdate=" + targetDate.trim();
            }

            AvailabilityCheckResult res = inspectMoviePage(targetUrl, rule, pageCache);

            // If zero screenings on the base page and rule allows ANY date, check if showDates exists on page (e.g. upcoming release date)
            if (!res.isAvailable() && (rule.getTargetDate() == null || "ANY".equalsIgnoreCase(rule.getTargetDate()))) {
                try {
                    Document doc = fetchMoviePage(targetUrl, pageCache);
                    String raw = doc.html();
                    Matcher sdm = Pattern.compile("\"showDates\":\\[\"([0-9]{4}-[0-9]{2}-[0-9]{2})\"").matcher(raw);
                    if (sdm.find()) {
                        String firstDate = sdm.group(1);
                        String dateUrl = targetUrl + (targetUrl.contains("?") ? "&" : "?") + "fromdate=" + firstDate;
                        AvailabilityCheckResult dateRes = inspectMoviePage(dateUrl, rule, pageCache);
                        if (dateRes.isAvailable()) {
                            return dateRes;
                        }
                    }
                } catch (Exception ignored) {}
            }

            return res;

        } catch (Exception e) {
            log.error("Error checking rule id={} (movie={}): {}", rule.getId(), rule.getMovieName(), e.getMessage());
            return new AvailabilityCheckResult(false, List.of(), List.of(), List.of(), rule.getTargetDate(),
                    rule.getLanguageFilter(), rule.getFormatFilter(), baseUrl, "ERROR", "District check failed; will retry");
        }
    }

    private AvailabilityCheckResult unavailableResult(WatchRule rule) {
        return new AvailabilityCheckResult(false, List.of(), List.of(), List.of(),
                rule.getTargetDate(), rule.getLanguageFilter(), rule.getFormatFilter(), baseUrl);
    }

    private String discoverMovieUrlInCity(String city, String movieName) {
        List<Map<String, String>> movies = new ArrayList<>(fetchCurrentVizagMovies());
        movies.addAll(fetchUpcomingVizagMovies());
        String normalizedTarget = movieName.trim().toLowerCase();

        for (Map<String, String> m : movies) {
            if (m.get("name").equalsIgnoreCase(normalizedTarget)) {
                return m.get("url");
            }
        }

        String[] tokens = normalizedTarget.split("\\W+");
        for (Map<String, String> m : movies) {
            String titleLower = m.get("name").toLowerCase();
            if (Arrays.stream(tokens).allMatch(titleLower::contains)) {
                return m.get("url");
            }
        }

        for (Map<String, String> m : movies) {
            String titleLower = m.get("name").toLowerCase();
            for (String t : tokens) {
                if (t.length() >= 4 && titleLower.contains(t)) {
                    return m.get("url");
                }
            }
        }

        // Standard District slug fallback
        String slug = normalizedTarget.replaceAll("[^a-z0-9]+", "-");
        return "https://www.district.in/movies/" + slug + "-movie-tickets-in-vizag";
    }

    private Document fetchMoviePage(String movieUrl, Map<String, Document> pageCache) throws IOException {
        if (pageCache.containsKey(movieUrl)) {
            Document cached = pageCache.get(movieUrl);
            if (cached == null) throw new IOException("District request already failed during this check");
            return cached;
        }
        try {
            Document document = fetchDocument(movieUrl, 15_000, "movie page");
            pageCache.put(movieUrl, document);
            return document;
        } catch (IOException e) {
            pageCache.put(movieUrl, null);
            throw e;
        }
    }

    private AvailabilityCheckResult inspectMoviePage(String movieUrl, WatchRule rule, Map<String, Document> pageCache) throws IOException {
        if (!isAllowedMoviePageUrl(movieUrl)) {
            throw new IOException("Rejected non-District movie URL");
        }
        Document doc = fetchMoviePage(movieUrl, pageCache);

        String rawHtml = doc.html();
        String fallbackDate = "ANY".equalsIgnoreCase(rule.getTargetDate())
                ? extractDateFromUrl(movieUrl)
                : rule.getTargetDate();
        List<ScreeningRecord> allScreenings = extractScreeningsFromPage(doc, rawHtml, rule.getMovieName(), fallbackDate);

        if (allScreenings.isEmpty()) {
            log.debug("Zero screenings found for movie='{}' (page={})", rule.getMovieName(), movieUrl);
            return new AvailabilityCheckResult(
                    false, List.of(), List.of(), List.of(), rule.getTargetDate(), rule.getLanguageFilter(), rule.getFormatFilter(), movieUrl,
                    "UNKNOWN", "No bookable sessions found; District may not have published session data yet"
            );
        }

        // Apply all filters: Date + Language + Format + Theatre + Screen
        List<ScreeningRecord> matched = allScreenings.stream()
                .filter(s -> matchesDate(s, rule.getTargetDate()))
                .filter(s -> matchesTheatre(s, rule.getTheatreName()))
                .filter(s -> matchesScreen(s, rule.getScreenFilter()))
                .toList();

        if (matched.isEmpty()) {
            log.debug("Movie '{}' has {} total screenings in Vizag, but 0 matched filter criteria (Screen={}, Theatre={}, Date={}).",
                    rule.getMovieName(), allScreenings.size(), rule.getScreenFilter(), rule.getTheatreName(), rule.getTargetDate());
            return new AvailabilityCheckResult(
                    false, List.of(), List.of(), List.of(), rule.getTargetDate(), rule.getLanguageFilter(), rule.getFormatFilter(), movieUrl,
                    "NO_MATCH", "No available session matches the selected theatre and screen"
            );
        }

        Set<String> uniqueTheatres = new LinkedHashSet<>();
        Set<String> uniqueScreens = new LinkedHashSet<>();
        Set<String> uniqueShowtimes = new LinkedHashSet<>();
        String bestBookingUrl = movieUrl;

        for (ScreeningRecord r : matched) {
            if (r.cinemaName() != null && !r.cinemaName().isBlank()) {
                uniqueTheatres.add(r.cinemaName());
            }
            if (r.audi() != null && !r.audi().isBlank()) {
                uniqueScreens.add(r.audi());
            }
            if (r.timeStr() != null && !r.timeStr().isBlank()) {
                uniqueShowtimes.add(r.timeStr());
            }
            if (isAllowedDistrictUrl(r.bookingUrl()) && bestBookingUrl.equals(movieUrl)) {
                bestBookingUrl = r.bookingUrl();
            }
        }

        log.info("Tickets AVAILABLE for rule #{}: {} in Vizag at {} theatres on screen(s) {}!",
                rule.getId(), rule.getMovieName(), uniqueTheatres.size(), uniqueScreens);

        return new AvailabilityCheckResult(
                true,
                new ArrayList<>(uniqueTheatres),
                new ArrayList<>(uniqueScreens),
                new ArrayList<>(uniqueShowtimes),
                rule.getTargetDate(),
                rule.getLanguageFilter(),
                rule.getFormatFilter(),
                bestBookingUrl
        );
    }

    private List<ScreeningRecord> extractScreeningsFromPage(Document doc, String rawHtml, String fallbackTitle, String fallbackDate) {
        List<ScreeningRecord> screenings = new ArrayList<>();
        boolean hasNativeSessions = false;

        // Strategy 1: Parse District's native Next.js state via __NEXT_DATA__
        Element nextDataEl = doc.selectFirst("script#__NEXT_DATA__");
        if (nextDataEl != null && !nextDataEl.html().isBlank()) {
            try {
                JsonNode nextData = objectMapper.readTree(nextDataEl.html());
                // District changed its Next.js state shape. Current pages publish
                // sessions under data.serverState.movieSessions; older pages used
                // initialState.movies.movieSessions. Read both shapes and recurse
                // through the session container so a harmless wrapper rename does
                // not turn every watch into a false negative again.
                List<JsonNode> sessionRoots = List.of(
                        nextData.path("props").path("pageProps").path("data").path("serverState").path("movieSessions"),
                        nextData.path("props").path("pageProps").path("initialState").path("movies").path("movieSessions"),
                        nextData.path("props").path("pageProps").path("data").path("movieSessions")
                );
                for (JsonNode sessionRoot : sessionRoots) {
                    if (sessionRoot.isContainerNode() && !sessionRoot.isEmpty()) hasNativeSessions = true;
                    collectMovieSessions(sessionRoot, screenings, fallbackTitle, fallbackDate);
                    if (!screenings.isEmpty()) break;
                }
            } catch (Exception e) {
                log.debug("Failed parsing __NEXT_DATA__ sessions: {}", e.getMessage());
            }
        }

        // Strategy 2: Fallback to regex-based HTML session extraction
        if (screenings.isEmpty() && !hasNativeSessions) {
            Matcher sm = SESSION_PATTERN.matcher(rawHtml);
            while (sm.find()) {
                String cinema = sm.group(1).trim();
                String showTimeRaw = sm.group(2).trim();
                String audiRaw = sm.group(3).trim();
                String sessionRest = sm.group(4);
                String format = sm.group(5).trim();
                String language = extractLanguage(sessionRest);
                String encSessionId = sm.group(6).trim();
                String mcd = sm.group(7).trim();

                String audi = formatAudiWithLabel(audiRaw, sessionRest);
                BigDecimal price = extractPrice(sessionRest);
                Integer availableSeats = extractAvailableSeats(sessionRest);
                String[] dt = parseDateTimeIst(showTimeRaw, fallbackDate);

                String seatLink = "https://www.district.in/movies/seat-layout/" + mcd
                        + "?encsessionid=" + encSessionId + "&freeseating=false&fromsessions=true&type=MOVIES";

                screenings.add(new ScreeningRecord(
                        fallbackTitle, dt[0], dt[1], cinema, format, audi, language, seatLink, price, availableSeats
                ));
            }
        }

        // Strategy 3: Parse Schema.org ScreeningEvent JSON-LD blocks
        if (screenings.isEmpty()) {
            for (Element script : doc.select("script[type=application/ld+json]")) {
                try {
                    String jsonText = script.html().trim();
                    if (!jsonText.contains("ScreeningEvent")) {
                        continue;
                    }
                    JsonNode root = objectMapper.readTree(jsonText);
                    if (root.isArray()) {
                        for (JsonNode node : root) {
                            parseScreeningNode(node, screenings);
                        }
                    } else {
                        parseScreeningNode(root, screenings);
                    }
                } catch (Exception e) {
                    log.debug("Failed parsing screening JSON-LD script: {}", e.getMessage());
                }
            }
        }

        return screenings;
    }

    private void collectMovieSessions(JsonNode node, List<ScreeningRecord> screenings, String fallbackTitle, String fallbackDate) {
        if (node == null || node.isMissingNode() || node.isNull()) return;

        if (node.isObject()) {
            JsonNode cinemas = node.path("pageData").path("nearbyCinemas");
            if (!cinemas.isArray()) {
                cinemas = node.path("nearbyCinemas");
            }
            if (cinemas.isArray()) {
                for (JsonNode cinema : cinemas) {
                    collectCinemaSessions(cinema, screenings, fallbackTitle, fallbackDate);
                }
                return;
            }

            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                collectMovieSessions(values.next(), screenings, fallbackTitle, fallbackDate);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectMovieSessions(item, screenings, fallbackTitle, fallbackDate);
            }
        }
    }

    private void collectCinemaSessions(JsonNode cinema, List<ScreeningRecord> screenings, String fallbackTitle, String fallbackDate) {
        String cinemaName = firstText(cinema, "cinemaInfo.name", "cinemaInfo.label", "name", "label");
        if (cinemaName.isBlank()) cinemaName = "Vizag Cinema";

        JsonNode sessions = cinema.path("sessions");
        if (!sessions.isArray()) sessions = cinema.path("showtimes");
        if (!sessions.isArray()) sessions = cinema.path("showTimes");
        if (!sessions.isArray()) return;

        for (JsonNode session : sessions) {
            if (session.path("disableClick").asBoolean(false) || (session.hasNonNull("avail") && session.path("avail").asInt() <= 0)) continue;
            String showTimeRaw = firstText(session, "showTime", "showtime", "startTime", "start_time", "dateTime");
            String audiRaw = firstText(session, "audi", "screenName", "screen", "screen_name");
            String premiumLabel = firstText(session, "premiumLabel", "premium", "seatType");
            String format = firstText(session, "scrnFmt", "screenFormat", "format", "screen_format");
            if (format.isBlank()) format = null;
            String language = extractLanguage(session);
            String encSessionId = firstText(session, "encSessionId", "encsessionid", "sessionId", "session_id");
            String mcd = firstText(session, "mcd", "cinemaId", "cinema_id", "cinemaCode");

            String audi = formatAudi(audiRaw, premiumLabel);
            BigDecimal price = extractPrice(session);
            Integer availableSeats = extractAvailableSeats(session);
            String[] dt = parseDateTimeIst(showTimeRaw, fallbackDate);

            String bookingUrl = firstText(session, "bookingUrl", "url", "deeplink");
            if (bookingUrl.isBlank() && (!mcd.isBlank() || !encSessionId.isBlank())) {
                bookingUrl = "https://www.district.in/movies/seat-layout/" + mcd
                        + "?encsessionid=" + encSessionId + "&freeseating=false&fromsessions=true&type=MOVIES";
            }
            if (bookingUrl.isBlank()) bookingUrl = "https://www.district.in/movies";

            screenings.add(new ScreeningRecord(
                    fallbackTitle, dt[0], dt[1], cinemaName, format, audi, language, bookingUrl,
                    price, availableSeats
            ));
        }
    }

    private String firstText(JsonNode node, String... paths) {
        for (String path : paths) {
            JsonNode value = node;
            for (String part : path.split("\\.")) value = value.path(part);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("").trim();
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    private String extractDateFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        Matcher matcher = Pattern.compile("(?:[?&])fromdate=([0-9]{4}-[0-9]{2}-[0-9]{2})").matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String[] parseDateTimeIst(String showTimeRaw) {
        return parseDateTimeIst(showTimeRaw, null);
    }

    private String[] parseDateTimeIst(String showTimeRaw, String fallbackDate) {
        if (showTimeRaw == null || showTimeRaw.isBlank()) {
            return new String[]{null, null};
        }
        try {
            ZonedDateTime istTime;
            if (showTimeRaw.endsWith("Z") || showTimeRaw.contains("+")) {
                istTime = OffsetDateTime.parse(showTimeRaw).atZoneSameInstant(IST_ZONE);
            } else {
                LocalDateTime ldt = LocalDateTime.parse(showTimeRaw);
                istTime = ldt.atZone(ZoneOffset.UTC).withZoneSameInstant(IST_ZONE);
            }
            String dateStr = istTime.format(DateTimeFormatter.ISO_LOCAL_DATE);
            String timeStr = istTime.format(DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH));
            return new String[]{dateStr, timeStr};
        } catch (Exception e) {
            Matcher timeOnly = Pattern.compile("(?i)^(\\d{1,2}):(\\d{2})\\s*(AM|PM)$").matcher(showTimeRaw.trim());
            if (timeOnly.find()) {
                String dateStr = fallbackDate != null && !fallbackDate.isBlank() && !"ANY".equalsIgnoreCase(fallbackDate)
                        ? fallbackDate : LocalDateTime.now(IST_ZONE).toLocalDate().toString();
                String timeStr = String.format(Locale.ENGLISH, "%02d:%s %s",
                        Integer.parseInt(timeOnly.group(1)), timeOnly.group(2), timeOnly.group(3).toUpperCase(Locale.ROOT));
                return new String[]{dateStr, timeStr};
            }
            String dateStr = showTimeRaw.length() >= 10 && showTimeRaw.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}")
                    ? showTimeRaw.substring(0, 10) : fallbackDate;
            String timeStr = showTimeRaw.length() >= 16 ? showTimeRaw.substring(11, 16) : showTimeRaw;
            return new String[]{dateStr, timeStr};
        }
    }

    private void parseScreeningNode(JsonNode node, List<ScreeningRecord> list) {
        if (!"ScreeningEvent".equalsIgnoreCase(node.path("@type").asText())) {
            return;
        }

        String title = node.path("name").asText("");
        String startIso = node.path("startDate").asText("");
        String cinemaName = node.path("location").path("name").asText("");
        String format = node.path("videoFormat").asText("");
        if (format.isBlank()) format = null;
        String bookingUrl = node.path("offers").path("url").asText("");
        String language = node.path("inLanguage").asText("");
        if (language.isBlank()) language = extractLanguage(node);
        BigDecimal price = extractPrice(node.path("offers"));
        Integer availableSeats = extractAvailableSeats(node);

        String[] dt = parseDateTimeIst(startIso);

        if (cinemaName.isBlank()) {
            cinemaName = "Vizag Cinema";
        }

        list.add(new ScreeningRecord(title, dt[0], dt[1], cinemaName, format, "Main Screen",
                language == null || language.isBlank() ? null : language, bookingUrl, price, availableSeats));
    }

    private boolean matchesDate(ScreeningRecord record, String targetDate) {
        if (targetDate == null || "ANY".equalsIgnoreCase(targetDate.trim())) {
            return true;
        }
        if (record.dateIso() == null || record.dateIso().isBlank()) return false;
        return record.dateIso().equalsIgnoreCase(targetDate.trim());
    }

    private boolean matchesLanguage(ScreeningRecord record, String langFilter) {
        if (langFilter == null || "ANY".equalsIgnoreCase(langFilter.trim())) {
            return true;
        }
        String lf = langFilter.trim().toLowerCase();
        return record.language() != null && record.language().toLowerCase().contains(lf);
    }

    private boolean matchesFormat(ScreeningRecord record, String formatFilter) {
        if (formatFilter == null || "ANY".equalsIgnoreCase(formatFilter.trim())) {
            return true;
        }
        String ff = formatFilter.trim().toLowerCase();
        if (record.format() != null && record.format().toLowerCase().contains(ff)) {
            return true;
        }
        return false;
    }

    private String extractLanguage(JsonNode node) {
        for (String key : List.of("inLanguage", "language", "lang", "languageName", "audioLanguage")) {
            JsonNode value = node.path(key);
            if (value.isArray()) {
                List<String> languages = new ArrayList<>();
                value.forEach(item -> {
                    String text = item.isObject() ? item.path("name").asText("") : item.asText("");
                    if (!text.isBlank()) languages.add(text.trim());
                });
                if (!languages.isEmpty()) return String.join(", ", languages);
            } else if (!value.isMissingNode() && !value.isNull()) {
                String text = value.isObject() ? value.path("name").asText("") : value.asText("");
                if (!text.isBlank()) return text.trim();
            }
        }
        return null;
    }

    private String extractLanguage(String sessionText) {
        if (sessionText == null || sessionText.isBlank()) return null;
        Matcher matcher = LANGUAGE_PATTERN.matcher(sessionText);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private boolean matchesTheatre(ScreeningRecord record, String theatreFilter) {
        if (theatreFilter == null || theatreFilter.isBlank() || "ANY".equalsIgnoreCase(theatreFilter.trim())) {
            return true;
        }
        if (record.cinemaName() == null) {
            return false;
        }
        return normalizeTheatreName(record.cinemaName()).contains(normalizeTheatreName(theatreFilter));
    }

    private String normalizeTheatreName(String name) {
        return name.replace('\u00a0', ' ').trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private boolean matchesScreen(ScreeningRecord record, String screenFilter) {
        if (screenFilter == null || "ANY".equalsIgnoreCase(screenFilter.trim())) {
            return true;
        }
        if (record.audi() == null || record.audi().isBlank()) {
            return false;
        }
        String left = normalizeScreenName(screenFilter);
        String right = normalizeScreenName(record.audi());
        if (left.equals(right)) return true;
        Matcher requested = Pattern.compile("^(?:screen|audi)0*(\\d+)").matcher(left);
        Matcher actual = Pattern.compile("^(?:screen|audi)0*(\\d+)").matcher(right);
        return requested.find() && actual.find() && requested.group(1).equals(actual.group(1));
    }

    private String normalizeScreenName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private boolean matchesPrice(ScreeningRecord record, BigDecimal maxPrice) {
        if (maxPrice == null || maxPrice.signum() <= 0) return true;
        return record.price() != null && record.price().compareTo(maxPrice) <= 0;
    }

    private boolean matchesAvailableSeats(ScreeningRecord record, int minAvailableSeats) {
        if (minAvailableSeats <= 0) return true;
        return record.availableSeats() != null && record.availableSeats() >= minAvailableSeats;
    }

    private BigDecimal extractPrice(JsonNode node) {
        JsonNode value = findFirstValue(node, Set.of(
                "price", "ticketprice", "ticket_price", "minprice", "baseprice", "finalprice", "amount", "pricevalue",
                "minfare", "fare", "basefare", "netamount", "ticketamount"
        ));
        return parseDecimal(value);
    }

    private BigDecimal extractPrice(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = Pattern.compile("\\\"(?:price|ticketPrice|ticket_price|minPrice|basePrice|finalPrice|amount|minFare|fare|baseFare|netAmount|ticketAmount)\\\"\\s*:\\s*\\\"?([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE)
                .matcher(text);
        return matcher.find() ? parseDecimal(matcher.group(1)) : null;
    }

    private Integer extractAvailableSeats(JsonNode node) {
        JsonNode value = findFirstValue(node, Set.of(
                "availableseats", "availableseatcount", "seatsavailable", "freeseats", "availabletickets", "availableticketcount",
                "availablecount", "freeseatcount", "vacantseats", "bookableseats", "avail", "availcount"
        ));
        Integer direct = parseInteger(value);
        if (direct != null) return direct;

        JsonNode seats = findFirstValue(node, Set.of("seatlayout", "seatlist", "seats"));
        if (seats != null && seats.isArray()) {
            int count = 0;
            for (JsonNode seat : seats) {
                String status = seat.isObject()
                        ? seat.path("status").asText(seat.path("availability").asText(""))
                        : seat.asText("");
                boolean available = status.isBlank()
                        ? seat.path("isAvailable").asBoolean(false)
                        : status.toLowerCase().contains("available") || status.equalsIgnoreCase("free");
                if (available) count++;
            }
            return count;
        }
        return null;
    }

    private Integer extractAvailableSeats(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = Pattern.compile("\\\"(?:availableSeats|availableSeatCount|seatsAvailable|freeSeats|availableTickets|availableTicketCount|availableCount|freeSeatCount|vacantSeats|bookableSeats|avail|availCount)\\\"\\s*:\\s*\\\"?([0-9]+)", Pattern.CASE_INSENSITIVE)
                .matcher(text);
        return matcher.find() ? parseInteger(matcher.group(1)) : null;
    }

    private JsonNode findFirstValue(JsonNode node, Set<String> keys) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String normalized = entry.getKey().replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                if (keys.contains(normalized)) return entry.getValue();
                JsonNode nested = findFirstValue(entry.getValue(), keys);
                if (nested != null) return nested;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                JsonNode nested = findFirstValue(item, keys);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private BigDecimal parseDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isObject()) {
            for (String key : List.of("value", "amount", "price", "fare")) {
                BigDecimal parsed = parseDecimal(node.path(key));
                if (parsed != null) return parsed;
            }
            return null;
        }
        return parseDecimal(node.asText());
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            BigDecimal parsed = new BigDecimal(value.replaceAll("[^0-9.]", ""));
            return parsed.signum() > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInteger(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : parseInteger(node.asText());
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Math.max(0, Integer.parseInt(value.replaceAll("[^0-9]", "")));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected Document fetchDocument(String url, int timeoutMillis, String operation) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Document document = Jsoup.connect(url)
                        .userAgent(randomUserAgent())
                        .cookie("city", "vizag")
                        .timeout(timeoutMillis)
                        .get();
                lastSuccessAt = java.time.Instant.now();
                consecutiveFailures = 0;
                lastFailureMessage = null;
                return document;
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < maxAttempts) {
                    log.warn("District {} attempt {}/{} failed: {}. Retrying...", operation, attempt, maxAttempts, e.getMessage());
                    sleepBeforeRetry(attempt);
                }
            }
        }

        consecutiveFailures++;
        lastFailureAt = java.time.Instant.now();
        lastFailureMessage = lastFailure == null ? "Unknown scraper failure" : lastFailure.getMessage();
        log.error("District {} failed after {} attempt(s): {}", operation, maxAttempts, lastFailureMessage);
        throw lastFailure == null ? new IOException("District scraper failed") : lastFailure;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            long delay = retryBackoffMs * (1L << Math.min(attempt - 1, 5));
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String formatAudi(String audiRaw, String premiumLabel) {
        if (audiRaw == null || audiRaw.isBlank()) return "Main Screen";
        if (premiumLabel != null && !premiumLabel.isBlank()) {
            String pl = premiumLabel.trim();
            if (pl.equalsIgnoreCase("Q LUXON LED") || pl.equalsIgnoreCase("Q-LUXON LED") || pl.toUpperCase().contains("LUXON")) {
                return audiRaw + " (Q-Luxon LED)";
            } else if (pl.equalsIgnoreCase("ATMOS") || pl.equalsIgnoreCase("DOLBY ATMOS") || pl.toUpperCase().contains("ATMOS")) {
                return audiRaw + " (Dolby Atmos)";
            } else if (!pl.equalsIgnoreCase(audiRaw) && !pl.toLowerCase().contains("screen") && !pl.toLowerCase().contains("audi")) {
                return audiRaw + " (" + pl + ")";
            }
        }
        return audiRaw;
    }

    private String formatAudiWithLabel(String audiRaw, String sessionRest) {
        if (audiRaw == null || audiRaw.isBlank()) return "Main Screen";
        if (sessionRest != null) {
            Matcher lm = PREMIUM_LABEL_PATTERN.matcher(sessionRest);
            if (lm.find()) {
                return formatAudi(audiRaw, lm.group(1));
            }
        }
        return audiRaw;
    }

    private String formatSlugToTitle(String slug) {
        if (slug == null || slug.isBlank()) return "";
        String[] parts = slug.split("-");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isBlank()) {
                sb.append(Character.toUpperCase(p.charAt(0)))
                        .append(p.substring(1).toLowerCase())
                        .append(" ");
            }
        }
        return sb.toString().trim();
    }

    private String randomUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }
}
