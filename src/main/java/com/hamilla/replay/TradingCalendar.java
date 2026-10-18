package com.hamilla.replay;

import com.hamilla.market.DailyBar;
import com.hamilla.massive.MassiveClient;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** Answers "which day was the most recent trading day" by asking Massive which days have bars. */
public final class TradingCalendar {

    private TradingCalendar() {
    }

    /** The last trading day strictly before {@code today} for which Massive has a daily bar for {@code symbol}. */
    public static LocalDate previousTradingDay(MassiveClient client, String symbol, LocalDate today)
            throws IOException, InterruptedException {
        List<DailyBar> bars = client.dailyBars(symbol, today.minusDays(14), today.minusDays(1));
        if (bars.isEmpty()) {
            throw new IOException("no daily bars for " + symbol + " in the two weeks before " + today);
        }
        return bars.get(bars.size() - 1).date();
    }
}
