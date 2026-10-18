package com.hamilla.sma;

import com.hamilla.market.PriceEvent;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovingAveragesTest {

    /** Values returned by Massive's /v1/indicators/sma/SPY for 2026-10-16, window 20/50/200, series close. */
    private static final double MASSIVE_SMA20 = 771.1840000000002;
    private static final double MASSIVE_SMA50 = 768.5017999999998;
    private static final double MASSIVE_SMA200 = 725.0145999999993;
    private static final double OFFICIAL_CLOSE_2026_10_16 = 763.25;
    private static final double TOLERANCE = 1e-6;

    /** Closes from the fixture, oldest first. The last row is 2026-10-16. */
    private static List<Double> fixtureCloses() throws IOException {
        List<Double> closes = new ArrayList<>();
        try (var in = MovingAveragesTest.class.getResourceAsStream("/spy-daily.csv");
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            assertEquals("v,vw,o,c,h,l,t,n", header);
            String line;
            while ((line = reader.readLine()) != null) {
                closes.add(Double.parseDouble(line.split(",")[3]));
            }
        }
        assertTrue(closes.size() >= 200, "fixture needs at least 200 rows, has " + closes.size());
        assertEquals(OFFICIAL_CLOSE_2026_10_16, closes.get(closes.size() - 1));
        return closes;
    }

    @Test
    void matchesMassiveSmaEndpointAtOfficialClose() throws IOException {
        List<Double> closes = fixtureCloses();
        List<Double> prior = closes.subList(0, closes.size() - 1);

        MovingAverages engine = new MovingAverages("SPY", prior);
        SmaSnapshot snap = engine.update(new PriceEvent("SPY", 1792195200003234508L, OFFICIAL_CLOSE_2026_10_16, 1));

        assertEquals(MASSIVE_SMA20, snap.sma20(), TOLERANCE);
        assertEquals(MASSIVE_SMA50, snap.sma50(), TOLERANCE);
        assertEquals(MASSIVE_SMA200, snap.sma200(), TOLERANCE);
    }

    @Test
    void intradayValueMovesByPriceChangeOverWindow() throws IOException {
        List<Double> prior = fixtureCloses();
        prior = prior.subList(0, prior.size() - 1);
        MovingAverages engine = new MovingAverages("SPY", prior);

        SmaSnapshot a = engine.snapshotAt(0, 760.0);
        SmaSnapshot b = engine.snapshotAt(0, 761.0);
        assertEquals(1.0 / 20, b.sma20() - a.sma20(), 1e-9);
        assertEquals(1.0 / 50, b.sma50() - a.sma50(), 1e-9);
        assertEquals(1.0 / 200, b.sma200() - a.sma200(), 1e-9);
    }

    @Test
    void closeDayRollsTheWindowForward() throws IOException {
        List<Double> closes = fixtureCloses();
        List<Double> prior = closes.subList(0, closes.size() - 1);
        MovingAverages engine = new MovingAverages("SPY", prior);
        engine.closeDay(OFFICIAL_CLOSE_2026_10_16);

        // After rolling, the engine must behave exactly as one built from all closes including 10-16.
        MovingAverages rebuilt = new MovingAverages("SPY", closes);
        double price = 770.0;
        assertEquals(rebuilt.snapshotAt(0, price).sma20(), engine.snapshotAt(0, price).sma20(), 1e-9);
        assertEquals(rebuilt.snapshotAt(0, price).sma50(), engine.snapshotAt(0, price).sma50(), 1e-9);
        assertEquals(rebuilt.snapshotAt(0, price).sma200(), engine.snapshotAt(0, price).sma200(), 1e-9);
    }

    @Test
    void windowsWithoutEnoughHistoryReportNaN() {
        List<Double> only30 = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            only30.add(100.0 + i);
        }
        MovingAverages engine = new MovingAverages("X", only30);
        SmaSnapshot snap = engine.snapshotAt(0, 130.0);

        assertTrue(engine.ready(20));
        assertFalse(engine.ready(50));
        assertFalse(engine.ready(200));
        assertFalse(Double.isNaN(snap.sma20()));
        assertTrue(Double.isNaN(snap.sma50()));
        assertTrue(Double.isNaN(snap.sma200()));
        // 20 day: last 19 of 111..129 plus 130 -> mean of 111..130 = 120.5
        assertEquals(120.5, snap.sma20(), 1e-9);
    }

    @Test
    void rejectsEventsForAnotherSymbol() {
        MovingAverages engine = new MovingAverages("SPY", List.of());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> engine.update(new PriceEvent("QQQ", 0, 1.0, 1)));
    }
}
