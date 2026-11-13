package com.district.monitor.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "web_user_accounts", uniqueConstraints = @UniqueConstraint(name = "uk_web_account_chat", columnNames = "chatId"))
public class WebUserAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 64) private String chatId;
    @Column(nullable = false, length = 255) private String displayName = "Movie fan";
    @Column(nullable = false) private boolean enabled = true;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant lastSeenAt = Instant.now();

    public WebUserAccount() {}
    public WebUserAccount(String chatId, String displayName) {
        this.chatId = chatId;
        this.displayName = displayName == null || displayName.isBlank() ? "Movie fan" : displayName.trim();
    }
    public Long getId() { return id; }
    public String getChatId() { return chatId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value == null || value.isBlank() ? "Movie fan" : value.trim(); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void touch(Instant now) { lastSeenAt = now; }
}
