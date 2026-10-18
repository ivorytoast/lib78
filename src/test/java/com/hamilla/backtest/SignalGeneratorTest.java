package com.hamilla.backtest;

import com.hamilla.sma.Action;
import com.hamilla.sma.Session;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalGeneratorTest {

    private static final LocalDate OCT16 = LocalDate.of(2026, 10, 16);

    /** What the trade-by-trade replay printed for RIVN on 2026-10-16 (time and price), all SHORT SELL. */
    private static final String[][] REPLAY = {
            {"10:31", "12.93"}, {"11:12", "12.82"}, {"11:19", "12.805"}, {"11:28", "12.7861"}, {"11:35", "12.785"},
            {"11:41", "12.775"}, {"12:04", "12.7178"}, {"12:11", "12.69"}, {"12:17", "12.69"}, {"12:23", "12.685"},
            {"13:20", "12.5401"}, {"14:15", "12.51"}, {"14:31", "12.46"}, {"14:37", "12.46"}, {"14:43", "12.4578"},
            {"15:57", "12.375"}};

    @Test
    void reproducesTheReplaysSignalsFromMinuteBars() {
        List<TradeSignal> signals = new SignalGenerator(Session.EXTENDED, 6).generate("RIVN", Bars.rivnOct15and16(), Set.of(OCT16));

        assertEquals(REPLAY.length, signals.size(), signals.toString());
        for (int i = 0; i < REPLAY.length; i++) {
            TradeSignal s = signals.get(i);
            assertEquals(REPLAY[i][0], s.time(), s.toString());
            assertEquals(Double.parseDouble(REPLAY[i][1]), s.price(), 1e-9, s.toString());
            assertEquals(Action.SHORT_SELL, s.action());
            assertEquals(OCT16, s.date());
        }
    }

    @Test
    void withoutCooldownEveryQualifyingMinuteIsASignal() {
        List<TradeSignal> signals = new SignalGenerator(Session.EXTENDED, 0).generate("RIVN", Bars.rivnOct15and16(), Set.of(OCT16));
        assertEquals(40, signals.size());                     // the 45 qualifying minutes less the five before 10:30
        assertEquals("10:31", signals.get(0).time());
        assertEquals("11:12", signals.get(1).time());
    }

    @Test
    void onlyRequestedDaysProduceSignals() {
        List<TradeSignal> signals = new SignalGenerator(Session.EXTENDED, 6).generate("RIVN", Bars.rivnOct15and16(), Set.of(LocalDate.of(2026, 10, 15)));
        assertTrue(signals.isEmpty()); // the fixture's 10/15 bars are all after 16:00
    }
}
