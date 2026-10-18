package com.hamilla.sma;

/**
 * The 20/50/200 alignment at one moment.
 * <ul>
 *   <li>{@link #UP}: 200 below 50, 50 below 20, and the price above the 20.</li>
 *   <li>{@link #DOWN}: 200 above 50, 50 above 20, and the price below the 20.</li>
 *   <li>{@link #NONE}: anything else, including any tie or an average that is not yet available.</li>
 * </ul>
 */
public enum Trend {
    UP, DOWN, NONE;

    public static Trend of(SmaSnapshot s) {
        double p = s.price();
        double a20 = s.sma20();
        double a50 = s.sma50();
        double a200 = s.sma200();
        if (Double.isNaN(a20) || Double.isNaN(a50) || Double.isNaN(a200)) {
            return NONE;
        }
        if (a200 < a50 && a50 < a20 && p > a20) {
            return UP;
        }
        if (a200 > a50 && a50 > a20 && p < a20) {
            return DOWN;
        }
        return NONE;
    }

    /** Lower-case label as printed by the replay. */
    public String label() {
        return name().toLowerCase();
    }
}
