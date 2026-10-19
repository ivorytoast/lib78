package com.hamilla.live;

import com.hamilla.backtest.BracketSimulator;
import com.hamilla.backtest.Sizing;
import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;
import com.hamilla.sma.Action;
import com.hamilla.sma.Cooldown;
import com.hamilla.sma.MovingAverages;
import com.hamilla.sma.Session;
import com.hamilla.sma.SignalWindow;
import com.hamilla.sma.SmaSnapshot;
import com.hamilla.sma.Thresholds;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A book of symbols traded live on completed one-minute bars, as a paper blotter: it says what to do, it
 * does not send orders. Every completed bar is rolled through the same 20/50/200 engine the replay and
 * grid use; a bar whose close is an entry signal inside the {@link SignalWindow} on the trading day
 * (after the per-symbol cooldown) opens a position sized by {@link Sizing} with the symbol's
 * {@link Bracket}, unless that would put more than {@code bookDollars} in open positions at once, in
 * which case the signal is reported as skipped. Later bars of the same symbol fill the stop or target
 * on their low and high (a minute touching both is a stop), and whatever is still open is closed at
 * the close of the 15:54 bar, exactly as {@link BracketSimulator} does, so a day run through this book
 * produces the grid's trades.
 *
 * <p>Bars from earlier days warm the averages up and never trade. Feed bars for each symbol oldest first.
 */
public final class PaperBook {

    public enum Exit { TARGET, STOP, CLOSE }

    /** An open or closed paper position. {@code barStartMs} is the signal bar; entry is at its close. */
    public record Position(String symbol, long barStartMs, Action action, double entryPrice, int shares,
                           double stopPrice, double targetPrice) {
        public boolean isLong() {
            return action == Action.BUY_LONG;
        }

        public double notional() {
            return entryPrice * shares;
        }

        public double pnlAt(double exitPrice) {
            return (isLong() ? exitPrice - entryPrice : entryPrice - exitPrice) * shares;
        }
    }

    /** A closed position with how it ended. */
    public record Trade(Position position, Exit exit, long exitBarMs, double exitPrice, double pnl) {
    }

    /** Told what happens as bars close. Every method has an empty default so a caller can pick what it wants. */
    public interface Listener {
        /** A signal that opened a position. {@code openNotional} includes it. */
        default void opened(Position p, double openNotional) { }

        /** A signal that was not taken because it would exceed the book cap. */
        default void skipped(String symbol, long barStartMs, Action action, double price, int shares, double openNotional) { }

        /** A position came off. {@code dayPnl} is the realized total for the trading day after it. */
        default void closed(Trade t, double dayPnl) { }

        /** A bar closed on the trading day; for callers that want to show every minute. */
        default void bar(String symbol, MinuteBar bar, SmaSnapshot atClose, Optional<Action> action) { }
    }

    private final Map<String, Bracket> brackets;
    private final Map<String, Thresholds> thresholds;
    private final Sizing sizing;
    private final double bookDollars;
    private final Session session;
    private final SignalWindow window;
    private final LocalDate tradingDay;
    private final Listener listener;
    private final Map<String, MovingAverages> engines = new HashMap<>();
    private final Map<String, Cooldown> cooldowns = new HashMap<>();
    private final Map<String, Long> lastBar = new HashMap<>();
    private final Map<String, Double> lastClose = new HashMap<>();
    private final List<Position> open = new ArrayList<>();
    private final List<Trade> closed = new ArrayList<>();
    private final List<Position> skipped = new ArrayList<>();
    private double dayPnl;

    /**
     * @param brackets    stop and target per symbol; a bar for a symbol not listed is rejected
     * @param sizing      how many shares a signal trades, at its stop and price
     * @param bookDollars most dollars in open positions at once; 0 for no limit
     * @param tradingDay  the only day whose signals are acted on; earlier bars only warm the averages
     */
    public PaperBook(Map<String, Bracket> brackets, Sizing sizing, double bookDollars, Session session, SignalWindow window,
                     int cooldownMinutes, LocalDate tradingDay, Listener listener) {
        this(brackets, Map.of(), sizing, bookDollars, session, window, cooldownMinutes, tradingDay, listener);
    }

