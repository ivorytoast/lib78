package com.hamilla.sma;

/**
 * The 20/50/200 alignment at one moment.
 * <ul>
 *   <li>{@link #UP}: 200 below 50, 50 below 20 by at least {@link #MIN_20_50_GAP}, and the price above the 20.</li>
 *   <li>{@link #DOWN}: 200 above 50, 50 above 20 by at least {@link #MIN_20_50_GAP}, and the price below the 20.</li>
 *   <li>{@link #NONE}: anything else, including any tie, a 20 and 50 closer than the gap, or an
 *       average that is not yet available.</li>
 * </ul>
 */
public enum Trend {
    UP, DOWN, NONE;

    /** The 20 and 50 must be at least this far apart, in dollars, for a zone to exist. */
    public static final double MIN_20_50_GAP = 0.03;
    /** Slack for floating-point averages, so a gap of exactly three cents still counts. */
    private static final double EPSILON = 1e-9;

    public static Trend of(SmaSnapshot s) {
        double p = s.price();
        double a20 = s.sma20();
        double a50 = s.sma50();
        double a200 = s.sma200();
        if (Double.isNaN(a20) || Double.isNaN(a50) || Double.isNaN(a200)) {
            return NONE;
        }
        if (Math.abs(a20 - a50) < MIN_20_50_GAP - EPSILON) {
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
