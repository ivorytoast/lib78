package com.hamilla.backtest;

/**
 * How many shares a trade uses. Either a fixed count, or a fixed dollar risk divided by the stop
 * distance, so a stopped trade loses about the same amount whatever the stop size and every cell
 * of a grid is compared at equal risk. A trade with no stop has no defined risk and uses the fixed count.
 *
 * @param fixedShares shares when there is no risk budget, or when the stop is "none"
 * @param riskDollars dollars to risk per trade; 0 means size by {@code fixedShares} only
 */
public record Sizing(int fixedShares, double riskDollars) {

    public static Sizing shares(int shares) {
        return new Sizing(shares, 0);
    }

    public static Sizing risk(double dollars, int fallbackShares) {
        return new Sizing(fallbackShares, dollars);
    }

    public Sizing {
        if (fixedShares < 1) {
            throw new IllegalArgumentException("shares must be at least 1, got " + fixedShares);
        }
        if (riskDollars < 0) {
            throw new IllegalArgumentException("risk must not be negative, got " + riskDollars);
        }
    }

    public boolean byRisk() {
        return riskDollars > 0;
    }

    /** Shares for a trade whose stop is {@code stop} dollars away; at least one. */
    public int sharesFor(double stop) {
        if (!byRisk() || stop >= BracketSimulator.NONE) {
            return fixedShares;
        }
        return Math.max(1, (int) Math.floor(riskDollars / stop + 1e-9));
    }
}
