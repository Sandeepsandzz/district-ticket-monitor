package com.district.monitor.service;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class WebLinkServiceTest {
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    @Test void codesAreSingleUseExpireAndNeverAuthenticateDirectly() {
        var clock = new TestClock();
        var service = new WebLinkService(clock);
        String code = service.issueCode("123");
        assertNull(service.resolve(code));
        String session = service.exchange(code);
        assertNotNull(session);
        assertNull(service.exchange(code));
        assertEquals("123", service.resolve(session));
        clock.now = clock.now.plus(Duration.ofHours(24));
        assertNull(service.resolve(session));
        String expired = service.issueCode("123");
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        assertNull(service.exchange(expired));
    }

    @Test void issuingANewCodeInvalidatesThePreviousOne() {
        var service = new WebLinkService(new TestClock());
        String first = service.issueCode("123");
        String second = service.issueCode("123");
        assertNull(service.exchange(first));
        String session = service.exchange(second);
        assertEquals("123", service.resolve(session));
        service.logout(session);
        assertNull(service.resolve(session));
    }
}
