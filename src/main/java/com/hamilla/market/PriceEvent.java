package com.hamilla.market;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * A single trade for a symbol. In replay this is one historical trade; in live mode it is one
 * trade from the stream. Both feed the SMA engine identically.
 *
 * @param symbol            ticker, e.g. "SPY"
 * @param sipTimestampNanos nanoseconds since the Unix epoch, as reported by the SIP
 * @param price             trade price in dollars per share
 * @param size              trade size in shares
 * @param conditions        Massive condition codes attached to the trade (empty for a plain trade)
 */
public record PriceEvent(String symbol, long sipTimestampNanos, double price, double size, List<Integer> conditions) {

    public static final ZoneId EASTERN = ZoneId.of("America/New_York");
    private static final long NANOS_PER_MINUTE = 60_000_000_000L;

    public PriceEvent {
        conditions = List.copyOf(conditions);
    }

    /** A plain trade with no condition codes. */
    public PriceEvent(String symbol, long sipTimestampNanos, double price, double size) {
        this(symbol, sipTimestampNanos, price, size, List.of());
    }

    /** True when this trade moves the bar's close (and so the moving averages), per {@link TradeConditions}. */
    public boolean updatesClose() {
        return TradeConditions.updatesClose(conditions);
    }

    /** Start of the one-minute bar this trade belongs to, in epoch milliseconds, matching Massive's bar timestamps. */
    public long minuteBarStartMs() {
        return Math.floorDiv(sipTimestampNanos, NANOS_PER_MINUTE) * 60_000L;
    }

    public Instant instant() {
        return Instant.ofEpochSecond(
                Math.floorDiv(sipTimestampNanos, 1_000_000_000L),
                Math.floorMod(sipTimestampNanos, 1_000_000_000L));
    }

    public ZonedDateTime easternTime() {
        return instant().atZone(EASTERN);
    }
}
