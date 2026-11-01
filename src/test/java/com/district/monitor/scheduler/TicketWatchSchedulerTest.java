package com.district.monitor.scheduler;

import com.district.monitor.dto.AvailabilityCheckResult;
import com.district.monitor.entity.WatchRule;
import com.district.monitor.repository.WatchRuleRepository;
import com.district.monitor.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TicketWatchSchedulerTest {
    private WatchRule rule(long id,String date) {
        var r=new WatchRule("123","vizag","Film","AAA","Screen 7",date,"ANY","ANY","https://www.district.in/movies/film-movie-tickets-MV123");
        ReflectionTestUtils.setField(r,"id",id); return r;
    }
    @Test void skipsExpiredWatches() {
        var repo=mock(WatchRuleRepository.class); var scraper=mock(DistrictScraperService.class); var dispatcher=mock(MultiChannelNotificationDispatcher.class);
        var watch=rule(1,"2000-01-01"); when(repo.findByAcknowledgedFalse()).thenReturn(List.of(watch));
        new TicketWatchScheduler(repo,scraper,dispatcher,3,15).pollActiveWatches();
        assertEquals("EXPIRED",watch.getLastCheckStatus()); verifyNoInteractions(scraper); verifyNoInteractions(dispatcher);
    }
    @Test void checksSharePageCacheAndRecordUnknownStatus() {
        var repo=mock(WatchRuleRepository.class); var scraper=mock(DistrictScraperService.class); var dispatcher=mock(MultiChannelNotificationDispatcher.class);
        var a=rule(1,"2099-01-01"); var b=rule(2,"2099-01-01"); when(repo.findByAcknowledgedFalse()).thenReturn(List.of(a,b));
        when(scraper.checkWatchRule(any(),anyMap())).thenReturn(new AvailabilityCheckResult(false,List.of(),List.of(),List.of(),"2099-01-01","ANY","ANY","url","UNKNOWN","District data unavailable"));
        new TicketWatchScheduler(repo,scraper,dispatcher,3,15).pollActiveWatches();
        var captor=org.mockito.ArgumentCaptor.forClass(Map.class); verify(scraper,times(2)).checkWatchRule(any(),captor.capture());
        assertSame(captor.getAllValues().get(0),captor.getAllValues().get(1));
        assertEquals("UNKNOWN",a.getLastCheckStatus()); assertNotNull(a.getLastCheckedAt());
    }
    @Test void onceWatchRetriesPendingChannelsWithoutGeneratingAnotherAlert() {
        var repo=mock(WatchRuleRepository.class); var scraper=mock(DistrictScraperService.class); var dispatcher=mock(MultiChannelNotificationDispatcher.class);
        var watch=rule(1,"2099-01-01"); watch.setRepeatAlerts(false); watch.setAlertCount(1);
        when(repo.findByAcknowledgedFalse()).thenReturn(List.of(watch)); when(dispatcher.hasPending(1L)).thenReturn(true);
        new TicketWatchScheduler(repo,scraper,dispatcher,3,15).pollActiveWatches();
        verify(dispatcher).retryPending(watch); verifyNoInteractions(scraper);
        verify(dispatcher,never()).queueAlert(any(),any(),any(),any(),any());
        assertEquals("NOTIFIED_ONCE",watch.getLastCheckStatus());
    }
}
