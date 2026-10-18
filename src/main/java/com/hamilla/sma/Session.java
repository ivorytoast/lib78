package com.hamilla.sma;

import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The daily window of one-minute bars the averages run on, in Eastern time. Bars outside it do not
 * exist, so the first bar of a day follows the last bar of the previous day directly.
 *
 * <p>Two series can be built on a session. The chart series ({@link #closes}) has a bar only for a
 * minute that had a close-updating trade, which is how charting platforms draw it. The filled series
 * ({@link #fillCloses}) has a bar for every minute, repeating the previous close through empty
 * minutes, which is how Massive's minute indicators are computed.
 *
 * @param open  first minute of the session, inclusive
 * @param close first minute after the session, exclusive
 */
public record Session(LocalTime open, LocalTime close) {

    public static final long MINUTE_MS = 60_000L;
    /** Massive's full extended session, 04:00 to 20:00. */
    public static final Session EXTENDED = new Session(LocalTime.of(4, 0), LocalTime.of(20, 0));
    /** The regular session, 09:30 to 16:00. */
    public static final Session REGULAR = new Session(LocalTime.of(9, 30), LocalTime.of(16, 0));

    public Session {
        if (!open.isBefore(close)) {
            throw new IllegalArgumentException("session must open before it closes: " + open + "-" + close);
        }
    }

    /** Parses {@code HH:MM-HH:MM}, e.g. {@code 07:00-20:00}. */
    public static Session parse(String text) {
        String[] parts = text.trim().split("-");
        if (parts.length != 2) {
            throw new IllegalArgumentException("session must look like HH:MM-HH:MM, got '" + text + "'");
        }
        try {
            return new Session(LocalTime.parse(parts[0]), LocalTime.parse(parts[1]));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("session must look like HH:MM-HH:MM, got '" + text + "'", e);
        }
    }

    @Override
    public String toString() {
        return open + "-" + close;
    }

    /** True when a bar starting at {@code barStartMs} is inside the session. */
    public boolean contains(long barStartMs) {
        LocalTime t = Instant.ofEpochMilli(barStartMs).atZone(PriceEvent.EASTERN).toLocalTime();
        return !t.isBefore(open) && t.isBefore(close);
    }

    /** Start of the last bar of the session on the day of {@code barStartMs}. */
    public long lastBarOfDay(long barStartMs) {
        ZonedDateTime et = Instant.ofEpochMilli(barStartMs).atZone(PriceEvent.EASTERN);
        return et.with(close).minusMinutes(1).toInstant().toEpochMilli();
    }

    /** Closes of the in-session bars at or before {@code throughBarStartMs}, oldest first, empty minutes skipped. */
    public List<Double> closes(List<MinuteBar> bars, long throughBarStartMs) {
        List<Double> closes = new ArrayList<>();
        for (MinuteBar bar : bars) {
            if (bar.startMs() > throughBarStartMs) {
                break;
            }
            if (contains(bar.startMs())) {
                closes.add(bar.close());
            }
        }
        return closes;
    }

    /**
     * Closes for every session minute from the first in-session bar through {@code throughBarStartMs}
     * inclusive, oldest first, carrying the last close into minutes that have no bar.
     * {@code bars} must be sorted by start time.
     */
    public List<Double> fillCloses(List<MinuteBar> bars, long throughBarStartMs) {
        List<Double> closes = new ArrayList<>();
        long next = Long.MIN_VALUE;
        double last = Double.NaN;
        for (MinuteBar bar : bars) {
            if (bar.startMs() > throughBarStartMs) {
                break;
            }
            if (!contains(bar.startMs())) {
                continue;
            }
            if (next != Long.MIN_VALUE) {
                for (long m = next; m < bar.startMs(); m += MINUTE_MS) {
                    if (contains(m)) {
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
                if (contains(m)) {
                    closes.add(last);
                }
            }
        }
        return closes;
    }
}
