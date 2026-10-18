package com.hamilla.market;

/** One completed one-minute bar. {@code startMs} is the bar's start in epoch milliseconds, as Massive reports it. */
public record MinuteBar(long startMs, double open, double high, double low, double close, double volume) {
}
