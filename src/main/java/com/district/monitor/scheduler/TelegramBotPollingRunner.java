package com.district.monitor.scheduler;

import com.district.monitor.service.TelegramCommandService;
import com.district.monitor.service.TelegramNotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Local/Single-instance Telegram getUpdates poller.
 * Processes both chat messages and interactive inline button clicks (callback_query).
 */
@Component
@ConditionalOnProperty(name = "telegram.polling-enabled", havingValue = "true", matchIfMissing = true)
public class TelegramBotPollingRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotPollingRunner.class);

    private final String botToken;
    private final TelegramCommandService commandService;
    private final RestClient restClient;
    private final TelegramNotificationService telegramService;
    private long lastUpdateId = 0;

    public TelegramBotPollingRunner(
            @Value("${telegram.bot-token:}") String botToken,
            TelegramCommandService commandService,
            TelegramNotificationService telegramService
    ) {
        this.botToken = botToken;
        this.commandService = commandService;
        this.telegramService = telegramService;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.telegram.org")
                .build();
    }

    @PostConstruct
    public void init() {
        if (botToken != null && !botToken.isBlank()) {
            clearWebhookIfAny();
            telegramService.registerBotCommands();
        }
    }

    private void clearWebhookIfAny() {
        try {
            restClient.post()
                    .uri("/bot{token}/deleteWebhook", botToken)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Telegram webhook cleared successfully for polling mode.");
        } catch (Exception e) {
            log.warn("Could not clear Telegram webhook: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${telegram.polling-interval-ms:2500}")
    public void pollTelegramUpdates() {
        if (botToken == null || botToken.isBlank()) {
            return;
        }

        try {
            JsonNode response = restClient.get()
                    .uri("/bot{token}/getUpdates?offset={offset}&timeout=2", botToken, lastUpdateId + 1)
                    .retrieve()
                    .body(JsonNode.class);

            if (response != null && response.path("ok").asBoolean() && response.has("result")) {
                for (JsonNode update : response.get("result")) {
                    long updateId = update.path("update_id").asLong();
                    if (updateId > lastUpdateId) {
                        lastUpdateId = updateId;
                    }
                    commandService.processTelegramUpdate(update);
                }
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("409")) {
                log.warn("Telegram returned 409 Conflict (webhook active). Attempting auto-clear...");
                clearWebhookIfAny();
            } else {
                log.debug("Telegram polling cycle: {}", e.getMessage());
            }
        }
    }
}
