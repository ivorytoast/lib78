package com.hamilla.sma;

import com.hamilla.market.PriceEvent;

import java.util.Optional;

/**
 * Turns a stream of trades into one-minute bars for the engine. Trades that do not update the
 * close (odd lots and the like) or fall outside the {@link Session} are ignored entirely. When a
 * trade arrives in a later minute, the bar now forming is closed at its last updating price and
 * rolled into the windows.
 *
 * <p>Every session minute in between is reported to the listener as well, so a caller can print
 * one line per minute. What such an empty minute does to the windows depends on the mode:
 * in chart mode ({@code fillEmptyMinutes} false) it is not a bar and the windows are untouched,
 * as charting platforms do; in filled mode it becomes a bar repeating the previous close, as
 * Massive's minute indicators do.
 */
public final class MinuteBarTracker {

    /** Told about each session minute once it has ended, with the averages as they stood at that point. */
    @FunctionalInterface
    public interface BarListener {
        /** @param empty true when the minute had no updating trade; its {@code close} is the previous one */
        void barClosed(long barStartMs, double close, SmaSnapshot atClose, boolean empty);
    }

    private final MovingAverages engine;
    private final Session session;
    private final boolean fillEmptyMinutes;
    private final BarListener listener;
    private long currentBarStartMs = Long.MIN_VALUE;
    private boolean seeded;
    private double lastClose = Double.NaN;
    private long lastTradeNanos;
    private SmaSnapshot lastSnapshot;
    /** The averages at the close of the last bar rolled into the windows; what an empty minute shows in chart mode. */
    private SmaSnapshot lastBarSnapshot;
    private long barsClosed;
    private long emptyMinutes;

    public MinuteBarTracker(MovingAverages engine, Session session, boolean fillEmptyMinutes, BarListener listener) {
        this.engine = engine;
        this.session = session;
        this.fillEmptyMinutes = fillEmptyMinutes;
        this.listener = listener == null ? (b, c, s, e) -> { } : listener;
    }

    /**
     * Rolls the last bar of the warm-up series into the windows and remembers where it ended, so
     * session minutes between it and the first trade are reported (and, in filled mode, become
     * bars). The engine must hold the warm-up closes before this bar, not this bar itself.
     */
    public void seed(long lastBarStartMs, double close) {
        currentBarStartMs = lastBarStartMs;
        lastClose = close;
        lastTradeNanos = lastBarStartMs * 1_000_000L;
        lastBarSnapshot = engine.snapshotAt(lastTradeNanos, close);
        engine.closeBar(close);
        seeded = true;
    }

    /**
     * Feeds one trade. Returns the averages as of that trade, or empty if the trade does not
     * update the close or is outside the session, and so changed nothing.
     */
    public Optional<SmaSnapshot> onTrade(PriceEvent event) {
        if (!event.updatesClose()) {
            return Optional.empty();
        }
        long bar = event.minuteBarStartMs();
        if (!session.contains(bar)) {
            return Optional.empty();
        }
        if (currentBarStartMs == Long.MIN_VALUE) {
            currentBarStartMs = bar;
        } else if (bar > currentBarStartMs) {
            if (seeded) {
                seeded = false; // the seed bar is already in the windows; only report what follows it
            } else {
                closeCurrentBar();
            }
            emptyThrough(bar - Session.MINUTE_MS);
            currentBarStartMs = bar;
        }
        // A trade stamped earlier than the forming bar (out of order) is applied to the forming bar.
        lastClose = event.price();
        lastTradeNanos = event.sipTimestampNanos();
        lastSnapshot = engine.update(event);
        return Optional.of(lastSnapshot);
    }

    /**
     * Closes the bar now forming and reports the rest of its day's session as empty minutes,
     * so the series ends at the session's last bar. Call once the day's stream has ended.
     */
    public void finishSession() {
        if (currentBarStartMs == Long.MIN_VALUE) {
            return;
        }
        long end = session.lastBarOfDay(currentBarStartMs);
        if (seeded) {
            seeded = false;
        } else {
            closeCurrentBar();
        }
        emptyThrough(end);
        currentBarStartMs = Long.MIN_VALUE;
    }

    /** Bars rolled into the windows: real bars, plus empty minutes in filled mode. */
    public long barsClosed() {
        return barsClosed;
    }

    /** Session minutes with no updating trade. */
    public long emptyMinutes() {
        return emptyMinutes;
    }

    /** The last trade's averages, or null before any updating trade. */
    public SmaSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    private void closeCurrentBar() {
        if (Double.isNaN(lastClose)) {
            return;
        }
        SmaSnapshot atClose = engine.snapshotAt(lastTradeNanos, lastClose);
        engine.closeBar(lastClose);
        lastBarSnapshot = atClose;
        barsClosed++;
        listener.barClosed(currentBarStartMs, lastClose, atClose, false);
    }

    /** Reports every session minute after the current bar up to {@code lastMs} inclusive as empty. */
    private void emptyThrough(long lastMs) {
        if (Double.isNaN(lastClose)) {
            return;
        }
        for (long m = currentBarStartMs + Session.MINUTE_MS; m <= lastMs; m += Session.MINUTE_MS) {
            if (!session.contains(m)) {
                continue;
            }
            currentBarStartMs = m;
            emptyMinutes++;
            SmaSnapshot state;
            if (fillEmptyMinutes) {
                // The empty minute is a bar closing at the previous close; report it as it closes, then roll it in.
                state = engine.snapshotAt(lastTradeNanos, lastClose);
                engine.closeBar(lastClose);
                lastBarSnapshot = state;
                barsClosed++;
            } else {
                // No bar: the chart still shows the last real bar's values.
                state = lastBarSnapshot;
            }
            listener.barClosed(m, lastClose, state, true);
        }
    }
}
