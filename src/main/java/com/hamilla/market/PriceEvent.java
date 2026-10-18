package com.hamilla.market;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * A single observed price for a symbol. In replay this is one historical trade;
 * in live mode it is one trade from the stream. Both feed the SMA engine identically.
 *
 * @param symbol        ticker, e.g. "SPY"
 * @param sipTimestampNanos nanoseconds since the Unix epoch, as reported by the SIP
 * @param price         trade price in dollars per share
 * @param size          trade size in shares
 */
public record PriceEvent(String symbol, long sipTimestampNanos, double price, double size) {

    public static final ZoneId EASTERN = ZoneId.of("America/New_York");

    public Instant instant() {
        return Instant.ofEpochSecond(
                Math.floorDiv(sipTimestampNanos, 1_000_000_000L),
                Math.floorMod(sipTimestampNanos, 1_000_000_000L));
    }

    public ZonedDateTime easternTime() {
        return instant().atZone(EASTERN);
    }
}
