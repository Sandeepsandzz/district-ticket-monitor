package com.district.monitor.controller;

import com.district.monitor.entity.WatchRule;
import com.district.monitor.repository.WatchRuleRepository;
import com.district.monitor.scheduler.TicketWatchScheduler;
import com.district.monitor.service.DistrictScraperService;
import com.district.monitor.service.TelegramCommandService;
import com.district.monitor.service.WebLinkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/web")
public class WebWatchController {
    private final WebLinkService links;
    private final WatchRuleRepository repository;
    private final DistrictScraperService scraper;
    private final TelegramCommandService telegram;
    private final TicketWatchScheduler scheduler;

    public record LinkRequest(String code) {}
    public record WatchRequest(String movieName, String movieUrl, String theatreName, String screen, String date) {}
    public record WatchView(Long id, String movie, String theatre, String screen, String date,
                            boolean paused, Instant snoozedUntil, int alertCount) {
        static WatchView from(WatchRule rule) {
            return new WatchView(rule.getId(), rule.getMovieName(), rule.getTheatreName(), rule.getScreenFilter(),
                    rule.getTargetDate(), rule.isAcknowledged(), rule.getSnoozedUntil(), rule.getAlertCount());
        }
    }

    public WebWatchController(WebLinkService links, WatchRuleRepository repository, DistrictScraperService scraper,
                              TelegramCommandService telegram, TicketWatchScheduler scheduler) {
        this.links = links;
        this.repository = repository;
        this.scraper = scraper;
        this.telegram = telegram;
        this.scheduler = scheduler;
    }

    @PostMapping("/link")
    public ResponseEntity<?> link(@RequestBody LinkRequest request) {
        String token = links.exchange(request == null ? null : request.code());
        if (token == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired linking code");
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("token", token));
    }

    @PostMapping("/logout")
    public void logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        links.logout(token(authorization));
    }

    @GetMapping("/movies")
    public Map<String, List<Map<String, String>>> movies(@RequestHeader(value = "Authorization", required = false) String authorization) {
        chat(authorization);
        return Map.of("showing", scraper.fetchCurrentVizagMovies(), "upcoming", scraper.fetchUpcomingVizagMovies());
    }

    @GetMapping("/theatres")
    public Map<String, Object> theatres(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestParam String movieUrl) {
        chat(authorization);
        requireMovieUrl(movieUrl);
        var live = scraper.getAvailableTheatresForMovie(movieUrl);
        return Map.of("items", live.isEmpty() ? scraper.getKnownVizagTheatres() : live, "suggested", live.isEmpty());
    }

    @GetMapping("/screens")
    public Map<String, Object> screens(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestParam String movieUrl, @RequestParam String theatre) {
        chat(authorization);
        requireMovieUrl(movieUrl);
        requireText(theatre, "theatre", 100);
        var live = scraper.getAvailableScreensForTheatre(movieUrl, theatre);
        return Map.of("items", live.isEmpty() ? telegram.getScreensForTheatre(theatre) : live, "suggested", live.isEmpty());
    }

    @GetMapping("/watches")
    public List<WatchView> watches(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return repository.findByChatId(chat(authorization)).stream().map(WatchView::from).toList();
    }

    @PostMapping("/watches")
    public WatchView create(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestBody WatchRequest request) {
        String chatId = chat(authorization);
        if (request == null) throw bad("Watch details are required");
        requireText(request.movieName(), "movie", 120);
        requireMovieUrl(request.movieUrl());
        requireText(request.theatreName(), "theatre", 100);
        requireText(request.screen(), "screen", 100);
        if ("ANY".equalsIgnoreCase(request.theatreName().trim()) || "ANY".equalsIgnoreCase(request.screen().trim())) throw bad("Choose a specific theatre and screen");
        try {
            LocalDate date = LocalDate.parse(request.date());
            if (date.isBefore(LocalDate.now(ZoneId.of("Asia/Kolkata")))) throw bad("Choose today or a future date");
        } catch (java.time.DateTimeException | NullPointerException e) { throw bad("Enter a valid date"); }
        var existing = repository.findByChatIdAndMovieNameIgnoreCaseAndAcknowledgedFalse(chatId, request.movieName());
        if (existing.stream().anyMatch(rule -> request.theatreName().trim().equalsIgnoreCase(rule.getTheatreName())
                && request.screen().trim().equalsIgnoreCase(rule.getScreenFilter()) && request.date().equals(rule.getTargetDate()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This alert already exists");
        }
        return WatchView.from(repository.save(new WatchRule(chatId, "vizag", request.movieName(), request.theatreName(),
                request.screen(), request.date(), "ANY", "ANY", request.movieUrl())));
    }

    @PostMapping("/watches/{id}/{action}")
    public WatchView change(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @PathVariable Long id, @PathVariable String action) {
        WatchRule rule = owned(id, chat(authorization));
        switch (action) {
            case "pause" -> { rule.setAcknowledged(true); rule.setSnoozedUntil(null); }
            case "resume" -> { rule.setAcknowledged(false); rule.setSnoozedUntil(null); rule.setAlertCount(0); rule.setLastAlertedAt(null); }
            case "snooze" -> { rule.setSnoozedUntil(Instant.now().plusSeconds(900)); }
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return WatchView.from(repository.save(rule));
    }

    @DeleteMapping("/watches/{id}")
    public ResponseEntity<Void> delete(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable Long id) {
        repository.delete(owned(id, chat(authorization)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/check")
    public Map<String, Integer> check(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return Map.of("notificationsSent", scheduler.pollActiveWatchesForChat(chat(authorization)));
    }

    private WatchRule owned(Long id, String chatId) {
        return repository.findByIdAndChatId(id, chatId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private String chat(String authorization) {
        String chatId = links.resolve(token(authorization));
        if (chatId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Link your Telegram account again");
        return chatId;
    }
    private String token(String header) { return header != null && header.startsWith("Bearer ") ? header.substring(7) : null; }
    private void requireMovieUrl(String url) { if (!scraper.isAllowedMoviePageUrl(url)) throw bad("Choose a valid District movie"); }
    private void requireText(String value, String name, int max) { if (value == null || value.isBlank() || value.length() > max) throw bad("Enter a valid " + name); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
