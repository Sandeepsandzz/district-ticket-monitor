package com.district.monitor.controller;

import com.district.monitor.entity.WatchRule;
import com.district.monitor.repository.WatchRuleRepository;
import com.district.monitor.repository.AlertEventRepository;
import com.district.monitor.repository.AlertDeliveryRepository;
import com.district.monitor.scheduler.TicketWatchScheduler;
import com.district.monitor.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebWatchControllerTest {
    WebLinkService links;
    WatchRuleRepository repository;
    WebWatchController controller;
    DistrictScraperService scraper;
    String alice;
    @BeforeEach void setup() {
        links = new WebLinkService();
        repository = mock(WatchRuleRepository.class);
        scraper = mock(DistrictScraperService.class);
        controller = new WebWatchController(links, repository, scraper,
                mock(TelegramCommandService.class), mock(TicketWatchScheduler.class), mock(AlertEventRepository.class), mock(AlertDeliveryRepository.class));
        alice = "Bearer " + links.exchange(links.issueCode("alice"));
    }
    @Test void rejectsUnauthenticatedAccess() {
        var exception = assertThrows(ResponseStatusException.class, () -> controller.watches(null));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        verifyNoInteractions(repository);
    }
    @Test void cannotModifyAnotherUsersWatch() {
        when(repository.findByIdAndChatId(42L, "alice")).thenReturn(Optional.empty());
        var exception = assertThrows(ResponseStatusException.class, () -> controller.change(alice, 42L, "pause"));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(repository, never()).save(any(WatchRule.class));
        assertThrows(ResponseStatusException.class, () -> controller.delete(alice, 42L));
        verify(repository, never()).delete(any());
    }
    @Test void pauseAndResumeAreScopedToLinkedChat() {
        var rule = new WatchRule("alice", "vizag", "Film", "AAA", "Screen 7", "2027-01-01", "ANY", "ANY", null);
        when(repository.findByIdAndChatId(1L, "alice")).thenReturn(Optional.of(rule));
        when(repository.save(rule)).thenReturn(rule);
        assertTrue(controller.change(alice, 1L, "pause").paused());
        assertFalse(controller.change(alice, 1L, "resume").paused());
    }
    @Test void createsWatchForLinkedTelegramChat() {
        String url = "https://www.district.in/movies/film-movie-tickets-in-vizag-MV123";
        when(scraper.isAllowedMoviePageUrl(url)).thenReturn(true);
        when(repository.findByChatIdAndMovieNameIgnoreCaseAndAcknowledgedFalse("alice", "Film")).thenReturn(List.of());
        when(repository.save(any(WatchRule.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var result = controller.create(alice, new WebWatchController.WatchRequest("Film", url, "AAA", "Screen 7", "2099-01-01", false));
        assertEquals("Film", result.movie());
        var capture = org.mockito.ArgumentCaptor.forClass(WatchRule.class);
        verify(repository).save(capture.capture());
        assertEquals("alice", capture.getValue().getChatId());
        assertEquals("Screen 7", capture.getValue().getScreenFilter());
        assertFalse(capture.getValue().isRepeatAlerts());
    }
    @Test void theatreChoicesAreCityWideEvenWhenMovieHasLimitedSessions() {
        String url = "https://www.district.in/movies/film-movie-tickets-in-vizag-MV123";
        when(scraper.isAllowedMoviePageUrl(url)).thenReturn(true);
        when(scraper.fetchVizagTheatres()).thenReturn(new DistrictScraperService.TheatreCatalogue(List.of("AAA", "INOX"), "live"));
        var result = controller.theatres(alice, url);
        assertEquals(List.of("AAA", "INOX"), result.get("items"));
        assertEquals(false, result.get("suggested"));
        verify(scraper, never()).getAvailableTheatresForMovie(anyString());
    }
}
