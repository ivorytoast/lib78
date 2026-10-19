package com.hamilla;

import com.hamilla.backtest.MonthGrid;
import com.hamilla.backtest.Sizing;
import com.hamilla.live.Bracket;
import com.hamilla.live.Live;
import com.hamilla.market.PriceEvent;
import com.hamilla.massive.MassiveClient;
import com.hamilla.query.PointInTime;
import com.hamilla.replay.Replay;
import com.hamilla.replay.TradingCalendar;
import com.hamilla.sma.Band;
import com.hamilla.sma.Session;
import com.hamilla.sma.SignalWindow;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Main {

    private static final String USAGE = """
            usage: lib78 replay <TICKER[,TICKER...]> [YYYY-MM-DD] [options]
                   lib78 at     <TICKER[,TICKER...]> <MM/DD/YYYY-HH:MM>
                   lib78 grid   <TICKER> <YYYY-MM> [<YYYY-MM>] [options]
                   lib78 live   <TICKER[,TICKER...]> --bracket <TICKER=stop/target>... [options]

            replay  Replays one past trading day trade by trade on one-minute bars. Directly after
                    each minute closes, inside --window (10:30 to 14:30 by default), prints the minute when
                    there is an entry signal:
                      BUY LONG    200 below 50 (and 6+ cents below the 20), 50 below 20 by 3+ cents,
                                  price above the 20 by at most 2 cents
                      SHORT SELL  200 above 50 (and 6+ cents above the 20), 50 above 20 by 3+ cents,
                                  price below the 20 by at most 2 cents
                    Each line ends with the price and, for RIVN, the price plus 8 cents.
                    After a signal prints, none prints until --cooldown minutes have passed.
                    Nothing else is printed. Minutes outside the window, pre-market included, still
                    feed the averages; they are never acted on.
                    With no date, the most recent completed trading day is used.
            at      Prints the price and the 20/50/200 minute-bar SMAs at one moment (Eastern time),
                    using the last trade at or before that minute. Example: at SPY 10/16/2026-12:12
            grid    Generates every completed trading day's signals for the month, then prints the
                    total P&L for each stop-and-target pair (entry at the signal price, brackets
                    filled on the minute's high or low, the rest closed at 15:55) and a recommended
                    pair chosen from the most consistent region of the table. With a second month,
                    runs every month in the range on the same levels and prints the total, the worst
                    month and the number of positive months per pair, recommending by steadiness.
            live    Trades the book on paper through today. Warms the averages up on the past week, catches
                    up on the day so far, then after each minute closes prints any entry signal with its
                    share count, stop and target, skips a signal that would put more than --book dollars in
                    open positions, and prints each stop, target or 15:55 close within seconds of the
                    price reaching it, with the day's running P&L. With --date it runs a past day the same way, all at once.

            options:
              --session <HH:MM-HH:MM>  daily window of bars the averages run on, Eastern time
                                       (default 04:00-20:00; a minute with no trade has no bar)
              --window <HH:MM-HH:MM>   minutes whose closes may produce a signal (default 10:30-14:30:
                                       the 10:30 close is the first acted on, nothing opens from 14:30)
              --cooldown <minutes>  replay: minutes after a printed signal during which no signal
                            prints (default 6; 0 prints every signal)
              --band <dollars|auto>  how close to the 20 a close must be for a signal (default 0.02);
                            the zone gaps scale with it (1.5x and 3x). "auto" sets it per symbol to
                            0.4 of its typical one-minute range, so a $240 stock is judged by its own
                            noise rather than RIVN's. Applies to replay, grid and live.
              --verbose     replay: print every minute with its up/down/none zone, any signal,
                            the price and the three averages, plus a summary line
              --verify      replay: run on Massive's filled 04:00-20:00 series instead and compare
                            every completed bar with Massive's own minute SMA endpoint
              --csv <path>  replay: write every close-updating trade and its SMA values to a CSV
                            file (with several tickers, the ticker is inserted before the extension)
              --shares <n>  grid: shares per trade (default: the symbol's configured size, else 100)
              --risk <dollars>  grid: size each trade as risk divided by its stop, so a stopped trade
                            loses about this much in every cell (no-stop rows use --shares)
              --cap <dollars>  grid: never hold more than this in one position; a trade whose risk
                            sizing would cost more buys cap divided by its entry price instead
              --stops <a,b,...>    grid: stop sizes in dollars to test ("none" allowed)
              --targets <a,b,...>  grid: target sizes in dollars to test ("none" allowed)
                            (defaults for both are multiples of the typical one-minute range)
              --show <stop,target>  grid over a range: print that pair's month-by-month P&L and
                            time-of-day breakdown; may be repeated
              --bracket <TICKER=stop/target>  live: that symbol's stop and target in dollars; one per
                            ticker, may be repeated or comma-separated (every ticker needs one)
              --book <dollars>  live: most dollars in open positions at once (default 0: no limit)
              --date <YYYY-MM-DD>  live: run that past day at once instead of today
              --poll <seconds>  live: seconds between fetches of the day's bars (default 5); stops and
                            targets are checked against the forming minute on every fetch
              (--shares, --risk, --cap, --cooldown and --window apply to live as to grid)

            environment:
              MASSIVE_API_KEY          required
              MASSIVE_BASE_URL         optional, defaults to https://api.massive.com
            """;

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !(args[0].equals("replay") || args[0].equals("at") || args[0].equals("grid")
                || args[0].equals("live"))) {
            System.err.print(USAGE);
            System.exit(2);
        }

        List<String> symbols = new ArrayList<>();
        for (String s : args[1].split(",")) {
            if (!s.isBlank()) {
                symbols.add(s.trim().toUpperCase());
            }
        }

        Session session = Session.EXTENDED;
        SignalWindow window = SignalWindow.DEFAULT;
        Band band = Band.DEFAULT;
        List<String> rest = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            try {
                if (args[i].equals("--session")) {
                    session = Session.parse(requireValue(args, ++i));
                } else if (args[i].equals("--window")) {
                    window = SignalWindow.parse(requireValue(args, ++i));
                } else if (args[i].equals("--band")) {
                    band = Band.parse(requireValue(args, ++i));
                } else {
                    rest.add(args[i]);
                }
            } catch (IllegalArgumentException e) {
                System.err.println("error: " + e.getMessage());
                System.exit(2);
            }
        }
        args = concat(args[0], args[1], rest);

        if (args[0].equals("at")) {
            System.exit(at(symbols, session, args));
        }
        if (args[0].equals("grid")) {
            System.exit(grid(symbols, session, window, band, args));
        }
        if (args[0].equals("live")) {
            System.exit(live(symbols, session, window, band, args));
        }
        LocalDate date = null;
        int cooldown = Replay.DEFAULT_COOLDOWN_MINUTES;
        boolean verbose = false;
        boolean verify = false;
        Path csv = null;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--cooldown" -> {
                    String value = requireValue(args, ++i);
                    try {
                        cooldown = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        cooldown = -1;
                    }
                    if (cooldown < 0) {
                        System.err.println("error: --cooldown needs a whole number of minutes, 0 or more, got '" + value + "'");
                        System.exit(2);
                    }
                }
                case "--verbose" -> verbose = true;
                case "--verify" -> verify = true;
                case "--csv" -> csv = Path.of(requireValue(args, ++i));
                default -> {
                    if (args[i].startsWith("--")) {
                        System.err.println("unknown option " + args[i]);
                        System.err.print(USAGE);
                        System.exit(2);
                    }
                    date = LocalDate.parse(args[i]);
                }
            }
        }

        MassiveClient client = clientOrExit();
        Replay replay = new Replay(client, System.out);
        boolean allPass = true;
        boolean first = true;
        for (String symbol : symbols) {
            if (!first) {
                System.out.println();
            }
            first = false;
            LocalDate day = date != null ? date
                    : TradingCalendar.previousTradingDay(client, symbol, LocalDate.now(PriceEvent.EASTERN));
            Path csvFor = csvFor(csv, symbol, symbols.size() > 1);
            Replay.Result result = replay.run(new Replay.Options(symbol, day, session, window, cooldown, verbose, verify, csvFor, band));
            allPass &= result.allOraclesPass();
        }
        System.exit(allPass ? 0 : 1);
    }

    private static String[] concat(String a, String b, List<String> rest) {
        String[] out = new String[rest.size() + 2];
        out[0] = a;
        out[1] = b;
        for (int i = 0; i < rest.size(); i++) {
            out[i + 2] = rest.get(i);
        }
        return out;
    }

    private static int at(List<String> symbols, Session session, String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("at needs exactly one time, e.g. at SPY 10/16/2026-12:12");
            return 2;
        }
        ZonedDateTime when;
        try {
            when = PointInTime.parse(args[2]);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }
        PointInTime query = new PointInTime(clientOrExit(), session);
        int failures = 0;
        for (String symbol : symbols) {
            try {
                System.out.print(PointInTime.format(query.lookup(symbol, when)));
            } catch (IOException e) {
                System.err.println("error: " + e.getMessage());
                failures++;
            }
            if (symbols.size() > 1) {
                System.out.println();
            }
        }
        return failures == 0 ? 0 : 1;
    }

    private static int grid(List<String> symbols, Session session, SignalWindow window, Band band, String[] args) throws Exception {
        if (symbols.size() != 1 || args.length < 3) {
            System.err.println("grid needs one ticker and a month, e.g. grid RIVN 2026-10");
            return 2;
        }
        YearMonth month;
        try {
            month = MonthGrid.parseMonth(args[2]);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }
        YearMonth toMonth = null;
        int firstOption = 3;
        if (args.length > 3 && !args[3].startsWith("--")) {
            try {
                toMonth = MonthGrid.parseMonth(args[3]);
            } catch (IllegalArgumentException e) {
                System.err.println("error: " + e.getMessage());
                return 2;
            }
            firstOption = 4;
        }
        int cooldown = Replay.DEFAULT_COOLDOWN_MINUTES;
        int shares = 0;
        double risk = 0;
        double cap = 0;
        List<Double> stops = null;
        List<Double> targets = null;
        List<double[]> show = new ArrayList<>();
        try {
            for (int i = firstOption; i < args.length; i++) {
                switch (args[i]) {
                    case "--cooldown" -> cooldown = Integer.parseInt(requireValue(args, ++i));
                    case "--shares" -> shares = Integer.parseInt(requireValue(args, ++i));
                    case "--risk" -> risk = Double.parseDouble(requireValue(args, ++i));
                    case "--cap" -> cap = Double.parseDouble(requireValue(args, ++i));
                    case "--stops" -> stops = MonthGrid.parseLevels(requireValue(args, ++i));
                    case "--targets" -> targets = MonthGrid.parseLevels(requireValue(args, ++i));
                    case "--show" -> show.add(MonthGrid.parsePair(requireValue(args, ++i)));
                    default -> {
                        System.err.println("unknown option " + args[i]);
                        return 2;
                    }
                }
            }
            if (cooldown < 0 || shares < 0 || risk < 0 || cap < 0) {
                throw new IllegalArgumentException("cooldown, shares, risk and cap must not be negative");
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }
        try {
            new MonthGrid(clientOrExit(), System.out).runAny(
                    new MonthGrid.Options(symbols.get(0), month, toMonth, session, window, cooldown, shares, risk, cap, stops, targets, show, band));
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static int live(List<String> symbols, Session session, SignalWindow window, Band band, String[] args) throws Exception {
        if (symbols.isEmpty()) {
            System.err.println("live needs at least one ticker, e.g. live STLD,DIS --bracket STLD=0.18/1.10 --bracket DIS=0.16/0.24");
            return 2;
        }
        int cooldown = Replay.DEFAULT_COOLDOWN_MINUTES;
        int shares = 0;
        double risk = 0;
        double cap = 0;
        double book = 0;
        int poll = 5;
        LocalDate date = null;
        Map<String, Bracket> brackets = new LinkedHashMap<>();
        try {
            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--cooldown" -> cooldown = Integer.parseInt(requireValue(args, ++i));
                    case "--shares" -> shares = Integer.parseInt(requireValue(args, ++i));
                    case "--risk" -> risk = Double.parseDouble(requireValue(args, ++i));
                    case "--cap" -> cap = Double.parseDouble(requireValue(args, ++i));
                    case "--book" -> book = Double.parseDouble(requireValue(args, ++i));
                    case "--poll" -> poll = Integer.parseInt(requireValue(args, ++i));
                    case "--date" -> date = LocalDate.parse(requireValue(args, ++i));
                    case "--bracket", "--brackets" -> brackets.putAll(Bracket.parseAll(requireValue(args, ++i)));
                    default -> {
                        System.err.println("unknown option " + args[i]);
                        return 2;
                    }
                }
            }
            if (cooldown < 0 || shares < 0 || risk < 0 || cap < 0 || book < 0) {
                throw new IllegalArgumentException("cooldown, shares, risk, cap and book must not be negative");
            }
            Map<String, Bracket> ordered = new LinkedHashMap<>();
            for (String symbol : symbols) {
                Bracket b = brackets.get(symbol);
                if (b == null) {
                    throw new IllegalArgumentException("no --bracket for " + symbol + ", e.g. --bracket " + symbol + "=0.18/1.10");
                }
                ordered.put(symbol, b);
            }
            for (String symbol : brackets.keySet()) {
                if (!ordered.containsKey(symbol)) {
                    throw new IllegalArgumentException("--bracket " + symbol + " is not one of the tickers");
                }
            }
            int fallback = shares > 0 ? shares : com.hamilla.sma.SymbolSettings.DEFAULT_SHARES;
            Sizing sizing = (risk > 0 ? Sizing.risk(risk, fallback) : Sizing.shares(fallback)).cappedAt(cap);
            new Live(clientOrExit(), System.out).run(
                    new Live.Options(ordered, sizing, book, session, window, cooldown, date, poll, band));
            return 0;
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static MassiveClient clientOrExit() {
        try {
            return MassiveClient.fromEnvironment();
        } catch (IllegalStateException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
            throw e;
        }
    }

    private static String requireValue(String[] args, int i) {
        if (i >= args.length) {
            System.err.println("missing value for " + args[i - 1]);
            System.exit(2);
        }
        return args[i];
    }

    private static Path csvFor(Path base, String symbol, boolean multi) {
        if (base == null || !multi) {
            return base;
        }
        String name = base.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String renamed = dot > 0 ? name.substring(0, dot) + "-" + symbol + name.substring(dot) : name + "-" + symbol;
        return base.resolveSibling(renamed);
    }
}
