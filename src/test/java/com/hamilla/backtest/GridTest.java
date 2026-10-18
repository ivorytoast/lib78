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
        Grid.Recommendation rec = Grid.recommend(stops, targets, pnl, cells);
        // The spike's own neighbourhood averages (9000 + 8*100)/9 = 1089. The row below it borders the
        // strong "none" row: (0.15, 0.20) averages (5*100 + 9000 + 3*5000)/9 = 2722 and wins; its edge
        // neighbours (0.15, 0.10) and (0.15, 0.30) pad their missing cells with their own 100.
        assertEquals(0.15, rec.stop());
        assertEquals(0.20, rec.target());
        assertEquals((100 + 9000 + 100 + 100 + 100 + 100 + 5000 + 5000 + 5000) / 9.0, rec.neighbourhoodAverage(), 1e-9);
        assertEquals(0.15 / 0.35, rec.breakevenWinRate(), 1e-9);
        assertEquals(5.0 / 8, rec.winRate(), 1e-9);
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

        assertEquals(3856.20, r.pnl()[0][0], 1e-6);      // 8 cent stop, 12 cent target, as computed by hand earlier
        assertEquals(12777.0, r.pnl()[0][2], 1e-6);      // 8 cent stop, no target
        assertTrue(r.recommendation().stop() < BracketSimulator.NONE && r.recommendation().target() < BracketSimulator.NONE);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Grid.print(r, new PrintStream(out, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("stop \\ target      0.12      0.40      none"), text);
        assertTrue(text.matches("(?s).*\\n0\\.08\\s+3,856\\s.*"), text);
        assertTrue(text.contains("recommendation: stop "), text);
        assertTrue(text.contains("one day supplies 100% of the P&L"), text);
        assertTrue(text.contains("small sample"), text);
    }
}
