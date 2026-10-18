package com.hamilla.backtest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SizingTest {

    @Test
    void fixedSharesIgnoreTheStop() {
        Sizing s = Sizing.shares(2000);
        assertFalse(s.byRisk());
        assertEquals(2000, s.sharesFor(0.05));
        assertEquals(2000, s.sharesFor(BracketSimulator.NONE));
    }

    @Test
    void riskSizingDividesTheBudgetByTheStop() {
        Sizing s = Sizing.risk(380, 2000);
        assertTrue(s.byRisk());
        assertEquals(7600, s.sharesFor(0.05));
        assertEquals(2000, s.sharesFor(0.19));
        assertEquals(115, s.sharesFor(3.30));        // 380 / 3.30 = 115.15, rounded down
        assertEquals(1, Sizing.risk(1, 2000).sharesFor(50));   // never zero
        assertEquals(2000, s.sharesFor(BracketSimulator.NONE)); // no stop: no defined risk, fixed size
        assertEquals(12666, Sizing.risk(380, 1).sharesFor(0.03));
    }

    @Test
    void rejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> Sizing.shares(0));
        assertThrows(IllegalArgumentException.class, () -> new Sizing(100, -1));
    }
}
