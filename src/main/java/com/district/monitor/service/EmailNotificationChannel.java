package com.district.monitor.service;

import com.district.monitor.entity.WatchRule;
import com.district.monitor.entity.UserNotificationSettings;
import com.district.monitor.repository.UserNotificationSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Optional Email Notification Channel via Spring Mail (SMTP).
 */
@Service
public class EmailNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationChannel.class);

    private final boolean enabled;
    private final String toAddress;
    private final String fromAddress;
    private final JavaMailSender mailSender;
    private final UserNotificationSettingsRepository settings;

    public EmailNotificationChannel(
            @Value("${notifications.email.enabled:false}") boolean enabled,
            @Value("${notifications.email.to:}") String toAddress,
            @Value("${notifications.email.from:noreply@districtmonitor.local}") String fromAddress,
            @Autowired(required = false) JavaMailSender mailSender,
            UserNotificationSettingsRepository settings
    ) {
        this.enabled = enabled;
        this.toAddress = toAddress;
        this.fromAddress = fromAddress;
        this.mailSender = mailSender;
        this.settings = settings;
    }

    @Override
    public String channelName() {
        return "Email";
    }

    @Override
    public boolean isEnabled() {
        return enabled && mailSender != null;
    }

    public boolean isConfigured() { return isEnabled() && validEmail(toAddress); }

    public boolean canSend(String chatId) {
        if (!isEnabled()) return false;
        return settings.findByChatId(chatId).map(s -> s.isEmailEnabled() && validEmail(s.getEmailAddress()))
                .orElse(validEmail(toAddress));
    }

    @Override
    public boolean appliesTo(WatchRule rule) {
        if (!isEnabled()) return false;
        return settings.findByChatId(rule.getChatId())
                .map(s -> s.isEmailEnabled() && validEmail(s.getEmailAddress()))
                .orElse(validEmail(toAddress));
    }

    @Override
    public void sendTicketAlert(WatchRule rule, String htmlMessage, String plainTextMessage, String bookingUrl) {
        if (!isEnabled()) {
            return;
        }
        String recipient = settings.findByChatId(rule.getChatId()).map(UserNotificationSettings::getEmailAddress).orElse(toAddress);
        if (!validEmail(recipient)) return;
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(recipient);
            msg.setSubject("🎟 District Ticket Alert: " + rule.getMovieName() + " (" + rule.getCity().toUpperCase() + ")");
            msg.setText(plainTextMessage + "\n\nBook Now: " + bookingUrl);
            mailSender.send(msg);
            log.info("Dispatched Email notification for watchId={} to {}", rule.getId(), recipient);
        } catch (Exception e) {
            throw new IllegalStateException("Email request failed");
        }
    }

    public void sendTest(String chatId) {
        if (!canSend(chatId)) throw new IllegalStateException("Email is not enabled for this account");
        String recipient = settings.findByChatId(chatId).map(UserNotificationSettings::getEmailAddress).orElse(toAddress);
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(recipient);
            msg.setSubject("District Monitor email test");
            msg.setText("Email alerts are configured correctly for your District Ticket Monitor account.");
            mailSender.send(msg);
            log.info("Dispatched email test for chatId={} to {}", chatId, recipient);
        } catch (Exception e) {
            throw new IllegalStateException("Email test failed");
        }
    }

    private boolean validEmail(String value) {
        return value != null && value.length() <= 320 && value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }
}
