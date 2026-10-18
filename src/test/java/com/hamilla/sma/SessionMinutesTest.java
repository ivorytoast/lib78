package com.hamilla.sma;

import com.hamilla.market.MinuteBar;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMinutesTest {

    private static final long T_0400 = 1792137600000L;   // 2026-10-16 04:00 ET
    private static final long T_1959 = T_0400 + 959 * 60_000L;
    private static final long T_2000 = T_0400 + 960 * 60_000L;
    private static final long PREV_1959 = T_0400 - 481 * 60_000L; // 2026-10-15 19:59 ET

    private static MinuteBar bar(long t, double c) {
        return new MinuteBar(t, c, c, c, c, 1);
    }

    @Test
    void sessionIsFourToEightEastern() {
        assertTrue(SessionMinutes.inSession(T_0400));
        assertTrue(SessionMinutes.inSession(T_1959));
        assertFalse(SessionMinutes.inSession(T_2000));
        assertFalse(SessionMinutes.inSession(T_0400 - 60_000L));
        assertTrue(SessionMinutes.inSession(PREV_1959));
        assertEquals(T_1959, SessionMinutes.lastBarOfSession(T_0400 + 7 * 3_600_000L));
    }

    @Test
    void fillsEmptyMinutesWithPreviousCloseAndTrailsToTheRequestedMinute() {
        List<Double> closes = SessionMinutes.fillCloses(List.of(
                bar(T_0400, 1.0),
                bar(T_0400 + 3 * 60_000L, 2.0),   // 04:01 and 04:02 are empty
                bar(T_0400 + 4 * 60_000L, 3.0)),
                T_0400 + 6 * 60_000L);            // 04:05 and 04:06 trail
        assertEquals(List.of(1.0, 1.0, 1.0, 2.0, 3.0, 3.0, 3.0), closes);
    }

    @Test
    void doesNotFillOvernight() {
        List<Double> closes = SessionMinutes.fillCloses(List.of(
                bar(PREV_1959 - 60_000L, 5.0),   // 19:58 the day before
                bar(T_0400 + 60_000L, 6.0)),      // 04:01 next day; 19:59 and 04:00 are empty
                T_0400 + 60_000L);
        assertEquals(List.of(5.0, 5.0, 5.0, 6.0), closes);
    }

    @Test
    void ignoresBarsAfterTheRequestedMinuteAndEmptyInput() {
        assertEquals(List.of(1.0), SessionMinutes.fillCloses(List.of(bar(T_0400, 1.0), bar(T_0400 + 60_000L, 2.0)), T_0400));
        assertTrue(SessionMinutes.fillCloses(List.of(), T_0400).isEmpty());
    }

    @Test
    void fillingTheFixtureProducesEveryMinuteFrom0802To1210() {
        List<Double> closes = SessionMinutes.fillCloses(Fixture.rivnBarsBefore1211(), Fixture.BAR_1211 - 60_000L);
        // 08:02 to 12:10 inclusive is 249 minutes.
        assertEquals(249, closes.size());
    }
}
