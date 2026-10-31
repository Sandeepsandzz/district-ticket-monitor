package com.district.monitor.service;

import com.district.monitor.dto.AvailabilityCheckResult;
import com.district.monitor.entity.WatchRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cache of discovered movies in Vizag: title -> url
    private volatile List<Map<String, String>> cachedVizagMovies = new ArrayList<>();
    private volatile long lastMoviesCacheTime = 0;

    public record ScreeningRecord(
            String title,
            String dateIso,
            String timeStr,
            String cinemaName,
            String format,
            String audi,
            String language,
            String bookingUrl
    ) {}

    public DistrictScraperService(
            @Value("${district.base-url:https://www.district.in/movies}") String baseUrl
    ) {
        this.baseUrl = baseUrl;
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
            Document doc = Jsoup.connect(vizagUrl)
                    .userAgent(randomUserAgent())
                    .cookie("city", "vizag")
                    .timeout(15_000)
                    .get();

            String rawHtml = doc.html();
            long nowEpochSec = System.currentTimeMillis() / 1000L;

            // 1. Match explicit Vizag movie links in HTML
            Matcher vm = VIZAG_MOVIE_URL_PATTERN.matcher(rawHtml);
            while (vm.find()) {
                String slug = vm.group(1);
                String fullUrl = normalizeToVizagUrl(vm.group(0));
                String formattedTitle = formatSlugToTitle(slug);
                if (!isExcludedPlaceholder(formattedTitle) && !isUpcomingTitle(formattedTitle)) {
                    if (!movieMap.containsKey(formattedTitle.toLowerCase())) {
                        movieMap.put(formattedTitle.toLowerCase(), Map.of(
                                "name", formattedTitle,
                                "url", fullUrl,
                                "slug", slug
                        ));
                    }
                }
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
                                if (!isExcludedPlaceholder(name) && !isUpcomingTitle(name)) {
                                    if (!movieMap.containsKey(name.toLowerCase())) {
                                        movieMap.put(name.toLowerCase(), Map.of(
                                                "name", name,
                                                "url", url,
                                                "slug", name.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                                        ));
                                    }
                                }
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
                if (isExcludedPlaceholder(name) || isUpcomingTitle(name)) continue;
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
        }

        cachedVizagMovies = new ArrayList<>(movieMap.values());
        lastMoviesCacheTime = now;
        return cachedVizagMovies;
    }

    private boolean isExcludedPlaceholder(String name) {
        if (name == null || name.isBlank()) return true;
        String lower = name.toLowerCase();
        return lower.contains("sense and sensibility")
                || lower.contains("the influencer project")
                || lower.contains("clayface")
                || lower.contains("udta teer")
                || lower.contains("doraemon")
                || lower.contains("nakhrewaalii")
                || lower.contains("prahaar")
                || lower.contains("nayyi navelli")
                || lower.contains("pyre")
                || lower.contains("hanuman ansh");
    }

    private boolean isUpcomingTitle(String name) {
        if (name == null || name.isBlank()) return false;
        String lower = name.toLowerCase();
        return lower.contains("ramayana")
                || lower.contains("mirzapur")
                || lower.contains("drishyam")
                || lower.contains("spider-man")
                || lower.contains("toy story")
                || lower.contains("arirang");
    }

    /**
     * Curated and dynamic upcoming blockbusters list for Vizag.
     */
    public List<Map<String, String>> fetchUpcomingVizagMovies() {
        return List.of(
                Map.of("name", "Ramayana", "url", "https://www.district.in/movies/namit-malhotra-s-ramayana-movie-tickets-in-vizag-MV176276"),
                Map.of("name", "Mirzapur The Movie", "url", "https://www.district.in/movies/mirzapur-the-movie-movie-tickets-in-vizag-MV181196"),
                Map.of("name", "Drishyam 3", "url", "https://www.district.in/movies/drishyam-the-conclusion-movie-tickets-in-vizag-MV207254"),
                Map.of("name", "Spider-Man: Brand New Day", "url", "https://www.district.in/movies/spider-man-brand-new-day-movie-tickets-in-vizag-MV194537"),
                Map.of("name", "BTS World Tour Arirang", "url", "https://www.district.in/movies/bts-world-tour-arirang-in-sao-paulo-live-viewing-movie-tickets-MV233822"),
                Map.of("name", "Toy Story 5", "url", "https://www.district.in/movies/toy-story-5-movie-tickets-in-vizag-MV178238")
        );
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
            Document doc = Jsoup.connect(targetUrl)
                    .userAgent(randomUserAgent())
                    .cookie("city", "vizag")
                    .timeout(10_000)
                    .get();

            String rawHtml = doc.html();
            List<ScreeningRecord> allScreenings = extractScreeningsFromPage(doc, rawHtml, "");
            Set<String> screens = new LinkedHashSet<>();
            for (ScreeningRecord r : allScreenings) {
                if (matchesTheatre(r, theatreFilter) && r.audi() != null && !r.audi().isBlank() && !"Main Screen".equalsIgnoreCase(r.audi())) {
                    screens.add(r.audi().trim());
                }
            }
            return new ArrayList<>(screens);
        } catch (Exception e) {
            log.debug("Could not extract live screens for theatre {}: {}", theatreFilter, e.getMessage());
            return List.of();
        }
    }

    public AvailabilityCheckResult checkWatchRule(WatchRule rule) {
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
                return unavailableResult(rule);
            }

            // Append fromdate if specific target date is requested (e.g. 2026-10-31)
            String targetDate = rule.getTargetDate();
            if (targetDate != null && !targetDate.isBlank() && !"ANY".equalsIgnoreCase(targetDate.trim())) {
                String sep = targetUrl.contains("?") ? "&" : "?";
                targetUrl = targetUrl + sep + "fromdate=" + targetDate.trim();
            }

            AvailabilityCheckResult res = inspectMoviePage(targetUrl, rule);

            // If zero screenings on the base page and rule allows ANY date, check if showDates exists on page (e.g. upcoming release date)
            if (!res.isAvailable() && (rule.getTargetDate() == null || "ANY".equalsIgnoreCase(rule.getTargetDate()))) {
                try {
                    Document doc = Jsoup.connect(targetUrl)
                            .userAgent(randomUserAgent())
                            .cookie("city", "vizag")
                            .timeout(10_000)
                            .get();
                    String raw = doc.html();
                    Matcher sdm = Pattern.compile("\"showDates\":\\[\"([0-9]{4}-[0-9]{2}-[0-9]{2})\"").matcher(raw);
                    if (sdm.find()) {
                        String firstDate = sdm.group(1);
                        String dateUrl = targetUrl + (targetUrl.contains("?") ? "&" : "?") + "fromdate=" + firstDate;
                        AvailabilityCheckResult dateRes = inspectMoviePage(dateUrl, rule);
                        if (dateRes.isAvailable()) {
                            return dateRes;
                        }
                    }
                } catch (Exception ignored) {}
            }

            return res;

        } catch (Exception e) {
            log.error("Error checking rule id={} (movie={}): {}", rule.getId(), rule.getMovieName(), e.getMessage());
            return unavailableResult(rule);
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

    private AvailabilityCheckResult inspectMoviePage(String movieUrl, WatchRule rule) throws IOException {
        if (!isAllowedMoviePageUrl(movieUrl)) {
            throw new IOException("Rejected non-District movie URL");
        }
        Document doc = Jsoup.connect(movieUrl)
                .userAgent(randomUserAgent())
                .cookie("city", "vizag")
                .timeout(15_000)
                .get();

        String rawHtml = doc.html();
        List<ScreeningRecord> allScreenings = extractScreeningsFromPage(doc, rawHtml, rule.getMovieName());

        if (allScreenings.isEmpty()) {
            log.debug("Zero screenings found for movie='{}' (page={})", rule.getMovieName(), movieUrl);
            return new AvailabilityCheckResult(
                    false, List.of(), List.of(), List.of(), rule.getTargetDate(), rule.getLanguageFilter(), rule.getFormatFilter(), movieUrl
            );
        }

        // Apply all filters: Date + Language + Format + Theatre + Screen
        List<ScreeningRecord> matched = allScreenings.stream()
                .filter(s -> matchesDate(s, rule.getTargetDate()))
                .filter(s -> matchesLanguage(s, rule.getLanguageFilter()))
                .filter(s -> matchesFormat(s, rule.getFormatFilter()))
                .filter(s -> matchesTheatre(s, rule.getTheatreName()))
                .filter(s -> matchesScreen(s, rule.getScreenFilter()))
                .toList();

        if (matched.isEmpty()) {
            log.debug("Movie '{}' has {} total screenings in Vizag, but 0 matched filter criteria (Screen={}, Theatre={}, Date={}).",
                    rule.getMovieName(), allScreenings.size(), rule.getScreenFilter(), rule.getTheatreName(), rule.getTargetDate());
            return new AvailabilityCheckResult(
                    false, List.of(), List.of(), List.of(), rule.getTargetDate(), rule.getLanguageFilter(), rule.getFormatFilter(), movieUrl
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

    private List<ScreeningRecord> extractScreeningsFromPage(Document doc, String rawHtml, String fallbackTitle) {
        List<ScreeningRecord> screenings = new ArrayList<>();

        // Strategy 1: Parse District's native Next.js state via __NEXT_DATA__
        Element nextDataEl = doc.selectFirst("script#__NEXT_DATA__");
        if (nextDataEl != null && !nextDataEl.html().isBlank()) {
            try {
                JsonNode nextData = objectMapper.readTree(nextDataEl.html());
                JsonNode movieSessions = nextData.path("props").path("pageProps").path("initialState").path("movies").path("movieSessions");
                if (movieSessions.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = movieSessions.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> entry = fields.next();
                        JsonNode cinemas = entry.getValue().path("pageData").path("nearbyCinemas");
                        if (cinemas.isArray()) {
                            for (JsonNode cinema : cinemas) {
                                String cinemaName = cinema.path("cinemaInfo").path("name").asText("").trim();
                                if (cinemaName.isBlank()) {
                                    cinemaName = cinema.path("cinemaInfo").path("label").asText("").trim();
                                }
                                if (cinemaName.isBlank()) {
                                    cinemaName = cinema.path("name").asText("").trim();
                                }
                                JsonNode sessions = cinema.path("sessions");
                                if (sessions.isArray()) {
                                    for (JsonNode s : sessions) {
                                        String showTimeRaw = s.path("showTime").asText("").trim();
                                        String audiRaw = s.path("audi").asText("").trim();
                                        String premiumLabel = s.path("premiumLabel").asText("").trim();
                                        String format = s.path("scrnFmt").asText("").trim();
                                        if (format.isBlank()) format = null;
                                        String language = extractLanguage(s);
                                        String encSessionId = s.path("encSessionId").asText("").trim();
                                        String mcd = s.path("mcd").asText("").trim();

                                        String audi = formatAudi(audiRaw, premiumLabel);
                                        String[] dt = parseDateTimeIst(showTimeRaw);

                                        String seatLink = "https://www.district.in/movies/seat-layout/" + mcd
                                                + "?encsessionid=" + encSessionId + "&freeseating=false&fromsessions=true&type=MOVIES";

                                        screenings.add(new ScreeningRecord(
                                                fallbackTitle, dt[0], dt[1], cinemaName, format, audi, language, seatLink
                                        ));
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Failed parsing __NEXT_DATA__ sessions: {}", e.getMessage());
            }
        }

        // Strategy 2: Fallback to regex-based HTML session extraction
        if (screenings.isEmpty()) {
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
                String[] dt = parseDateTimeIst(showTimeRaw);

                String seatLink = "https://www.district.in/movies/seat-layout/" + mcd
                        + "?encsessionid=" + encSessionId + "&freeseating=false&fromsessions=true&type=MOVIES";

                screenings.add(new ScreeningRecord(
                        fallbackTitle, dt[0], dt[1], cinema, format, audi, language, seatLink
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

    private String[] parseDateTimeIst(String showTimeRaw) {
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
            String dateStr = showTimeRaw.length() >= 10 ? showTimeRaw.substring(0, 10) : null;
            String timeStr = showTimeRaw.length() >= 16 ? showTimeRaw.substring(11, 16) : null;
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

        String[] dt = parseDateTimeIst(startIso);

        if (cinemaName.isBlank()) {
            cinemaName = "Vizag Cinema";
        }

        list.add(new ScreeningRecord(title, dt[0], dt[1], cinemaName, format, "Main Screen",
                language == null || language.isBlank() ? null : language, bookingUrl));
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
        return record.cinemaName().toLowerCase().contains(theatreFilter.trim().toLowerCase());
    }

    private boolean matchesScreen(ScreeningRecord record, String screenFilter) {
        if (screenFilter == null || "ANY".equalsIgnoreCase(screenFilter.trim())) {
            return true;
        }
        if (record.audi() == null || record.audi().isBlank()) {
            return false;
        }

        String sf = screenFilter.trim().toLowerCase().replaceAll("\\s+", "");
        String ra = record.audi().toLowerCase().replaceAll("\\s+", "");

        if (ra.contains(sf) || sf.contains(ra)) {
            return true;
        }

        // Compare number tokens (e.g., "Screen 2" vs "SCREEN 2 (Dolby Atmos)", "Audi 3" vs "AUDI03 (Q-Luxon LED)")
        Matcher m1 = Pattern.compile("\\d+").matcher(sf);
        Matcher m2 = Pattern.compile("\\d+").matcher(ra);
        if (m1.find() && m2.find()) {
            try {
                int num1 = Integer.parseInt(m1.group());
                int num2 = Integer.parseInt(m2.group());
                if (num1 == num2) {
                    return true;
                }
            } catch (Exception ignored) {}
        }

        // Match premium screen keywords
        if (sf.contains("luxon") && ra.contains("luxon")) return true;
        if (sf.contains("atmos") && ra.contains("atmos")) return true;
        if (sf.contains("led") && ra.contains("led")) return true;

        return false;
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
