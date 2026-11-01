package com.district.monitor.controller;

import com.district.monitor.entity.WatchRule;
import com.district.monitor.repository.WatchRuleRepository;
import com.district.monitor.scheduler.TicketWatchScheduler;
import com.district.monitor.service.DistrictScraperService;
import com.district.monitor.service.TelegramCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WatchImportTest {
    @Test void importsWithoutOverwritingAndPreservesState() {
        var repository=mock(WatchRuleRepository.class);
        when(repository.findByChatId("123")).thenReturn(List.of());
        var controller=new WatchAndTelegramController(repository,mock(TicketWatchScheduler.class),mock(TelegramCommandService.class),mock(DistrictScraperService.class),"secret","");
        Instant created=Instant.parse("2026-01-01T00:00:00Z");
        var backup=new WatchAndTelegramController.BackupWatch("123","vizag","Film","AAA","Screen 7","2099-01-01","ANY","ANY",null,null,0,true,3,null,null,created,false);
        assertEquals(HttpStatus.UNAUTHORIZED,controller.importWatches("wrong",List.of(backup)).getStatusCode());
        assertEquals(HttpStatus.OK,controller.importWatches("secret",List.of(backup)).getStatusCode());
        var captor=org.mockito.ArgumentCaptor.forClass(WatchRule.class); verify(repository).save(captor.capture());
        assertTrue(captor.getValue().isAcknowledged()); assertEquals(3,captor.getValue().getAlertCount());
        assertFalse(captor.getValue().isRepeatAlerts()); assertEquals(created,captor.getValue().getCreatedAt());
    }

    @Test void validatesEntireBackupBeforeSavingAnything() {
        var repository=mock(WatchRuleRepository.class);
        var controller=new WatchAndTelegramController(repository,mock(TicketWatchScheduler.class),mock(TelegramCommandService.class),mock(DistrictScraperService.class),"secret","");
        var valid=new WatchAndTelegramController.BackupWatch("123","vizag","Film","AAA","Screen 7","2099-01-01","ANY","ANY",null,null,0,false,0,null,null,null,null);
        var invalid=new WatchAndTelegramController.BackupWatch("not-a-chat","vizag","Film","AAA","Screen 7","2099-01-01","ANY","ANY",null,null,0,false,0,null,null,null,null);
        assertEquals(HttpStatus.BAD_REQUEST,controller.importWatches("secret",List.of(valid,invalid)).getStatusCode());
        verifyNoInteractions(repository);
    }
}
