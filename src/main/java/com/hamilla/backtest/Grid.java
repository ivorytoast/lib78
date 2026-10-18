package com.hamilla.backtest;

import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;
import com.hamilla.sma.Session;
import com.hamilla.sma.SignalWindow;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stop-by-target tables of P&L for one month or a range of months, and a recommendation read from them.
 *
 * <p>For one month the recommendation is the finite cell whose 3-by-3 neighbourhood averages highest,
 * so it sits on a plateau rather than a spike. For several months each cell is scored by the mean of its
 * monthly P&L minus the standard deviation of that series, so a cell that makes a little every month
 * beats one that makes a lot once, and the same neighbourhood smoothing is applied to that score.
 */
public final class Grid {

    /** Stop sizes as multiples of the typical one-minute range. */
    static final double[] STOP_MULTIPLES = {1, 1.5, 2, 3, 4, 6};
    /** Target sizes as multiples of the typical one-minute range. */
    static final double[] TARGET_MULTIPLES = {1.5, 2, 3, 4, 6, 8};

    /** Entry-time buckets for the time-of-day breakdown, from the first acted-on minute. */
    static final LocalTime[] BUCKET_STARTS = {
            SignalWindow.FIRST, LocalTime.of(11, 30), LocalTime.of(12, 30), LocalTime.of(13, 30), LocalTime.of(14, 30)};

    public record Result(String symbol, List<Double> stops, List<Double> targets, double[][] pnl,
                         BracketSimulator.Summary[][] cells, double typicalRange, int signals, int shares,
                         Recommendation recommendation, List<TimeBucket> byTime) {
    }

    public record Recommendation(double stop, double target, BracketSimulator.Summary summary, double score) {
        /** Win rate needed to break even with these brackets, ignoring held trades. */
        public double breakevenWinRate() {
            return stop / (stop + target);
        }

        public double winRate() {
            int decided = summary.targets() + summary.stops();
            return decided == 0 ? Double.NaN : (double) summary.targets() / decided;
        }
    }

    /** Trades of one bracket setting grouped by entry time. */
    public record TimeBucket(String label, int trades, int targets, int stops, int held, double pnl) {
    }

    /** Several months: per-month tables plus their aggregate. */
    public record MultiResult(String symbol, List<YearMonth> months, List<Double> stops, List<Double> targets,
                              BracketSimulator.Summary[][][] cells, double[][] total, double[][] worstMonth,
                              int[][] monthsPositive, double typicalRange, int signals, int shares,
                              Recommendation recommendation, Map<YearMonth, Double> recommendedByMonth,
                              List<TimeBucket> byTime) {
    }

    private final BracketSimulator simulator;

    public Grid(BracketSimulator simulator) {
        this.simulator = simulator;
    }

