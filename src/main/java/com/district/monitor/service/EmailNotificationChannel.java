package com.district.monitor.service;

import com.district.monitor.entity.WatchRule;
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

    public EmailNotificationChannel(
            @Value("${notifications.email.enabled:false}") boolean enabled,
            @Value("${notifications.email.to:}") String toAddress,
            @Value("${notifications.email.from:noreply@districtmonitor.local}") String fromAddress,
            @Autowired(required = false) JavaMailSender mailSender
    ) {
        this.enabled = enabled;
        this.toAddress = toAddress;
        this.fromAddress = fromAddress;
        this.mailSender = mailSender;
    }

    @Override
    public String channelName() {
        return "Email";
    }

    @Override
    public boolean isEnabled() {
        return enabled && mailSender != null && toAddress != null && !toAddress.isBlank();
    }

    @Override
    public void sendTicketAlert(WatchRule rule, String htmlMessage, String plainTextMessage, String bookingUrl) {
        if (!isEnabled()) {
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(toAddress);
            msg.setSubject("🎟 District Ticket Alert: " + rule.getMovieName() + " (" + rule.getCity().toUpperCase() + ")");
            msg.setText(plainTextMessage + "\n\nBook Now: " + bookingUrl);
            mailSender.send(msg);
            log.info("Dispatched Email notification for watchId={} to {}", rule.getId(), toAddress);
        } catch (Exception e) {
            throw new IllegalStateException("Email request failed");
        }
    }
}
