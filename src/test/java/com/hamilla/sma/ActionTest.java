package com.hamilla.sma;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionTest {

    private static SmaSnapshot snap(double price, double a20, double a50, double a200) {
        return new SmaSnapshot("X", 0, price, a20, a50, a200);
    }

    @Test
    void buyLongNeedsTheUpZoneAndPriceWithinTwoCentsAboveThe20() {
        assertEquals(Optional.of(Action.BUY_LONG), Action.of(snap(10.01, 10.00, 9.90, 9.50)));
        assertEquals(Optional.of(Action.BUY_LONG), Action.of(snap(10.02, 10.00, 9.90, 9.50)));   // exactly two cents
        assertEquals(Optional.empty(), Action.of(snap(10.021, 10.00, 9.90, 9.50)));              // just over
        assertEquals(Optional.empty(), Action.of(snap(10.00, 10.00, 9.90, 9.50)));               // on the 20: not up
        assertEquals(Optional.empty(), Action.of(snap(10.01, 10.00, 9.50, 9.90)));               // 200 above 50: not up
    }

    @Test
    void shortSellNeedsTheDownZoneAndPriceWithinTwoCentsBelowThe20() {
        assertEquals(Optional.of(Action.SHORT_SELL), Action.of(snap(9.99, 10.00, 10.10, 10.50)));
        assertEquals(Optional.of(Action.SHORT_SELL), Action.of(snap(9.98, 10.00, 10.10, 10.50)));  // exactly two cents
        assertEquals(Optional.empty(), Action.of(snap(9.979, 10.00, 10.10, 10.50)));              // just under
        assertEquals(Optional.empty(), Action.of(snap(9.99, 10.00, 10.50, 10.10)));               // 200 below 50: not down
        assertEquals(Optional.empty(), Action.of(snap(10.00, 10.00, 10.10, 10.50)));              // on the 20: not down
    }

    @Test
    void floatingPointAveragesDoNotLoseAnExactTwoCentCase() {
        // 10.02 - (10.0 + 0.0000000001) is a hair over 0.02 in binary; it still counts.
        assertEquals(Optional.of(Action.BUY_LONG), Action.of(snap(10.02, 10.0 + 1e-10, 9.90, 9.50)));
    }

    @Test
    void labels() {
        assertEquals("BUY LONG", Action.BUY_LONG.label());
        assertEquals("SHORT SELL", Action.SHORT_SELL.label());
    }
}
