package com.district.monitor.scheduler;

import com.district.monitor.dto.AvailabilityCheckResult;
import com.district.monitor.entity.WatchRule;
import com.district.monitor.repository.WatchRuleRepository;
import com.district.monitor.service.DistrictScraperService;
import com.district.monitor.service.MultiChannelNotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class TicketWatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(TicketWatchScheduler.class);

    private final WatchRuleRepository repository;
    private final DistrictScraperService scraperService;
    private final MultiChannelNotificationDispatcher notificationDispatcher;
    private final int repeatIntervalMinutes;
    private final int maxAlertsPerWatch;
    private final ReentrantLock pollLock = new ReentrantLock();

    public TicketWatchScheduler(
            WatchRuleRepository repository,
            DistrictScraperService scraperService,
            MultiChannelNotificationDispatcher notificationDispatcher,
            @Value("${notifications.persistent.repeat-interval-minutes:3}") int repeatIntervalMinutes,
            @Value("${notifications.persistent.max-alerts-per-watch:15}") int maxAlertsPerWatch
    ) {
        this.repository = repository;
        this.scraperService = scraperService;
        this.notificationDispatcher = notificationDispatcher;
        this.repeatIntervalMinutes = Math.max(1, repeatIntervalMinutes);
        this.maxAlertsPerWatch = Math.max(1, maxAlertsPerWatch);
    }

    @Scheduled(fixedDelayString = "${district.poll-interval-ms:120000}")
    public int pollActiveWatches() {
        return poll(repository.findByAcknowledgedFalse());
    }

    public int pollActiveWatchesForChat(String chatId) {
        if (chatId == null || chatId.isBlank()) return 0;
        return poll(repository.findByChatIdAndAcknowledgedFalse(chatId));
    }

    private int poll(List<WatchRule> activeWatches) {
        if (!pollLock.tryLock()) {
            log.debug("Skipping ticket poll because another poll is already running.");
            return 0;
        }
        try {
            return pollActiveWatchesLocked(activeWatches);
        } finally {
            pollLock.unlock();
        }
    }

    private int pollActiveWatchesLocked(List<WatchRule> activeWatches) {
        if (activeWatches.isEmpty()) {
            return 0;
        }

        log.info("Polling District for {} unacknowledged watch rule(s)...", activeWatches.size());
        int notificationsSent = 0;
        Instant now = Instant.now();

        for (WatchRule rule : activeWatches) {
            // 1. Respect Snooze window if user tapped "Snooze 15m"
            if (rule.getSnoozedUntil() != null && now.isBefore(rule.getSnoozedUntil())) {
                continue;
            }

            // 2. Respect Persistent Repeat Cooldown (avoid spamming faster than repeatIntervalMinutes)
            if (rule.getLastAlertedAt() != null
                    && now.isBefore(rule.getLastAlertedAt().plus(repeatIntervalMinutes, ChronoUnit.MINUTES))) {
                continue;
            }

            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(400, 1500));

                AvailabilityCheckResult result = scraperService.checkWatchRule(rule);

            // Cache discovered movie URL if valid movie page (not seat-layout or root /movies)
            if (rule.getMovieUrl() == null && result.bookingUrl() != null && result.bookingUrl().contains("-movie-tickets-")) {
                rule.setMovieUrl(result.bookingUrl());
                repository.save(rule);
            }

            if (result.isAvailable()) {
                int currentAttempt = rule.getAlertCount() + 1;
                String theatresStr = result.matchedTheatres().isEmpty()
                        ? (rule.getTheatreName() != null ? rule.getTheatreName() : "Open in City")
                        : String.join(", ", result.matchedTheatres().stream().limit(3).toList());

                String screensStr = (result.matchedScreens() != null && !result.matchedScreens().isEmpty())
                        ? String.join(", ", result.matchedScreens().stream().distinct().limit(4).toList())
                        : ((rule.getScreenFilter() != null && !rule.getScreenFilter().equalsIgnoreCase("ANY"))
                            ? rule.getScreenFilter() : "Any Screen");

                String showtimesStr = result.matchedShowtimes().isEmpty()
                        ? "Multiple Showtimes"
                        : String.join(", ", result.matchedShowtimes().stream().limit(5).toList());

                String htmlMsg = """
                        🚨 <b>TICKETS AVAILABLE ON DISTRICT! (Alert #%d/%d)</b>

                        🎬 <b>Movie:</b> %s
                        🏙 <b>City:</b> %s
                        🏛 <b>Theatres:</b> %s
                        🖥 <b>Screen:</b> %s
                        🕒 <b>Showtimes:</b> %s
                        📅 <b>Date:</b> %s

                        <i>Tap <b>Acknowledge &amp; Stop Alerts</b> below once you've seen this!</i>
                        """.formatted(
                        currentAttempt,
                        maxAlertsPerWatch,
                        escapeHtml(rule.getMovieName()),
                        escapeHtml(rule.getCity().toUpperCase()),
                        escapeHtml(theatresStr),
                        escapeHtml(screensStr),
                        escapeHtml(showtimesStr),
                        escapeHtml(rule.getTargetDate())
                );

                String plainMsg = "TICKETS AVAILABLE: %s in %s | Theatre: %s | Screen: %s | Showtimes: %s | Date: %s"
                        .formatted(rule.getMovieName(), rule.getCity().toUpperCase(), theatresStr, screensStr, showtimesStr,
                                rule.getTargetDate());

                notificationDispatcher.dispatchAlert(rule, htmlMsg, plainMsg, result.bookingUrl());

                rule.setAlertCount(currentAttempt);
                rule.setLastAlertedAt(now);
                if (currentAttempt >= maxAlertsPerWatch) {
                    rule.setAcknowledged(true);
                }
                repository.save(rule);
                notificationsSent++;
            }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Ticket poll failed for watch #{} (movie={}): {}. Continuing with remaining watches.",
                        rule.getId(), rule.getMovieName(), e.getMessage());
            }
        }
        return notificationsSent;
    }

    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
