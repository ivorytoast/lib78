package com.hamilla.sma;

/**
 * The three moving averages as they stood at the moment of one price event.
 * A value is NaN when there was not enough history to compute it.
 */
public record SmaSnapshot(String symbol, long sipTimestampNanos, double price,
                          double sma20, double sma50, double sma200) {
}
