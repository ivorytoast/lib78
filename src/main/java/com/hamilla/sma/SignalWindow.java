package com.hamilla.sma;

import com.hamilla.market.PriceEvent;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * The minutes of the day whose closes may produce a signal. Minutes outside it still feed the averages
 * but are never acted on: the first hour combines averages still dominated by pre-market bars with
 * the day's widest ranges, and in the last hours the range narrows so the zone is met on wiggles with
 * no follow-through. Positions opened inside the window are still held until 15:55.
 *
 * @param open  first minute whose close is acted on
 * @param close first minute that is not acted on, so {@code 10:30-14:30} acts on closes through 14:29
 */
public record SignalWindow(LocalTime open, LocalTime close) {

    /** The default: act on closes from 10:30 through 14:29. */
    public static final SignalWindow DEFAULT = new SignalWindow(LocalTime.of(10, 30), LocalTime.of(14, 30));

    public SignalWindow {
        if (!open.isBefore(close)) {
            throw new IllegalArgumentException("window must open before it closes: " + open + "-" + close);
        }
    }

    /** Parses {@code HH:MM-HH:MM}, e.g. {@code 10:30-12:30}. */
    public static SignalWindow parse(String text) {
        String[] parts = text.trim().split("-");
        if (parts.length != 2) {
            throw new IllegalArgumentException("window must look like HH:MM-HH:MM, got '" + text + "'");
        }
        try {
            return new SignalWindow(LocalTime.parse(parts[0]), LocalTime.parse(parts[1]));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("window must look like HH:MM-HH:MM, got '" + text + "'", e);
        }
    }

    /** Last minute whose close is acted on. */
    public LocalTime last() {
        return close.minusMinutes(1);
    }

    public boolean contains(long barStartMs) {
        LocalTime t = Instant.ofEpochMilli(barStartMs).atZone(PriceEvent.EASTERN).toLocalTime();
        return !t.isBefore(open) && t.isBefore(close);
    }

    @Override
    public String toString() {
        return open + "-" + close;
    }
}
