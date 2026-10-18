package com.hamilla.query;

import com.hamilla.market.DailyBar;
import com.hamilla.market.PriceEvent;
import com.hamilla.massive.MassiveClient;
import com.hamilla.sma.MovingAverages;
import com.hamilla.sma.SmaSnapshot;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Answers "what were the price and the 20/50/200 day SMAs for this symbol at this moment".
 * The price is the last trade at or before the moment; the averages are what the engine would
 * have shown on that trade, using the daily closes completed before that day.
 */
public final class PointInTime {

    /** Input format, interpreted in Eastern time: {@code MM/dd/yyyy-HH:mm}, e.g. {@code 10/16/2026-12:12}. */
    public static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("MM/dd/yyyy-HH:mm", Locale.US);
    private static final DateTimeFormatter TRADE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int WARMUP_CALENDAR_DAYS = 330;

    public record Answer(String symbol, ZonedDateTime asked, PriceEvent trade, SmaSnapshot snapshot, int closesLoaded) {
    }

    private final MassiveClient client;

    public PointInTime(MassiveClient client) {
        this.client = client;
    }

    /** Parses {@code MM/dd/yyyy-HH:mm} as a wall-clock time in New York. */
    public static ZonedDateTime parse(String text) {
        try {
            return LocalDateTime.parse(text.trim(), INPUT).atZone(PriceEvent.EASTERN);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("time must look like MM/DD/YYYY-HH:MM, e.g. 10/16/2026-12:12, got '" + text + "'", e);
        }
    }

    public Answer lookup(String symbol, ZonedDateTime at) throws IOException, InterruptedException {
        LocalDate date = at.toLocalDate();
        List<DailyBar> history = client.dailyBars(symbol, date.minusDays(WARMUP_CALENDAR_DAYS), date.minusDays(1));
        List<Double> closes = new ArrayList<>(history.size());
        for (DailyBar bar : history) {
            closes.add(bar.close());
        }

        Optional<PriceEvent> trade = client.lastTradeAtOrBefore(symbol, date, at.toInstant());
        if (trade.isEmpty()) {
            throw new IOException("no " + symbol + " trade on " + date + " at or before "
                    + at.toLocalTime() + " Eastern (not a trading day, or before the 04:00 open?)");
        }

        MovingAverages engine = new MovingAverages(symbol, closes);
        return new Answer(symbol, at, trade.get(), engine.update(trade.get()), closes.size());
    }

    /** The four requested lines, preceded by one line saying which trade answered the question. */
    public static String format(Answer a) {
        return String.format(Locale.US, "%s %s ET (last trade %s, %d prior closes)%nPrice: %s%n20: %s%n50: %s%n200: %s%n",
                a.symbol(), a.asked().format(INPUT), a.trade().easternTime().format(TRADE_TIME), a.closesLoaded(),
                money(a.trade().price()), money(a.snapshot().sma20()), money(a.snapshot().sma50()), money(a.snapshot().sma200()));
    }

    private static String money(double v) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.US, "%.2f", v);
    }
}
