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

    /** Entry-time buckets for the time-of-day breakdown: hourly from the window's first acted-on minute to its last. */
    static LocalTime[] bucketStarts(SignalWindow window) {
        List<LocalTime> starts = new ArrayList<>();
        for (LocalTime t = window.open(); !t.isAfter(window.last()); t = t.plusHours(1)) {
            starts.add(t);
        }
        return starts.toArray(new LocalTime[0]);
    }

    public record Result(String symbol, List<Double> stops, List<Double> targets, double[][] pnl,
                         BracketSimulator.Summary[][] cells, double typicalRange, int signals, Sizing sizing, double referencePrice,
                         Recommendation recommendation, List<TimeBucket> byTime) {
        /** Shares for a trade in the row with this stop, at the average signal price when a position cap applies. */
        public int shares(double stop) {
            return sizing.sharesFor(stop, referencePrice);
        }
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
                              int[][] monthsPositive, double typicalRange, int signals, Sizing sizing, double referencePrice,
                              Recommendation recommendation, Map<YearMonth, Double> recommendedByMonth,
                              List<TimeBucket> byTime, List<CellDetail> shown) {
        public int shares(double stop) {
            return sizing.sharesFor(stop, referencePrice);
        }
    }

    /** Everything about one stop-and-target pair, for a cell the user asked to see. */
    public record CellDetail(double stop, double target, BracketSimulator.Summary summary, Map<YearMonth, Double> byMonth,
                             List<TimeBucket> byTime) {
    }

    private final BracketSimulator simulator;
    private final SignalWindow window;

    public Grid(BracketSimulator simulator) {
        this(simulator, SignalWindow.DEFAULT);
    }

    public Grid(BracketSimulator simulator, SignalWindow window) {
        this.simulator = simulator;
        this.window = window;
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

    /** Average signal price, the price a capped share count is quoted at. */
    static double referencePrice(List<TradeSignal> signals) {
        double sum = 0;
        for (TradeSignal s : signals) {
            sum += s.price();
        }
        return signals.isEmpty() ? Double.NaN : sum / signals.size();
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
        return run(symbol, signals, stops, targets, typicalRange, Sizing.shares(shares));
    }

    public Result run(String symbol, List<TradeSignal> signals, List<Double> stops, List<Double> targets,
                      double typicalRange, Sizing sizing) {
        double[][] pnl = new double[stops.size()][targets.size()];
        BracketSimulator.Summary[][] cells = new BracketSimulator.Summary[stops.size()][targets.size()];
        for (int i = 0; i < stops.size(); i++) {
            for (int j = 0; j < targets.size(); j++) {
                cells[i][j] = simulator.summarize(signals, stops.get(i), targets.get(j), sizing);
                pnl[i][j] = cells[i][j].pnl();
            }
        }
        Recommendation rec = recommend(stops, targets, pnl, (i, j) -> cells[i][j]);
        List<TimeBucket> byTime = rec == null ? List.of()
                : byTime(simulator.simulate(signals, rec.stop(), rec.target(), sizing), window);
        return new Result(symbol, stops, targets, pnl, cells, typicalRange, signals.size(), sizing, referencePrice(signals), rec, byTime);
    }

    public MultiResult runMonths(String symbol, Map<YearMonth, List<TradeSignal>> signalsByMonth, List<Double> stops,
                                 List<Double> targets, double typicalRange, int shares) {
        return runMonths(symbol, signalsByMonth, stops, targets, typicalRange, Sizing.shares(shares), List.of());
    }

    public MultiResult runMonths(String symbol, Map<YearMonth, List<TradeSignal>> signalsByMonth, List<Double> stops,
                                 List<Double> targets, double typicalRange, int shares, List<double[]> show) {
        return runMonths(symbol, signalsByMonth, stops, targets, typicalRange, Sizing.shares(shares), show);
    }

    /**
     * Runs each month separately and aggregates. Months with no signals are left out of the consistency
     * counts. {@code show} names extra stop-and-target pairs whose detail is wanted regardless of the grid.
     */
    public MultiResult runMonths(String symbol, Map<YearMonth, List<TradeSignal>> signalsByMonth, List<Double> stops,
                                 List<Double> targets, double typicalRange, Sizing sizing, List<double[]> show) {
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
                    cells[m][i][j] = simulator.summarize(signals, stops.get(i), targets.get(j), sizing);
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
                combined[i][j] = simulator.summarize(all, stops.get(i), targets.get(j), sizing);
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
            byTime = byTime(simulator.simulate(all, rec.stop(), rec.target(), sizing), window);
        }
        List<CellDetail> shown = new ArrayList<>();
        for (double[] pair : show) {
            Map<YearMonth, Double> byMonth = new LinkedHashMap<>();
            for (YearMonth m : months) {
                byMonth.put(m, simulator.summarize(signalsByMonth.get(m), pair[0], pair[1], sizing).pnl());
            }
            shown.add(new CellDetail(pair[0], pair[1], simulator.summarize(all, pair[0], pair[1], sizing), byMonth,
                    byTime(simulator.simulate(all, pair[0], pair[1], sizing), window)));
        }
        return new MultiResult(symbol, months, stops, targets, cells, total, worst, positive, typicalRange,
                all.size(), sizing, referencePrice(all), rec, recByMonth, byTime, shown);
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

    /** Groups trades by entry time into hourly buckets across the window. */
    static List<TimeBucket> byTime(List<BracketSimulator.Trade> trades, SignalWindow window) {
        LocalTime[] BUCKET_STARTS = bucketStarts(window);
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
        LocalTime lastEntry = window.last().isBefore(BracketSimulator.LAST_ENTRY) ? window.last() : BracketSimulator.LAST_ENTRY.minusMinutes(1);
        for (int b = 0; b < n; b++) {
            LocalTime end = b + 1 < n ? BUCKET_STARTS[b + 1].minusMinutes(1) : lastEntry;
            buckets.add(new TimeBucket(BUCKET_STARTS[b] + "-" + end, count[b], t[b], s[b], h[b], pnl[b]));
        }
        return buckets;
    }

    public static void print(Result r, PrintStream out) {
        out.printf(Locale.US, "%s: %d signals, %s, typical regular-hours minute range %s%n%n",
                r.symbol(), r.signals(), sizingText(r.sizing(), r.stops(), r.referencePrice()), money(r.typicalRange()));
        table(out, "total P&L", r.stops(), r.targets(), (i, j) -> String.format(Locale.US, "%,.0f", r.pnl()[i][j]));

        Recommendation rec = r.recommendation();
        if (rec == null) {
            out.println("recommendation: none (no finite cells)");
            return;
        }
        BracketSimulator.Summary s = rec.summary();
        out.printf(Locale.US, "recommendation: stop %s, target %s, %d shares%n", money(rec.stop()), money(rec.target()), r.shares(rec.stop()));
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
        out.printf(Locale.US, "%s %s to %s: %d months, %d signals, %s, levels from typical minute range %s%n%n",
                r.symbol(), r.months().get(0), r.months().get(n - 1), n, r.signals(), sizingText(r.sizing(), r.stops(), r.referencePrice()), money(r.typicalRange()));
        table(out, "total P&L over all months", r.stops(), r.targets(), (i, j) -> String.format(Locale.US, "%,.0f", r.total()[i][j]));
        table(out, "worst month", r.stops(), r.targets(), (i, j) -> String.format(Locale.US, "%,.0f", r.worstMonth()[i][j]));
        table(out, "months positive (of " + n + ")", r.stops(), r.targets(), (i, j) -> Integer.toString(r.monthsPositive()[i][j]));

        Recommendation rec = r.recommendation();
        if (rec == null) {
            out.println("recommendation: none (no finite cells)");
        } else {
            BracketSimulator.Summary s = rec.summary();
            int ri = r.stops().indexOf(rec.stop());
            int rj = r.targets().indexOf(rec.target());
            if (rec.score() < 0) {
                out.println("recommendation: none. No setting is steadily positive: for every cell the month-to-month swing"
                        + " exceeds the average month.");
                out.printf(Locale.US, "  least unsteady: stop %s, target %s (score %,.0f); the strongest totals are:%n",
                        money(rec.stop()), money(rec.target()), rec.score());
                for (int[] c : topByTotal(r, 3)) {
                    out.printf(Locale.US, "    stop %s, target %s: total %,.0f, worst month %,.0f, %d of %d months positive%n",
                            level(r.stops().get(c[0])), level(r.targets().get(c[1])), r.total()[c[0]][c[1]],
                            r.worstMonth()[c[0]][c[1]], r.monthsPositive()[c[0]][c[1]], n);
                }
            } else {
                out.printf(Locale.US, "recommendation: stop %s, target %s, %d shares%n", money(rec.stop()), money(rec.target()), r.shares(rec.stop()));
                out.printf(Locale.US, "  total P&L %,.2f over %d trades: %d targets, %d stops, %d held to 15:55; worst trade %,.2f%n",
                        s.pnl(), s.trades(), s.targets(), s.stops(), s.held(), s.worstTrade());
                out.printf(Locale.US, "  by month: %s%n", monthly(r.recommendedByMonth()));
                out.printf(Locale.US, "  worst month %,.0f, %d of %d months positive%n", r.worstMonth()[ri][rj], r.monthsPositive()[ri][rj], n);
                out.printf(Locale.US, "  chosen by mean monthly P&L minus its standard deviation (%,.0f here), smoothed over neighbouring cells,%n"
                        + "  so a setting that earns steadily beats one that earns once%n", rec.score());
                printCharacter(rec, out);
                printByTime(r.byTime(), out);
            }
        }
        for (CellDetail d : r.shown()) {
            BracketSimulator.Summary s = d.summary();
            out.println();
            out.printf(Locale.US, "detail: stop %s, target %s, %d shares%n", level(d.stop()), level(d.target()), r.shares(d.stop()));
            out.printf(Locale.US, "  total P&L %,.2f over %d trades: %d targets, %d stops, %d held to 15:55; worst trade %,.2f%n",
                    s.pnl(), s.trades(), s.targets(), s.stops(), s.held(), s.worstTrade());
            out.printf(Locale.US, "  by month: %s%n", monthly(d.byMonth()));
            double worst = d.byMonth().values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
            long positive = d.byMonth().values().stream().filter(v -> v > 0).count();
            out.printf(Locale.US, "  worst month %,.0f, %d of %d months positive%n", worst, positive, n);
            if (d.stop() < BracketSimulator.NONE && d.target() < BracketSimulator.NONE) {
                printCharacter(new Recommendation(d.stop(), d.target(), s, 0), out);
            }
            printByTime(d.byTime(), out);
        }
    }

    private static String monthly(Map<YearMonth, Double> byMonth) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<YearMonth, Double> e : byMonth.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append(' ').append(String.format(Locale.US, "%+,.0f", e.getValue()));
        }
        return sb.toString();
    }

    /** Row and column of the {@code k} finite cells with the largest total, best first. */
    static List<int[]> topByTotal(MultiResult r, int k) {
        List<int[]> cells = new ArrayList<>();
        for (int i = 0; i < r.stops().size(); i++) {
            for (int j = 0; j < r.targets().size(); j++) {
                if (r.stops().get(i) < BracketSimulator.NONE && r.targets().get(j) < BracketSimulator.NONE) {
                    cells.add(new int[]{i, j});
                }
            }
        }
        cells.sort((a, b) -> Double.compare(r.total()[b[0]][b[1]], r.total()[a[0]][a[1]]));
        return cells.subList(0, Math.min(k, cells.size()));
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

    /**
     * "2000 shares per trade", or the risk budget and the share count it implies for each stop; with a position
     * cap, the cap and the counts it allows at the average signal price.
     */
    static String sizingText(Sizing sizing, List<Double> stops, double referencePrice) {
        if (!sizing.byRisk() && !sizing.capped()) {
            return sizing.fixedShares() + " shares per trade";
        }
        StringBuilder sb = new StringBuilder();
        if (sizing.byRisk()) {
            sb.append(String.format(Locale.US, "%,.0f risked per trade", sizing.riskDollars()));
        } else {
            sb.append(sizing.fixedShares()).append(" shares per trade");
        }
        if (sizing.capped()) {
            sb.append(String.format(Locale.US, ", at most %,.0f per position", sizing.capDollars()));
        }
        sb.append(sizing.capped() ? String.format(Locale.US, " (shares by stop at ~%.2f:", referencePrice) : " (shares by stop:");
        for (double stop : stops) {
            sb.append(' ').append(level(stop)).append('\u2192').append(sizing.sharesFor(stop, referencePrice));
        }
        return sb.append(')').toString();
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
