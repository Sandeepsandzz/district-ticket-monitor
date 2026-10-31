package com.district.monitor.controller;

import com.district.monitor.dto.CreateWatchRequest;
import com.district.monitor.entity.WatchRule;
import com.district.monitor.repository.WatchRuleRepository;
import com.district.monitor.scheduler.TicketWatchScheduler;
import com.district.monitor.service.DistrictScraperService;
import com.district.monitor.service.TelegramCommandService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WatchAndTelegramController {

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
                req.languageFilter(),
                req.formatFilter(),
                req.movieUrl()
        ));
        return ResponseEntity.ok(saved);
    }

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
        return ResponseEntity.ok(Map.of("status", "COMPLETED", "notificationsSent", triggered));
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
        if (!validDateFilter(req.targetDate())) return "targetDate must be ANY or a valid YYYY-MM-DD date";
        if (tooLong(req.theatreName()) || tooLong(req.screenFilter()) || tooLong(req.languageFilter()) || tooLong(req.formatFilter())) {
            return "Filter values must not exceed 100 characters";
        }
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

    private boolean tooLong(String value) {
        return value != null && value.length() > 100;
    }

    private boolean isAuthorized(String expected, String supplied) {
        return expected != null && !expected.isBlank()
                && supplied != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
