package com.hamilla.sma;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrendTest {

    private static SmaSnapshot snap(double price, double a20, double a50, double a200) {
        return new SmaSnapshot("X", 0, price, a20, a50, a200);
    }

    @Test
    void upNeedsAscendingAveragesAndPriceAboveThe20() {
        assertEquals(Trend.UP, Trend.of(snap(10.10, 10.00, 9.90, 9.50)));
        assertEquals(Trend.NONE, Trend.of(snap(10.00, 10.00, 9.90, 9.50)));   // price equals the 20
        assertEquals(Trend.NONE, Trend.of(snap(9.95, 10.00, 9.90, 9.50)));    // price below the 20
        assertEquals(Trend.NONE, Trend.of(snap(10.10, 10.00, 9.50, 9.90)));   // 200 above 50
        assertEquals(Trend.NONE, Trend.of(snap(10.10, 10.00, 10.00, 9.50)));  // 50 equals 20
    }

    @Test
    void downNeedsDescendingAveragesAndPriceBelowThe20() {
        assertEquals(Trend.DOWN, Trend.of(snap(9.80, 9.90, 10.00, 10.50)));
        assertEquals(Trend.NONE, Trend.of(snap(9.90, 9.90, 10.00, 10.50)));   // price equals the 20
        assertEquals(Trend.NONE, Trend.of(snap(9.95, 9.90, 10.00, 10.50)));   // price above the 20
        assertEquals(Trend.NONE, Trend.of(snap(9.80, 9.90, 10.50, 10.00)));   // 200 below 50
        assertEquals(Trend.NONE, Trend.of(snap(9.80, 9.90, 9.90, 10.50)));    // 50 equals 20
    }

    @Test
    void the20And50MustBeAtLeastThreeCentsApart() {
        assertEquals(Trend.UP, Trend.of(snap(10.10, 10.00, 9.97, 9.50)));      // exactly three cents
        assertEquals(Trend.NONE, Trend.of(snap(10.10, 10.00, 9.971, 9.50)));   // 2.9 cents
        assertEquals(Trend.NONE, Trend.of(snap(10.10, 10.00, 9.99, 9.50)));
        assertEquals(Trend.DOWN, Trend.of(snap(9.80, 9.90, 9.93, 10.50)));     // exactly three cents
        assertEquals(Trend.NONE, Trend.of(snap(9.80, 9.90, 9.929, 10.50)));    // 2.9 cents
        // Floating point: 10.00 and 9.97 differ by a hair under 0.03 in binary; the gap still counts.
        assertEquals(Trend.UP, Trend.of(snap(10.10, 10.00, 10.00 - 0.03, 9.50)));
    }

    @Test
    void missingAveragesAreNone() {
        assertEquals(Trend.NONE, Trend.of(snap(10.10, 10.00, 9.90, Double.NaN)));
        assertEquals(Trend.NONE, Trend.of(snap(10.10, Double.NaN, Double.NaN, Double.NaN)));
    }

    @Test
    void labelsAreLowerCase() {
        assertEquals("up", Trend.UP.label());
        assertEquals("down", Trend.DOWN.label());
        assertEquals("none", Trend.NONE.label());
    }
}
