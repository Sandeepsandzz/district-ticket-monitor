package com.district.monitor.service;

import com.district.monitor.entity.WatchRule;
import com.district.monitor.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:deliverytest")
class NotificationPersistenceTest {
    @Autowired WatchRuleRepository watches;
    @Autowired AlertEventRepository events;
    @Autowired AlertDeliveryRepository deliveries;
    @Test void storesEventAndPerChannelDeliveryAcrossRepositoryReads() {
        NotificationChannel channel=new NotificationChannel() {
            public String channelName(){return "Test";}
            public boolean isEnabled(){return true;}
            public void sendTicketAlert(WatchRule r,String h,String p,String u){}
        };
        var dispatcher=new MultiChannelNotificationDispatcher(List.of(channel),events,deliveries,watches);
        var watch=watches.saveAndFlush(new WatchRule("123","vizag","Film","AAA","Screen 7","2099-01-01","ANY","ANY",null));
        assertTrue(dispatcher.queueAlert(watch,"html","plain","url","10:00 AM"));
        assertTrue(dispatcher.hasPending(watch.getId()));
        assertEquals(1,dispatcher.retryPending(watch));
        assertFalse(dispatcher.hasPending(watch.getId()));
        var event=events.findTop50ByChatIdOrderByCreatedAtDesc("123").get(0);
        assertEquals("10:00 AM",event.getShowtimes());
        assertEquals("ACCEPTED",deliveries.findByEventId(event.getId()).get(0).getStatus());
        assertEquals(1,watches.findById(watch.getId()).orElseThrow().getAlertCount());
    }
}
