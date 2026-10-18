package com.hamilla;

import com.hamilla.market.PriceEvent;
import com.hamilla.massive.MassiveClient;
import com.hamilla.query.PointInTime;
import com.hamilla.replay.Replay;
import com.hamilla.replay.TradingCalendar;
import com.hamilla.sma.Session;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public final class Main {

    private static final String USAGE = """
            usage: lib78 replay <TICKER[,TICKER...]> [YYYY-MM-DD] [options]
                   lib78 at     <TICKER[,TICKER...]> <MM/DD/YYYY-HH:MM>

            replay  Replays one past trading day trade by trade on one-minute bars. Directly after
                    each minute closes, from 09:45 through 15:59 Eastern, prints the minute when
                    there is an entry signal:
                      BUY LONG    200 below 50, 50 below 20 by 3+ cents, price above the 20 by at most 2 cents
                      SHORT SELL  200 above 50, 50 above 20 by 3+ cents, price below the 20 by at most 2 cents
                    Nothing else is printed. Pre-market bars still feed the averages.
                    With no date, the most recent completed trading day is used.
            at      Prints the price and the 20/50/200 minute-bar SMAs at one moment (Eastern time),
                    using the last trade at or before that minute. Example: at SPY 10/16/2026-12:12

            options:
              --session <HH:MM-HH:MM>  daily window of bars the averages run on, Eastern time
                                       (default 04:00-20:00; a minute with no trade has no bar)
              --verbose     replay: print every minute with its up/down/none zone, any signal,
                            the price and the three averages, plus a summary line
              --verify      replay: run on Massive's filled 04:00-20:00 series instead and compare
                            every completed bar with Massive's own minute SMA endpoint
              --csv <path>  replay: write every close-updating trade and its SMA values to a CSV
                            file (with several tickers, the ticker is inserted before the extension)

            environment:
              MASSIVE_API_KEY          required
              MASSIVE_BASE_URL         optional, defaults to https://api.massive.com
            """;

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !(args[0].equals("replay") || args[0].equals("at"))) {
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
        List<String> rest = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--session")) {
                try {
                    session = Session.parse(requireValue(args, ++i));
                } catch (IllegalArgumentException e) {
                    System.err.println("error: " + e.getMessage());
                    System.exit(2);
                }
            } else {
                rest.add(args[i]);
            }
        }
        args = concat(args[0], args[1], rest);

        if (args[0].equals("at")) {
            System.exit(at(symbols, session, args));
        }
        LocalDate date = null;
        boolean verbose = false;
        boolean verify = false;
        Path csv = null;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
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
            Replay.Result result = replay.run(new Replay.Options(symbol, day, session, verbose, verify, csvFor));
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
