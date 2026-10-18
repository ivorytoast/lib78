package com.hamilla.replay;

import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;
import com.hamilla.massive.MassiveClient;
import com.hamilla.sma.MinuteBarTracker;
import com.hamilla.sma.MovingAverages;
import com.hamilla.sma.Session;
import com.hamilla.sma.SmaSnapshot;
import com.hamilla.sma.Trend;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Replays one past trading day for one symbol, trade by trade, on the one-minute bar series.
 * Directly after each minute closes, from 09:45 through 15:59 Eastern, it prints the minute and
 * whether the 20/50/200 alignment and price say "up", "down" or "none". Earlier minutes, including
 * pre-market, still feed the averages; they are just not printed.
 *
 * <p>By default the bars are the chart series: the {@link Options#session()} window, with a bar
 * only for a minute that had a close-updating trade. With {@link Options#verify()} the replay
 * instead runs on Massive's filled 04:00 to 20:00 series and compares every completed bar's three
 * averages with Massive's own minute SMA endpoint, which is the correctness test for the engine.
 */
public final class Replay {

    /** Calendar days of minute bars to load before the replay day; 199 bars is well under one session. */
    private static final int WARMUP_CALENDAR_DAYS = 7;
    private static final int LARGEST_WINDOW = 200;
    private static final double ORACLE_TOLERANCE = 1e-6;
    private static final int MISMATCH_EXAMPLES = 5;
    /** First and last minute whose close is printed. */
    static final LocalTime FIRST_PRINTED = LocalTime.of(9, 45);
    static final LocalTime LAST_PRINTED = LocalTime.of(15, 59);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter BAR_TIME = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * @param symbol  ticker to replay
     * @param date    trading day to replay
     * @param session daily window of bars the averages run on (ignored with {@code verify})
     * @param verbose append price and the three averages to each printed minute
     * @param verify  run on Massive's filled series and compare every bar with its minute SMA endpoint
     * @param csvOut  optional path to write one row per close-updating trade
     */
    public record Options(String symbol, LocalDate date, Session session, boolean verbose, boolean verify, Path csvOut) {
        public Options(String symbol, LocalDate date, boolean verbose, boolean verify, Path csvOut) {
            this(symbol, date, Session.EXTENDED, verbose, verify, csvOut);
        }

        /** The series actually used: Massive's filled extended session when verifying, else the chart series. */
        public Session effectiveSession() {
            return verify ? Session.EXTENDED : session;
        }
    }

    /** One printed minute. */
    public record Signal(long barStartMs, Trend trend, SmaSnapshot atClose) {
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
                         long emptyMinutes, List<Signal> signals, List<OracleCheck> oracleChecks) {
        public boolean allOraclesPass() {
            return oracleChecks.stream().allMatch(OracleCheck::pass);
        }

        public Map<Trend, Long> counts() {
            Map<Trend, Long> counts = new EnumMap<>(Trend.class);
            for (Trend t : Trend.values()) {
                counts.put(t, 0L);
            }
            for (Signal s : signals) {
                counts.merge(s.trend(), 1L, Long::sum);
            }
            return counts;
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

        Session session = opts.effectiveSession();
        boolean fill = opts.verify();

        List<MinuteBar> history = client.minuteBars(symbol, date.minusDays(WARMUP_CALENDAR_DAYS), date.minusDays(1));
        List<Double> closes;
        if (history.isEmpty()) {
            closes = List.of();
        } else if (fill) {
            closes = session.fillCloses(history, session.lastBarOfDay(history.get(history.size() - 1).startMs()));
        } else {
            closes = session.closes(history, Long.MAX_VALUE);
        }
        if (closes.size() < LARGEST_WINDOW - 1) {
            out.printf("warning: only %d prior minute bars, the 200 bar SMA will be unavailable at first%n", closes.size());
        }

        List<TreeMap<Long, Double>> massive = new ArrayList<>();
        if (opts.verify()) {
            for (int window : MovingAverages.WINDOWS) {
                massive.add(client.minuteSma(symbol, window, date));
            }
        }

        // The engine starts with every warm-up close but the last; the tracker rolls that one in as the seed bar.
        MovingAverages engine = new MovingAverages(symbol, closes.isEmpty() ? closes : closes.subList(0, closes.size() - 1));
        Collector collector = new Collector(opts, engine, massive);
        if (!closes.isEmpty()) {
            long lastInSession = history.get(history.size() - 1).startMs();
            for (int i = history.size() - 1; i >= 0; i--) {
                if (session.contains(history.get(i).startMs())) {
                    lastInSession = history.get(i).startMs();
                    break;
                }
            }
            collector.tracker.seed(fill ? session.lastBarOfDay(lastInSession) : lastInSession, closes.get(closes.size() - 1));
        }
        try (collector) {
            new ReplaySource(client, symbol, date).stream(collector::accept);
            collector.tracker.finishSession();
        }

        if (collector.applied == 0) {
            throw new IOException("no trades returned for " + symbol + " on " + date);
        }

        List<OracleCheck> checks = new ArrayList<>();
        if (opts.verify()) {
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
        }

        Result result = new Result(symbol, date, collector.seen, collector.applied, collector.tracker.barsClosed(),
                collector.tracker.emptyMinutes(), collector.signals, checks);
        printSummary(result);
        return result;
    }

    private void printSummary(Result r) {
        Map<Trend, Long> counts = r.counts();
        out.printf("%s %s: %d minutes, up %d, down %d, none %d (%,d trades, %,d applied, %,d bars, %,d empty minutes)%n",
                r.symbol(), r.date(), r.signals().size(), counts.get(Trend.UP), counts.get(Trend.DOWN), counts.get(Trend.NONE),
                r.tradesSeen(), r.tradesApplied(), r.barsClosed(), r.emptyMinutes());
        if (r.oracleChecks().isEmpty()) {
            return;
        }
        out.println("verify (engine on Massive's filled 04:00-20:00 series, at each bar close, vs Massive minute SMA):");
        for (OracleCheck c : r.oracleChecks()) {
            out.printf("  sma%-3d %4d/%-4d bars match, max diff %.9f, %d bars Massive lacks, %d bars engine lacks  %s%n",
                    c.window(), c.barsMatched(), c.barsCompared(), c.maxAbsDiff(),
                    c.barsMissingFromMassive(), c.barsMissingFromEngine(), c.pass() ? "PASS" : "FAIL");
            for (Mismatch m : c.examples()) {
                out.printf("    %s engine=%.6f massive=%.6f diff=%+.6f%n",
                        barTime(m.barStartMs()), m.engine(), m.massive(), m.engine() - m.massive());
            }
        }
    }

    private static String barTime(long startMs) {
        return Instant.ofEpochMilli(startMs).atZone(PriceEvent.EASTERN).format(BAR_TIME);
    }

    private static boolean printed(long barStartMs) {
        LocalTime t = Instant.ofEpochMilli(barStartMs).atZone(PriceEvent.EASTERN).toLocalTime();
        return !t.isBefore(FIRST_PRINTED) && !t.isAfter(LAST_PRINTED);
    }

    /** Feeds trades to the tracker, prints each minute's trend, checks bars against Massive, and optionally writes a CSV. */
    private final class Collector implements AutoCloseable, MinuteBarTracker.BarListener {
        private final Options opts;
        private final List<TreeMap<Long, Double>> massive;
        private final PrintWriter csv;
        final MinuteBarTracker tracker;
        final Set<Long> engineBars = new HashSet<>();
        final List<Signal> signals = new ArrayList<>();
        final int[] compared = new int[MovingAverages.WINDOWS.length];
        final int[] matched = new int[MovingAverages.WINDOWS.length];
        final int[] missingFromMassive = new int[MovingAverages.WINDOWS.length];
        final double[] maxDiff = new double[MovingAverages.WINDOWS.length];
        final List<List<Mismatch>> examples = new ArrayList<>();
        long seen;
        long applied;

        Collector(Options opts, MovingAverages engine, List<TreeMap<Long, Double>> massive) throws IOException {
            this.opts = opts;
            this.massive = massive;
            this.tracker = new MinuteBarTracker(engine, opts.effectiveSession(), opts.verify(), this);
            for (int i = 0; i < MovingAverages.WINDOWS.length; i++) {
                examples.add(new ArrayList<>());
            }
            if (opts.csvOut() != null) {
                csv = new PrintWriter(Files.newBufferedWriter(opts.csvOut(), StandardCharsets.UTF_8));
                csv.println("time_et,sip_timestamp_ns,price,size,sma20,sma50,sma200,trend");
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
            applied++;
            if (csv != null) {
                SmaSnapshot snap = maybe.get();
                ZonedDateTime et = event.easternTime();
                csv.printf(Locale.US, "%s,%d,%s,%s,%s,%s,%s,%s%n", et.format(TIME), event.sipTimestampNanos(),
                        event.price(), event.size(), snap.sma20(), snap.sma50(), snap.sma200(), Trend.of(snap).label());
            }
        }

        @Override
        public void barClosed(long barStartMs, double close, SmaSnapshot atClose, boolean empty) {
            if (printed(barStartMs)) {
                Trend trend = Trend.of(atClose);
                signals.add(new Signal(barStartMs, trend, atClose));
                if (opts.verbose()) {
                    out.printf(Locale.US, "%s %-4s  px=%-10.4f sma20=%-10.4f sma50=%-10.4f sma200=%-10.4f%n",
                            barTime(barStartMs), trend.label(), atClose.price(), atClose.sma20(), atClose.sma50(), atClose.sma200());
                } else {
                    out.printf("%s %s%n", barTime(barStartMs), trend.label());
                }
            }
            if (!opts.verify()) {
                return;
            }
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
