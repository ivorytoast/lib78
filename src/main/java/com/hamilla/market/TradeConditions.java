package com.hamilla.market;

import java.util.List;
import java.util.Set;

/**
 * Which trades count toward a bar's close. Massive builds its aggregates from the consolidated
 * tape's update rules: a trade carrying a condition whose "updates open/close" flag is false
 * (odd lots, average price trades, derivatively priced, prior reference price, and so on)
 * leaves the close alone. The one exception is the extended-hours markers (Form T), which are
 * flagged non-updating for the official daily close but do form the pre- and post-market
 * minute bars. This matches Massive's minute bars and its minute SMA endpoint exactly.
 */
public final class TradeConditions {

    /** Condition ids whose consolidated "updates open/close" rule is false, per Massive's /v3/reference/conditions. */
    static final Set<Integer> DOES_NOT_UPDATE_CLOSE = Set.of(
            2,   // Average Price Trade
            5,   // Bunched Sold Trade
            7,   // Cash Sale
            10,  // Derivatively Priced
            15,  // Market Center Official Close
            16,  // Market Center Official Open
            20,  // Next Day
            21,  // Price Variation Trade
            22,  // Prior Reference Price
            29,  // Seller
            32,  // Sold (Out Of Sequence)
            33,  // Sold (Out of Sequence) and Stopped Stock
            37,  // Odd Lot Trade
            52,  // Contingent Trade
            53   // Qualified Contingent Trade
    );
    // Deliberately absent: 12 (Form T / Extended Hours) and 13 (Extended Hours, Sold Out Of Sequence)
    // are flagged non-updating by the reference data but do set extended-hours bar closes.

    private TradeConditions() {
    }

    public static boolean updatesClose(List<Integer> conditions) {
        for (int c : conditions) {
            if (DOES_NOT_UPDATE_CLOSE.contains(c)) {
                return false;
            }
        }
        return true;
    }
}
