package com.district.monitor.service;

import com.district.monitor.entity.WatchRule;

/**
 * Pluggable interface for Multi-Channel Notifications (Telegram, Webhook/Discord/Slack/WhatsApp, Email).
 */
public interface NotificationChannel {
    String channelName();
    boolean isEnabled();
    default boolean appliesTo(WatchRule rule) { return isEnabled(); }
    void sendTicketAlert(WatchRule rule, String htmlMessage, String plainTextMessage, String bookingUrl);
}