    /** Average high-minus-low of regular-session bars, the unit the default grid levels are built from. */
    public static double typicalMinuteRange(List<MinuteBar> bars, Set<LocalDate> days) {
        double sum = 0;
        int n = 0;
        for (MinuteBar b : bars) {
            var et = Instant.ofEpochMilli(b.startMs()).atZone(PriceEvent.EASTERN);
            if (days.contains(et.toLocalDate()) && Session.REGULAR.contains(b.startMs())) {
                sum += b.high() - b.low();
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** Rounds a dollar amount to a sensible tick: cents under $1, five cents under $10, else a quarter. */
    static double nice(double dollars) {
        double tick = dollars < 1 ? 0.01 : dollars < 10 ? 0.05 : 0.25;
        double rounded = Math.max(tick, Math.round(dollars / tick) * tick);
        return BigDecimal.valueOf(rounded).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    static List<Double> defaultLevels(double[] multiples, double typicalRange) {
        List<Double> levels = new ArrayList<>();
        for (double m : multiples) {
            double level = nice(m * typicalRange);
            if (levels.isEmpty() || level > levels.get(levels.size() - 1)) {
                levels.add(level);
            }
        }
        levels.add(BracketSimulator.NONE);
        return levels;
    }

    public Result run(String symbol, List<TradeSignal> signals, List<Double> stops, List<Double> targets,
                      double typicalRange, int shares) {
        double[][] pnl = new double[stops.size()][targets.size()];
        BracketSimulator.Summary[][] cells = new BracketSimulator.Summary[stops.size()][targets.size()];
        for (int i = 0; i < stops.size(); i++) {
            for (int j = 0; j < targets.size(); j++) {
                cells[i][j] = simulator.summarize(signals, stops.get(i), targets.get(j), shares);
                pnl[i][j] = cells[i][j].pnl();
            }
        }
        Recommendation rec = recommend(stops, targets, pnl, (i, j) -> cells[i][j]);
        List<TimeBucket> byTime = rec == null ? List.of() : byTime(simulator.simulate(signals, rec.stop(), rec.target(), shares));
        return new Result(symbol, stops, targets, pnl, cells, typicalRange, signals.size(), shares, rec, byTime);
    }

    /** Runs each month separately and aggregates. Months with no signals are left out of the consistency counts. */
    public MultiResult runMonths(String symbol, Map<YearMonth, List<TradeSignal>> signalsByMonth, List<Double> stops,
                                 List<Double> targets, double typicalRange, int shares) {
        List<YearMonth> months = new ArrayList<>(signalsByMonth.keySet());
        int rows = stops.size();
        int cols = targets.size();
        BracketSimulator.Summary[][][] cells = new BracketSimulator.Summary[months.size()][rows][cols];
        double[][] total = new double[rows][cols];
        double[][] worst = new double[rows][cols];
        int[][] positive = new int[rows][cols];
        double[][] score = new double[rows][cols];
        List<TradeSignal> all = new ArrayList<>();
        for (int m = 0; m < months.size(); m++) {
            List<TradeSignal> signals = signalsByMonth.get(months.get(m));
            all.addAll(signals);
            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    cells[m][i][j] = simulator.summarize(signals, stops.get(i), targets.get(j), shares);
                }
            }
        }
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                double sum = 0;
                double sumSq = 0;
                int n = 0;
                double w = Double.POSITIVE_INFINITY;
                for (int m = 0; m < months.size(); m++) {
                    BracketSimulator.Summary c = cells[m][i][j];
                    if (c.trades() == 0) {
                        continue;
                    }
                    sum += c.pnl();
                    sumSq += c.pnl() * c.pnl();
                    n++;
                    w = Math.min(w, c.pnl());
                    if (c.pnl() > 0) {
                        positive[i][j]++;
                    }
                }
                total[i][j] = sum;
                worst[i][j] = n == 0 ? 0 : w;
                if (n == 0) {
                    score[i][j] = 0;
                } else {
                    double mean = sum / n;
                    double var = Math.max(0, sumSq / n - mean * mean);
                    score[i][j] = mean - Math.sqrt(var);
                }
            }
        }
        BracketSimulator.Summary[][] combined = new BracketSimulator.Summary[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                combined[i][j] = simulator.summarize(all, stops.get(i), targets.get(j), shares);
            }
        }
        Recommendation rec = recommend(stops, targets, score, (i, j) -> combined[i][j]);
        Map<YearMonth, Double> recByMonth = new LinkedHashMap<>();
        List<TimeBucket> byTime = List.of();
        if (rec != null) {
            int ri = stops.indexOf(rec.stop());
            int rj = targets.indexOf(rec.target());
            for (int m = 0; m < months.size(); m++) {
                recByMonth.put(months.get(m), cells[m][ri][rj].pnl());
            }
            byTime = byTime(simulator.simulate(all, rec.stop(), rec.target(), shares));
        }
        return new MultiResult(symbol, months, stops, targets, cells, total, worst, positive, typicalRange,
                all.size(), shares, rec, recByMonth, byTime);
    }

    interface CellLookup {
        BracketSimulator.Summary get(int i, int j);
    }

    /**
     * The finite cell with the best 3-by-3 neighbourhood average of {@code value}. A neighbour outside the
     * table counts as the cell's own value, so edge cells are neither favoured nor penalised.
     */
    static Recommendation recommend(List<Double> stops, List<Double> targets, double[][] value, CellLookup cells) {
        Recommendation best = null;
        for (int i = 0; i < stops.size(); i++) {
            if (stops.get(i) >= BracketSimulator.NONE) {
                continue;
            }
            for (int j = 0; j < targets.size(); j++) {
                if (targets.get(j) >= BracketSimulator.NONE) {
                    continue;
                }
                double sum = 0;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        int a = i + di;
                        int b = j + dj;
                        boolean inside = a >= 0 && a < stops.size() && b >= 0 && b < targets.size();
                        sum += inside ? value[a][b] : value[i][j];
                    }
                }
                double avg = sum / 9;
                if (best == null || avg > best.score()) {
                    best = new Recommendation(stops.get(i), targets.get(j), cells.get(i, j), avg);
                }
            }
        }
        return best;
    }

    /** Groups trades by entry time into the standard buckets. */
    static List<TimeBucket> byTime(List<BracketSimulator.Trade> trades) {
        int n = BUCKET_STARTS.length;
        int[] count = new int[n];
        int[] t = new int[n];
        int[] s = new int[n];
        int[] h = new int[n];
        double[] pnl = new double[n];
        for (BracketSimulator.Trade tr : trades) {
            LocalTime time = Instant.ofEpochMilli(tr.signal().barStartMs()).atZone(PriceEvent.EASTERN).toLocalTime();
            int b = 0;
            for (int k = 1; k < n; k++) {
                if (!time.isBefore(BUCKET_STARTS[k])) {
                    b = k;
                }
            }
            count[b]++;
            pnl[b] += tr.pnl();
            switch (tr.outcome()) {
                case TARGET -> t[b]++;
                case STOP -> s[b]++;
                case HELD -> h[b]++;
            }
        }
        List<TimeBucket> buckets = new ArrayList<>();
        for (int b = 0; b < n; b++) {
            LocalTime end = b + 1 < n ? BUCKET_STARTS[b + 1].minusMinutes(1) : BracketSimulator.LAST_ENTRY.minusMinutes(1);
            buckets.add(new TimeBucket(BUCKET_STARTS[b] + "-" + end, count[b], t[b], s[b], h[b], pnl[b]));
        }
        return buckets;
    }

    public static void print(Result r, PrintStream out) {
        out.printf(Locale.US, "%s: %d signals, %d shares per trade, typical regular-hours minute range %s%n%n",
                r.symbol(), r.signals(), r.shares(), money(r.typicalRange()));
        table(out, "total P&L", r.stops(), r.targets(), (i, j) -> String.format(Locale.US, "%,.0f", r.pnl()[i][j]));

        Recommendation rec = r.recommendation();
        if (rec == null) {
            out.println("recommendation: none (no finite cells)");
            return;
        }
        BracketSimulator.Summary s = rec.summary();
        out.printf(Locale.US, "recommendation: stop %s, target %s%n", money(rec.stop()), money(rec.target()));
        out.printf(Locale.US, "  P&L %,.2f over %d trades: %d targets, %d stops, %d held to 15:55; worst trade %,.2f%n",
                s.pnl(), s.trades(), s.targets(), s.stops(), s.held(), s.worstTrade());
        out.printf(Locale.US, "  chosen for its neighbourhood: the surrounding cells average %,.0f, so it sits on a plateau, not a spike%n",
                rec.score());
        printCharacter(rec, out);
        double share = biggestDayShare(s);
        if (share > 1.0) {
            out.printf(Locale.US, "  caution: the best day made %,.0f, more than the whole month; the other days net a loss%n", bestDay(s));
        } else if (share > 0.5) {
            out.printf(Locale.US, "  caution: one day supplies %.0f%% of the P&L; validate on another month before trusting it%n", 100 * share);
        }
        if (s.trades() < 100) {
            out.printf(Locale.US, "  caution: %d trades is a small sample; differences of a few hundred dollars between cells are noise%n", s.trades());
        }
        printByTime(r.byTime(), out);
    }

    public static void print(MultiResult r, PrintStream out) {
        int n = r.months().size();
        out.printf(Locale.US, "%s %s to %s: %d months, %d signals, %d shares per trade, levels from typical minute range %s%n%n",
                r.symbol(), r.months().get(0), r.months().get(n - 1), n, r.signals(), r.shares(), money(r.typicalRange()));
        table(out, "total P&L over all months", r.stops(), r.targets(), (i, j) -> String.format(Locale.US, "%,.0f", r.total()[i][j]));
        table(out, "worst month", r.stops(), r.targets(), (i, j) -> String.format(Locale.US, "%,.0f", r.worstMonth()[i][j]));
        table(out, "months positive (of " + n + ")", r.stops(), r.targets(), (i, j) -> Integer.toString(r.monthsPositive()[i][j]));

        Recommendation rec = r.recommendation();
        if (rec == null) {
            out.println("recommendation: none (no finite cells)");
            return;
        }
        BracketSimulator.Summary s = rec.summary();
        int ri = r.stops().indexOf(rec.stop());
        int rj = r.targets().indexOf(rec.target());
        out.printf(Locale.US, "recommendation: stop %s, target %s%n", money(rec.stop()), money(rec.target()));
        out.printf(Locale.US, "  total P&L %,.2f over %d trades: %d targets, %d stops, %d held to 15:55; worst trade %,.2f%n",
                s.pnl(), s.trades(), s.targets(), s.stops(), s.held(), s.worstTrade());
        StringBuilder monthly = new StringBuilder();
        for (Map.Entry<YearMonth, Double> e : r.recommendedByMonth().entrySet()) {
            if (monthly.length() > 0) {
                monthly.append(", ");
            }
            monthly.append(e.getKey()).append(' ').append(String.format(Locale.US, "%+,.0f", e.getValue()));
        }
        out.printf(Locale.US, "  by month: %s%n", monthly);
        out.printf(Locale.US, "  worst month %,.0f, %d of %d months positive%n", r.worstMonth()[ri][rj], r.monthsPositive()[ri][rj], n);
        out.printf(Locale.US, "  chosen by mean monthly P&L minus its standard deviation (%,.0f here), smoothed over neighbouring cells,%n"
                + "  so a setting that earns steadily beats one that earns once%n", rec.score());
        printCharacter(rec, out);
        printByTime(r.byTime(), out);
    }

    private static void printCharacter(Recommendation rec, PrintStream out) {
        out.printf(Locale.US, "  risk/reward %.2f to 1: breakeven win rate %.0f%%, actual %s%n",
                rec.target() / rec.stop(), 100 * rec.breakevenWinRate(),
                Double.isNaN(rec.winRate()) ? "n/a" : String.format(Locale.US, "%.0f%%", 100 * rec.winRate()));
        if (rec.target() >= 2 * rec.stop()) {
            out.println("  this is a trend bet: it wins rarely but by more than it loses, and chops will string losses together");
        } else if (rec.stop() >= 1.5 * rec.target()) {
            out.println("  this is a scalp: it needs a high win rate, and a strong move against it costs several wins");
        }
    }

    private static void printByTime(List<TimeBucket> buckets, PrintStream out) {
        if (buckets.isEmpty()) {
            return;
        }
        out.println("  by entry time, with these brackets:");
        out.printf("    %-12s %7s %8s %6s %5s %12s%n", "entry", "trades", "targets", "stops", "held", "P&L");
        for (TimeBucket b : buckets) {
            out.printf(Locale.US, "    %-12s %7d %8d %6d %5d %12s%n", b.label(), b.trades(), b.targets(), b.stops(), b.held(),
                    String.format(Locale.US, "%,.0f", b.pnl()));
        }
    }

    interface CellText {
        String get(int i, int j);
    }

    private static void table(PrintStream out, String title, List<Double> stops, List<Double> targets, CellText text) {
        out.println(title);
        out.print("stop \\ target");
        for (double t : targets) {
            out.printf("%10s", level(t));
        }
        out.println();
        for (int i = 0; i < stops.size(); i++) {
            out.printf("%-13s", level(stops.get(i)));
            for (int j = 0; j < targets.size(); j++) {
                out.printf("%10s", text.get(i, j));
            }
            out.println();
        }
        out.println();
    }

    static double bestDay(BracketSimulator.Summary s) {
        double max = 0;
        for (double v : s.pnlByDay().values()) {
            max = Math.max(max, v);
        }
        return max;
    }

    static double biggestDayShare(BracketSimulator.Summary s) {
        return s.pnl() <= 0 ? 0 : bestDay(s) / s.pnl();
    }

    private static String level(double v) {
        return v >= BracketSimulator.NONE ? "none" : money(v);
    }

    private static String money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
