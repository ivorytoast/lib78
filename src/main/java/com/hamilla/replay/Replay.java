package com.hamilla.replay;

import com.hamilla.market.DailyBar;
import com.hamilla.market.PriceEvent;
import com.hamilla.massive.MassiveClient;
import com.hamilla.sma.MovingAverages;
import com.hamilla.sma.SmaSnapshot;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Stage one: run one past trading day for one symbol, trade by trade, and report how the
 * 20/50/200 day SMAs moved. At the end, the engine's value using the official close is checked
 * against Massive's own SMA endpoint for the same day.
 */
public final class Replay {

    /** Trading days of history to request. 200 trading days is about 290 calendar days; this leaves margin for holidays. */
    private static final int WARMUP_CALENDAR_DAYS = 330;
    private static final int LARGEST_WINDOW = 200;
    private static final double ORACLE_TOLERANCE = 1e-6;
    private static final LocalTime REGULAR_OPEN = LocalTime.of(9, 30);
    private static final LocalTime REGULAR_CLOSE = LocalTime.of(16, 0);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /**
     * @param symbol            ticker to replay
     * @param date              trading day to replay
     * @param regularHoursOnly  drop trades outside 09:30 to 16:00 Eastern
     * @param printEveryMinutes print a line to the console on each N minute boundary (0 disables)
     * @param csvOut            optional path to write one row per trade
     */
    public record Options(String symbol, LocalDate date, boolean regularHoursOnly, int printEveryMinutes, Path csvOut) {
    }

    /** What the replay found, for callers that want to inspect rather than read the console. */
    public record Result(String symbol, LocalDate date, long trades, SmaSnapshot first, SmaSnapshot last,
                         DailyBar officialBar, List<OracleCheck> oracleChecks) {
        public boolean allOraclesPass() {
            return oracleChecks.stream().allMatch(OracleCheck::pass);
        }
    }

    /** Engine value using the official close versus Massive's SMA endpoint for the same window and day. */
    public record OracleCheck(int window, double engine, OptionalDouble massive, boolean pass) {
    }

    private final MassiveClient client;
    private final PrintStream out;

    public Replay(MassiveClient client, PrintStream out) {
        this.client = client;
        this.out = out;
    }

    public Result run(Options opts) throws IOException, InterruptedException {
        String symbol = opts.symbol();
        LocalDate date = opts.date();

        List<DailyBar> history = client.dailyBars(symbol, date.minusDays(WARMUP_CALENDAR_DAYS), date.minusDays(1));
        List<Double> closes = new ArrayList<>(history.size());
        for (DailyBar bar : history) {
            closes.add(bar.close());
        }
        out.printf("%s %s: %d prior daily closes loaded (%s to %s)%n", symbol, date, closes.size(),
                history.isEmpty() ? "-" : history.get(0).date(),
                history.isEmpty() ? "-" : history.get(history.size() - 1).date());
        if (closes.size() < LARGEST_WINDOW - 1) {
            out.printf("warning: only %d closes, the 200 day SMA will be unavailable%n", closes.size());
        }

        List<DailyBar> dayBars = client.dailyBars(symbol, date, date);
        if (dayBars.isEmpty()) {
            throw new IOException("Massive has no daily bar for " + symbol + " on " + date + "; was it a trading day?");
        }
        DailyBar officialBar = dayBars.get(0);

        MovingAverages engine = new MovingAverages(symbol, closes);
        Collector collector = new Collector(opts, engine);
        try (collector) {
            new ReplaySource(client, symbol, date).stream(collector::accept);
        }

        if (collector.count == 0) {
            throw new IOException("no trades returned for " + symbol + " on " + date);
        }

        List<OracleCheck> checks = new ArrayList<>();
        SmaSnapshot atClose = engine.snapshotAt(collector.last.sipTimestampNanos(), officialBar.close());
        for (int window : MovingAverages.WINDOWS) {
            double engineValue = switch (window) {
                case 20 -> atClose.sma20();
                case 50 -> atClose.sma50();
                default -> atClose.sma200();
            };
            OptionalDouble massive = engine.ready(window) ? client.sma(symbol, window, date) : OptionalDouble.empty();
            boolean pass = massive.isPresent() && Math.abs(massive.getAsDouble() - engineValue) <= ORACLE_TOLERANCE;
            checks.add(new OracleCheck(window, engineValue, massive, pass));
        }

        Result result = new Result(symbol, date, collector.count, collector.first, collector.last, officialBar, checks);
        printSummary(result);
        return result;
    }

