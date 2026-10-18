package com.hamilla.sma;

import com.hamilla.market.PriceEvent;

import java.util.Optional;

/**
 * Turns a stream of trades into the one-minute bar series the engine runs on. Trades that do not
 * update the close (odd lots and the like) are ignored entirely. When a trade arrives in a later
 * minute, the bar now forming is closed at its last updating price and rolled into the windows,
 * and every session minute skipped in between gets a bar repeating that close, exactly as
 * Massive's minute indicators do. Minutes outside 04:00 to 20:00 Eastern produce nothing.
 */
public final class MinuteBarTracker {

    /** Told about each bar once it has completed, with the averages as they stood at its close. */
    @FunctionalInterface
    public interface BarListener {
        /** @param filled true when the minute had no updating trade and repeats the previous close */
        void barClosed(long barStartMs, double close, SmaSnapshot atClose, boolean filled);
    }

    private final MovingAverages engine;
    private final BarListener listener;
    private long currentBarStartMs = Long.MIN_VALUE;
    private double lastClose = Double.NaN;
    private long lastTradeNanos;
    private SmaSnapshot lastSnapshot;
    private long barsClosed;
    private long barsFilled;

    public MinuteBarTracker(MovingAverages engine, BarListener listener) {
        this.engine = engine;
        this.listener = listener == null ? (b, c, s, f) -> { } : listener;
    }

    /**
     * Tells the tracker where the warm-up series ended, so session minutes between that bar and the
     * first trade get bars repeating {@code close}. The bar itself must already be in the engine's windows.
     */
    public void seed(long lastBarStartMs, double close) {
        currentBarStartMs = lastBarStartMs;
        lastClose = close;
        lastTradeNanos = lastBarStartMs * 1_000_000L;
        seeded = true;
    }

    private boolean seeded;

    /**
     * Feeds one trade. Returns the averages as of that trade, or empty if the trade does not
     * update the close and so changed nothing.
     */
    public Optional<SmaSnapshot> onTrade(PriceEvent event) {
        if (!event.updatesClose()) {
            return Optional.empty();
        }
        long bar = event.minuteBarStartMs();
        if (currentBarStartMs == Long.MIN_VALUE) {
            currentBarStartMs = bar;
        } else if (bar > currentBarStartMs) {
            if (seeded) {
                seeded = false; // the seed bar is already in the windows; only fill after it
            } else {
                closeCurrentBar(false);
            }
            fillThrough(bar - SessionMinutes.MINUTE_MS);
            currentBarStartMs = bar;
        }
        // A trade stamped earlier than the forming bar (out of order) is applied to the forming bar.
        lastClose = event.price();
        lastTradeNanos = event.sipTimestampNanos();
        lastSnapshot = engine.update(event);
        return Optional.of(lastSnapshot);
    }

    /**
     * Closes the bar now forming and fills the rest of its session with repeats of its close,
     * so the series ends at the 19:59 bar. Call once the day's stream has ended.
     */
    public void finishSession() {
        if (currentBarStartMs == Long.MIN_VALUE) {
            return;
        }
        long end = SessionMinutes.lastBarOfSession(currentBarStartMs);
        if (seeded) {
            seeded = false;
        } else {
            closeCurrentBar(false);
        }
        fillThrough(end);
        currentBarStartMs = Long.MIN_VALUE;
    }

    public long barsClosed() {
        return barsClosed;
    }

    public long barsFilled() {
        return barsFilled;
    }

    /** The last trade's averages, or null before any updating trade. */
    public SmaSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    private void closeCurrentBar(boolean filled) {
        if (Double.isNaN(lastClose)) {
            return;
        }
        SmaSnapshot atClose = engine.snapshotAt(lastTradeNanos, lastClose);
        engine.closeBar(lastClose);
        barsClosed++;
        if (filled) {
            barsFilled++;
        }
        listener.barClosed(currentBarStartMs, atClose.price(), atClose, filled);
    }

    /** Emits a repeated-close bar for every session minute after the current bar up to {@code lastMs} inclusive. */
    private void fillThrough(long lastMs) {
        for (long m = currentBarStartMs + SessionMinutes.MINUTE_MS; m <= lastMs; m += SessionMinutes.MINUTE_MS) {
            if (!SessionMinutes.inSession(m)) {
                continue;
            }
            currentBarStartMs = m;
            closeCurrentBar(true);
        }
    }
}
