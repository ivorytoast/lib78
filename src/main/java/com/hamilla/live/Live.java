package com.hamilla.live;

import com.hamilla.backtest.Sizing;
import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;
import com.hamilla.massive.MassiveClient;
import com.hamilla.sma.Action;
import com.hamilla.sma.Session;
import com.hamilla.sma.SignalPrices;
import com.hamilla.sma.SignalWindow;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a {@link PaperBook} through the trading day. At start it loads a week of bars for every symbol to
 * warm the averages and catches up on the day so far; then, a few seconds after each minute closes, it
 * fetches the day's bars again and rolls the newly completed minute through the book, printing one line
 * for each signal taken, each signal skipped for lack of room, and each position that comes off. For a
 * past day (or once the market has closed) it runs the whole day at once, so a day can be rehearsed.
 */
public final class Live {

    /** Days of bars to load before the trading day; a week always holds well over 200 session bars. */
    private static final int WARMUP_CALENDAR_DAYS = 7;
    /** Seconds after a minute closes before its bar is asked for; aggregates take a moment to settle. */
    private static final int SETTLE_SECONDS = 2;
    /** Seconds into a minute after which a still-missing bar is taken as an empty minute until the next poll. */
    private static final int GIVE_UP_SECONDS = 30;
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * @param brackets    the book: each symbol with its stop and target
     * @param sizing      shares per signal
     * @param bookDollars most dollars in open positions at once; 0 for no limit
     * @param date        the day to run; null means today (Eastern)
     * @param pollSeconds seconds between polls while a minute's bar is still missing
     */
    public record Options(Map<String, Bracket> brackets, Sizing sizing, double bookDollars, Session session, SignalWindow window,
                          int cooldownMinutes, LocalDate date, int pollSeconds) {
        public Options {
            if (pollSeconds < 1) {
                throw new IllegalArgumentException("poll must be at least 1 second, got " + pollSeconds);
            }
        }
    }

    private final MassiveClient client;
    private final PrintStream out;
    private final Clock clock;

    public Live(MassiveClient client, PrintStream out) {
        this(client, out, Clock.system(PriceEvent.EASTERN));
    }

    public Live(MassiveClient client, PrintStream out, Clock clock) {
        this.client = client;
        this.out = out;
        this.clock = clock;
    }

    public PaperBook run(Options opts) throws IOException, InterruptedException {
        LocalDate day = opts.date() != null ? opts.date() : LocalDate.now(clock);
        PaperBook book = new PaperBook(opts.brackets(), opts.sizing(), opts.bookDollars(), opts.session(), opts.window(),
                opts.cooldownMinutes(), day, new Printer());
        long closeMs = day.atTime(MARKET_CLOSE).atZone(PriceEvent.EASTERN).toInstant().toEpochMilli();
        boolean liveDay = clock.millis() < closeMs;

        out.printf("%s %s: %s, window %s, %s, book %s%n", liveDay ? "live" : "rehearsal", day,
                String.join(" ", opts.brackets().keySet()), opts.window(), sizingText(opts.sizing()),
                opts.bookDollars() > 0 ? String.format("$%,.0f", opts.bookDollars()) : "unlimited");
        for (var e : opts.brackets().entrySet()) {
            out.printf("  %-5s stop %.2f target %.2f%n", e.getKey(), e.getValue().stop(), e.getValue().target());
        }

        // Warm up and catch up: everything completed so far.
        for (String symbol : opts.brackets().keySet()) {
            List<MinuteBar> bars = client.minuteBars(symbol, day.minusDays(WARMUP_CALENDAR_DAYS), day);
            book.onBars(symbol, completed(bars, clock.millis()));
            long onDay = bars.stream().filter(b -> sameDay(b.startMs(), day)).count();
            if (onDay == 0 && !liveDay) {
                out.printf("warning: no bars for %s on %s%n", symbol, day);
            }
        }
        if (!liveDay) {
            finish(book, "rehearsal complete");
            return book;
        }
        out.printf("caught up to %s, watching...%n", HHMM.format(ZonedDateTime.now(clock)));

        while (true) {
            long now = clock.millis();
            if (now >= closeMs) {
                finish(book, "market closed");
                return book;
            }
            ZonedDateTime et = Instant.ofEpochMilli(now).atZone(PriceEvent.EASTERN);
            if (!et.toLocalTime().isBefore(opts.window().close()) && book.openPositions().isEmpty()) {
                finish(book, "window closed and nothing open");
                return book;
            }
            long minuteStart = Math.floorDiv(now, Session.MINUTE_MS) * Session.MINUTE_MS;
            long previousBar = minuteStart - Session.MINUTE_MS;
            long secondsIn = (now - minuteStart) / 1000;
            List<String> behind = new ArrayList<>();
            for (String symbol : opts.brackets().keySet()) {
                if (book.lastBar(symbol).orElse(Long.MIN_VALUE) < previousBar && opts.session().contains(previousBar)) {
                    behind.add(symbol);
                }
            }
            if (behind.isEmpty() || secondsIn > GIVE_UP_SECONDS || secondsIn < SETTLE_SECONDS) {
                long next = secondsIn < SETTLE_SECONDS ? minuteStart + SETTLE_SECONDS * 1000L
                        : minuteStart + Session.MINUTE_MS + SETTLE_SECONDS * 1000L;
                Thread.sleep(Math.max(250, next - now));
                continue;
            }
            for (String symbol : behind) {
                try {
                    book.onBars(symbol, completed(client.minuteBars(symbol, day, day), clock.millis()));
                } catch (IOException e) {
                    out.printf("%s %-5s fetch failed, will retry: %s%n", HHMM.format(ZonedDateTime.now(clock)), symbol, e.getMessage());
                }
            }
            Thread.sleep(opts.pollSeconds() * 1000L);
        }
    }