    private void printSummary(Result r) {
        out.println();
        out.printf("%s %s: %,d trades replayed%n", r.symbol(), r.date(), r.trades());
        out.printf("  first %s  %s%n", time(r.first()), smas(r.first()));
        out.printf("  last  %s  %s%n", time(r.last()), smas(r.last()));
        out.printf("  official bar: o=%.4f h=%.4f l=%.4f c=%.4f%n",
                r.officialBar().open(), r.officialBar().high(), r.officialBar().low(), r.officialBar().close());
        out.println("  oracle check (engine at official close vs Massive SMA endpoint):");
        for (OracleCheck c : r.oracleChecks()) {
            String massive = c.massive().isPresent() ? String.format(Locale.US, "%.6f", c.massive().getAsDouble()) : "n/a";
            String diff = c.massive().isPresent()
                    ? String.format(Locale.US, "%+.9f", c.engine() - c.massive().getAsDouble()) : "";
            out.printf("    sma%-3d engine=%-14.6f massive=%-14s %s %s%n",
                    c.window(), c.engine(), massive, c.pass() ? "PASS" : "FAIL", diff);
        }
    }

    private static String time(SmaSnapshot s) {
        return new PriceEvent(s.symbol(), s.sipTimestampNanos(), s.price(), 0).easternTime().format(TIME);
    }

    private static String smas(SmaSnapshot s) {
        return String.format(Locale.US, "px=%-10.4f sma20=%-10.4f sma50=%-10.4f sma200=%-10.4f",
                s.price(), s.sma20(), s.sma50(), s.sma200());
    }

    /** Feeds trades to the engine, prints at minute boundaries, and optionally writes a CSV. */
    private final class Collector implements AutoCloseable {
        private final Options opts;
        private final MovingAverages engine;
        private final PrintWriter csv;
        long count;
        SmaSnapshot first;
        SmaSnapshot last;
        private long lastPrintedBucket = Long.MIN_VALUE;

        Collector(Options opts, MovingAverages engine) throws IOException {
            this.opts = opts;
            this.engine = engine;
            if (opts.csvOut() != null) {
                csv = new PrintWriter(Files.newBufferedWriter(opts.csvOut(), StandardCharsets.UTF_8));
                csv.println("time_et,sip_timestamp_ns,price,size,sma20,sma50,sma200");
            } else {
                csv = null;
            }
        }

        void accept(PriceEvent event) {
            ZonedDateTime et = event.easternTime();
            if (opts.regularHoursOnly()) {
                LocalTime t = et.toLocalTime();
                if (t.isBefore(REGULAR_OPEN) || !t.isBefore(REGULAR_CLOSE)) {
                    return;
                }
            }
            SmaSnapshot snap = engine.update(event);
            if (first == null) {
                first = snap;
            }
            last = snap;
            count++;

            if (csv != null) {
                csv.printf(Locale.US, "%s,%d,%s,%s,%s,%s,%s%n", et.format(TIME), event.sipTimestampNanos(),
                        event.price(), event.size(), snap.sma20(), snap.sma50(), snap.sma200());
            }
            if (opts.printEveryMinutes() > 0) {
                long bucket = et.toEpochSecond() / (60L * opts.printEveryMinutes());
                if (bucket != lastPrintedBucket) {
                    lastPrintedBucket = bucket;
                    out.printf("%s  %s%n", et.format(TIME), smas(snap));
                }
            }
        }

        @Override
        public void close() {
            if (csv != null) {
                csv.close();
            }
        }
    }
}
