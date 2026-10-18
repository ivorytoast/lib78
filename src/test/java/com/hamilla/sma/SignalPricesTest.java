package com.hamilla.sma;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignalPricesTest {

    @Test
    void rivnGetsThePricePlusEightCents() {
        assertEquals(Optional.of(0.08), SignalPrices.offsetFor("RIVN"));
        assertEquals("12.995 13.075", SignalPrices.describe("RIVN", 12.995));
        assertEquals("12.69 12.77", SignalPrices.describe("RIVN", 12.69));
        assertEquals("12.7861 12.8661", SignalPrices.describe("RIVN", 12.7861));
    }

    @Test
    void otherSymbolsGetJustThePrice() {
        assertEquals(Optional.empty(), SignalPrices.offsetFor("SPY"));
        assertEquals("763.25", SignalPrices.describe("SPY", 763.25));
    }

    @Test
    void formatShowsTradedPrecisionWithoutBinaryNoise() {
        assertEquals("13.00", SignalPrices.format(13.0));
        assertEquals("12.50", SignalPrices.format(12.5));
        assertEquals("12.995", SignalPrices.format(12.995));
        assertEquals("12.6895", SignalPrices.format(12.6895));
        assertEquals("13.075", SignalPrices.format(12.995 + 0.08));   // 13.075000000000001 in binary
        assertEquals("12.4578", SignalPrices.format(12.45779999));
    }
}
