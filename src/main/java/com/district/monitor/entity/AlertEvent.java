package com.district.monitor.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "alert_events", indexes = {@Index(name = "idx_alert_chat_time", columnList = "chatId,createdAt")})
public class AlertEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long watchId;
    @Column(nullable = false) private String chatId;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private boolean counted;
    @Column(length = 4000) private String htmlMessage;
    @Column(length = 4000) private String plainMessage;
    @Column(length = 2000) private String bookingUrl;
    @Column(length = 1000) private String showtimes;
    @Column(length = 120) private String movieName;
    @Column(length = 255) private String theatreName;
    @Column(length = 255) private String screenName;
    public AlertEvent() {}
    public AlertEvent(WatchRule rule, String html, String plain, String url, String showtimes) {
        watchId=rule.getId(); chatId=rule.getChatId(); htmlMessage=html; plainMessage=plain; bookingUrl=url; this.showtimes=showtimes;
        movieName=rule.getMovieName(); theatreName=rule.getTheatreName(); screenName=rule.getScreenFilter();
    }
    public Long getId() { return id; }
    public Long getWatchId() { return watchId; }
    public String getChatId() { return chatId; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isCounted() { return counted; }
    public void setCounted(boolean counted) { this.counted=counted; }
    public String getHtmlMessage() { return htmlMessage; }
    public String getPlainMessage() { return plainMessage; }
    public String getBookingUrl() { return bookingUrl; }
    public String getShowtimes() { return showtimes; }
    public String getMovieName() { return movieName; }
    public String getTheatreName() { return theatreName; }
    public String getScreenName() { return screenName; }
}
