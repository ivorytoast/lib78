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
    void capLimitsThePositionToTheDollarsItCosts() {
        Sizing capped = Sizing.risk(380, 2000).cappedAt(50_000);
        assertTrue(capped.capped());
        assertEquals(3166, capped.sharesFor(0.12));                 // uncapped count, used for headers without a price
        assertEquals(243, capped.sharesFor(0.12, 205.10));          // 50,000 / 205.10 = 243.8, rounded down
        assertEquals(2000, capped.sharesFor(0.19, 16.00));          // 50,000 / 16 = 3,125 would allow it: risk wins
        assertEquals(2000, capped.sharesFor(BracketSimulator.NONE, 16.00));
        assertEquals(100, capped.sharesFor(BracketSimulator.NONE, 500.0)); // fixed count is capped too
        assertEquals(1, capped.sharesFor(0.12, 90_000));            // never zero
        assertEquals(3166, capped.sharesFor(0.12, 0));              // no price, no cap
        assertEquals(3166, Sizing.risk(380, 2000).sharesFor(0.12, 205.10)); // no cap set
        assertEquals(243, Sizing.shares(2000).cappedAt(50_000).sharesFor(0.12, 205.10));
        assertFalse(capped.cappedAt(0).capped());
    }

    @Test
    void rejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> Sizing.shares(0));
        assertThrows(IllegalArgumentException.class, () -> new Sizing(100, -1));
        assertThrows(IllegalArgumentException.class, () -> Sizing.shares(100).cappedAt(-1));
    }
}
