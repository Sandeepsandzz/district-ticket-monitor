package com.district.monitor;

import com.district.monitor.entity.WatchRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WatchRuleTest {

    @Test
    void normalizesPriceAndSeatFilters() {
        WatchRule rule = new WatchRule(
                "chat-1", "vizag", "Movie", "ANY", "ANY", "ANY", "ANY", "ANY", "ANY",
                new BigDecimal("250.5"), 2
        );

        assertEquals(new BigDecimal("250.50"), rule.getMaxPrice());
        assertEquals(2, rule.getMinAvailableSeats());
    }

    @Test
    void disablesFiltersWhenValuesAreNotPositive() {
        WatchRule rule = new WatchRule(
                "chat-1", "vizag", "Movie", "ANY", "ANY", "ANY", "ANY", "ANY", "ANY",
                new BigDecimal("-1"), -4
        );

        assertNull(rule.getMaxPrice());
        assertEquals(0, rule.getMinAvailableSeats());
    }
}
