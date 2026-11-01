package com.district.monitor.service;

import com.district.monitor.entity.WatchRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/** Optional Meta Cloud API channel, scoped to a single Telegram chat. */
@Service
public class WhatsAppNotificationChannel implements NotificationChannel {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppNotificationChannel.class);
    private final boolean enabled;
    private final String accessToken, version, phoneNumberId, recipient, ownerChatId, template, language;
    private final RestClient client;

    @Autowired
    public WhatsAppNotificationChannel(
            @Value("${notifications.whatsapp.enabled:false}") boolean enabled,
            @Value("${notifications.whatsapp.access-token:}") String accessToken,
            @Value("${notifications.whatsapp.api-version:}") String version,
            @Value("${notifications.whatsapp.phone-number-id:}") String phoneNumberId,
            @Value("${notifications.whatsapp.recipient:}") String recipient,
            @Value("${notifications.whatsapp.owner-chat-id:}") String ownerChatId,
            @Value("${notifications.whatsapp.template:}") String template,
            @Value("${notifications.whatsapp.language:en_US}") String language) {
        this(enabled, accessToken, version, phoneNumberId, recipient, ownerChatId, template, language, RestClient.builder());
    }

    WhatsAppNotificationChannel(boolean enabled, String accessToken, String version, String phoneNumberId,
                                String recipient, String ownerChatId, String template, String language, RestClient.Builder builder) {
        this.enabled = enabled;
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.version = version == null ? "" : version.trim();
        this.phoneNumberId = phoneNumberId == null ? "" : phoneNumberId.trim();
        this.recipient = recipient == null ? "" : recipient.trim();
        this.ownerChatId = ownerChatId == null ? "" : ownerChatId.trim();
        this.template = template == null ? "" : template.trim();
        this.language = language == null ? "" : language.trim();
        this.client = builder.baseUrl("https://graph.facebook.com").build();
    }

    @Override public String channelName() { return "WhatsApp"; }

    @Override public boolean isEnabled() {
        return enabled && !accessToken.isBlank() && version.matches("v\\d+\\.\\d+")
                && phoneNumberId.matches("\\d+") && recipient.matches("[1-9]\\d{6,14}")
                && ownerChatId.matches("[1-9]\\d{0,19}")
                && template.matches("[a-z0-9_]{1,512}") && language.matches("[a-zA-Z_]{2,20}");
    }

    /** Only Meta's pre-approved, parameterless test template is sent by this command. */
    public boolean sendHelloWorldTest(String chatId) {
        if (!isEnabled() || !ownerChatId.equals(chatId) || !"hello_world".equals(template)) return false;
        WatchRule testRule = new WatchRule(chatId, "vizag", "Test", "Test", "Test", "ANY", "ANY", "ANY", null);
        sendTicketAlert(testRule, "", "", "");
        return true;
    }

    @Override public void sendTicketAlert(WatchRule rule, String htmlMessage, String plainTextMessage, String bookingUrl) {
        if (!isEnabled() || rule == null || !ownerChatId.equals(rule.getChatId())) return;
        Map<String, Object> templateBody;
        if ("hello_world".equals(template)) {
            // Meta's pre-approved test message has no variables and contains no ticket details.
            templateBody = Map.of("name", template, "language", Map.of("code", language));
        } else {
            List<Map<String, String>> parameters = List.of(
                    parameter(rule.getMovieName()), parameter(rule.getTheatreName()), parameter(rule.getScreenFilter()),
                    parameter(rule.getTargetDate()), parameter(bookingUrl));
            templateBody = Map.of("name", template, "language", Map.of("code", language),
                    "components", List.of(Map.of("type", "body", "parameters", parameters)));
        }
        Map<String, Object> payload = Map.of("messaging_product", "whatsapp", "recipient_type", "individual",
                "to", recipient, "type", "template", "template", templateBody);
        try {
            client.post().uri("/{version}/{phoneId}/messages", version, phoneNumberId)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().toBodilessEntity();
            log.info("WhatsApp request accepted for watchId={}", rule.getId());
        } catch (RestClientException e) {
            // Do not log response bodies, credentials or phone numbers.
            log.warn("WhatsApp request failed for watchId={}; check Meta configuration and template approval", rule.getId());
            throw new IllegalStateException("WhatsApp request failed");
        }
    }

    private Map<String, String> parameter(String value) {
        String safe = value == null || value.isBlank() ? "Not specified" : value.replaceAll("\\s+", " ").trim();
        return Map.of("type", "text", "text", safe);
    }
}
