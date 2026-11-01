package com.district.monitor.service;

import com.district.monitor.entity.WatchRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TelegramNotificationService implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    private final String botToken;
    private final RestClient restClient;

    public TelegramNotificationService(@Value("${telegram.bot-token:}") String botToken) {
        this.botToken = botToken;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.telegram.org")
                .build();
    }

    @Override
    public String channelName() {
        return "Telegram";
    }

    @Override
    public boolean isEnabled() {
        return botToken != null && !botToken.isBlank();
    }

    @Override
    public void sendTicketAlert(WatchRule rule, String htmlMessage, String plainTextMessage, String bookingUrl) {
        List<List<Map<String, Object>>> inlineKeyboard = new java.util.ArrayList<>();
        if (bookingUrl != null && bookingUrl.startsWith("https://www.district.in/movies/")) {
            inlineKeyboard.add(List.of(Map.of("text", "🎟 Book Now on District", "url", bookingUrl)));
        }
        inlineKeyboard.add(List.of(
                Map.of("text", "🛑 Acknowledge & Stop Alerts", "callback_data", "ack:" + rule.getId()),
                Map.of("text", "⏸ Snooze 15m", "callback_data", "snooze:" + rule.getId())
        ));
        sendWithInlineKeyboard(rule.getChatId(), htmlMessage, inlineKeyboard);
    }

    public void sendMessage(String chatId, String htmlMessage) {
        sendWithInlineKeyboard(chatId, htmlMessage, null);
    }

    public void registerBotCommands() {
        if (!isEnabled()) return;
        List<Map<String, String>> cmds = List.of(
                Map.of("command", "menu", "description", "🍿 Open Vizag Main Menu"),
                Map.of("command", "new", "description", "➕ New Movie Alert"),
                Map.of("command", "link", "description", "🔐 Link the website"),
                Map.of("command", "list", "description", "📋 My Active Alerts"),
                Map.of("command", "mywatches", "description", "📋 List all my watches"),
                Map.of("command", "pause", "description", "⏸ Pause a watch by ID"),
                Map.of("command", "resume", "description", "▶️ Resume a watch by ID"),
                Map.of("command", "snooze", "description", "⏸ Snooze a watch"),
                Map.of("command", "delete", "description", "🗑 Delete a watch by ID"),
                Map.of("command", "check", "description", "🔄 Check Tickets Now"),
                Map.of("command", "clear", "description", "🗑 Clear All Alerts"),
                Map.of("command", "help", "description", "❓ Show command help")
        );
        try {
            restClient.post()
                    .uri("/bot{token}/setMyCommands", botToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("commands", cmds))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Registered Telegram persistent bot commands menu successfully.");
        } catch (Exception e) {
            log.warn("Could not register bot commands menu: {}", e.getMessage());
        }
    }

    public void sendWithPersistentReplyKeyboard(
            String chatId,
            String htmlMessage,
            List<List<Map<String, Object>>> keyboardRows
    ) {
        if (!isEnabled()) {
            return;
        }

        Map<String, Object> replyMarkup = new HashMap<>();
        replyMarkup.put("keyboard", keyboardRows);
        replyMarkup.put("resize_keyboard", true);
        replyMarkup.put("is_persistent", true);

        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", htmlMessage);
        payload.put("parse_mode", "HTML");
        payload.put("reply_markup", replyMarkup);

        try {
            restClient.post()
                    .uri("/bot{token}/sendMessage", botToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to send persistent keyboard message to chatId={}: {}", chatId, e.getMessage());
        }
    }

    public void sendWithInlineKeyboard(
            String chatId,
            String htmlMessage,
            List<List<Map<String, Object>>> inlineKeyboardRows
    ) {
        if (!isEnabled()) {
            log.debug("Telegram delivery skipped because TELEGRAM_BOT_TOKEN is not configured.");
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", htmlMessage);
        payload.put("parse_mode", "HTML");
        payload.put("disable_web_page_preview", false);

        if (inlineKeyboardRows != null && !inlineKeyboardRows.isEmpty()) {
            payload.put("reply_markup", Map.of("inline_keyboard", inlineKeyboardRows));
        }

        try {
            restClient.post()
                    .uri("/bot{token}/sendMessage", botToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to send Telegram message to chatId={}: {}", chatId, e.getMessage());
        }
    }

    public void editMessageReplyMarkup(
            String chatId,
            long messageId,
            List<List<Map<String, Object>>> inlineKeyboardRows
    ) {
        if (!isEnabled()) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("message_id", messageId);
        if (inlineKeyboardRows != null) {
            payload.put("reply_markup", Map.of("inline_keyboard", inlineKeyboardRows));
        } else {
            payload.put("reply_markup", Map.of("inline_keyboard", List.of()));
        }

        try {
            restClient.post()
                    .uri("/bot{token}/editMessageReplyMarkup", botToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Could not edit reply markup for msgId={}: {}", messageId, e.getMessage());
        }
    }

    public void answerCallbackQuery(String callbackQueryId, String toastText) {
        if (!isEnabled() || callbackQueryId == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("callback_query_id", callbackQueryId);
        if (toastText != null && !toastText.isBlank()) {
            payload.put("text", toastText);
        }
        try {
            restClient.post()
                    .uri("/bot{token}/answerCallbackQuery", botToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.debug("Failed to answer callback query: {}", e.getMessage());
        }
    }
}
