package com.hamilla.sma;

import java.util.Optional;

/**
 * An entry signal: the trend zone is in place and the price is within {@link #BAND} of the 20.
 * <ul>
 *   <li>{@link #BUY_LONG}: {@link Trend#UP} with the price no more than two cents above the 20.</li>
 *   <li>{@link #SHORT_SELL}: {@link Trend#DOWN} with the price no more than two cents below the 20.</li>
 * </ul>
 */
public enum Action {
    BUY_LONG("BUY LONG"), SHORT_SELL("SHORT SELL");

    /** How far from the 20 the price may be, in dollars. */
    public static final double BAND = 0.02;
    /** Slack for floating-point averages, so a price exactly two cents away still counts. */
    private static final double EPSILON = 1e-9;

    private final String label;

    Action(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<Action> of(SmaSnapshot s) {
        Trend trend = Trend.of(s);
        if (trend == Trend.UP && s.price() - s.sma20() <= BAND + EPSILON) {
            return Optional.of(BUY_LONG);
        }
        if (trend == Trend.DOWN && s.sma20() - s.price() <= BAND + EPSILON) {
            return Optional.of(SHORT_SELL);
        }
        return Optional.empty();
    }
}
