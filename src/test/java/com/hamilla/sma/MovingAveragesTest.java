package com.hamilla.sma;

import com.hamilla.market.PriceEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovingAveragesTest {

    private static final double TOLERANCE = 1e-9;

    @Test
    void matchesMassiveMinuteSmaOnFilledSeries() {
        List<Double> prior = Session.EXTENDED.fillCloses(Fixture.rivnBarsBefore1211(), Fixture.BAR_1211 - Session.MINUTE_MS);
        assertTrue(prior.size() >= 199, "need 199 filled minutes, got " + prior.size());

        MovingAverages engine = new MovingAverages("RIVN", prior);
        SmaSnapshot snap = engine.update(new PriceEvent("RIVN", 1792167116830639117L, Fixture.CLOSE_1211, 400));

        assertEquals(Fixture.MASSIVE_SMA20_AT_1211, snap.sma20(), TOLERANCE);
        assertEquals(Fixture.MASSIVE_SMA50_AT_1211, snap.sma50(), TOLERANCE);
        assertEquals(Fixture.MASSIVE_SMA200_AT_1211, snap.sma200(), TOLERANCE);
    }

    @Test
    void chartSeriesSkipsEmptyMinutesSoThe200ReachesFurtherBack() {
        // The chart series uses the 199 real bars, which reach back to 08:02 instead of 08:52.
        List<Double> chart = Session.EXTENDED.closes(Fixture.rivnBarsBefore1211(), Fixture.BAR_1211 - Session.MINUTE_MS);
        assertEquals(199, chart.size());
        MovingAverages engine = new MovingAverages("RIVN", chart);
        SmaSnapshot snap = engine.snapshotAt(0, Fixture.CLOSE_1211);
        assertEquals(Fixture.MASSIVE_SMA20_AT_1211, snap.sma20(), TOLERANCE);   // no empty minutes in the last 20
        assertEquals(Fixture.MASSIVE_SMA50_AT_1211, snap.sma50(), TOLERANCE);   // nor the last 50
        assertEquals(Fixture.CHART_SMA200_AT_1211, snap.sma200(), TOLERANCE);
        assertTrue(Math.abs(snap.sma200() - Fixture.MASSIVE_SMA200_AT_1211) > 1e-4);
    }

    @Test
    void intradayValueMovesByPriceChangeOverWindow() {
        List<Double> prior = Session.EXTENDED.fillCloses(Fixture.rivnBarsBefore1211(), Fixture.BAR_1211 - Session.MINUTE_MS);
        MovingAverages engine = new MovingAverages("RIVN", prior);

        SmaSnapshot a = engine.snapshotAt(0, 12.60);
        SmaSnapshot b = engine.snapshotAt(0, 12.61);
        assertEquals(0.01 / 20, b.sma20() - a.sma20(), TOLERANCE);
        assertEquals(0.01 / 50, b.sma50() - a.sma50(), TOLERANCE);
        assertEquals(0.01 / 200, b.sma200() - a.sma200(), TOLERANCE);
    }

    @Test
    void closeBarRollsTheWindowForward() {
        List<Double> prior = Session.EXTENDED.fillCloses(Fixture.rivnBarsBefore1211(), Fixture.BAR_1211 - Session.MINUTE_MS);
        MovingAverages engine = new MovingAverages("RIVN", prior);
        engine.closeBar(Fixture.CLOSE_1211);

        List<Double> all = new ArrayList<>(prior);
        all.add(Fixture.CLOSE_1211);
        MovingAverages rebuilt = new MovingAverages("RIVN", all);
        double price = 12.70;
        assertEquals(rebuilt.snapshotAt(0, price).sma20(), engine.snapshotAt(0, price).sma20(), TOLERANCE);
        assertEquals(rebuilt.snapshotAt(0, price).sma50(), engine.snapshotAt(0, price).sma50(), TOLERANCE);
        assertEquals(rebuilt.snapshotAt(0, price).sma200(), engine.snapshotAt(0, price).sma200(), TOLERANCE);
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
        assertTrue(Double.isNaN(snap.sma50()));
        assertTrue(Double.isNaN(snap.sma200()));
        // 20 bar: last 19 of 111..129 plus 130 -> mean of 111..130 = 120.5
        assertEquals(120.5, snap.sma20(), TOLERANCE);
    }

    @Test
    void rejectsEventsForAnotherSymbol() {
        MovingAverages engine = new MovingAverages("SPY", List.of());
        assertThrows(IllegalArgumentException.class, () -> engine.update(new PriceEvent("QQQ", 0, 1.0, 1)));
    }
}
