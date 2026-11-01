package com.district.monitor.service;

import com.district.monitor.entity.*;
import com.district.monitor.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationRetryTest {
    @Test void retriesOnlyFailedChannelAndCountsAlertOnce() {
        var telegram = mock(NotificationChannel.class);
        var whatsapp = mock(NotificationChannel.class);
        when(telegram.channelName()).thenReturn("Telegram"); when(whatsapp.channelName()).thenReturn("WhatsApp");
        when(telegram.appliesTo(any())).thenReturn(true); when(whatsapp.appliesTo(any())).thenReturn(true);
        doThrow(new IllegalStateException("temporary")).doNothing().when(whatsapp).sendTicketAlert(any(),any(),any(),any());
        var events=mock(AlertEventRepository.class); var deliveries=mock(AlertDeliveryRepository.class); var watches=mock(WatchRuleRepository.class);
        var rule=new WatchRule("123", "vizag", "Film", "AAA", "Screen 7", "2099-01-01", "ANY", "ANY", null);
        ReflectionTestUtils.setField(rule,"id",1L);
        var event=new AlertEvent(rule,"html","plain","url","10:00 AM"); ReflectionTestUtils.setField(event,"id",2L);
        var first=new AlertDelivery(2L,"Telegram"); var second=new AlertDelivery(2L,"WhatsApp");
        when(events.findFirstByWatchIdOrderByCreatedAtDescIdDesc(1L)).thenReturn(Optional.of(event));
        when(deliveries.findByEventId(2L)).thenReturn(List.of(first,second));
        var dispatcher=new MultiChannelNotificationDispatcher(List.of(telegram,whatsapp),events,deliveries,watches);
        assertEquals(1,dispatcher.retryPending(rule));
        assertEquals("ACCEPTED",first.getStatus()); assertEquals("RETRYING",second.getStatus());
        assertEquals(1,rule.getAlertCount()); assertTrue(dispatcher.hasPending(1L));
        assertEquals(0,dispatcher.retryPending(rule));
        second.setNextAttemptAt(Instant.now().minusSeconds(1));
        assertEquals(1,dispatcher.retryPending(rule));
        assertFalse(dispatcher.hasPending(1L)); assertEquals(1,rule.getAlertCount());
        verify(telegram,times(1)).sendTicketAlert(any(),any(),any(),any());
        verify(whatsapp,times(2)).sendTicketAlert(any(),any(),any(),any());
    }
}
