package com.hamilla.sma;

import com.hamilla.market.PriceEvent;

import java.time.Instant;
import java.time.LocalTime;

/**
 * The minutes of the day whose closes may produce a signal. Minutes outside it still feed the averages
 * but are never acted on: the first hour combines averages still dominated by pre-market bars with
 * the day's widest ranges, and in the last hours the range narrows so the zone is met on wiggles with
 * no follow-through. Positions opened inside the window are still held until 15:55.
 */
public final class SignalWindow {

    /** First minute whose close is acted on. */
    public static final LocalTime FIRST = LocalTime.of(10, 30);
    /** Last minute whose close is acted on: the 14:29 bar, so no trade opens from 14:30 on. */
    public static final LocalTime LAST = LocalTime.of(14, 29);

    private SignalWindow() {
    }

    public static boolean contains(long barStartMs) {
        LocalTime t = Instant.ofEpochMilli(barStartMs).atZone(PriceEvent.EASTERN).toLocalTime();
        return !t.isBefore(FIRST) && !t.isAfter(LAST);
    }
}
