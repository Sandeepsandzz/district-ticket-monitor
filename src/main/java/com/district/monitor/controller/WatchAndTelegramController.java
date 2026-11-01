package com.district.monitor.controller;

import com.district.monitor.dto.CreateWatchRequest;
import com.district.monitor.entity.WatchRule;
import com.district.monitor.repository.WatchRuleRepository;
import com.district.monitor.scheduler.TicketWatchScheduler;
import com.district.monitor.service.DistrictScraperService;
import com.district.monitor.service.TelegramCommandService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api")
public class WatchAndTelegramController {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BackupWatch(String chatId, String city, String movieName, String theatreName, String screenFilter,
                              String targetDate, String languageFilter, String formatFilter, String movieUrl,
                              BigDecimal maxPrice, Integer minAvailableSeats, Boolean acknowledged, Integer alertCount,
                              Instant lastAlertedAt, Instant snoozedUntil, Instant createdAt, Boolean repeatAlerts) {}

    private final WatchRuleRepository repository;
    private final TicketWatchScheduler scheduler;
    private final TelegramCommandService telegramCommandService;
    private final DistrictScraperService scraperService;
    private final String adminToken;
    private final String webhookSecret;

    public WatchAndTelegramController(
            WatchRuleRepository repository,
            TicketWatchScheduler scheduler,
            TelegramCommandService telegramCommandService,
            DistrictScraperService scraperService,
            @Value("${api.admin-token:}") String adminToken,
            @Value("${telegram.webhook-secret:}") String webhookSecret
    ) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.telegramCommandService = telegramCommandService;
        this.scraperService = scraperService;
        this.adminToken = adminToken;
        this.webhookSecret = webhookSecret;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP");
    }

    @PostMapping("/watches")
    public ResponseEntity<?> createWatch(
            @RequestHeader(value = "X-Admin-Token", required = false) String suppliedToken,
            @RequestBody(required = false) CreateWatchRequest req
    ) {
        if (!isAuthorized(adminToken, suppliedToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String validationError = validate(req);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", validationError));
        }
        WatchRule saved = repository.save(new WatchRule(
                req.chatId(),
                "vizag",
                req.movieName(),
                req.theatreName(),
                req.screenFilter(),
                req.targetDate(),
                "ANY",
                "ANY",
                req.movieUrl(),
                null,
                0
        ));
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/admin/summary")
    public ResponseEntity<?> adminSummary(
            @RequestHeader(value = "X-Admin-Token", required = false) String suppliedToken
    ) {
        if (!isAuthorized(adminToken, suppliedToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<WatchRule> all = repository.findAll();
        long active = all.stream().filter(rule -> !rule.isAcknowledged()).count();
        long paused = all.stream().filter(WatchRule::isAcknowledged).count();
        return ResponseEntity.ok(Map.of(
                "generatedAt", Instant.now(),
                "totalWatches", all.size(),
                "activeWatches", active,
                "pausedWatches", paused,
                "scraper", scraperService.getHealth()
        ));
    }

    @GetMapping("/admin/watches")
    public ResponseEntity<?> adminWatches(
            @RequestHeader(value = "X-Admin-Token", required = false) String suppliedToken
    ) {
        if (!isAuthorized(adminToken, suppliedToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(repository.findAll());
    }

    /** Imports exported watch definitions without overwriting an existing watch. IDs are assigned by the target database. */
    @PostMapping("/admin/watches/import")
    @Transactional
    public ResponseEntity<?> importWatches(@RequestHeader(value = "X-Admin-Token", required = false) String suppliedToken,
                                           @RequestBody List<BackupWatch> backup) {
        if (!isAuthorized(adminToken, suppliedToken)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (backup == null || backup.size() > 2000) return ResponseEntity.badRequest().body(Map.of("error", "Expected at most 2000 watches"));
        for (BackupWatch item : backup) {
            if (item == null || item.chatId() == null || !item.chatId().matches("-?\\d{1,20}")
                    || item.movieName() == null || item.movieName().isBlank() || item.movieName().length() > 255
                    || backupTooLong(item.city()) || backupTooLong(item.theatreName()) || backupTooLong(item.screenFilter())
                    || backupTooLong(item.languageFilter()) || backupTooLong(item.formatFilter()) || backupTooLong(item.movieUrl())
                    || !validDateFilter(item.targetDate())
                    || (item.movieUrl() != null && !item.movieUrl().isBlank() && !scraperService.isAllowedMoviePageUrl(item.movieUrl())))
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid watch in backup; nothing was imported"));
        }
        int imported = 0, skipped = 0;
        for (BackupWatch item : backup) {
            boolean exists = repository.findByChatId(item.chatId()).stream().anyMatch(r ->
                    r.getMovieName().equalsIgnoreCase(item.movieName())
                    && backupValue(r.getTheatreName()).equals(backupValue(item.theatreName()))
                    && backupValue(r.getScreenFilter()).equals(backupValue(item.screenFilter()))
                    && backupValue(r.getTargetDate()).equals(backupValue(item.targetDate())));
            if (exists) { skipped++; continue; }
            WatchRule rule = new WatchRule(item.chatId(), item.city(), item.movieName(), item.theatreName(),
                    item.screenFilter(), item.targetDate(), item.languageFilter(), item.formatFilter(), item.movieUrl(),
                    item.maxPrice(), item.minAvailableSeats());
            rule.setAcknowledged(Boolean.TRUE.equals(item.acknowledged()));
            rule.setAlertCount(item.alertCount() == null ? 0 : Math.max(0, item.alertCount()));
            rule.setLastAlertedAt(item.lastAlertedAt()); rule.setSnoozedUntil(item.snoozedUntil()); rule.setCreatedAt(item.createdAt());
            rule.setRepeatAlerts(item.repeatAlerts() == null || item.repeatAlerts());
            repository.save(rule); imported++;
        }
        return ResponseEntity.ok(Map.of("imported", imported, "skipped", skipped));
    }

    private String backupValue(String value) {
        return value == null || value.isBlank() || "ANY".equalsIgnoreCase(value.trim())
                ? "any" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean backupTooLong(String value) { return value != null && value.length() > 255; }

    @GetMapping("/watches")
    public ResponseEntity<List<WatchRule>> listWatches(
            @RequestHeader(value = "X-Admin-Token", required = false) String suppliedToken,
            @RequestParam(required = false) String chatId
    ) {
        if (!isAuthorized(adminToken, suppliedToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (chatId != null && !chatId.isBlank()) {
            return ResponseEntity.ok(repository.findByChatIdAndAcknowledgedFalse(chatId));
        }
        return ResponseEntity.ok(repository.findByAcknowledgedFalse());
    }

    @PostMapping("/watches/{id}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeWatch(
            @RequestHeader(value = "X-Admin-Token", required = false) String suppliedToken,
            @PathVariable Long id
    ) {
        if (!isAuthorized(adminToken, suppliedToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return repository.findById(id).map(rule -> {
            rule.setAcknowledged(true);
            repository.save(rule);
            return ResponseEntity.ok(Map.<String, Object>of("acknowledgedId", id, "status", "STOPPED"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/watches/{id}/resume")
    public ResponseEntity<Map<String, Object>> resumeWatch(
            @RequestHeader(value = "X-Admin-Token", required = false) String suppliedToken,
            @PathVariable Long id
    ) {
        if (!isAuthorized(adminToken, suppliedToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return repository.findById(id).map(rule -> {
            rule.setAcknowledged(false);
            rule.setAlertCount(0);
            rule.setLastAlertedAt(null);
            rule.setSnoozedUntil(null);
            repository.save(rule);
            return ResponseEntity.ok(Map.<String, Object>of("resumedId", id, "status", "ACTIVE"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/watches/{id}/snooze")
    public ResponseEntity<Map<String, Object>> snoozeWatch(
            @RequestHeader(value = "X-Admin-Token", required = false) String suppliedToken,
            @PathVariable Long id,
            @RequestParam(defaultValue = "15") int minutes
    ) {
        if (!isAuthorized(adminToken, suppliedToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        int safeMinutes = Math.max(1, Math.min(minutes, 1440));
        return repository.findById(id).map(rule -> {
            rule.setAcknowledged(false);
            rule.setSnoozedUntil(Instant.now().plusSeconds(safeMinutes * 60L));
            repository.save(rule);
            return ResponseEntity.ok(Map.<String, Object>of("snoozedId", id, "minutes", safeMinutes, "status", "SNOOZED"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/watches/{id}")
    public ResponseEntity<Map<String, Object>> deleteWatch(
            @RequestHeader(value = "X-Admin-Token", required = false) String suppliedToken,
            @PathVariable Long id
    ) {
        if (!isAuthorized(adminToken, suppliedToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(id);
        return ResponseEntity.ok(Map.of("deletedId", id));
    }

    @PostMapping("/watches/check-now")
    public ResponseEntity<Map<String, Object>> triggerManualCheck(
            @RequestHeader(value = "X-Admin-Token", required = false) String suppliedToken
    ) {
        if (!isAuthorized(adminToken, suppliedToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        int triggered = scheduler.pollActiveWatches();
        return ResponseEntity.ok(Map.of("status", triggered < 0 ? "BUSY" : "COMPLETED", "notificationsSent", Math.max(0, triggered)));
    }

    @PostMapping("/telegram/webhook")
    public ResponseEntity<Map<String, Boolean>> handleTelegramWebhook(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String suppliedSecret,
            @RequestBody JsonNode update
    ) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!isAuthorized(webhookSecret, suppliedSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        telegramCommandService.processTelegramUpdate(update);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private String validate(CreateWatchRequest req) {
        if (req == null) return "Request body is required";
        if (req.chatId() == null || !req.chatId().matches("-?\\d{1,20}")) return "chatId must be a numeric Telegram chat ID";
        if (req.movieName() == null || req.movieName().isBlank() || req.movieName().length() > 120) return "movieName must be 1 to 120 characters";
        if (req.city() != null && !req.city().isBlank() && !"vizag".equalsIgnoreCase(req.city().trim())) return "Only Vizag is supported";
        if (req.theatreName() == null || req.theatreName().isBlank() || "ANY".equalsIgnoreCase(req.theatreName().trim())) return "theatreName is required";
        if (req.screenFilter() == null || req.screenFilter().isBlank() || "ANY".equalsIgnoreCase(req.screenFilter().trim())) return "screenFilter is required";
        if (!validSpecificDate(req.targetDate())) return "targetDate must be a valid YYYY-MM-DD date";
        if (req.theatreName().length() > 255 || tooLong(req.screenFilter())) return "Theatre must not exceed 255 characters and screen must not exceed 100 characters";
        if (req.movieUrl() != null && !req.movieUrl().isBlank() && !scraperService.isAllowedMoviePageUrl(req.movieUrl())) {
            return "movieUrl must be an HTTPS movie page on www.district.in";
        }
        return null;
    }

    private boolean validDateFilter(String value) {
        if (value == null || value.isBlank() || "ANY".equalsIgnoreCase(value.trim())) return true;
        try {
            return LocalDate.parse(value.trim()).toString().equals(value.trim());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private boolean validSpecificDate(String value) {
        return value != null && !value.isBlank() && !"ANY".equalsIgnoreCase(value.trim()) && validDateFilter(value);
    }

    private boolean tooLong(String value) {
        return value != null && value.length() > 100;
    }

    private boolean isAuthorized(String expected, String supplied) {
        return expected != null && !expected.isBlank()
                && supplied != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
