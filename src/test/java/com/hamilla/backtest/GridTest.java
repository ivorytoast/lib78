package com.hamilla.backtest;

import com.hamilla.market.MinuteBar;
import com.hamilla.sma.Session;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridTest {

    @Test
    void recommendationPrefersAPlateauOverASpike() {
        List<Double> stops = List.of(0.05, 0.10, 0.15, BracketSimulator.NONE);
        List<Double> targets = List.of(0.10, 0.20, 0.30, BracketSimulator.NONE);
        double[][] pnl = {
                {100, 100, 100, 100},
                {100, 9000, 100, 100},     // spike at (0.10, 0.20) surrounded by small cells
                {100, 100, 100, 100},
                {5000, 5000, 5000, 5000},  // "no stop" row is high but not eligible
        };
        BracketSimulator.Summary[][] cells = new BracketSimulator.Summary[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                cells[i][j] = new BracketSimulator.Summary(stops.get(i), targets.get(j), pnl[i][j], 10, 5, 3, 2, -1, java.util.Map.of());
            }
        }
        Grid.Recommendation rec = Grid.recommend(stops, targets, pnl, (i, j) -> cells[i][j]);
        // The spike's own neighbourhood averages (9000 + 8*100)/9 = 1089. The row below it borders the
        // strong "none" row: (0.15, 0.20) averages (5*100 + 9000 + 3*5000)/9 = 2722 and wins; its edge
        // neighbours (0.15, 0.10) and (0.15, 0.30) pad their missing cells with their own 100.
        assertEquals(0.15, rec.stop());
        assertEquals(0.20, rec.target());
        assertEquals((100 + 9000 + 100 + 100 + 100 + 100 + 5000 + 5000 + 5000) / 9.0, rec.score(), 1e-9);
        assertEquals(0.15 / 0.35, rec.breakevenWinRate(), 1e-9);
        assertEquals(5.0 / 8, rec.winRate(), 1e-9);
    }

    @Test
    void multiMonthScoresBySteadinessAndReportsConsistency() {
        List<MinuteBar> bars = Bars.rivnOct15and16();
        Set<LocalDate> days = Set.of(LocalDate.of(2026, 10, 16));
        List<TradeSignal> signals = new SignalGenerator(Session.EXTENDED, 6).generate("RIVN", bars, days);
        // Pretend the same day's signals happened in two months, with an empty month in between.
        java.util.Map<java.time.YearMonth, List<TradeSignal>> byMonth = new java.util.LinkedHashMap<>();
        byMonth.put(java.time.YearMonth.of(2026, 8), signals);
        byMonth.put(java.time.YearMonth.of(2026, 9), List.of());
        byMonth.put(java.time.YearMonth.of(2026, 10), signals);

        Grid.MultiResult r = new Grid(new BracketSimulator(bars)).runMonths("RIVN", byMonth,
                List.of(0.08, 0.15, BracketSimulator.NONE), List.of(0.12, 0.40, BracketSimulator.NONE), 0.02, 2000,
                List.of(new double[]{0.08, 0.12}, new double[]{0.05, BracketSimulator.NONE}));

        assertEquals(3, r.months().size());
        assertEquals(2 * 2480.0, r.total()[0][0], 1e-6);
        assertEquals(2480.0, r.worstMonth()[0][0], 1e-6);
        assertEquals(2, r.monthsPositive()[0][0]);              // the empty month does not count
        assertEquals(2 * signals.size(), r.signals());
        // Identical months have zero deviation, so the score is the mean monthly P&L and the
        // recommendation is the finite cell with the best smoothed mean.
        assertTrue(r.recommendation().stop() < BracketSimulator.NONE);
        assertEquals(3, r.recommendedByMonth().size());
        assertEquals(r.recommendedByMonth().get(java.time.YearMonth.of(2026, 8)), r.recommendedByMonth().get(java.time.YearMonth.of(2026, 10)));
        assertEquals(0.0, r.recommendedByMonth().get(java.time.YearMonth.of(2026, 9)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Grid.print(r, new PrintStream(out, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("RIVN 2026-08 to 2026-10: 3 months"), text);
        assertTrue(text.contains("total P&L over all months"), text);
        assertTrue(text.contains("worst month"), text);
        assertTrue(text.contains("months positive (of 3)"), text);
        assertTrue(text.contains("by month: 2026-08 +"), text);
        assertTrue(text.contains("chosen by mean monthly P&L minus its standard deviation"), text);
        assertTrue(text.contains("by entry time, with these brackets:"), text);
        // The two requested cells are detailed after the recommendation.
        assertEquals(2, r.shown().size());
        assertEquals(2480.0, r.shown().get(0).byMonth().get(java.time.YearMonth.of(2026, 8)), 1e-6);
        assertTrue(text.contains("detail: stop 0.08, target 0.12"), text);
        assertTrue(text.contains("detail: stop 0.05, target none"), text);
        assertTrue(text.contains("by month: 2026-08 +2,480, 2026-09 +0, 2026-10 +2,480"), text);
    }

    @Test
    void negativeScoreIsReportedAsNoRecommendationWithTheStrongestTotals() {
        List<MinuteBar> bars = Bars.rivnOct15and16();
        Set<LocalDate> days = Set.of(LocalDate.of(2026, 10, 16));
        List<TradeSignal> signals = new SignalGenerator(Session.EXTENDED, 6).generate("RIVN", bars, days);
        // One good month and one month whose signals are the same trades but shorted at a loss: make the
        // second month a mirror by flipping every signal to BUY LONG, so every cell swings wildly.
        List<TradeSignal> flipped = new java.util.ArrayList<>();
        for (TradeSignal s : signals) {
            flipped.add(new TradeSignal(s.barStartMs(), com.hamilla.sma.Action.BUY_LONG, s.price()));
        }
        java.util.Map<java.time.YearMonth, List<TradeSignal>> byMonth = new java.util.LinkedHashMap<>();
        byMonth.put(java.time.YearMonth.of(2026, 9), signals);
        byMonth.put(java.time.YearMonth.of(2026, 10), flipped);

        Grid.MultiResult r = new Grid(new BracketSimulator(bars)).runMonths("RIVN", byMonth,
                List.of(0.08, 0.15, BracketSimulator.NONE), List.of(0.12, 0.40, BracketSimulator.NONE), 0.02, 2000);
        assertTrue(r.recommendation().score() < 0, "score " + r.recommendation().score());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Grid.print(r, new PrintStream(out, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("recommendation: none. No setting is steadily positive"), text);
        assertTrue(text.contains("the strongest totals are:"), text);
        assertEquals(3, Grid.topByTotal(r, 3).size());
    }

    @Test
    void timeBucketsGroupTradesByEntryMinute() {
        long d = Bars.T_0400_1016;
        TradeSignal early = new TradeSignal(d + 390 * 60_000L, com.hamilla.sma.Action.BUY_LONG, 10);      // 10:30
        TradeSignal noon = new TradeSignal(d + 509 * 60_000L, com.hamilla.sma.Action.BUY_LONG, 10);       // 12:29
        TradeSignal late = new TradeSignal(d + 629 * 60_000L, com.hamilla.sma.Action.BUY_LONG, 10);       // 14:29
        List<BracketSimulator.Trade> trades = List.of(
                new BracketSimulator.Trade(early, BracketSimulator.Outcome.TARGET, 0, 0, 20),
                new BracketSimulator.Trade(noon, BracketSimulator.Outcome.STOP, 0, 0, -10),
                new BracketSimulator.Trade(late, BracketSimulator.Outcome.HELD, 0, 0, 5));
        List<Grid.TimeBucket> buckets = Grid.byTime(trades, com.hamilla.sma.SignalWindow.DEFAULT);
        assertEquals(4, buckets.size());
        assertEquals("10:30-11:29", buckets.get(0).label());
        assertEquals(1, buckets.get(0).targets());
        assertEquals("11:30-12:29", buckets.get(1).label());
        assertEquals(1, buckets.get(1).stops());
        assertEquals(-10.0, buckets.get(1).pnl(), 1e-9);
        assertEquals("13:30-14:29", buckets.get(3).label());
        assertEquals(1, buckets.get(3).held());
        assertEquals(0, buckets.get(2).trades());

        List<Grid.TimeBucket> morning = Grid.byTime(List.of(trades.get(0)), com.hamilla.sma.SignalWindow.parse("10:30-12:30"));
        assertEquals(2, morning.size());
        assertEquals("11:30-12:29", morning.get(1).label());
    }

    @Test
    void riskSizingMakesEveryStoppedTradeLoseTheBudget() {
        List<MinuteBar> bars = Bars.rivnOct15and16();
        Set<LocalDate> days = Set.of(LocalDate.of(2026, 10, 16));
        List<TradeSignal> signals = new SignalGenerator(Session.EXTENDED, 6).generate("RIVN", bars, days);

        Grid.Result r = new Grid(new BracketSimulator(bars)).run("RIVN", signals,
                List.of(0.08, 0.19, BracketSimulator.NONE), List.of(0.12, 0.25, BracketSimulator.NONE), 0.02, Sizing.risk(380, 2000));

        assertEquals(4750, r.shares(0.08));                       // 380 / 0.08
        assertEquals(2000, r.shares(0.19));
        assertEquals(2000, r.shares(BracketSimulator.NONE));
        // 8 cent stop, 12 cent target on 10/16: 11 targets, 1 stop, 0 held, now at 4,750 shares.
        assertEquals(11 * 0.12 * 4750 - 0.08 * 4750, r.pnl()[0][0], 1e-6);
        assertEquals(-380.0, r.cells()[0][0].worstTrade(), 1e-6);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Grid.print(r, new PrintStream(out, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("380 risked per trade (shares by stop: 0.08\u21924750 0.19\u21922000 none\u21922000)"), text);
        assertTrue(text.contains("recommendation: stop 0.08, target 0.12, 4750 shares") || text.contains("recommendation: stop 0.19, target 0.25, 2000 shares")
                || text.contains("recommendation: stop 0.08, target 0.25, 4750 shares") || text.contains("recommendation: stop 0.19, target 0.12, 2000 shares"), text);
    }

    @Test
    void defaultLevelsScaleWithTheMinuteRangeAndRoundToTicks() {
        assertEquals(List.of(0.03, 0.05, 0.06, 0.09, 0.12, 0.18, BracketSimulator.NONE), Grid.defaultLevels(Grid.STOP_MULTIPLES, 0.03));
        assertEquals(List.of(0.5, 0.75, 1.0, 1.5, 2.0, 3.0, BracketSimulator.NONE), Grid.defaultLevels(Grid.STOP_MULTIPLES, 0.5));
        assertEquals(List.of(0.01, 0.02, 0.03, 0.04, BracketSimulator.NONE), Grid.defaultLevels(Grid.STOP_MULTIPLES, 0.007)); // duplicates collapse
        assertEquals(0.08, Grid.nice(0.0812), 1e-9);
        assertEquals(1.25, Grid.nice(1.23), 1e-9);
        assertEquals(12.25, Grid.nice(12.3), 1e-9);
    }

    @Test
    void realOctober16GridAndPrintout() {
        List<MinuteBar> bars = Bars.rivnOct15and16();
        Set<LocalDate> days = Set.of(LocalDate.of(2026, 10, 16));
        List<TradeSignal> signals = new SignalGenerator(Session.EXTENDED, 6).generate("RIVN", bars, days);
        double range = Grid.typicalMinuteRange(bars, days);
        assertTrue(range > 0.01 && range < 0.05, "RIVN's regular-hours minute range should be a couple of cents, got " + range);

        Grid.Result r = new Grid(new BracketSimulator(bars)).run("RIVN", signals,
                List.of(0.08, 0.15, BracketSimulator.NONE), List.of(0.12, 0.40, BracketSimulator.NONE), range, 2000);

        assertEquals(2480.0, r.pnl()[0][0], 1e-6);       // 8 cent stop, 12 cent target
        assertEquals(7650.0, r.pnl()[0][2], 1e-6);       // 8 cent stop, no target
        assertTrue(r.recommendation().stop() < BracketSimulator.NONE && r.recommendation().target() < BracketSimulator.NONE);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Grid.print(r, new PrintStream(out, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("stop \\ target      0.12      0.40      none"), text);
        assertTrue(text.matches("(?s).*\\n0\\.08\\s+2,480\\s.*"), text);
        assertTrue(text.contains("by entry time, with these brackets:"), text);
        assertTrue(text.contains("10:30-11:29"), text);
        assertTrue(text.contains("13:30-14:29"), text);
        assertFalse(text.contains("14:30-"), text);
        assertTrue(text.contains("recommendation: stop "), text);
        assertTrue(text.contains("one day supplies 100% of the P&L"), text);
        assertTrue(text.contains("small sample"), text);
    }
}
