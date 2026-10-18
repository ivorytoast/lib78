package com.hamilla.sma;

import com.hamilla.market.PriceEvent;

import java.util.List;

/** The 20, 50 and 200 day simple moving averages for one symbol, updated on every price. */
public final class MovingAverages {

    public static final int[] WINDOWS = {20, 50, 200};

    private final String symbol;
    private final SmaWindow sma20;
    private final SmaWindow sma50;
    private final SmaWindow sma200;

    /**
     * @param symbol      ticker this engine tracks
     * @param priorCloses completed daily closes, oldest first, not including today. At least 199
     *                    are needed for the 200 day average to be available.
     */
    public MovingAverages(String symbol, List<Double> priorCloses) {
        this.symbol = symbol;
        this.sma20 = new SmaWindow(20, priorCloses);
        this.sma50 = new SmaWindow(50, priorCloses);
        this.sma200 = new SmaWindow(200, priorCloses);
    }

    public String symbol() {
        return symbol;
    }

    /** Computes the averages as of {@code event}. Does not change any state; days roll via {@link #closeDay}. */
    public SmaSnapshot update(PriceEvent event) {
        if (!event.symbol().equals(symbol)) {
            throw new IllegalArgumentException("engine for " + symbol + " received " + event.symbol());
        }
        return snapshotAt(event.sipTimestampNanos(), event.price());
    }

    /** The averages if the current day closed at {@code price}. */
    public SmaSnapshot snapshotAt(long sipTimestampNanos, double price) {
        return new SmaSnapshot(symbol, sipTimestampNanos, price,
                sma20.valueAt(price), sma50.valueAt(price), sma200.valueAt(price));
    }

    /** Call once the trading day is over with its official close, so tomorrow's values use it. */
    public void closeDay(double close) {
        sma20.closeDay(close);
        sma50.closeDay(close);
        sma200.closeDay(close);
    }

    public boolean ready(int window) {
        return switch (window) {
            case 20 -> sma20.ready();
            case 50 -> sma50.ready();
            case 200 -> sma200.ready();
            default -> throw new IllegalArgumentException("no window " + window);
        };
    }
}
