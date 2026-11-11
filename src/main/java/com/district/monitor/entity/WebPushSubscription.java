package com.district.monitor.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "web_push_subscriptions", indexes = @Index(name = "idx_push_chat", columnList = "chatId"),
        uniqueConstraints = @UniqueConstraint(name = "uk_push_endpoint", columnNames = "endpoint"))
public class WebPushSubscription {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 64) private String chatId;
    @Column(nullable = false, length = 2048) private String endpoint;
    @Column(nullable = false, length = 512) private String p256dh;
    @Column(nullable = false, length = 512) private String auth;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    public WebPushSubscription() {}
    public WebPushSubscription(String chatId, String endpoint, String p256dh, String auth) {
        this.chatId = chatId; this.endpoint = endpoint; this.p256dh = p256dh; this.auth = auth;
    }
    public Long getId() { return id; }
    public String getChatId() { return chatId; }
    public String getEndpoint() { return endpoint; }
    public String getP256dh() { return p256dh; }
    public String getAuth() { return auth; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void refresh(String p256dh, String auth) { this.p256dh = p256dh; this.auth = auth; updatedAt = Instant.now(); }
}
