package com.district.monitor.scheduler;

import com.district.monitor.dto.AvailabilityCheckResult;
import com.district.monitor.entity.WatchRule;
import com.district.monitor.repository.*;
import com.district.monitor.service.DistrictScraperService;
import com.district.monitor.service.TelegramNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:deliveryflow", "telegram.polling-enabled=false", "district.poll-interval-ms=3600000", "district.initial-delay-ms=3600000", "notifications.whatsapp.enabled=false", "notifications.email.enabled=false", "notifications.webhook.enabled=false"})
class DeliveryFlowIntegrationTest {
    @Autowired TicketWatchScheduler scheduler;
    @Autowired WatchRuleRepository watches;
    @Autowired AlertEventRepository events;
    @Autowired AlertDeliveryRepository deliveries;
    @MockBean DistrictScraperService scraper;
    @MockBean TelegramNotificationService telegram;

    @Test void failureIsRetriedAndOnlySuccessIncrementsWatch() {
        when(telegram.isEnabled()).thenReturn(true);
        when(telegram.appliesTo(any())).thenReturn(true);
        when(telegram.channelName()).thenReturn("Telegram");
        doThrow(new IllegalStateException("temporary")).doNothing().when(telegram).sendTicketAlert(any(),any(),any(),any());
        when(scraper.checkWatchRule(any(),anyMap())).thenReturn(new AvailabilityCheckResult(true,List.of("AAA"),List.of("Screen 7"),List.of("10:00 AM"),"2099-01-01","ANY","ANY","https://www.district.in/movies/film"));
        var rule=watches.save(new WatchRule("123","vizag","Film","AAA","Screen 7","2099-01-01","ANY","ANY","https://www.district.in/movies/film"));
        assertEquals(0,scheduler.pollActiveWatches());
        assertEquals(0,watches.findById(rule.getId()).orElseThrow().getAlertCount());
        var event=events.findTop50ByChatIdOrderByCreatedAtDesc("123").get(0);
        var delivery=deliveries.findByEventId(event.getId()).get(0);
        assertEquals("RETRYING",delivery.getStatus());
        delivery.setNextAttemptAt(Instant.now().minusSeconds(1)); deliveries.save(delivery);
        assertEquals(1,scheduler.pollActiveWatches());
        assertEquals(1,watches.findById(rule.getId()).orElseThrow().getAlertCount());
        assertEquals("ACCEPTED",deliveries.findById(delivery.getId()).orElseThrow().getStatus());
        assertEquals(1,events.findTop50ByChatIdOrderByCreatedAtDesc("123").size());
    }
}
