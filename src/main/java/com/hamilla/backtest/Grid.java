package com.hamilla.backtest;

import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;
import com.hamilla.sma.Session;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The stop-by-target table of total P&L for a month of signals, and a recommendation read from it.
 *
 * <p>The recommendation is not the best cell. It is the finite stop and target whose 3-by-3
 * neighbourhood has the highest average P&L, so it sits on a plateau rather than a spike: a
 * setting whose neighbours are all good is one that should survive a different month.
 */
public final class Grid {

    /** Stop sizes as multiples of the typical one-minute range; the last is "no stop". */
    static final double[] STOP_MULTIPLES = {1, 1.5, 2, 3, 4, 6};
    /** Target sizes as multiples of the typical one-minute range; the last is "no target". */
    static final double[] TARGET_MULTIPLES = {1.5, 2, 3, 4, 6, 8};

    public record Result(String symbol, List<Double> stops, List<Double> targets, double[][] pnl,
                         BracketSimulator.Summary[][] cells, double typicalRange, int signals, int shares,
                         Recommendation recommendation) {
    }

    public record Recommendation(double stop, double target, BracketSimulator.Summary summary, double neighbourhoodAverage) {
        /** Win rate needed to break even with these brackets, ignoring held trades. */
        public double breakevenWinRate() {
            return stop / (stop + target);
        }

        public double winRate() {
            int decided = summary.targets() + summary.stops();
            return decided == 0 ? Double.NaN : (double) summary.targets() / decided;
        }
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
        return new Result(symbol, stops, targets, pnl, cells, typicalRange, signals.size(), shares,
                recommend(stops, targets, pnl, cells));
    }

    /**
     * The finite cell with the best 3-by-3 neighbourhood average. A neighbour outside the table counts
     * as the cell's own value, so edge cells are neither favoured nor penalised for having fewer neighbours.
     */
    static Recommendation recommend(List<Double> stops, List<Double> targets, double[][] pnl, BracketSimulator.Summary[][] cells) {
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
                        sum += inside ? pnl[a][b] : pnl[i][j];
                    }
                }
                double avg = sum / 9;
                if (best == null || avg > best.neighbourhoodAverage()) {
                    best = new Recommendation(stops.get(i), targets.get(j), cells[i][j], avg);
                }
            }
        }
        return best;
    }

    public static void print(Result r, PrintStream out) {
        out.printf(Locale.US, "%s: %d signals, %d shares per trade, typical regular-hours minute range %s%n%n",
                r.symbol(), r.signals(), r.shares(), money(r.typicalRange()));
        out.print("stop \\ target");
        for (double t : r.targets()) {
            out.printf("%10s", level(t));
        }
        out.println();
        for (int i = 0; i < r.stops().size(); i++) {
            out.printf("%-13s", level(r.stops().get(i)));
            for (int j = 0; j < r.targets().size(); j++) {
                out.printf("%10s", String.format(Locale.US, "%,.0f", r.pnl()[i][j]));
            }
            out.println();
        }
        out.println();

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
                rec.neighbourhoodAverage());
        out.printf(Locale.US, "  risk/reward %.2f to 1: breakeven win rate %.0f%%, actual %s%n",
                rec.target() / rec.stop(), 100 * rec.breakevenWinRate(),
                Double.isNaN(rec.winRate()) ? "n/a" : String.format(Locale.US, "%.0f%%", 100 * rec.winRate()));
        if (rec.target() >= 2 * rec.stop()) {
            out.println("  this is a trend bet: it wins rarely but by more than it loses, and chops will string losses together");
        } else if (rec.stop() >= 1.5 * rec.target()) {
            out.println("  this is a scalp: it needs a high win rate, and a strong move against it costs several wins");
        }
        double share = biggestDayShare(s);
        if (share > 1.0) {
            out.printf(Locale.US, "  caution: the best day made %,.0f, more than the whole month; the other days net a loss%n", bestDay(s));
        } else if (share > 0.5) {
            out.printf(Locale.US, "  caution: one day supplies %.0f%% of the P&L; validate on another month before trusting it%n", 100 * share);
        }
        if (s.trades() < 100) {
            out.printf(Locale.US, "  caution: %d trades is a small sample; differences of a few hundred dollars between cells are noise%n", s.trades());
        }
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
