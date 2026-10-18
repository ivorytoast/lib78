package com.hamilla.sma;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalWindowTest {

    private static final long T_0400 = 1792137600000L; // 2026-10-16 04:00 ET

    private static long at(int minutesAfter0400) {
        return T_0400 + minutesAfter0400 * 60_000L;
    }

    @Test
    void defaultActsOnTenThirtyThroughFourteenTwentyNine() {
        SignalWindow w = SignalWindow.DEFAULT;
        assertEquals("10:30-14:30", w.toString());
        assertEquals(LocalTime.of(14, 29), w.last());
        assertFalse(w.contains(at(389)));   // 10:29
        assertTrue(w.contains(at(390)));    // 10:30
        assertTrue(w.contains(at(629)));    // 14:29
        assertFalse(w.contains(at(630)));   // 14:30
    }

    @Test
    void parsesAndValidates() {
        SignalWindow w = SignalWindow.parse("10:30-12:30");
        assertEquals(LocalTime.of(10, 30), w.open());
        assertEquals(LocalTime.of(12, 29), w.last());
        assertTrue(w.contains(at(509)));    // 12:29
        assertFalse(w.contains(at(510)));   // 12:30
        assertThrows(IllegalArgumentException.class, () -> SignalWindow.parse("10:30"));
        assertThrows(IllegalArgumentException.class, () -> SignalWindow.parse("12:30-10:30"));
        assertThrows(IllegalArgumentException.class, () -> SignalWindow.parse("ten-noon"));
    }
}
