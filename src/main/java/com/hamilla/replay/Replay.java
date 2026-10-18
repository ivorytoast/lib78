package com.hamilla.replay;

import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;
import com.hamilla.massive.MassiveClient;
import com.hamilla.sma.Action;
import com.hamilla.sma.MinuteBarTracker;
import com.hamilla.sma.MovingAverages;
import com.hamilla.sma.Session;
import com.hamilla.sma.SignalPrices;
import com.hamilla.sma.SignalWindow;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Replays one past trading day for one symbol, trade by trade, on the one-minute bar series.
 * Directly after each minute closes, from 10:30 to 14:30 Eastern, it classifies the 20/50/200
 * alignment and price as "up", "down" or "none" and prints the minute when that gives an entry
 * signal: "BUY LONG" in the up zone with the price within two cents above the 20, "SHORT SELL" in
 * the down zone with the price within two cents below it, followed by the price and, for symbols
 * with an offset in {@link SignalPrices}, the price plus that offset. Nothing else is printed unless
 * {@link Options#verbose()} is set. Earlier minutes, including pre-market, still feed the averages.
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
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter BAR_TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** Default minutes that must pass after a printed signal before another is printed. */
    public static final int DEFAULT_COOLDOWN_MINUTES = 6;

    /**
     * @param symbol          ticker to replay
     * @param date            trading day to replay
     * @param session         daily window of bars the averages run on (ignored with {@code verify})
     * @param cooldownMinutes after a signal prints, no signal prints until this many minutes have passed (0 = none)
     * @param verbose         print every minute with its zone, signal, price and averages
     * @param verify          run on Massive's filled series and compare every bar with its minute SMA endpoint
     * @param csvOut          optional path to write one row per close-updating trade
     */
    public record Options(String symbol, LocalDate date, Session session, int cooldownMinutes,
                          boolean verbose, boolean verify, Path csvOut) {
        public Options {
            if (cooldownMinutes < 0) {
                throw new IllegalArgumentException("cooldown must be 0 or more minutes, got " + cooldownMinutes);
            }
        }

        public Options(String symbol, LocalDate date, boolean verbose, boolean verify, Path csvOut) {
            this(symbol, date, Session.EXTENDED, DEFAULT_COOLDOWN_MINUTES, verbose, verify, csvOut);
        }

        public Options(String symbol, LocalDate date, Session session, boolean verbose, boolean verify, Path csvOut) {
            this(symbol, date, session, DEFAULT_COOLDOWN_MINUTES, verbose, verify, csvOut);
        }

        /** The series actually used: Massive's filled extended session when verifying, else the chart series. */
        public Session effectiveSession() {
            return verify ? Session.EXTENDED : session;
        }
    }

    /**
     * One classified minute. {@code action} is empty when there is no entry signal; {@code printed} is
     * false for a signal held back by the cooldown.
     */
    public record Signal(long barStartMs, Trend trend, Optional<Action> action, boolean printed, SmaSnapshot atClose) {
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

        /** Signals by kind, counting only those printed (not held back by the cooldown). */
        public Map<Action, Long> actionCounts() {
            Map<Action, Long> counts = new EnumMap<>(Action.class);
            for (Action a : Action.values()) {
                counts.put(a, 0L);
            }
            for (Signal s : signals) {
                if (s.printed()) {
                    s.action().ifPresent(a -> counts.merge(a, 1L, Long::sum));
                }
            }
            return counts;
        }

        public long signalsHeldByCooldown() {
            return signals.stream().filter(s -> s.action().isPresent() && !s.printed()).count();
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
        this.opts = opts;
        printSummary(result);
        return result;
    }

    /** Options of the run in progress, for the summary. */
    private Options opts;

    private void printSummary(Result r) {
        if (opts.verbose()) {
            Map<Trend, Long> counts = r.counts();
            Map<Action, Long> actions = r.actionCounts();
            out.printf("%s %s: %d minutes, up %d, down %d, none %d, buy long %d, short sell %d, %d held by cooldown"
                            + " (%,d trades, %,d applied, %,d bars, %,d empty minutes)%n",
                    r.symbol(), r.date(), r.signals().size(), counts.get(Trend.UP), counts.get(Trend.DOWN), counts.get(Trend.NONE),
                    actions.get(Action.BUY_LONG), actions.get(Action.SHORT_SELL), r.signalsHeldByCooldown(),
                    r.tradesSeen(), r.tradesApplied(), r.barsClosed(), r.emptyMinutes());
        }
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
        return SignalWindow.contains(barStartMs);
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
        private long lastPrintedMs = Long.MIN_VALUE;

        Collector(Options opts, MovingAverages engine, List<TreeMap<Long, Double>> massive) throws IOException {
            this.opts = opts;
            this.massive = massive;
            this.tracker = new MinuteBarTracker(engine, opts.effectiveSession(), opts.verify(), this);
            for (int i = 0; i < MovingAverages.WINDOWS.length; i++) {
                examples.add(new ArrayList<>());
            }
            if (opts.csvOut() != null) {
                csv = new PrintWriter(Files.newBufferedWriter(opts.csvOut(), StandardCharsets.UTF_8));
                csv.println("time_et,sip_timestamp_ns,price,size,sma20,sma50,sma200,trend,action");
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
                csv.printf(Locale.US, "%s,%d,%s,%s,%s,%s,%s,%s,%s%n", et.format(TIME), event.sipTimestampNanos(),
                        event.price(), event.size(), snap.sma20(), snap.sma50(), snap.sma200(), Trend.of(snap).label(),
                        Action.of(snap).map(Action::label).orElse(""));
            }
        }

        @Override
        public void barClosed(long barStartMs, double close, SmaSnapshot atClose, boolean empty) {
            if (printed(barStartMs)) {
                Trend trend = Trend.of(atClose);
                Optional<Action> action = Action.of(atClose);
                boolean print = action.isPresent()
                        && (lastPrintedMs == Long.MIN_VALUE
                            || barStartMs - lastPrintedMs >= opts.cooldownMinutes() * Session.MINUTE_MS);
                if (print) {
                    lastPrintedMs = barStartMs;
                }
                signals.add(new Signal(barStartMs, trend, action, print, atClose));
                if (opts.verbose()) {
                    String label = action.map(Action::label).orElse("");
                    if (action.isPresent() && !print) {
                        label += " (cooldown)";
                    }
                    out.printf(Locale.US, "%s %-4s %-21s px=%-10.4f sma20=%-10.4f sma50=%-10.4f sma200=%-10.4f%n",
                            barTime(barStartMs), trend.label(), label,
                            atClose.price(), atClose.sma20(), atClose.sma50(), atClose.sma200());
                } else if (print) {
                    out.printf("%s %s %s%n", barTime(barStartMs), action.get().label(),
                            SignalPrices.describe(atClose.symbol(), atClose.price()));
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
