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

    record Closed(long bar, double close, boolean filled) {
    }

    private static PriceEvent trade(long ms, double price, Integer... conditions) {
        return new PriceEvent("X", ms * NS, price, 100, List.of(conditions));
    }

    @Test
    void closesBarsOnMinuteChangeAndSkipsNonUpdatingTrades() {
        List<Double> prior = new ArrayList<>();
        for (int i = 0; i < 199; i++) {
            prior.add(10.0);
        }
        List<Closed> closed = new ArrayList<>();
        MovingAverages engine = new MovingAverages("X", prior);
        MinuteBarTracker tracker = new MinuteBarTracker(engine, (b, c, s, f) -> closed.add(new Closed(b, c, f)));

        long m0 = T_0400 + 5 * 3_600_000L; // 09:00
        assertEquals(10.5, tracker.onTrade(trade(m0 + 1_000, 20.0)).orElseThrow().sma20(), 1e-9); // (19*10 + 20)/20
        assertTrue(tracker.onTrade(trade(m0 + 2_000, 99.0, 37)).isEmpty());                        // odd lot ignored
        assertEquals(21.0, tracker.onTrade(trade(m0 + 3_000, 21.0)).orElseThrow().price());
        assertTrue(closed.isEmpty());

        // Next trade is three minutes later: 09:00 closes at 21, 09:01 and 09:02 repeat it.
        SmaSnapshot s = tracker.onTrade(trade(m0 + 3 * 60_000L + 500, 22.0)).orElseThrow();
        assertEquals(List.of(new Closed(m0, 21.0, false), new Closed(m0 + 60_000L, 21.0, true),
                new Closed(m0 + 120_000L, 21.0, true)), closed);
        assertEquals((16 * 10.0 + 3 * 21.0 + 22.0) / 20, s.sma20(), 1e-9);
        assertEquals(3, tracker.barsClosed());
        assertEquals(2, tracker.barsFilled());
    }

    @Test
    void finishSessionFillsThroughNineFiftyNinePm() {
        List<Closed> closed = new ArrayList<>();
        MinuteBarTracker tracker = new MinuteBarTracker(new MovingAverages("X", List.of()), (b, c, s, f) -> closed.add(new Closed(b, c, f)));
        long m = T_0400 + 955 * 60_000L; // 19:55
        tracker.onTrade(trade(m + 10, 7.0));
        tracker.finishSession();

        assertEquals(5, closed.size());
        assertEquals(new Closed(m, 7.0, false), closed.get(0));
        assertEquals(new Closed(T_0400 + 959 * 60_000L, 7.0, true), closed.get(4));
        tracker.finishSession(); // idempotent
        assertEquals(5, closed.size());
    }

    @Test
    void doesNotFillOvernightBetweenSessions() {
        List<Closed> closed = new ArrayList<>();
        MinuteBarTracker tracker = new MinuteBarTracker(new MovingAverages("X", List.of()), (b, c, s, f) -> closed.add(new Closed(b, c, f)));
        long prev1959 = T_0400 - 481 * 60_000L;
        tracker.onTrade(trade(prev1959 + 10, 7.0));
        tracker.onTrade(trade(T_0400 + 60_000L + 10, 8.0)); // 04:01 next day

        // 19:59 closes, 04:00 is filled, nothing in between.
        assertEquals(List.of(new Closed(prev1959, 7.0, false), new Closed(T_0400, 7.0, true)), closed);
    }

    @Test
    void seedFillsFromTheWarmupBarToTheFirstTrade() {
        List<Closed> closed = new ArrayList<>();
        List<Double> prior = new ArrayList<>();
        for (int i = 0; i < 199; i++) {
            prior.add(10.0);
        }
        MovingAverages engine = new MovingAverages("X", prior);
        MinuteBarTracker tracker = new MinuteBarTracker(engine, (b, c, s, f) -> closed.add(new Closed(b, c, f)));
        tracker.seed(T_0400 - 481 * 60_000L, 10.0);         // previous day's 19:59 bar, close 10, already in the windows

        tracker.onTrade(trade(T_0400 + 500, 99.0, 37));        // 04:00 has only an odd lot
        SmaSnapshot s = tracker.onTrade(trade(T_0400 + 120_000L + 5, 30.0)).orElseThrow(); // 04:02

        // 04:00 and 04:01 are filled with 10 (the seed is not closed again); nothing overnight.
        assertEquals(List.of(new Closed(T_0400, 10.0, true), new Closed(T_0400 + 60_000L, 10.0, true)), closed);
        assertEquals((19 * 10.0 + 30.0) / 20, s.sma20(), 1e-9);
        assertEquals(2, tracker.barsClosed());
    }

    @Test
    void closeOfABarIsItsLastUpdatingTradeNotItsLastPrint() {
        List<Closed> closed = new ArrayList<>();
        MinuteBarTracker tracker = new MinuteBarTracker(new MovingAverages("X", List.of()), (b, c, s, f) -> closed.add(new Closed(b, c, f)));
        long m = T_0400 + 491 * 60_000L; // 12:11
        tracker.onTrade(trade(m + 56_830, 12.69));
        tracker.onTrade(trade(m + 59_603, 12.685, 37));
        tracker.onTrade(trade(m + 60_000, 12.70));
        assertEquals(List.of(new Closed(m, 12.69, false)), closed);
    }
}
