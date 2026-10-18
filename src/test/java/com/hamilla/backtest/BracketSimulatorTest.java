package com.hamilla.backtest;

import com.hamilla.market.MinuteBar;
import com.hamilla.sma.Action;
import com.hamilla.sma.Session;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BracketSimulatorTest {

    private static final LocalDate OCT16 = LocalDate.of(2026, 10, 16);

    @Test
    void rivnOctober16WithEightCentStopAndTwelveCentTarget() {
        List<MinuteBar> bars = Bars.rivnOct15and16();
        List<TradeSignal> signals = new SignalGenerator(Session.EXTENDED, 6).generate("RIVN", bars, Set.of(OCT16));
        BracketSimulator sim = new BracketSimulator(bars);

        List<BracketSimulator.Trade> trades = sim.simulate(signals, 0.08, 0.12, 2000);
        assertEquals(19, trades.size());                               // the 15:57 signal cannot be held
        BracketSimulator.Summary s = sim.summarize(signals, 0.08, 0.12, 2000);
        assertEquals(15, s.targets());
        assertEquals(1, s.stops());
        assertEquals(3, s.held());
        assertEquals(3856.20, s.pnl(), 1e-6);
        assertEquals(-160.0, s.worstTrade(), 1e-9);
        assertEquals(3856.20, s.pnlByDay().get(OCT16), 1e-6);

        BracketSimulator.Trade first = trades.get(0);                  // 10:02 short at 12.995, target 12.875 hit at 10:54
        assertEquals(BracketSimulator.Outcome.TARGET, first.outcome());
        assertEquals(Bars.at("10:54"), first.exitBarMs());
        assertEquals(240.0, first.pnl(), 1e-9);

        BracketSimulator.Trade stopped = trades.get(14);               // 13:20 short at 12.5401, stop 12.6201 hit at 13:41
        assertEquals(BracketSimulator.Outcome.STOP, stopped.outcome());
        assertEquals(Bars.at("13:41"), stopped.exitBarMs());
        assertEquals(-160.0, stopped.pnl(), 1e-9);

        BracketSimulator.Trade held = trades.get(16);                  // 14:31 short at 12.46, closed at the 15:54 bar's 12.3899
        assertEquals(BracketSimulator.Outcome.HELD, held.outcome());
        assertEquals(Bars.at("15:54"), held.exitBarMs());
        assertEquals(12.3899, held.exitPrice(), 1e-9);
        assertEquals(140.2, held.pnl(), 1e-6);
    }

    @Test
    void stopOnlyMatchesTheHandRun() {
        List<MinuteBar> bars = Bars.rivnOct15and16();
        List<TradeSignal> signals = new SignalGenerator(Session.EXTENDED, 6).generate("RIVN", bars, Set.of(OCT16));
        BracketSimulator.Summary s = new BracketSimulator(bars).summarize(signals, 0.08, BracketSimulator.NONE, 2000);
        assertEquals(12777.0, s.pnl(), 1e-6);
        assertEquals(1, s.stops());
        assertEquals(18, s.held());
    }

    private static MinuteBar bar(long t, double o, double h, double l, double c) {
        return new MinuteBar(t, o, h, l, c, 100);
    }

    @Test
    void aMinuteTouchingBothBracketsCountsAsTheStop() {
        long m = Bars.at("10:00");
        List<MinuteBar> bars = List.of(
                bar(m, 10, 10, 10, 10),
                bar(m + 60_000, 10, 10.5, 9.5, 10),         // both the 0.10 stop and 0.20 target are inside this range
                bar(Bars.at("15:54"), 10, 10, 10, 10));
        TradeSignal longSignal = new TradeSignal(m, Action.BUY_LONG, 10.0);
        List<BracketSimulator.Trade> trades = new BracketSimulator(bars).simulate(List.of(longSignal), 0.10, 0.20, 100);
        assertEquals(BracketSimulator.Outcome.STOP, trades.get(0).outcome());
        assertEquals(-10.0, trades.get(0).pnl(), 1e-9);
    }

    @Test
    void targetBeforeStopWinsAndLateSignalsAreSkipped() {
        long m = Bars.at("10:00");
        List<MinuteBar> bars = List.of(
                bar(m, 10, 10, 10, 10),
                bar(m + 60_000, 10, 10.05, 9.98, 10),
                bar(m + 120_000, 10, 10.25, 10.0, 10.2),     // target 10.20 reached
                bar(m + 180_000, 10, 10.3, 9.5, 9.6),        // stop would be hit here, too late
                bar(Bars.at("15:54"), 9.6, 9.6, 9.6, 9.6));
        BracketSimulator sim = new BracketSimulator(bars);
        List<TradeSignal> signals = List.of(
                new TradeSignal(m, Action.BUY_LONG, 10.0),
                new TradeSignal(Bars.at("15:54"), Action.SHORT_SELL, 9.6),
                new TradeSignal(Bars.at("15:57"), Action.SHORT_SELL, 9.6));
        List<BracketSimulator.Trade> trades = sim.simulate(signals, 0.10, 0.20, 100);
        assertEquals(1, trades.size());
        assertEquals(BracketSimulator.Outcome.TARGET, trades.get(0).outcome());
        assertEquals(20.0, trades.get(0).pnl(), 1e-9);
        assertTrue(sim.days().contains(LocalDate.of(2026, 10, 16)));
    }

    @Test
    void shortSideUsesHighForStopAndLowForTarget() {
        long m = Bars.at("11:00");
        List<MinuteBar> bars = List.of(
                bar(m, 20, 20, 20, 20),
                bar(m + 60_000, 20, 20.04, 19.9, 19.95),     // target 19.90 reached, stop 20.10 not
                bar(Bars.at("15:54"), 19.95, 19.95, 19.95, 19.95));
        List<BracketSimulator.Trade> trades = new BracketSimulator(bars)
                .simulate(List.of(new TradeSignal(m, Action.SHORT_SELL, 20.0)), 0.10, 0.10, 50);
        assertEquals(BracketSimulator.Outcome.TARGET, trades.get(0).outcome());
        assertEquals(5.0, trades.get(0).pnl(), 1e-9);
    }
}