    /** Bars whose minute has fully elapsed by {@code nowMs}; the one still forming is left out. */
    static List<MinuteBar> completed(List<MinuteBar> bars, long nowMs) {
        List<MinuteBar> done = new ArrayList<>();
        for (MinuteBar b : bars) {
            if (b.startMs() + Session.MINUTE_MS <= nowMs) {
                done.add(b);
            }
        }
        return done;
    }

    private static boolean sameDay(long startMs, LocalDate day) {
        return Instant.ofEpochMilli(startMs).atZone(PriceEvent.EASTERN).toLocalDate().equals(day);
    }

    private void finish(PaperBook book, String why) {
        book.closeAll();
        int targets = 0;
        int stops = 0;
        int closes = 0;
        for (PaperBook.Trade t : book.trades()) {
            switch (t.exit()) {
                case TARGET -> targets++;
                case STOP -> stops++;
                case CLOSE -> closes++;
            }
        }
        Map<String, Double> bySymbol = new LinkedHashMap<>();
        for (PaperBook.Trade t : book.trades()) {
            bySymbol.merge(t.position().symbol(), t.pnl(), Double::sum);
        }
        out.printf("%s: %d trades, %d targets, %d stops, %d closed at the day close, %d skipped (book full), P&L %s%n", why,
                book.trades().size(), targets, stops, closes, book.skippedSignals().size(), money(book.dayPnl()));
        for (var e : bySymbol.entrySet()) {
            out.printf("  %-5s %s%n", e.getKey(), money(e.getValue()));
        }
    }

    private static String sizingText(Sizing sizing) {
        StringBuilder sb = new StringBuilder();
        if (sizing.byRisk()) {
            sb.append(String.format("$%,.0f risked per trade", sizing.riskDollars()));
        } else {
            sb.append(sizing.fixedShares()).append(" shares per trade");
        }
        if (sizing.capped()) {
            sb.append(String.format(", at most $%,.0f per position", sizing.capDollars()));
        }
        return sb.toString();
    }

    /** Bars are stamped by their start minute, as the replay and grid print them; the line appears as that minute closes. */
    private static ZonedDateTime barTime(long barStartMs) {
        return Instant.ofEpochMilli(barStartMs).atZone(PriceEvent.EASTERN);
    }

    private static String money(double v) {
        return (v < 0 ? "-$" : "+$") + String.format("%,.0f", Math.abs(v));
    }

    /**
     * One line per event, stamped with the close of the bar it happened on, written as the instruction a
     * person acts on: what to do, how many, which symbol, at what price, and where the stop and target sit.
     */
    private final class Printer implements PaperBook.Listener {
        @Override
        public void opened(PaperBook.Position p, double openNotional) {
            out.printf("%s  %-12s %5d %-5s @ %-8s  stop %-8s target %-8s  ($%,.0f; book $%,.0f)%n",
                    HHMM.format(barTime(p.barStartMs())), p.isLong() ? "BUY" : "SELL SHORT", p.shares(), p.symbol(),
                    SignalPrices.format(p.entryPrice()), SignalPrices.format(p.stopPrice()), SignalPrices.format(p.targetPrice()),
                    p.notional(), openNotional);
        }

        @Override
        public void skipped(String symbol, long barStartMs, Action action, double price, int shares, double openNotional) {
            out.printf("%s  %-12s %5d %-5s @ %-8s  SKIP: book full ($%,.0f open)%n",
                    HHMM.format(barTime(barStartMs)), action == Action.BUY_LONG ? "no BUY" : "no SHORT", shares, symbol,
                    SignalPrices.format(price), openNotional);
        }

        @Override
        public void closed(PaperBook.Trade t, double dayPnl) {
            PaperBook.Position p = t.position();
            String why = switch (t.exit()) {
                case TARGET -> "target hit";
                case STOP -> "stop hit";
                case CLOSE -> "day close";
            };
            out.printf("%s  %-12s %5d %-5s @ %-8s  %-11s %s  (day %s)%n", HHMM.format(barTime(t.exitBarMs())),
                    p.isLong() ? "SELL" : "BUY TO COVER", p.shares(), p.symbol(), SignalPrices.format(t.exitPrice()),
                    why, money(t.pnl()), money(dayPnl));
        }
    }
}
