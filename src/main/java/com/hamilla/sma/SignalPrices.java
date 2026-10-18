package com.hamilla.sma;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * The prices printed with a signal: the current price, and for symbols with a configured offset,
 * the current price plus that offset. Offsets are in dollars and apply to both signal kinds.
 */
public final class SignalPrices {

    /** Per-symbol offsets added to the current price on a signal line. */
    private static final Map<String, Double> OFFSETS = Map.of(
            "RIVN", 0.08
    );

    private SignalPrices() {
    }

    public static Optional<Double> offsetFor(String symbol) {
        return Optional.ofNullable(OFFSETS.get(symbol));
    }

    /** "12.995 13.075" for RIVN at 12.995; just "12.995" for a symbol with no offset. */
    public static String describe(String symbol, double price) {
        Optional<Double> offset = offsetFor(symbol);
        if (offset.isEmpty()) {
            return format(price);
        }
        return format(price) + " " + format(price + offset.get());
    }

    /** Prices as traded: up to four decimals, never fewer than two, no binary noise. */
    public static String format(double price) {
        BigDecimal d = BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        if (d.scale() < 2) {
            d = d.setScale(2, RoundingMode.UNNECESSARY);
        }
        return d.toPlainString();
    }
}
