package com.hamilla.sma;

import java.util.Map;

/**
 * Per-symbol trading parameters: how many shares a signal trades, and the offset printed next to
 * the price on a signal line. Symbols not listed trade {@link #DEFAULT_SHARES} and print no offset.
 */
public final class SymbolSettings {

    public static final int DEFAULT_SHARES = 100;

    private static final Map<String, Integer> SHARES = Map.of(
            "RIVN", 2000,
            "IWM", 160
    );

    private SymbolSettings() {
    }

    public static int shares(String symbol) {
        return SHARES.getOrDefault(symbol, DEFAULT_SHARES);
    }
}
