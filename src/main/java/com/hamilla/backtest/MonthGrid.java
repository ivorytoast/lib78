package com.hamilla.backtest;

import com.hamilla.market.DailyBar;
import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;
import com.hamilla.massive.MassiveClient;
import com.hamilla.sma.Session;
import com.hamilla.sma.SymbolSettings;

import java.io.IOException;
import java.io.PrintStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Fetches a month of data from Massive, generates the signals, and prints the grid and recommendation. */
public final class MonthGrid {

    private static final int WARMUP_CALENDAR_DAYS = 7;

    /**
     * @param symbol    ticker
     * @param month     the month to test; only completed trading days are used
     * @param session   bar session for the signals
     * @param cooldown  signal cooldown in minutes
     * @param shares    shares per trade (0 means the symbol's configured default)
     * @param stops     explicit stop levels, or null for defaults built from the typical minute range
     * @param targets   explicit target levels, or null for defaults
     */
    public record Options(String symbol, YearMonth month, Session session, int cooldown, int shares,
                          List<Double> stops, List<Double> targets) {
    }

    /** Parses {@code YYYY-MM} or {@code MM/YYYY}. */
    public static YearMonth parseMonth(String text) {
        String t = text.trim();
        try {
            if (t.matches("\\d{1,2}/\\d{4}")) {
                return YearMonth.parse(t, DateTimeFormatter.ofPattern("M/yyyy", Locale.US));
            }
            return YearMonth.parse(t);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month must look like 2026-10 or 10/2026, got '" + text + "'", e);
        }
    }

    public static List<Double> parseLevels(String text) {
        List<Double> levels = new java.util.ArrayList<>();
        for (String part : text.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            if (part.trim().equalsIgnoreCase("none")) {
                levels.add(BracketSimulator.NONE);
            } else {
                double v = Double.parseDouble(part.trim());
                if (v <= 0) {
                    throw new IllegalArgumentException("levels must be positive, got " + part);
                }
                levels.add(v);
            }
        }
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("no levels in '" + text + "'");
        }
        return levels;
    }

    private final MassiveClient client;
    private final PrintStream out;

    public MonthGrid(MassiveClient client, PrintStream out) {
        this.client = client;
        this.out = out;
    }

    public Grid.Result run(Options opts) throws IOException, InterruptedException {
        LocalDate first = opts.month().atDay(1);
        LocalDate last = opts.month().atEndOfMonth();
        LocalDate today = LocalDate.now(PriceEvent.EASTERN);
        if (!last.isBefore(today)) {
            last = today.minusDays(1);
        }
        if (last.isBefore(first)) {
            throw new IOException("no completed trading days in " + opts.month() + " yet");
        }

        Set<LocalDate> days = new TreeSet<>();
        for (DailyBar bar : client.dailyBars(opts.symbol(), first, last)) {
            days.add(bar.date());
        }
        if (days.isEmpty()) {
            throw new IOException("Massive has no trading days for " + opts.symbol() + " in " + opts.month());
        }

        List<MinuteBar> bars = client.minuteBars(opts.symbol(), first.minusDays(WARMUP_CALENDAR_DAYS), last);
        List<TradeSignal> signals = new SignalGenerator(opts.session(), opts.cooldown()).generate(opts.symbol(), bars, days);
        out.printf("%s %s: %d trading days (%s to %s), %d signals%n", opts.symbol(), opts.month(), days.size(),
                days.iterator().next(), ((TreeSet<LocalDate>) days).last(), signals.size());
        if (signals.isEmpty()) {
            throw new IOException("no signals in " + opts.month());
        }

        double range = Grid.typicalMinuteRange(bars, days);
        List<Double> stops = opts.stops() != null ? opts.stops() : Grid.defaultLevels(Grid.STOP_MULTIPLES, range);
        List<Double> targets = opts.targets() != null ? opts.targets() : Grid.defaultLevels(Grid.TARGET_MULTIPLES, range);
        int shares = opts.shares() > 0 ? opts.shares() : SymbolSettings.shares(opts.symbol());

        BracketSimulator simulator = new BracketSimulator(bars);
        Grid.Result result = new Grid(simulator).run(opts.symbol(), signals, stops, targets, range, shares);
        Grid.print(result, out);
        return result;
    }
}
