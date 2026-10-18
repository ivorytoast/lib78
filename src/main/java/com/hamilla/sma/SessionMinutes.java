package com.hamilla.sma;

import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The one-minute bar series Massive's minute indicators are computed on: one bar for every minute
 * of the extended session (04:00 to 20:00 Eastern), with a minute that had no close-updating trade
 * repeating the previous bar's close. Nothing exists between 20:00 and 04:00, so the first bar of a
 * day follows the last bar of the previous day directly.
 */
public final class SessionMinutes {

    public static final long MINUTE_MS = 60_000L;
    static final LocalTime SESSION_OPEN = LocalTime.of(4, 0);
    static final LocalTime SESSION_CLOSE = LocalTime.of(20, 0);

    private SessionMinutes() {
    }

    /** True when a bar starting at {@code barStartMs} is inside the 04:00 to 20:00 Eastern session. */
    public static boolean inSession(long barStartMs) {
        LocalTime t = Instant.ofEpochMilli(barStartMs).atZone(PriceEvent.EASTERN).toLocalTime();
        return !t.isBefore(SESSION_OPEN) && t.isBefore(SESSION_CLOSE);
    }

    /**
     * Closes for every session minute from the first bar through {@code throughBarStartMs} inclusive,
     * oldest first, carrying the last close into minutes that have no bar. {@code bars} must be sorted
     * by start time; bars after {@code throughBarStartMs} are ignored.
     */
    public static List<Double> fillCloses(List<MinuteBar> bars, long throughBarStartMs) {
        List<Double> closes = new ArrayList<>();
        long next = Long.MIN_VALUE;
        double last = Double.NaN;
        for (MinuteBar bar : bars) {
            if (bar.startMs() > throughBarStartMs) {
                break;
            }
            if (next != Long.MIN_VALUE) {
                for (long m = next; m < bar.startMs(); m += MINUTE_MS) {
                    if (inSession(m)) {
                        closes.add(last);
                    }
                }
            }
            closes.add(bar.close());
            last = bar.close();
            next = bar.startMs() + MINUTE_MS;
        }
        if (next != Long.MIN_VALUE) {
            for (long m = next; m <= throughBarStartMs; m += MINUTE_MS) {
                if (inSession(m)) {
                    closes.add(last);
                }
            }
        }
        return closes;
    }

    /** Start of the last bar of the session that contains {@code barStartMs}, i.e. the 19:59 bar of that day. */
    public static long lastBarOfSession(long barStartMs) {
        var et = Instant.ofEpochMilli(barStartMs).atZone(PriceEvent.EASTERN);
        return et.with(SESSION_CLOSE).minusMinutes(1).toInstant().toEpochMilli();
    }
}
