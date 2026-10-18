package com.hamilla.sma;

import com.hamilla.market.PriceEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinuteBarTrackerTest {

    private static final long T_0400 = 1792137600000L; // 2026-10-16 04:00 ET
    private static final long NS = 1_000_000L;         // ns per ms

    record Closed(long bar, double close, boolean empty) {
    }

    private static PriceEvent trade(long ms, double price, Integer... conditions) {
        return new PriceEvent("X", ms * NS, price, 100, List.of(conditions));
    }

    private static List<Double> tens(int n) {
        List<Double> prior = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            prior.add(10.0);
        }
        return prior;
    }

    @Test
    void chartModeReportsEmptyMinutesWithoutRollingTheWindows() {
        List<Closed> closed = new ArrayList<>();
        MovingAverages engine = new MovingAverages("X", tens(199));
        MinuteBarTracker tracker = new MinuteBarTracker(engine, Session.EXTENDED, false, (b, c, s, e) -> closed.add(new Closed(b, c, e)));

        long m0 = T_0400 + 5 * 3_600_000L; // 09:00
        assertEquals(10.5, tracker.onTrade(trade(m0 + 1_000, 20.0)).orElseThrow().sma20(), 1e-9); // (19*10 + 20)/20
        assertTrue(tracker.onTrade(trade(m0 + 2_000, 99.0, 37)).isEmpty());                        // odd lot ignored
        assertEquals(21.0, tracker.onTrade(trade(m0 + 3_000, 21.0)).orElseThrow().price());
        assertTrue(closed.isEmpty());

        // Next trade is three minutes later: 09:00 closes at 21, 09:01 and 09:02 are empty.
        SmaSnapshot s = tracker.onTrade(trade(m0 + 3 * 60_000L + 500, 22.0)).orElseThrow();
        assertEquals(List.of(new Closed(m0, 21.0, false), new Closed(m0 + 60_000L, 21.0, true),
                new Closed(m0 + 120_000L, 21.0, true)), closed);
        // Only the 09:00 bar entered the windows: 18 tens, one 21, and the new price.
        assertEquals((18 * 10.0 + 21.0 + 22.0) / 20, s.sma20(), 1e-9);
        assertEquals(1, tracker.barsClosed());
        assertEquals(2, tracker.emptyMinutes());
    }

    @Test
    void filledModeMakesEmptyMinutesBars() {
        List<Closed> closed = new ArrayList<>();
        MovingAverages engine = new MovingAverages("X", tens(199));
        MinuteBarTracker tracker = new MinuteBarTracker(engine, Session.EXTENDED, true, (b, c, s, e) -> closed.add(new Closed(b, c, e)));

        long m0 = T_0400 + 5 * 3_600_000L;
        tracker.onTrade(trade(m0 + 3_000, 21.0));
        SmaSnapshot s = tracker.onTrade(trade(m0 + 3 * 60_000L + 500, 22.0)).orElseThrow();
        assertEquals(3, closed.size());
        assertEquals((16 * 10.0 + 3 * 21.0 + 22.0) / 20, s.sma20(), 1e-9);
        assertEquals(3, tracker.barsClosed());
        assertEquals(2, tracker.emptyMinutes());
    }

    @Test
    void finishSessionReportsThroughTheLastSessionMinute() {
        List<Closed> closed = new ArrayList<>();
        MinuteBarTracker tracker = new MinuteBarTracker(new MovingAverages("X", List.of()), Session.EXTENDED, false,
                (b, c, s, e) -> closed.add(new Closed(b, c, e)));
        long m = T_0400 + 955 * 60_000L; // 19:55
        tracker.onTrade(trade(m + 10, 7.0));
        tracker.finishSession();

        assertEquals(5, closed.size());
        assertEquals(new Closed(m, 7.0, false), closed.get(0));
        assertEquals(new Closed(T_0400 + 959 * 60_000L, 7.0, true), closed.get(4));
        assertEquals(1, tracker.barsClosed());
        tracker.finishSession(); // idempotent
        assertEquals(5, closed.size());
    }

    @Test
    void ignoresTradesOutsideTheSessionAndDoesNotReportOvernight() {
        List<Closed> closed = new ArrayList<>();
        MinuteBarTracker tracker = new MinuteBarTracker(new MovingAverages("X", List.of()), Session.parse("07:00-20:00"), false,
                (b, c, s, e) -> closed.add(new Closed(b, c, e)));
        long prev1959 = T_0400 - 481 * 60_000L;
        tracker.onTrade(trade(prev1959 + 10, 7.0));
        assertTrue(tracker.onTrade(trade(T_0400 + 10, 1.0)).isEmpty());          // 04:00 is outside 07:00-20:00
        tracker.onTrade(trade(T_0400 + 181 * 60_000L + 10, 8.0));                // 07:01

        // 19:59 closes, 07:00 is reported empty, nothing overnight or before 07:00.
        assertEquals(List.of(new Closed(prev1959, 7.0, false), new Closed(T_0400 + 180 * 60_000L, 7.0, true)), closed);
    }

    @Test
    void seedReportsFromTheWarmupBarToTheFirstTrade() {
        List<Closed> closed = new ArrayList<>();
        MovingAverages engine = new MovingAverages("X", tens(198));
        MinuteBarTracker tracker = new MinuteBarTracker(engine, Session.EXTENDED, true, (b, c, s, e) -> closed.add(new Closed(b, c, e)));
        tracker.seed(T_0400 - 481 * 60_000L, 10.0);         // previous day's 19:59 bar, close 10, rolled in by the tracker

        tracker.onTrade(trade(T_0400 + 500, 99.0, 37));        // 04:00 has only an odd lot
        SmaSnapshot s = tracker.onTrade(trade(T_0400 + 120_000L + 5, 30.0)).orElseThrow(); // 04:02

        // 04:00 and 04:01 are filled with 10 (the seed is not closed again); nothing overnight.
        assertEquals(List.of(new Closed(T_0400, 10.0, true), new Closed(T_0400 + 60_000L, 10.0, true)), closed);
        assertEquals((19 * 10.0 + 30.0) / 20, s.sma20(), 1e-9);
        assertEquals(2, tracker.barsClosed());
    }

    @Test
    void chartModeEmptyMinutesShowTheLastBarsValuesEvenBeforeTheFirstTrade() {
        List<SmaSnapshot> states = new ArrayList<>();
        MovingAverages engine = new MovingAverages("X", tens(198));
        MinuteBarTracker tracker = new MinuteBarTracker(engine, Session.EXTENDED, false, (b, c, s, e) -> states.add(s));
        tracker.seed(T_0400 - 481 * 60_000L, 12.0);          // 19:59 closed at 12 after 198 tens: its 20 is (19*10+12)/20

        tracker.onTrade(trade(T_0400 + 120_000L + 5, 30.0)); // first trade at 04:02
        assertEquals(2, states.size());                       // 04:00 and 04:01 reported empty
        assertEquals((19 * 10.0 + 12.0) / 20, states.get(0).sma20(), 1e-9);
        assertEquals(12.0, states.get(1).price());
        assertEquals(0, tracker.barsClosed());
    }

    @Test
    void closeOfABarIsItsLastUpdatingTradeNotItsLastPrint() {
        List<Closed> closed = new ArrayList<>();
        MinuteBarTracker tracker = new MinuteBarTracker(new MovingAverages("X", List.of()), Session.EXTENDED, false,
                (b, c, s, e) -> closed.add(new Closed(b, c, e)));
        long m = T_0400 + 491 * 60_000L; // 12:11
        tracker.onTrade(trade(m + 56_830, 12.69));
        tracker.onTrade(trade(m + 59_603, 12.685, 37));
        tracker.onTrade(trade(m + 60_000, 12.70));
        assertEquals(List.of(new Closed(m, 12.69, false)), closed);
    }
}
