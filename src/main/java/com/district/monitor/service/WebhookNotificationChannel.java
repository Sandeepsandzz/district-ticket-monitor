package com.district.monitor.service;

import com.district.monitor.entity.WatchRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Multi-Channel Webhook Notifier (supports Discord, Slack, ntfy.sh, custom WhatsApp/Push bridges).
 */
@Service
public class WebhookNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotificationChannel.class);

    private final boolean enabled;
    private final String webhookUrl;
    private final RestClient restClient;

    public WebhookNotificationChannel(
            @Value("${notifications.webhook.enabled:false}") boolean enabled,
            @Value("${notifications.webhook.url:}") String webhookUrl
    ) {
        this.enabled = enabled;
        this.webhookUrl = webhookUrl;
        this.restClient = RestClient.create();
    }

    @Override
    public String channelName() {
        return "Webhook";
    }

    @Override
    public boolean isEnabled() {
        return enabled && webhookUrl != null && !webhookUrl.isBlank();
    }

    @Override
    public void sendTicketAlert(WatchRule rule, String htmlMessage, String plainTextMessage, String bookingUrl) {
        if (!isEnabled()) {
            return;
        }
        try {
            Map<String, Object> body = Map.of(
                    "content", plainTextMessage + "\nBook now: " + bookingUrl,
                    "text", plainTextMessage + "\nBook now: " + bookingUrl,
                    "movie", rule.getMovieName(),
                    "city", rule.getCity(),
                    "bookingUrl", bookingUrl
            );
            restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Dispatched Webhook notification for watchId={}", rule.getId());
        } catch (Exception e) {
            throw new IllegalStateException("Webhook request failed");
        }
    }
}
