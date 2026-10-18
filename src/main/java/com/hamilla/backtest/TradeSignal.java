package com.hamilla.backtest;

import com.hamilla.market.PriceEvent;
import com.hamilla.sma.Action;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** One entry signal the replay would have printed: the bar it closed on, its kind, and the entry price. */
public record TradeSignal(long barStartMs, Action action, double price) {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    public LocalDate date() {
        return Instant.ofEpochMilli(barStartMs).atZone(PriceEvent.EASTERN).toLocalDate();
    }

    public String time() {
        return Instant.ofEpochMilli(barStartMs).atZone(PriceEvent.EASTERN).format(HHMM);
    }

    public boolean isLong() {
        return action == Action.BUY_LONG;
    }

    @Override
    public String toString() {
        return date() + " " + time() + " " + action.label() + " " + price;
    }
}
