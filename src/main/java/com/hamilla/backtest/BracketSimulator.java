package com.hamilla.backtest;

import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;
import com.hamilla.sma.Session;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Simulates bracket orders on one-minute bars. Each signal is entered at its price when the next
 * minute opens, with a stop {@code stop} dollars against it and a target {@code target} dollars in
 * its favor. A later minute whose low (long) or high (short) reaches the stop fills it at the stop
 * price; one whose high or low reaches the target fills at the target; whichever comes first wins,
 * and a minute that touches both counts as the stop. Anything still open is closed at the price at
 * {@link #EXIT_TIME}, the close of the last bar before it. Signals from {@link #LAST_ENTRY} on are
 * skipped, as they could not be held.
 */
public final class BracketSimulator {

    public static final LocalTime EXIT_TIME = LocalTime.of(15, 55);
    public static final LocalTime LAST_ENTRY = LocalTime.of(15, 54);
    /** A target or stop of at least this size means "none". */
    public static final double NONE = 99;

    public enum Outcome { TARGET, STOP, HELD }

    public record Trade(TradeSignal signal, Outcome outcome, long exitBarMs, double exitPrice, double pnl) {
    }

    public record Summary(double stop, double target, double pnl, int trades, int targets, int stops, int held,
                          double worstTrade, Map<LocalDate, Double> pnlByDay) {
    }

    private final NavigableMap<Long, MinuteBar> bars = new TreeMap<>();
    private final Map<LocalDate, Double> exitPriceByDay = new TreeMap<>();
    private final Map<LocalDate, Long> exitBarByDay = new TreeMap<>();

    public BracketSimulator(List<MinuteBar> bars) {
        for (MinuteBar b : bars) {
            this.bars.put(b.startMs(), b);
        }
        for (MinuteBar b : bars) {
            LocalDate day = Instant.ofEpochMilli(b.startMs()).atZone(PriceEvent.EASTERN).toLocalDate();
            if (!exitPriceByDay.containsKey(day)) {
                long lastEntryBar = day.atTime(LAST_ENTRY).atZone(PriceEvent.EASTERN).toInstant().toEpochMilli();
                long dayStart = day.atStartOfDay(PriceEvent.EASTERN).toInstant().toEpochMilli();
                Map.Entry<Long, MinuteBar> exitBar = this.bars.floorEntry(lastEntryBar);
                if (exitBar != null && exitBar.getKey() >= dayStart) {
                    exitPriceByDay.put(day, exitBar.getValue().close());
                    exitBarByDay.put(day, exitBar.getKey());
                }
            }
        }
    }

    /** Runs every signal with the given brackets. */
    public List<Trade> simulate(List<TradeSignal> signals, double stop, double target, int shares) {
        List<Trade> trades = new ArrayList<>();
        for (TradeSignal s : signals) {
            LocalDate day = s.date();
            LocalTime time = Instant.ofEpochMilli(s.barStartMs()).atZone(PriceEvent.EASTERN).toLocalTime();
            Long exitBar = exitBarByDay.get(day);
            if (exitBar == null || !time.isBefore(LAST_ENTRY)) {
                continue;
            }
            double stopPx = s.isLong() ? s.price() - stop : s.price() + stop;
            double targetPx = s.isLong() ? s.price() + target : s.price() - target;
            Outcome outcome = Outcome.HELD;
            long exitAt = exitBar;
            double exitPx = exitPriceByDay.get(day);
            for (MinuteBar b : bars.subMap(s.barStartMs() + Session.MINUTE_MS, true, exitBar, true).values()) {
                boolean stopHit = s.isLong() ? b.low() <= stopPx + 1e-9 : b.high() >= stopPx - 1e-9;
                boolean targetHit = s.isLong() ? b.high() >= targetPx - 1e-9 : b.low() <= targetPx + 1e-9;
                if (stopHit) {
                    outcome = Outcome.STOP;
                    exitAt = b.startMs();
                    exitPx = stopPx;
                    break;
                }
                if (targetHit) {
                    outcome = Outcome.TARGET;
                    exitAt = b.startMs();
                    exitPx = targetPx;
                    break;
                }
            }
            double pnl = (s.isLong() ? exitPx - s.price() : s.price() - exitPx) * shares;
            trades.add(new Trade(s, outcome, exitAt, exitPx, pnl));
        }
        return trades;
    }

    public Summary summarize(List<TradeSignal> signals, double stop, double target, int shares) {
        List<Trade> trades = simulate(signals, stop, target, shares);
        double total = 0;
        double worst = 0;
        int t = 0;
        int st = 0;
        int h = 0;
        Map<LocalDate, Double> byDay = new TreeMap<>();
        for (Trade tr : trades) {
            total += tr.pnl();
            worst = Math.min(worst, tr.pnl());
            byDay.merge(tr.signal().date(), tr.pnl(), Double::sum);
            switch (tr.outcome()) {
                case TARGET -> t++;
                case STOP -> st++;
                case HELD -> h++;
            }
        }
        return new Summary(stop, target, total, trades.size(), t, st, h, worst, byDay);
    }

    /** Days the simulator can exit on: days with a bar at or before 15:54. */
    public java.util.Set<LocalDate> days() {
        return exitPriceByDay.keySet();
    }
}
