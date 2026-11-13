package com.district.monitor.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "web_sessions", indexes = @Index(name = "idx_web_session_chat", columnList = "chatId"),
        uniqueConstraints = @UniqueConstraint(name = "uk_web_session_token", columnNames = "tokenHash"))
public class WebSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 64) private String chatId;
    @Column(nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant lastSeenAt = Instant.now();
    @Column(nullable = false) private Instant expiresAt;

    public WebSession() {}
    public WebSession(String chatId, String tokenHash, Instant expiresAt) {
        this.chatId = chatId; this.tokenHash = tokenHash; this.expiresAt = expiresAt;
    }
    public Long getId() { return id; }
    public String getChatId() { return chatId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void touch(Instant now) { lastSeenAt = now; }
}
