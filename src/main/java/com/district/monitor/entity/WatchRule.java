package com.district.monitor.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "watch_rules")
public class WatchRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String chatId;

    @Column(nullable = false)
    private String city; // e.g., "vizag", "gajuwaka", "hyderabad"

    @Column(nullable = false)
    private String movieName; // e.g., "Jailer 2"

    @Column
    private String theatreName; // null or "ANY" = any theatre in city

    @Column(nullable = false)
    private String screenFilter = "ANY"; // e.g., "Screen 1", "Audi 2", "ANY"

    @Column(nullable = false)
    private String targetDate = "ANY"; // e.g., "2026-10-30" or "ANY"

    @Column(nullable = false)
    private String languageFilter = "ANY"; // e.g., "Telugu", "Hindi", "English", "ANY"

    @Column(nullable = false)
    private String formatFilter = "ANY"; // e.g., "2D", "3D", "IMAX", "4DX", "Dolby Atmos", "ANY"

    @Column
    private String movieUrl; // Cached District movie URL once discovered

    @Column(precision = 10, scale = 2)
    private BigDecimal maxPrice;

    @Column
    private Integer minAvailableSeats = 0;

    /**
     * Persistent notification tracking:
     * Alerts repeat until acknowledged == true (or alertCount >= maxAlerts)
     */
    @Column(nullable = false)
    private boolean acknowledged = false;

    @Column(nullable = false)
    private int alertCount = 0;

    @Column
    private Instant lastAlertedAt;

    @Column
    private Instant snoozedUntil;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column private Instant lastCheckedAt;
    @Column(length = 30) private String lastCheckStatus;
    @Column(length = 500) private String lastCheckMessage;
    // Null for existing rows means the original repeating behavior.
    @Column private Boolean repeatAlerts;

    /** Hash of the last availability snapshot that generated an alert. */
    @Column(length = 64) private String lastAvailabilityFingerprint;

    // Nullable keeps Hibernate's update path safe for existing PostgreSQL rows.
    @Column
    private Boolean archived = false;

    @Column
    private Instant archivedAt;

    public WatchRule() {
    }

    public WatchRule(
            String chatId,
            String city,
            String movieName,
            String theatreName,
            String screenFilter,
            String targetDate,
            String languageFilter,
            String formatFilter,
            String movieUrl,
            BigDecimal maxPrice,
            Integer minAvailableSeats
    ) {
        this.chatId = chatId;
        this.city = city != null ? city.trim().toLowerCase() : "vizag";
        this.movieName = movieName != null ? movieName.trim() : "";
        this.theatreName = normalizeOptional(theatreName);
        this.screenFilter = normalizeFilter(screenFilter);
        this.targetDate = normalizeFilter(targetDate);
        this.languageFilter = normalizeFilter(languageFilter);
        this.formatFilter = normalizeFilter(formatFilter);
        this.movieUrl = normalizeOptional(movieUrl);
        this.maxPrice = normalizePrice(maxPrice);
        this.minAvailableSeats = minAvailableSeats == null ? 0 : Math.max(0, minAvailableSeats);
        this.acknowledged = false;
        this.alertCount = 0;
        this.createdAt = Instant.now();
    }

    public WatchRule(
            String chatId,
            String city,
            String movieName,
            String theatreName,
            String targetDate,
            String languageFilter,
            String formatFilter,
            String movieUrl
    ) {
        this(chatId, city, movieName, theatreName, "ANY", targetDate, languageFilter, formatFilter, movieUrl);
    }

    public WatchRule(
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
        this(chatId, city, movieName, theatreName, screenFilter, targetDate, languageFilter, formatFilter,
                movieUrl, null, 0);
    }

    private BigDecimal normalizePrice(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private String normalizeOptional(String val) {
        if (val == null || val.isBlank() || "ANY".equalsIgnoreCase(val.trim())) {
            return null;
        }
        return val.trim();
    }

    private String normalizeFilter(String val) {
        if (val == null || val.isBlank()) {
            return "ANY";
        }
        return val.trim();
    }

    public Long getId() { return id; }
    public String getChatId() { return chatId; }
    public String getCity() { return city; }
    public String getMovieName() { return movieName; }
    public String getTheatreName() { return theatreName; }
    public String getScreenFilter() { return screenFilter; }
    public void setScreenFilter(String screenFilter) { this.screenFilter = screenFilter; }
    public String getTargetDate() { return targetDate; }
    public String getLanguageFilter() { return languageFilter; }
    public String getFormatFilter() { return formatFilter; }
    public String getMovieUrl() { return movieUrl; }
    public void setMovieUrl(String movieUrl) { this.movieUrl = movieUrl; }
    public BigDecimal getMaxPrice() { return maxPrice; }
    public void setMaxPrice(BigDecimal maxPrice) { this.maxPrice = normalizePrice(maxPrice); }
    public int getMinAvailableSeats() { return minAvailableSeats == null ? 0 : minAvailableSeats; }
    public void setMinAvailableSeats(int minAvailableSeats) { this.minAvailableSeats = Math.max(0, minAvailableSeats); }
    public boolean isAcknowledged() { return acknowledged; }
    public void setAcknowledged(boolean acknowledged) { this.acknowledged = acknowledged; }
    public int getAlertCount() { return alertCount; }
    public void setAlertCount(int alertCount) { this.alertCount = alertCount; }
    public Instant getLastAlertedAt() { return lastAlertedAt; }
    public void setLastAlertedAt(Instant lastAlertedAt) { this.lastAlertedAt = lastAlertedAt; }
    public Instant getSnoozedUntil() { return snoozedUntil; }
    public void setSnoozedUntil(Instant snoozedUntil) { this.snoozedUntil = snoozedUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { if (value != null) createdAt = value; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant value) { lastCheckedAt = value; }
    public String getLastCheckStatus() { return lastCheckStatus; }
    public void setLastCheckStatus(String value) { lastCheckStatus = value; }
    public String getLastCheckMessage() { return lastCheckMessage; }
    public void setLastCheckMessage(String value) { lastCheckMessage = value; }
    public boolean isRepeatAlerts() { return repeatAlerts == null || repeatAlerts; }
    public void setRepeatAlerts(boolean value) { repeatAlerts = value; }
    public String getLastAvailabilityFingerprint() { return lastAvailabilityFingerprint; }
    public void setLastAvailabilityFingerprint(String value) { lastAvailabilityFingerprint = value; }
    public boolean isArchived() { return Boolean.TRUE.equals(archived); }
    public void setArchived(boolean value) { archived = value; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant value) { archivedAt = value; }
}
