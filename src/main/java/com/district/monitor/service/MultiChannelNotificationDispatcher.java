package com.district.monitor.service;

import com.district.monitor.entity.WatchRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fan-out dispatcher that sends ticket alerts across all active NotificationChannels
 * (Telegram + Webhook + Email).
 */
@Service
public class MultiChannelNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MultiChannelNotificationDispatcher.class);

    private final List<NotificationChannel> channels;

    public MultiChannelNotificationDispatcher(List<NotificationChannel> channels) {
        this.channels = channels;
    }

    public void dispatchAlert(WatchRule rule, String htmlMessage, String plainTextMessage, String bookingUrl) {
        for (NotificationChannel channel : channels) {
            if (channel.isEnabled()) {
                log.info("Dispatching alert for watchId={} via channel={}", rule.getId(), channel.channelName());
                try {
                    channel.sendTicketAlert(rule, htmlMessage, plainTextMessage, bookingUrl);
                } catch (RuntimeException e) {
                    log.error("Notification channel {} failed for watchId={}: {}",
                            channel.channelName(), rule.getId(), e.getMessage());
                }
            }
        }
    }
}
