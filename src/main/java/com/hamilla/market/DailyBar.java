package com.hamilla.market;

import java.time.LocalDate;

/** One completed trading day for a symbol. */
public record DailyBar(LocalDate date, double open, double high, double low, double close, double volume) {
}
