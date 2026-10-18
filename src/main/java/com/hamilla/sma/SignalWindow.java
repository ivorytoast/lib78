package com.hamilla.sma;

import com.hamilla.market.PriceEvent;

import java.time.Instant;
import java.time.LocalTime;

/**
 * The minutes of the day whose closes may produce a signal. Earlier minutes still feed the averages
 * but are never acted on: the first hour combines averages still dominated by pre-market bars with
 * the day's widest ranges.
 */
public final class SignalWindow {

    /** First minute whose close is acted on. */
    public static final LocalTime FIRST = LocalTime.of(10, 30);
    /** Last minute whose close is acted on. */
    public static final LocalTime LAST = LocalTime.of(15, 59);

    private SignalWindow() {
    }

    public static boolean contains(long barStartMs) {
        LocalTime t = Instant.ofEpochMilli(barStartMs).atZone(PriceEvent.EASTERN).toLocalTime();
        return !t.isBefore(FIRST) && !t.isAfter(LAST);
    }
}
