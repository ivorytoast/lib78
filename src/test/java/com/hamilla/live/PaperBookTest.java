package com.hamilla.live;

import com.hamilla.backtest.BracketSimulator;
import com.hamilla.backtest.SignalGenerator;
import com.hamilla.backtest.Sizing;
import com.hamilla.backtest.TradeSignal;
import com.hamilla.market.MinuteBar;
import com.hamilla.sma.Action;
import com.hamilla.sma.Session;
import com.hamilla.sma.SignalWindow;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperBookTest {

    private static final LocalDate OCT16 = LocalDate.of(2026, 10, 16);
    private static final SignalWindow WINDOW = SignalWindow.parse("10:30-12:30");
    private static final Bracket RIVN = new Bracket(0.19, 0.25);

    /** The RIVN fixture the backtest tests use: 2026-10-15 from 16:00 and all of 2026-10-16. */
    static List<MinuteBar> rivn() {
        List<MinuteBar> bars = new ArrayList<>();
        try (var in = PaperBookTest.class.getResourceAsStream("/rivn-bars-2026-10-15-16.csv");
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split(",");
                double c = Double.parseDouble(f[1]);
                bars.add(new MinuteBar(Long.parseLong(f[0]), c, Double.parseDouble(f[2]), Double.parseDouble(f[3]), c, 100));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bars;
    }

    @Test
    void aDayThroughTheBookIsTheGridsTrades() {
        List<MinuteBar> bars = rivn();
        List<TradeSignal> signals = new SignalGenerator(Session.EXTENDED, WINDOW, 6).generate("RIVN", bars, Set.of(OCT16));
        List<BracketSimulator.Trade> expected = new BracketSimulator(bars).simulate(signals, RIVN.stop(), RIVN.target(), 2000);

        PaperBook book = new PaperBook(Map.of("RIVN", RIVN), Sizing.shares(2000), 0, Session.EXTENDED, WINDOW, 6, OCT16, null);
        book.onBars("RIVN", bars);

        assertEquals(10, signals.size());
        assertEquals(expected.size(), book.trades().size());
        assertTrue(book.openPositions().isEmpty(), "everything is closed by 15:55");
        double total = 0;
        for (int i = 0; i < expected.size(); i++) {
            BracketSimulator.Trade e = expected.get(i);
            PaperBook.Trade t = book.trades().stream()
                    .filter(x -> x.position().barStartMs() == e.signal().barStartMs()).findFirst().orElseThrow();
            assertEquals(e.signal().price(), t.position().entryPrice(), 1e-9);
            assertEquals(2000, t.position().shares());
            assertEquals(e.exitBarMs(), t.exitBarMs(), e.toString());
            assertEquals(e.exitPrice(), t.exitPrice(), 1e-9, e.toString());
            assertEquals(e.pnl(), t.pnl(), 1e-6, e.toString());
            assertEquals(e.outcome().name().replace("HELD", "CLOSE"), t.exit().name());
            total += e.pnl();
        }
        assertEquals(total, book.dayPnl(), 1e-6);
    }

    @Test
    void shortsCarryTheirBracketsTheRightWayRound() {
        PaperBook book = new PaperBook(Map.of("RIVN", RIVN), Sizing.shares(2000), 0, Session.EXTENDED, WINDOW, 6, OCT16, null);
        book.onBars("RIVN", rivn());
        PaperBook.Position first = book.trades().get(0).position();
        assertEquals(Action.SHORT_SELL, first.action());
        assertEquals(12.93, first.entryPrice(), 1e-9);
        assertEquals(12.93 + 0.19, first.stopPrice(), 1e-9);
        assertEquals(12.93 - 0.25, first.targetPrice(), 1e-9);
    }

    @Test
    void aSignalThatWouldOverfillTheBookIsSkippedButStillCountsForTheCooldown() {
        List<String> events = new ArrayList<>();
        PaperBook.Listener listener = new PaperBook.Listener() {
            @Override
            public void opened(PaperBook.Position p, double openNotional) {
                events.add("open " + p.barStartMs());
            }

            @Override
            public void skipped(String symbol, long barStartMs, Action action, double price, int shares, double openNotional) {
                events.add("skip " + barStartMs);
            }
        };
        // 2000 RIVN is about $25,900; a $30,000 book holds one position at a time.
        PaperBook book = new PaperBook(Map.of("RIVN", RIVN), Sizing.shares(2000), 30_000, Session.EXTENDED, WINDOW, 6, OCT16, listener);
        book.onBars("RIVN", rivn());

        PaperBook unlimited = new PaperBook(Map.of("RIVN", RIVN), Sizing.shares(2000), 0, Session.EXTENDED, WINDOW, 6, OCT16, null);
        unlimited.onBars("RIVN", rivn());

        assertEquals(10, events.size(), "the same ten signals are seen; " + events);
        assertEquals(10, book.trades().size() + book.skippedSignals().size());
        assertTrue(book.skippedSignals().size() > 0, "some signals overlap an open position");
        assertEquals(unlimited.trades().size(), book.trades().size() + book.skippedSignals().size());
        for (PaperBook.Trade t : book.trades()) {
            assertTrue(t.position().notional() <= 30_000);
        }
    }

    @Test
    void sizingByRiskAndCapUsesEachTradesOwnPrice() {
        PaperBook book = new PaperBook(Map.of("RIVN", RIVN), Sizing.risk(500, 100).cappedAt(20_000), 0,
                Session.EXTENDED, WINDOW, 6, OCT16, null);
        book.onBars("RIVN", rivn());
        for (PaperBook.Trade t : book.trades()) {
            int byRisk = (int) Math.floor(500 / 0.19 + 1e-9);
            int byCap = (int) Math.floor(20_000 / t.position().entryPrice() + 1e-9);
            assertEquals(Math.min(byRisk, byCap), t.position().shares(), t.toString());
        }
    }

    @Test
    void earlierDaysOnlyWarmTheAveragesUp() {
        PaperBook book = new PaperBook(Map.of("RIVN", RIVN), Sizing.shares(2000), 0, Session.EXTENDED, WINDOW, 6,
                LocalDate.of(2026, 10, 15), null);
        book.onBars("RIVN", rivn());
        assertTrue(book.trades().isEmpty());
        assertTrue(book.openPositions().isEmpty());
    }

    @Test
    void closeAllTakesWhateverIsOpenOffAtTheLastClose() {
        List<MinuteBar> bars = rivn();
        List<MinuteBar> untilNoon = bars.stream().filter(b -> b.startMs() < com.hamilla.backtest.BarsAccess.at("12:00")).toList();
        PaperBook book = new PaperBook(Map.of("RIVN", RIVN), Sizing.shares(2000), 0, Session.EXTENDED, WINDOW, 6, OCT16, null);
        book.onBars("RIVN", untilNoon);
        int before = book.trades().size();
        List<PaperBook.Trade> closed = book.closeAll();
        assertEquals(book.openPositions().size(), 0);
        assertEquals(before + closed.size(), book.trades().size());
        for (PaperBook.Trade t : closed) {
            assertEquals(PaperBook.Exit.CLOSE, t.exit());
            assertEquals(untilNoon.get(untilNoon.size() - 1).close(), t.exitPrice(), 1e-9);
        }
    }

    @Test
    void barsOutOfOrderOrRepeatedAreIgnored() {
        List<MinuteBar> bars = rivn();
        PaperBook once = new PaperBook(Map.of("RIVN", RIVN), Sizing.shares(2000), 0, Session.EXTENDED, WINDOW, 6, OCT16, null);
        once.onBars("RIVN", bars);
        PaperBook twice = new PaperBook(Map.of("RIVN", RIVN), Sizing.shares(2000), 0, Session.EXTENDED, WINDOW, 6, OCT16, null);
        for (int i = 0; i < bars.size(); i++) {
            twice.onBar("RIVN", bars.get(i));
            twice.onBar("RIVN", bars.get(i));            // the same bar fetched again
            if (i > 0) {
                twice.onBar("RIVN", bars.get(i - 1));    // and an older one
            }
        }
        assertEquals(once.trades(), twice.trades());
    }

    @Test
    void aSymbolWithoutABracketIsRejected() {
        PaperBook book = new PaperBook(Map.of("RIVN", RIVN), Sizing.shares(2000), 0, Session.EXTENDED, WINDOW, 6, OCT16, null);
        assertThrows(IllegalArgumentException.class, () -> book.onBar("SPY", rivn().get(0)));
    }

    @Test
    void bracketsParse() {
        Map<String, Bracket> all = Bracket.parseAll("stld=0.18/1.10, DIS=0.16/0.24");
        assertEquals(List.of("STLD", "DIS"), List.copyOf(all.keySet()));
        assertEquals(1.10, all.get("STLD").target(), 1e-9);
        assertEquals(0.16, all.get("DIS").stop(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> Bracket.parseEntry("STLD=0.18"));
        assertThrows(IllegalArgumentException.class, () -> Bracket.parseEntry("STLD=0/1"));
        assertThrows(IllegalArgumentException.class, () -> Bracket.parseEntry("0.18/1.10"));
    }

    @Test
    void onlyCompletedBarsAreFed() {
        MinuteBar forming = new MinuteBar(1_000_000L, 1, 1, 1, 1, 1);
        MinuteBar done = new MinuteBar(940_000L, 1, 1, 1, 1, 1);
        assertEquals(List.of(done), Live.completed(List.of(done, forming), 1_000_000L + 30_000L));
        assertEquals(List.of(done, forming), Live.completed(List.of(done, forming), 1_060_000L));
    }

    @Test
    void aFormingBarFillsAStopOrTargetBeforeTheMinuteCloses() {
        List<MinuteBar> bars = rivn();
        PaperBook book = new PaperBook(Map.of("RIVN", RIVN), Sizing.shares(2000), 0, Session.EXTENDED, WINDOW, 6, OCT16, null);
        // Feed through the first signal bar (10:31 short at 12.93, target 12.68).
        for (MinuteBar b : bars) {
            if (b.startMs() > com.hamilla.backtest.BarsAccess.at("10:31")) {
                break;
            }
            book.onBar("RIVN", b);
        }
        assertEquals(1, book.openPositions().size());
        PaperBook.Position p = book.openPositions().get(0);

        // A forming 10:32 minute that has not reached either level yet changes nothing.
        long t1032 = com.hamilla.backtest.BarsAccess.at("10:32");
        book.onFormingBar("RIVN", new MinuteBar(t1032, 12.93, 12.95, 12.90, 12.91, 10));
        assertEquals(1, book.openPositions().size());
        assertTrue(book.trades().isEmpty());

        // Later in the same minute the low touches the target: taken off now, at the target price.
        book.onFormingBar("RIVN", new MinuteBar(t1032, 12.93, 12.95, 12.68, 12.70, 10));
        assertTrue(book.openPositions().isEmpty());
        assertEquals(1, book.trades().size());
        PaperBook.Trade t = book.trades().get(0);
        assertEquals(PaperBook.Exit.TARGET, t.exit());
        assertEquals(p.targetPrice(), t.exitPrice(), 1e-9);
        assertEquals(t1032, t.exitBarMs());
        assertEquals(0.25 * 2000, t.pnl(), 1e-6);

        // The completed 10:32 bar then arrives: nothing doubles up.
        book.onBar("RIVN", new MinuteBar(t1032, 12.93, 12.95, 12.68, 12.70, 10));
        assertEquals(1, book.trades().size());
        assertEquals(0.25 * 2000, book.dayPnl(), 1e-6);
    }

    @Test
    void aFormingBarNeverEntersOrClosesAtTheDayEnd() {
        List<MinuteBar> bars = rivn();
        PaperBook book = new PaperBook(Map.of("RIVN", RIVN), Sizing.shares(2000), 0, Session.EXTENDED, WINDOW, 6, OCT16, null);
        for (MinuteBar b : bars) {
            if (b.startMs() > com.hamilla.backtest.BarsAccess.at("10:30")) {
                break;
            }
            book.onBar("RIVN", b);
        }
        // The 10:31 bar is a signal when completed; as a forming bar it is not acted on.
        MinuteBar signalBar = bars.stream().filter(b -> b.startMs() == com.hamilla.backtest.BarsAccess.at("10:31")).findFirst().orElseThrow();
        book.onFormingBar("RIVN", signalBar);
        assertTrue(book.openPositions().isEmpty());
        book.onBar("RIVN", signalBar);
        assertEquals(1, book.openPositions().size());
        // A forming 15:54 bar that reaches neither level leaves the position open; only the completed bar closes it.
        book.onFormingBar("RIVN", new MinuteBar(com.hamilla.backtest.BarsAccess.at("15:54"), 12.90, 12.95, 12.85, 12.90, 10));
        assertEquals(1, book.openPositions().size());
    }

    @Test
    void theFormingBarIsTheOneWhoseMinuteHasNotEnded() {
        MinuteBar done = new MinuteBar(940_000L, 1, 1, 1, 1, 1);
        MinuteBar forming = new MinuteBar(1_000_000L, 1, 1, 1, 1, 1);
        assertEquals(java.util.Optional.of(forming), Live.forming(List.of(done, forming), 1_030_000L));
        assertEquals(java.util.Optional.empty(), Live.forming(List.of(done, forming), 1_060_000L));
        assertEquals(java.util.Optional.empty(), Live.forming(List.of(done), 1_030_000L));
    }
}
