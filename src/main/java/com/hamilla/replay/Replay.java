package com.hamilla.replay;

import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;
import com.hamilla.massive.MassiveClient;
import com.hamilla.sma.MinuteBarTracker;
import com.hamilla.sma.MovingAverages;
import com.hamilla.sma.SessionMinutes;
import com.hamilla.sma.SmaSnapshot;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Stage one: run one past trading day for one symbol, trade by trade, and report how the
 * 20/50/200 minute-bar SMAs moved. Every completed bar's values are checked against Massive's
 * own minute SMA endpoint for the same bar, which is the correctness test for the engine.
 */
public final class Replay {

    /** Calendar days of minute bars to load before the replay day; 199 bars is well under one session. */
    private static final int WARMUP_CALENDAR_DAYS = 7;
    private static final int LARGEST_WINDOW = 200;
    private static final double ORACLE_TOLERANCE = 1e-6;
    private static final int MISMATCH_EXAMPLES = 5;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter BAR_TIME = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * @param symbol            ticker to replay
     * @param date              trading day to replay
     * @param printEveryMinutes print a line to the console on each N minute boundary (0 disables)
     * @param csvOut            optional path to write one row per updating trade
     */
    public record Options(String symbol, LocalDate date, int printEveryMinutes, Path csvOut) {
    }

    /** One bar where the engine and Massive disagree. */
    public record Mismatch(long barStartMs, double engine, double massive) {
    }

    /** How one window's values compared with Massive's, bar by bar. */
    public record OracleCheck(int window, int barsCompared, int barsMatched, double maxAbsDiff,
                              int barsMissingFromMassive, int barsMissingFromEngine, List<Mismatch> examples) {
        public boolean pass() {
            return barsCompared > 0 && barsMatched == barsCompared && barsMissingFromEngine == 0;
        }
    }

    /** What the replay found, for callers that want to inspect rather than read the console. */
    public record Result(String symbol, LocalDate date, long tradesSeen, long tradesApplied, long barsClosed,
                         long barsFilled, SmaSnapshot first, SmaSnapshot last, List<OracleCheck> oracleChecks) {
        public boolean allOraclesPass() {
            return oracleChecks.stream().allMatch(OracleCheck::pass);
        }
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

        List<MinuteBar> history = client.minuteBars(symbol, date.minusDays(WARMUP_CALENDAR_DAYS), date.minusDays(1));
        List<Double> closes = history.isEmpty() ? List.of()
                : SessionMinutes.fillCloses(history, SessionMinutes.lastBarOfSession(history.get(history.size() - 1).startMs()));
        out.printf("%s %s: %d prior minute bars loaded, %d session minutes after filling (%s to %s)%n",
                symbol, date, history.size(), closes.size(),
                history.isEmpty() ? "-" : barTime(history.get(0).startMs()),
                history.isEmpty() ? "-" : barTime(history.get(history.size() - 1).startMs()));
        if (closes.size() < LARGEST_WINDOW - 1) {
            out.printf("warning: only %d bars, the 200 bar SMA will be unavailable at first%n", closes.size());
        }

        List<TreeMap<Long, Double>> massive = new ArrayList<>();
        for (int window : MovingAverages.WINDOWS) {
            massive.add(client.minuteSma(symbol, window, date));
        }

        MovingAverages engine = new MovingAverages(symbol, closes);
        Collector collector = new Collector(opts, engine, massive);
        if (!history.isEmpty()) {
            MinuteBar lastBar = history.get(history.size() - 1);
            collector.tracker.seed(SessionMinutes.lastBarOfSession(lastBar.startMs()), lastBar.close());
        }
        try (collector) {
            new ReplaySource(client, symbol, date).stream(collector::accept);
            collector.tracker.finishSession();
        }

        if (collector.applied == 0) {
            throw new IOException("no trades returned for " + symbol + " on " + date);
        }

        List<OracleCheck> checks = new ArrayList<>();
        for (int i = 0; i < MovingAverages.WINDOWS.length; i++) {
            int missingFromEngine = 0;
            for (long t : massive.get(i).keySet()) {
                if (!collector.engineBars.contains(t)) {
                    missingFromEngine++;
                }
            }
            checks.add(new OracleCheck(MovingAverages.WINDOWS[i], collector.compared[i], collector.matched[i],
                    collector.maxDiff[i], collector.missingFromMassive[i], missingFromEngine, collector.examples.get(i)));
        }

        Result result = new Result(symbol, date, collector.seen, collector.applied, collector.tracker.barsClosed(),
                collector.tracker.barsFilled(), collector.first, collector.tracker.lastSnapshot(), checks);
        printSummary(result);
        return result;
    }

