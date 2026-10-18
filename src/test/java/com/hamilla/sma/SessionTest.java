package com.hamilla.sma;

import com.hamilla.market.MinuteBar;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTest {

    private static final long T_0400 = 1792137600000L;   // 2026-10-16 04:00 ET
    private static final long T_1959 = T_0400 + 959 * 60_000L;
    private static final long T_2000 = T_0400 + 960 * 60_000L;
    private static final long PREV_1959 = T_0400 - 481 * 60_000L; // 2026-10-15 19:59 ET

    private static MinuteBar bar(long t, double c) {
        return new MinuteBar(t, c, c, c, c, 1);
    }

    @Test
    void extendedSessionIsFourToEightEastern() {
        Session s = Session.EXTENDED;
        assertTrue(s.contains(T_0400));
        assertTrue(s.contains(T_1959));
        assertFalse(s.contains(T_2000));
        assertFalse(s.contains(T_0400 - 60_000L));
        assertTrue(s.contains(PREV_1959));
        assertEquals(T_1959, s.lastBarOfDay(T_0400 + 7 * 3_600_000L));
    }

    @Test
    void parsesAndPrints() {
        Session s = Session.parse("07:00-20:00");
        assertEquals(LocalTime.of(7, 0), s.open());
        assertEquals(LocalTime.of(20, 0), s.close());
        assertEquals("07:00-20:00", s.toString());
        assertFalse(s.contains(T_0400));
        assertTrue(s.contains(T_0400 + 180 * 60_000L));
        assertEquals(Session.REGULAR, Session.parse("09:30-16:00"));
        assertThrows(IllegalArgumentException.class, () -> Session.parse("7am-8pm"));
        assertThrows(IllegalArgumentException.class, () -> Session.parse("16:00-09:30"));
    }

    @Test
    void closesKeepsOnlyInSessionBarsAndSkipsEmptyMinutes() {
        List<MinuteBar> bars = List.of(
                bar(T_0400 - 60_000L, 0.5),               // 03:59, outside
                bar(T_0400, 1.0),
                bar(T_0400 + 3 * 60_000L, 2.0),           // 04:01 and 04:02 empty
                bar(T_0400 + 4 * 60_000L, 3.0),
                bar(T_0400 + 5 * 60_000L, 4.0));
        assertEquals(List.of(1.0, 2.0, 3.0), Session.EXTENDED.closes(bars, T_0400 + 4 * 60_000L));
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0), Session.EXTENDED.closes(bars, Long.MAX_VALUE));
        assertEquals(List.of(), Session.REGULAR.closes(bars, Long.MAX_VALUE));
    }

    @Test
    void fillClosesRepeatsThePreviousCloseThroughEmptyMinutes() {
        List<Double> closes = Session.EXTENDED.fillCloses(List.of(
                bar(T_0400, 1.0),
                bar(T_0400 + 3 * 60_000L, 2.0),   // 04:01 and 04:02 are empty
                bar(T_0400 + 4 * 60_000L, 3.0)),
                T_0400 + 6 * 60_000L);            // 04:05 and 04:06 trail
        assertEquals(List.of(1.0, 1.0, 1.0, 2.0, 3.0, 3.0, 3.0), closes);
    }

    @Test
    void fillClosesDoesNotFillOvernightOrOutsideTheSession() {
        List<Double> closes = Session.EXTENDED.fillCloses(List.of(
                bar(PREV_1959 - 60_000L, 5.0),   // 19:58 the day before
                bar(PREV_1959 + 60_000L, 9.0),   // 20:00 the day before, outside: ignored
                bar(T_0400 + 60_000L, 6.0)),      // 04:01 next day; 19:59 and 04:00 are empty
                T_0400 + 60_000L);
        assertEquals(List.of(5.0, 5.0, 5.0, 6.0), closes);
        assertTrue(Session.EXTENDED.fillCloses(List.of(), T_0400).isEmpty());
    }

    @Test
    void fillingTheFixtureProducesEveryMinuteFrom0802To1210() {
        List<Double> closes = Session.EXTENDED.fillCloses(Fixture.rivnBarsBefore1211(), Fixture.BAR_1211 - 60_000L);
        assertEquals(249, closes.size()); // 08:02 to 12:10 inclusive
        assertEquals(199, Session.EXTENDED.closes(Fixture.rivnBarsBefore1211(), Fixture.BAR_1211 - 60_000L).size());
    }
}