    /** @param thresholds per-symbol entry band and zone gaps; a symbol not listed uses {@link Thresholds#DEFAULT} */
    public PaperBook(Map<String, Bracket> brackets, Map<String, Thresholds> thresholds, Sizing sizing, double bookDollars,
                     Session session, SignalWindow window, int cooldownMinutes, LocalDate tradingDay, Listener listener) {
        if (brackets.isEmpty()) {
            throw new IllegalArgumentException("at least one symbol with a bracket is needed");
        }
        if (bookDollars < 0) {
            throw new IllegalArgumentException("book must not be negative, got " + bookDollars);
        }
        this.brackets = Map.copyOf(brackets);
        this.thresholds = Map.copyOf(thresholds);
        this.sizing = sizing;
        this.bookDollars = bookDollars;
        this.session = session;
        this.window = window;
        this.tradingDay = tradingDay;
        this.listener = listener == null ? new Listener() { } : listener;
        for (String symbol : brackets.keySet()) {
            engines.put(symbol, new MovingAverages(symbol, List.of()));
            cooldowns.put(symbol, new Cooldown(cooldownMinutes));
        }
    }

    public LocalDate tradingDay() {
        return tradingDay;
    }

    /** Start of the last bar fed for {@code symbol}, or empty before any. */
    public Optional<Long> lastBar(String symbol) {
        return Optional.ofNullable(lastBar.get(symbol));
    }

    public List<Position> openPositions() {
        return List.copyOf(open);
    }

    public List<Trade> trades() {
        return List.copyOf(closed);
    }

    /** Signals skipped because the book was full, as the positions they would have been. */
    public List<Position> skippedSignals() {
        return List.copyOf(skipped);
    }

    public double dayPnl() {
        return dayPnl;
    }

    public double openNotional() {
        double total = 0;
        for (Position p : open) {
            total += p.notional();
        }
        return total;
    }

    /** Feeds every bar in order; bars at or before the symbol's last bar are ignored. */
    public void onBars(String symbol, List<MinuteBar> bars) {
        for (MinuteBar bar : bars) {
            onBar(symbol, bar);
        }
    }

    /**
     * Rolls one completed bar through the engine. Outside the session, or not after the symbol's last bar,
     * it is ignored. On the trading day it first fills any open position of the symbol, then acts on the close.
     */
    public void onBar(String symbol, MinuteBar bar) {
        MovingAverages engine = engines.get(symbol);
        if (engine == null) {
            throw new IllegalArgumentException("no bracket for " + symbol);
        }
        Long last = lastBar.get(symbol);
        if ((last != null && bar.startMs() <= last) || !session.contains(bar.startMs())) {
            return;
        }
        lastBar.put(symbol, bar.startMs());
        lastClose.put(symbol, bar.close());
        long nanos = (bar.startMs() + Session.MINUTE_MS - 1) * 1_000_000L;
        SmaSnapshot atClose = engine.snapshotAt(nanos, bar.close());
        engine.closeBar(bar.close());

        ZonedDateTime et = Instant.ofEpochMilli(bar.startMs()).atZone(PriceEvent.EASTERN);
        if (!et.toLocalDate().equals(tradingDay)) {
            return;
        }
        fill(symbol, bar, et.toLocalTime());
        Optional<Action> action = Action.of(atClose, thresholds.getOrDefault(symbol, Thresholds.DEFAULT));
        listener.bar(symbol, bar, atClose, action);
        if (action.isEmpty() || !window.contains(bar.startMs()) || !et.toLocalTime().isBefore(BracketSimulator.LAST_ENTRY)) {
            return;
        }
        if (!cooldowns.get(symbol).take(bar.startMs())) {
            return;
        }
        Bracket b = brackets.get(symbol);
        double price = bar.close();
        int shares = sizing.sharesFor(b.stop(), price);
        boolean isLong = action.get() == Action.BUY_LONG;
        Position p = new Position(symbol, bar.startMs(), action.get(), price, shares,
                isLong ? price - b.stop() : price + b.stop(), isLong ? price + b.target() : price - b.target());
        double before = openNotional();
        if (bookDollars > 0 && before + p.notional() > bookDollars + 1e-9) {
            skipped.add(p);
            listener.skipped(symbol, bar.startMs(), action.get(), price, shares, before);
            return;
        }
        open.add(p);
        listener.opened(p, before + p.notional());
    }