    private void printSummary(Result r) {
        out.println();
        out.printf("%s %s: %,d trades seen, %,d applied to bars, %,d bars closed (%,d of them repeated an empty minute)%n",
                r.symbol(), r.date(), r.tradesSeen(), r.tradesApplied(), r.barsClosed(), r.barsFilled());
        out.printf("  first %s  %s%n", time(r.first()), smas(r.first()));
        out.printf("  last  %s  %s%n", time(r.last()), smas(r.last()));
        out.println("  oracle check (engine at each bar close vs Massive minute SMA):");
        for (OracleCheck c : r.oracleChecks()) {
            out.printf("    sma%-3d %4d/%-4d bars match, max diff %.9f, %d bars Massive lacks, %d bars engine lacks  %s%n",
                    c.window(), c.barsMatched(), c.barsCompared(), c.maxAbsDiff(),
                    c.barsMissingFromMassive(), c.barsMissingFromEngine(), c.pass() ? "PASS" : "FAIL");
            for (Mismatch m : c.examples()) {
                out.printf("      %s engine=%.6f massive=%.6f diff=%+.6f%n",
                        barTime(m.barStartMs()), m.engine(), m.massive(), m.engine() - m.massive());
            }
        }
    }

    private static String barTime(long startMs) {
        return Instant.ofEpochMilli(startMs).atZone(PriceEvent.EASTERN).format(BAR_TIME);
    }

    private static String time(SmaSnapshot s) {
        return new PriceEvent(s.symbol(), s.sipTimestampNanos(), s.price(), 0).easternTime().format(TIME);
    }

    private static String smas(SmaSnapshot s) {
        return String.format(Locale.US, "px=%-10.4f sma20=%-10.4f sma50=%-10.4f sma200=%-10.4f",
                s.price(), s.sma20(), s.sma50(), s.sma200());
    }

    /** Feeds trades to the tracker, checks each closed bar against Massive, prints, and optionally writes a CSV. */
    private final class Collector implements AutoCloseable, MinuteBarTracker.BarListener {
        private final Options opts;
        private final List<TreeMap<Long, Double>> massive;
        private final PrintWriter csv;
        final MinuteBarTracker tracker;
        final java.util.Set<Long> engineBars = new java.util.HashSet<>();
        final int[] compared = new int[MovingAverages.WINDOWS.length];
        final int[] matched = new int[MovingAverages.WINDOWS.length];
        final int[] missingFromMassive = new int[MovingAverages.WINDOWS.length];
        final double[] maxDiff = new double[MovingAverages.WINDOWS.length];
        final List<List<Mismatch>> examples = new ArrayList<>();
        long seen;
        long applied;
        SmaSnapshot first;
        private long lastPrintedBucket = Long.MIN_VALUE;

        Collector(Options opts, MovingAverages engine, List<TreeMap<Long, Double>> massive) throws IOException {
            this.opts = opts;
            this.massive = massive;
            this.tracker = new MinuteBarTracker(engine, this);
            for (int i = 0; i < MovingAverages.WINDOWS.length; i++) {
                examples.add(new ArrayList<>());
            }
            if (opts.csvOut() != null) {
                csv = new PrintWriter(Files.newBufferedWriter(opts.csvOut(), StandardCharsets.UTF_8));
                csv.println("time_et,sip_timestamp_ns,price,size,sma20,sma50,sma200");
            } else {
                csv = null;
            }
        }

        void accept(PriceEvent event) {
            seen++;
            var maybe = tracker.onTrade(event);
            if (maybe.isEmpty()) {
                return;
            }
            SmaSnapshot snap = maybe.get();
            applied++;
            if (first == null) {
                first = snap;
            }
            ZonedDateTime et = event.easternTime();
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
        public void barClosed(long barStartMs, double close, SmaSnapshot atClose, boolean filled) {
            engineBars.add(barStartMs);
            double[] engineValues = {atClose.sma20(), atClose.sma50(), atClose.sma200()};
            for (int i = 0; i < MovingAverages.WINDOWS.length; i++) {
                if (Double.isNaN(engineValues[i])) {
                    continue;
                }
                Double expected = massive.get(i).get(barStartMs);
                if (expected == null) {
                    missingFromMassive[i]++;
                    continue;
                }
                compared[i]++;
                double diff = Math.abs(engineValues[i] - expected);
                maxDiff[i] = Math.max(maxDiff[i], diff);
                if (diff <= ORACLE_TOLERANCE) {
                    matched[i]++;
                } else if (examples.get(i).size() < MISMATCH_EXAMPLES) {
                    examples.get(i).add(new Mismatch(barStartMs, engineValues[i], expected));
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
