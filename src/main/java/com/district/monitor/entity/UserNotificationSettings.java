package com.district.monitor.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "notification_settings", uniqueConstraints = @UniqueConstraint(name = "uk_notification_chat", columnNames = "chatId"))
public class UserNotificationSettings {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 64) private String chatId;
    @Column(length = 320) private String emailAddress;
    @Column(nullable = false) private boolean emailEnabled = false;
    @Column(nullable = false) private boolean pushEnabled = false;
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    public UserNotificationSettings() {}
    public UserNotificationSettings(String chatId) { this.chatId = chatId; }
    public Long getId() { return id; }
    public String getChatId() { return chatId; }
    public String getEmailAddress() { return emailAddress; }
    public void setEmailAddress(String value) { emailAddress = value == null || value.isBlank() ? null : value.trim(); updatedAt = Instant.now(); }
    public boolean isEmailEnabled() { return emailEnabled; }
    public void setEmailEnabled(boolean value) { emailEnabled = value; updatedAt = Instant.now(); }
    public boolean isPushEnabled() { return pushEnabled; }
    public void setPushEnabled(boolean value) { pushEnabled = value; updatedAt = Instant.now(); }
    public Instant getUpdatedAt() { return updatedAt; }
}
