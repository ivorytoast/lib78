package com.hamilla.sma;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThresholdsTest {

    private static SmaSnapshot snap(double price, double a20, double a50, double a200) {
        return new SmaSnapshot("X", 0, price, a20, a50, a200);
    }

    @Test
    void rivnsFiveCentRangeGivesTheOriginalThresholds() {
        assertEquals(Thresholds.DEFAULT, Thresholds.fromRange(0.05));
        assertTrue(Band.AUTO.thresholdsFor(0.05).isDefault());
        assertTrue(Band.DEFAULT.thresholdsFor(0.18).isDefault());
    }

    @Test
    void aWiderRangeScalesAllThreeInProportion() {
        Thresholds t = Thresholds.fromRange(0.18);
        assertEquals(0.072, t.band(), 1e-9);
        assertEquals(0.108, t.gap2050(), 1e-9);
        assertEquals(0.216, t.gap20200(), 1e-9);
        assertEquals(Thresholds.DEFAULT, Thresholds.fromRange(Double.NaN));
        assertEquals(0.01, Thresholds.fromRange(0.001).band(), 1e-9);
    }

    @Test
    void theWiderBandAdmitsACloseTheDefaultRejects() {
        // Price 15 cents over the 20, the 50 thirty cents and the 200 sixty cents below it: STLD on a normal minute.
        SmaSnapshot s = snap(243.23, 243.08, 242.78, 242.48);
        assertEquals(Optional.empty(), Action.of(s));
        assertEquals(Trend.UP, Trend.of(s));
        assertEquals(Optional.empty(), Action.of(s, Thresholds.fromRange(0.18)));          // 15c is outside a 7.2c band
        assertEquals(Optional.of(Action.BUY_LONG), Action.of(s, Thresholds.scaledTo(0.15)));
        // With gaps scaled up, averages 5 cents apart no longer make a zone.
        assertEquals(Trend.UP, Trend.of(snap(10.10, 10.00, 9.95, 9.90)));
        assertEquals(Trend.NONE, Trend.of(snap(10.10, 10.00, 9.95, 9.90), Thresholds.fromRange(0.18)));
    }

    @Test
    void bandParses() {
        assertEquals(Band.AUTO, Band.parse("auto"));
        assertEquals(new Band(false, 0.07), Band.parse("0.07"));
        assertThrows(IllegalArgumentException.class, () -> Band.parse("wide"));
        assertThrows(IllegalArgumentException.class, () -> Band.parse("0"));
        assertThrows(IllegalArgumentException.class, () -> new Thresholds(0.02, 0, 0.06));
    }
}
