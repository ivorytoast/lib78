package com.hamilla.backtest;

/**
 * How many shares a trade uses. Either a fixed count, or a fixed dollar risk divided by the stop
 * distance, so a stopped trade loses about the same amount whatever the stop size and every cell
 * of a grid is compared at equal risk. A trade with no stop has no defined risk and uses the fixed count.
 * On top of either, an optional cap on the dollars in any one position: a trade never buys more
 * shares than the cap divided by its entry price, even if that leaves it risking less than the budget.
 *
 * @param fixedShares shares when there is no risk budget, or when the stop is "none"
 * @param riskDollars dollars to risk per trade; 0 means size by {@code fixedShares} only
 * @param capDollars  most dollars in one position; 0 means no cap
 */
public record Sizing(int fixedShares, double riskDollars, double capDollars) {

    public Sizing(int fixedShares, double riskDollars) {
        this(fixedShares, riskDollars, 0);
    }

    public static Sizing shares(int shares) {
        return new Sizing(shares, 0, 0);
    }

    public static Sizing risk(double dollars, int fallbackShares) {
        return new Sizing(fallbackShares, dollars, 0);
    }

    public Sizing {
        if (fixedShares < 1) {
            throw new IllegalArgumentException("shares must be at least 1, got " + fixedShares);
        }
        if (riskDollars < 0) {
            throw new IllegalArgumentException("risk must not be negative, got " + riskDollars);
        }
        if (capDollars < 0) {
            throw new IllegalArgumentException("cap must not be negative, got " + capDollars);
        }
    }

    /** The same sizing with a position cap in dollars; 0 removes it. */
    public Sizing cappedAt(double dollars) {
        return new Sizing(fixedShares, riskDollars, dollars);
    }

    public boolean byRisk() {
        return riskDollars > 0;
    }

    public boolean capped() {
        return capDollars > 0;
    }

    /** Shares for a trade whose stop is {@code stop} dollars away, before any position cap; at least one. */
    public int sharesFor(double stop) {
        if (!byRisk() || stop >= BracketSimulator.NONE) {
            return fixedShares;
        }
        return Math.max(1, (int) Math.floor(riskDollars / stop + 1e-9));
    }

    /**
     * Shares for a trade entered at {@code price} with a stop {@code stop} dollars away: the uncapped count,
     * reduced so the position costs no more than the cap. A missing or non-positive price leaves the cap unapplied.
     */
    public int sharesFor(double stop, double price) {
        int shares = sharesFor(stop);
        if (!capped() || !(price > 0)) {
            return shares;
        }
        return Math.max(1, Math.min(shares, (int) Math.floor(capDollars / price + 1e-9)));
    }
}
