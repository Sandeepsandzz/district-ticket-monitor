package com.district.monitor.service;

import com.district.monitor.entity.AlertDelivery;
import com.district.monitor.entity.AlertEvent;
import com.district.monitor.entity.WatchRule;
import com.district.monitor.repository.AlertDeliveryRepository;
import com.district.monitor.repository.AlertEventRepository;
import com.district.monitor.repository.WatchRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

/** Persists each alert and tracks acceptance/retries independently per channel. */
@Service
public class MultiChannelNotificationDispatcher {
    private static final Logger log=LoggerFactory.getLogger(MultiChannelNotificationDispatcher.class);
    private final List<NotificationChannel> channels;
    private final AlertEventRepository events;
    private final AlertDeliveryRepository deliveries;
    private final WatchRuleRepository watches;

    public MultiChannelNotificationDispatcher(List<NotificationChannel> channels, AlertEventRepository events,
                                              AlertDeliveryRepository deliveries, WatchRuleRepository watches) {
        this.channels=channels; this.events=events; this.deliveries=deliveries; this.watches=watches;
    }

    @Transactional
    public boolean queueAlert(WatchRule rule, String html, String plain, String bookingUrl, String showtimes) {
        List<NotificationChannel> applicable=channels.stream().filter(c -> c.appliesTo(rule)).toList();
        if (applicable.isEmpty()) return false;
        AlertEvent event=events.save(new AlertEvent(rule, html, plain, bookingUrl, showtimes));
        for (NotificationChannel channel : applicable) deliveries.save(new AlertDelivery(event.getId(),channel.channelName()));
        return true;
    }

    public boolean hasPending(Long watchId) {
        return events.findFirstByWatchIdOrderByCreatedAtDescIdDesc(watchId)
                .map(event -> deliveries.findByEventId(event.getId()).stream().anyMatch(d -> "PENDING".equals(d.getStatus()) || "RETRYING".equals(d.getStatus())))
                .orElse(false);
    }

    @Transactional
    public int retryPending(WatchRule rule) {
        AlertEvent event=events.findFirstByWatchIdOrderByCreatedAtDescIdDesc(rule.getId()).orElse(null);
        if(event==null) return 0;
        int accepted=0;
        Instant now=Instant.now();
        for(AlertDelivery delivery : deliveries.findByEventId(event.getId())) {
            if (!("PENDING".equals(delivery.getStatus()) || "RETRYING".equals(delivery.getStatus()))
                    || (delivery.getNextAttemptAt()!=null && now.isBefore(delivery.getNextAttemptAt()))) continue;
            if (now.isAfter(event.getCreatedAt().plusSeconds(900))) {
                delivery.setStatus("EXPIRED"); delivery.setErrorMessage("Alert is stale; tickets will be checked again");
                delivery.setNextAttemptAt(null); deliveries.save(delivery); continue;
            }
            NotificationChannel channel=channels.stream().filter(c -> c.channelName().equals(delivery.getChannel())).findFirst().orElse(null);
            if(channel==null || !channel.appliesTo(rule)) {
                delivery.setStatus("SKIPPED"); delivery.setErrorMessage("Channel disabled or no longer applies"); deliveries.save(delivery); continue;
            }
            delivery.setAttempts(delivery.getAttempts()+1);
            delivery.setLastAttemptAt(now);
            try {
                channel.sendTicketAlert(rule,event.getHtmlMessage(),event.getPlainMessage(),event.getBookingUrl());
                delivery.setStatus("ACCEPTED"); delivery.setErrorMessage(null); delivery.setNextAttemptAt(null); accepted++;
                if (!event.isCounted()) {
                    event.setCounted(true); events.save(event);
                    rule.setAlertCount(rule.getAlertCount()+1); rule.setLastAlertedAt(now);
                    watches.save(rule);
                }
            } catch (RuntimeException e) {
                delivery.setStatus("RETRYING"); delivery.setErrorMessage("Delivery failed; retry scheduled");
                long delay=Math.min(900, 30L << Math.min(delivery.getAttempts()-1,5));
                delivery.setNextAttemptAt(now.plusSeconds(delay));
                log.warn("Notification failed for watch {} via {}; retry scheduled",rule.getId(),delivery.getChannel());
            }
            deliveries.save(delivery);
        }
        return accepted;
    }
}
