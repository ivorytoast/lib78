package com.hamilla;

import com.hamilla.market.PriceEvent;
import com.hamilla.massive.MassiveClient;
import com.hamilla.query.PointInTime;
import com.hamilla.replay.Replay;
import com.hamilla.replay.TradingCalendar;

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

            replay  Replays one past trading day trade by trade and reports the 20/50/200 day SMAs.
                    With no date, the most recent completed trading day is used.
            at      Prints the price and the 20/50/200 day SMAs at one moment (Eastern time), using
                    the last trade at or before that minute. Example: at SPY 10/16/2026-12:12

            replay options:
              --regular-hours          only trades between 09:30 and 16:00 Eastern
              --print-every <minutes>  console line every N minutes (default 30, 0 = off)
              --csv <path>             write every trade and its SMA values to a CSV file
                                       (with several tickers, the ticker is inserted before the extension)

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

        if (args[0].equals("at")) {
            System.exit(at(symbols, args));
        }
        LocalDate date = null;
        boolean regularHours = false;
        int printEvery = 30;
        Path csv = null;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--regular-hours" -> regularHours = true;
                case "--print-every" -> printEvery = Integer.parseInt(requireValue(args, ++i));
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
        for (String symbol : symbols) {
            LocalDate day = date != null ? date
                    : TradingCalendar.previousTradingDay(client, symbol, LocalDate.now(PriceEvent.EASTERN));
            Path csvFor = csvFor(csv, symbol, symbols.size() > 1);
            Replay.Result result = replay.run(new Replay.Options(symbol, day, regularHours, printEvery, csvFor));
            allPass &= result.allOraclesPass();
            System.out.println();
        }
        System.exit(allPass ? 0 : 1);
    }

    private static int at(List<String> symbols, String[] args) throws Exception {
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
        PointInTime query = new PointInTime(clientOrExit());
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