    /**
     * Checks the minute still forming against {@code symbol}'s open positions: a stop or target its high or
     * low has already reached is filled now rather than when the minute closes. The bar is not rolled into
     * the averages and nothing is entered on it; feed it again with {@link #onBar} once it completes. A
     * forming bar on another day than the trading day, or at or before a position's own bar, does nothing.
     */
    public void onFormingBar(String symbol, MinuteBar bar) {
        if (!engines.containsKey(symbol)) {
            throw new IllegalArgumentException("no bracket for " + symbol);
        }
        ZonedDateTime et = Instant.ofEpochMilli(bar.startMs()).atZone(PriceEvent.EASTERN);
        if (!et.toLocalDate().equals(tradingDay) || !session.contains(bar.startMs())) {
            return;
        }
        fill(symbol, bar, et.toLocalTime(), false);
    }

    private void fill(String symbol, MinuteBar bar, LocalTime time) {
        fill(symbol, bar, time, true);
    }

    /**
     * Fills stops and targets of {@code symbol}'s open positions on this bar. For a completed bar from 15:54
     * on, the rest is closed at its close; a forming bar never closes anything.
     */
    private void fill(String symbol, MinuteBar bar, LocalTime time, boolean completed) {
        boolean lastBarOfDay = completed && !time.isBefore(BracketSimulator.LAST_ENTRY);
        for (Iterator<Position> it = open.iterator(); it.hasNext(); ) {
            Position p = it.next();
            if (!p.symbol().equals(symbol) || bar.startMs() <= p.barStartMs()) {
                continue;
            }
            boolean stopHit = p.isLong() ? bar.low() <= p.stopPrice() + 1e-9 : bar.high() >= p.stopPrice() - 1e-9;
            boolean targetHit = p.isLong() ? bar.high() >= p.targetPrice() - 1e-9 : bar.low() <= p.targetPrice() + 1e-9;
            Exit exit;
            double exitPx;
            if (stopHit) {
                exit = Exit.STOP;
                exitPx = p.stopPrice();
            } else if (targetHit) {
                exit = Exit.TARGET;
                exitPx = p.targetPrice();
            } else if (lastBarOfDay) {
                exit = Exit.CLOSE;
                exitPx = bar.close();
            } else {
                continue;
            }
            it.remove();
            record(new Trade(p, exit, bar.startMs(), exitPx, p.pnlAt(exitPx)));
        }
    }

    /**
     * Closes every open position at its symbol's last close, for a day whose bars ended before 15:54
     * (an early close, or a run stopped early). Returns what was closed.
     */
    public List<Trade> closeAll() {
        List<Trade> out = new ArrayList<>();
        for (Iterator<Position> it = open.iterator(); it.hasNext(); ) {
            Position p = it.next();
            it.remove();
            double px = lastClose.getOrDefault(p.symbol(), p.entryPrice());
            Trade t = new Trade(p, Exit.CLOSE, lastBar.getOrDefault(p.symbol(), p.barStartMs()), px, p.pnlAt(px));
            record(t);
            out.add(t);
        }
        return out;
    }

    private void record(Trade t) {
        closed.add(t);
        dayPnl += t.pnl();
        listener.closed(t, dayPnl);
    }
}
