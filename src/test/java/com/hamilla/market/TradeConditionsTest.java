package com.hamilla.market;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeConditionsTest {

    @Test
    void plainAndSweepTradesUpdateTheClose() {
        assertTrue(TradeConditions.updatesClose(List.of()));
        assertTrue(TradeConditions.updatesClose(List.of(14, 41)));   // intermarket sweep, trade-thru exempt
        assertTrue(TradeConditions.updatesClose(List.of(12)));       // Form T extended hours forms pre-market bars
        assertTrue(TradeConditions.updatesClose(List.of(12, 41)));
    }

    @Test
    void oddLotsAndOtherNonUpdatingPrintsDoNot() {
        assertFalse(TradeConditions.updatesClose(List.of(37)));          // odd lot
        assertFalse(TradeConditions.updatesClose(List.of(12, 37)));      // pre-market odd lot
        assertFalse(TradeConditions.updatesClose(List.of(10, 37, 41)));  // derivatively priced odd lot
        assertFalse(TradeConditions.updatesClose(List.of(2, 10, 41)));   // average price
        assertFalse(TradeConditions.updatesClose(List.of(15)));          // official close message
    }

    @Test
    void minuteBarStartFloorsToTheMinute() {
        PriceEvent e = new PriceEvent("RIVN", 1792167119603049308L, 12.685, 37, List.of(37));
        assertEquals(1792167060000L, e.minuteBarStartMs());
        assertFalse(e.updatesClose());
        assertEquals("12:11:59.603", e.easternTime().toLocalTime().toString().substring(0, 12));
    }
}
