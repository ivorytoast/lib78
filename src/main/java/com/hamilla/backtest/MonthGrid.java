package com.hamilla.backtest;

import com.hamilla.market.DailyBar;
import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;
import com.hamilla.massive.MassiveClient;
import com.hamilla.sma.Session;
import com.hamilla.sma.SignalWindow;
import com.hamilla.sma.SymbolSettings;

import java.io.IOException;
import java.io.PrintStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Fetches a month of data from Massive, generates the signals, and prints the grid and recommendation. */
public final class MonthGrid {

    private static final int WARMUP_CALENDAR_DAYS = 7;

    /**
     * @param symbol    ticker
     * @param month     the first (or only) month to test; only completed trading days are used
     * @param toMonth   the last month of a range, or null for a single month
     * @param session   bar session for the signals
     * @param window    minutes whose closes may produce a signal
     * @param cooldown  signal cooldown in minutes
     * @param shares    shares per trade (0 means the symbol's configured default)
     * @param stops     explicit stop levels, or null for defaults built from the typical minute range
     * @param targets   explicit target levels, or null for defaults
     */
    public record Options(String symbol, YearMonth month, YearMonth toMonth, Session session, SignalWindow window,
                          int cooldown, int shares, List<Double> stops, List<Double> targets, List<double[]> show) {
        public Options(String symbol, YearMonth month, Session session, int cooldown, int shares,
                       List<Double> stops, List<Double> targets) {
            this(symbol, month, null, session, SignalWindow.DEFAULT, cooldown, shares, stops, targets, List.of());
        }

        public Options(String symbol, YearMonth month, YearMonth toMonth, Session session, int cooldown, int shares,
                       List<Double> stops, List<Double> targets) {
            this(symbol, month, toMonth, session, SignalWindow.DEFAULT, cooldown, shares, stops, targets, List.of());
        }

        public Options(String symbol, YearMonth month, YearMonth toMonth, Session session, int cooldown, int shares,
                       List<Double> stops, List<Double> targets, List<double[]> show) {
            this(symbol, month, toMonth, session, SignalWindow.DEFAULT, cooldown, shares, stops, targets, show);
        }

        public Options {
            if (toMonth != null && toMonth.isBefore(month)) {
                throw new IllegalArgumentException("the range ends before it starts: " + month + " to " + toMonth);
            }
        }

        public boolean isRange() {
            return toMonth != null && !toMonth.equals(month);
        }

        public YearMonth lastMonth() {
            return toMonth == null ? month : toMonth;
        }
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

    /** Parses {@code stop,target}, e.g. {@code 0.05,0.25} or {@code none,0.09}. */
    public static double[] parsePair(String text) {
        List<Double> levels = parseLevels(text);
        if (levels.size() != 2) {
            throw new IllegalArgumentException("--show needs a stop and a target, e.g. 0.05,0.25, got '" + text + "'");
        }
        return new double[]{levels.get(0), levels.get(1)};
    }

    public static List<Double> parseLevels(String text) {
        List<Double> levels = new ArrayList<>();
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

    /** Runs one month or a range; one of the two results is non-null. */
    public record Outcome(Grid.Result single, Grid.MultiResult multi) {
    }

    public Grid.Result run(Options opts) throws IOException, InterruptedException {
        Outcome outcome = runAny(opts);
        if (outcome.single() == null) {
            throw new IllegalStateException("range options passed to run(); use runAny()");
        }
        return outcome.single();
    }

    public Outcome runAny(Options opts) throws IOException, InterruptedException {
        LocalDate first = opts.month().atDay(1);
        LocalDate last = opts.lastMonth().atEndOfMonth();
        LocalDate today = LocalDate.now(PriceEvent.EASTERN);
        if (!last.isBefore(today)) {
            last = today.minusDays(1);
        }
        if (last.isBefore(first)) {
            throw new IOException("no completed trading days in " + opts.month() + (opts.isRange() ? " to " + opts.lastMonth() : "") + " yet");
        }

        Set<LocalDate> days = new TreeSet<>();
        for (DailyBar bar : client.dailyBars(opts.symbol(), first, last)) {
            days.add(bar.date());
        }
        if (days.isEmpty()) {
            throw new IOException("Massive has no trading days for " + opts.symbol() + " in " + opts.month()
                    + (opts.isRange() ? " to " + opts.lastMonth() : ""));
        }

        List<MinuteBar> bars = client.minuteBars(opts.symbol(), first.minusDays(WARMUP_CALENDAR_DAYS), last);
        List<TradeSignal> signals = new SignalGenerator(opts.session(), opts.window(), opts.cooldown()).generate(opts.symbol(), bars, days);
        TreeSet<LocalDate> sorted = (TreeSet<LocalDate>) days;
        out.printf("%s %s%s: %d trading days (%s to %s), %d signals in window %s%n", opts.symbol(), opts.month(),
                opts.isRange() ? " to " + opts.lastMonth() : "", days.size(), sorted.first(), sorted.last(), signals.size(), opts.window());
        if (signals.isEmpty()) {
            throw new IOException("no signals in the period");
        }

        double range = Grid.typicalMinuteRange(bars, days);
        List<Double> stops = opts.stops() != null ? opts.stops() : Grid.defaultLevels(Grid.STOP_MULTIPLES, range);
        List<Double> targets = opts.targets() != null ? opts.targets() : Grid.defaultLevels(Grid.TARGET_MULTIPLES, range);
        int shares = opts.shares() > 0 ? opts.shares() : SymbolSettings.shares(opts.symbol());
        Grid grid = new Grid(new BracketSimulator(bars), opts.window());

        if (!opts.isRange()) {
            Grid.Result result = grid.run(opts.symbol(), signals, stops, targets, range, shares);
            Grid.print(result, out);
            return new Outcome(result, null);
        }

        Map<YearMonth, List<TradeSignal>> byMonth = new LinkedHashMap<>();
        for (YearMonth m = opts.month(); !m.isAfter(opts.lastMonth()); m = m.plusMonths(1)) {
            byMonth.put(m, new ArrayList<>());
        }
        for (TradeSignal s : signals) {
            byMonth.get(YearMonth.from(s.date())).add(s);
        }
        Grid.MultiResult result = grid.runMonths(opts.symbol(), byMonth, stops, targets, range, shares, opts.show());
        Grid.print(result, out);
        return new Outcome(null, result);
    }
}
