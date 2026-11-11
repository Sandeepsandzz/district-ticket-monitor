package com.district.monitor.service;

import com.district.monitor.entity.UserNotificationSettings;
import com.district.monitor.entity.WatchRule;
import com.district.monitor.entity.WebPushSubscription;
import com.district.monitor.repository.UserNotificationSettingsRepository;
import com.district.monitor.repository.WebPushSubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.Security;
import java.util.List;
import java.util.Map;

/** VAPID-authenticated browser notifications. Subscriptions are independent of web sessions. */
@Service
public class WebPushNotificationChannel implements NotificationChannel {
    private final UserNotificationSettingsRepository settings;
    private final WebPushSubscriptionRepository subscriptions;
    private final ObjectMapper mapper;
    private final String publicKey;
    private final String privateKey;
    private final String subject;

    public WebPushNotificationChannel(UserNotificationSettingsRepository settings,
                                      WebPushSubscriptionRepository subscriptions,
                                      ObjectMapper mapper,
                                      @Value("${notifications.push.public-key:}") String publicKey,
                                      @Value("${notifications.push.private-key:}") String privateKey,
                                      @Value("${notifications.push.subject:mailto:admin@example.com}") String subject) {
        this.settings = settings;
        this.subscriptions = subscriptions;
        this.mapper = mapper;
        this.publicKey = publicKey == null ? "" : publicKey.trim();
        this.privateKey = privateKey == null ? "" : privateKey.trim();
        this.subject = subject;
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) Security.addProvider(new BouncyCastleProvider());
    }

    @Override public String channelName() { return "BrowserPush"; }
    @Override public boolean isEnabled() { return isConfigured(); }
    public boolean isConfigured() { return !publicKey.isBlank() && !privateKey.isBlank(); }
    public String publicKey() { return publicKey; }
    public List<WebPushSubscription> subscriptionsFor(String chatId) { return subscriptions.findByChatId(chatId); }

    @Override
    public boolean appliesTo(WatchRule rule) {
        if (!isConfigured()) return false;
        return settings.findByChatId(rule.getChatId()).map(UserNotificationSettings::isPushEnabled).orElse(false)
                && !subscriptions.findByChatId(rule.getChatId()).isEmpty();
    }

    @Override
    public void sendTicketAlert(WatchRule rule, String htmlMessage, String plainTextMessage, String bookingUrl) {
        if (!appliesTo(rule)) return;
        String payload;
        try {
            payload = mapper.writeValueAsString(Map.of(
                    "title", "🎟 Tickets available: " + rule.getMovieName(),
                    "body", plainTextMessage,
                    "url", bookingUrl == null ? "/" : bookingUrl
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Browser push payload failed", e);
        }
        int delivered = 0;
        for (WebPushSubscription saved : subscriptions.findByChatId(rule.getChatId())) {
            try {
                Subscription subscription = new Subscription();
                subscription.endpoint = saved.getEndpoint();
                subscription.keys = new Subscription.Keys();
                subscription.keys.p256dh = saved.getP256dh();
                subscription.keys.auth = saved.getAuth();
                PushService service = new PushService(publicKey, privateKey, subject);
                HttpResponse response = service.send(new Notification(subscription, payload));
                int status = response.getStatusLine().getStatusCode();
                if (status == 404 || status == 410) {
                    subscriptions.delete(saved);
                } else if (status >= 200 && status < 300) {
                    delivered++;
                } else {
                    throw new IllegalStateException("Browser push service returned HTTP " + status);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Browser push request failed", e);
            }
        }
        if (delivered == 0) throw new IllegalStateException("No browser push subscription accepted the alert");
    }
}
