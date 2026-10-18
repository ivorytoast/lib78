package com.hamilla.replay;

import com.hamilla.massive.MassiveClient;
import com.hamilla.sma.Action;
import com.hamilla.sma.Session;
import com.hamilla.sma.Trend;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the whole replay against a local server with a hand-built day. The previous day is flat at 10,
 * the replay day trades 10 once a minute from 10:15 to 10:29, then 10.02 at 10:30 (price above the 20
 * but the 20 and 50 are only 0.06 cents apart: none), 9 at 10:31 (aligned down, 20 and 50 just under
 * three cents apart: none), 8 at 10:32 (down zone, too far below the 20), 9.83 at 10:33 (down zone,
 * within two cents: SHORT SELL), then nothing until the 16:00 closing print at 8.5.
 * Massive's minute SMA values are computed in the test from the same series, so with --verify every
 * bar must match.
 */
class ReplayTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final long T_0400 = 1792137600000L;
    private static final long T_1015 = T_0400 + 375 * 60_000L;
    private static final long T_1030 = T_0400 + 390 * 60_000L;
    private static final long T_1600 = T_0400 + 720 * 60_000L;
    private static final long NS = 1_000_000L;
    private static final long DAY_MS = 86_400_000L;

    private HttpServer server;
    private String base;
    private MassiveClient client;
    private ByteArrayOutputStream console;
    /** Close of every session minute: previous day (960) then replay day (960). */
    private final List<Double> series = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        for (int i = 0; i < 960; i++) {
            series.add(10.0);
        }
        for (int m = 0; m < 960; m++) {
            long t = T_0400 + m * 60_000L;
            double c;
            if (t < T_1015) {
                c = 10.0;                                      // filled from the previous day
            } else if (t < T_1030) {
                c = 10.0;                                      // 10:15 .. 10:29
            } else if (t == T_1030) {
                c = 10.02;                                     // 10:30
            } else if (t == T_1030 + 60_000L) {
                c = 9.0;                                       // 10:31
            } else if (t == T_1030 + 2 * 60_000L) {
                c = 8.0;                                       // 10:32
            } else if (t < T_1600) {
                c = 9.83;                                      // 10:33 then filled
            } else {
                c = 8.5;                                       // 16:00 closing print then filled
            }
            series.add(c);
        }

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        // Previous day: a bar for every session minute, all at 10, plus a 20:00 bar that must be ignored.
        server.createContext("/v2/aggs/ticker/RIVN/range/1/minute/", ex -> {
            List<String> bars = new ArrayList<>();
            for (int i = 0; i <= 960; i++) {
                bars.add(String.format(Locale.US, "{\"o\":10,\"h\":10,\"l\":10,\"c\":10,\"t\":%d}", T_0400 - DAY_MS + i * 60_000L));
            }
            reply(ex, "{\"results\":[" + String.join(",", bars) + "]}");
        });
        server.createContext("/v3/trades/RIVN", ex -> {
            List<String> trades = new ArrayList<>();
            trades.add(trade(T_0400 + 500, 99.0, true));                       // 04:00 odd lot, ignored
            for (int i = 0; i < 15; i++) {
                trades.add(trade(T_1015 + i * 60_000L + 100, 10.0, false));
            }
            trades.add(trade(T_1030 + 100, 10.02, false));
            trades.add(trade(T_1030 + 200, 50.0, true));                       // odd lot, ignored
            trades.add(trade(T_1030 + 60_000L + 100, 9.0, false));
            trades.add(trade(T_1030 + 2 * 60_000L + 100, 8.0, false));
            trades.add(trade(T_1030 + 3 * 60_000L + 100, 9.83, false));
            trades.add(trade(T_1600 + 10, 8.5, false));
            reply(ex, "{\"results\":[" + String.join(",", trades) + "]}");
        });
        server.createContext("/v1/indicators/sma/RIVN", ex -> {
            String q = ex.getRequestURI().getQuery();
            int window = q.contains("window=20&") ? 20 : q.contains("window=50&") ? 50 : 200;
            List<String> values = new ArrayList<>();
            for (int i = 960; i < 1920; i++) {
                double sum = 0;
                for (int k = i - window + 1; k <= i; k++) {
                    sum += series.get(k);
                }
                values.add(String.format(Locale.US, "{\"timestamp\":%d,\"value\":%s}", T_0400 + (i - 960) * 60_000L, sum / window));
            }
            reply(ex, "{\"results\":{\"values\":[" + String.join(",", values) + "]}}");
        });
        server.start();

        client = new MassiveClient(HttpClient.newHttpClient(), base, "k");
        console = new ByteArrayOutputStream();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String trade(long ms, double price, boolean oddLot) {
        return String.format(Locale.US, "{\"sip_timestamp\":%d,\"price\":%s,\"size\":100%s}",
                ms * NS, price, oddLot ? ",\"conditions\":[37]" : "");
    }

    @Test
    void chartModePrintsOnlyEntrySignalsAndLeavesWindowsAloneOnEmptyMinutes() throws Exception {
        Replay replay = new Replay(client, new PrintStream(console, true, StandardCharsets.UTF_8));

        Replay.Result r = replay.run(new Replay.Options("RIVN", DAY, false, false, null));

        assertEquals(240, r.signals().size());                 // 10:30 .. 14:29 classified
        assertEquals(T_1030, r.signals().get(0).barStartMs());
        assertEquals(T_0400 + 629 * 60_000L, r.signals().get(239).barStartMs());
        // 10:30: 20=10.001 50=10.0004 200=10.0001, price 10.02: aligned, but the 20 and 50 are far closer than 3 cents -> none
        assertEquals(Trend.NONE, r.signals().get(0).trend());
        assertEquals(Optional.empty(), r.signals().get(0).action());
        assertEquals(10.001, r.signals().get(0).atClose().sma20(), 1e-9);
        // 10:31: 20=9.951 50=9.9804 200=9.995, price 9: aligned down, but the 20 and 50 are 2.94 cents apart -> none
        assertEquals(Trend.NONE, r.signals().get(1).trend());
        assertEquals(Optional.empty(), r.signals().get(1).action());
        // 10:32: 20=9.851 50=9.9404, price 8 -> down, far below the 20 -> no signal
        assertEquals(Trend.DOWN, r.signals().get(2).trend());
        assertEquals(Optional.empty(), r.signals().get(2).action());
        // 10:33: 20=9.8425, price 9.83 -> down, 1.25 cents below -> SHORT SELL
        assertEquals(Trend.DOWN, r.signals().get(3).trend());
        assertEquals(Optional.of(Action.SHORT_SELL), r.signals().get(3).action());
        assertEquals(9.8425, r.signals().get(3).atClose().sma20(), 1e-9);
        // No trades until 16:00: every later minute repeats the 10:33 state, nothing rolls into the windows.
        assertEquals(Optional.of(Action.SHORT_SELL), r.signals().get(239).action());
        assertEquals(9.8425, r.signals().get(239).atClose().sma20(), 1e-9);
        assertEquals(20, r.barsClosed());                       // 10:15 .. 10:33 and 16:00
        assertEquals(960 - 20, r.emptyMinutes());
        assertEquals(22, r.tradesSeen());
        assertEquals(20, r.tradesApplied());
        assertTrue(r.oracleChecks().isEmpty());

        // The signal holds every minute from 10:33 to 14:29 (237 minutes); with the default six-minute
        // cooldown only every sixth one prints: 10:33, 10:39, ... 14:27.
        assertTrue(r.signals().get(3).printed());
        assertFalse(r.signals().get(4).printed());
        assertTrue(r.signals().get(9).printed());
        assertEquals(237 - 40, r.signalsHeldByCooldown());
        String[] lines = console.toString(StandardCharsets.UTF_8).split("\\R");
        assertEquals("10:33 SHORT SELL 9.83 9.91", lines[0]);  // RIVN: price, then price plus eight cents
        assertEquals("10:39 SHORT SELL 9.83 9.91", lines[1]);
        assertEquals("14:27 SHORT SELL 9.83 9.91", lines[lines.length - 1]);
        assertEquals(40, lines.length);                         // no summary line
        assertFalse(console.toString(StandardCharsets.UTF_8).contains("none"));
        assertFalse(console.toString(StandardCharsets.UTF_8).contains("BUY"));
    }

    @Test
    void cooldownZeroPrintsEverySignal() throws Exception {
        Replay replay = new Replay(client, new PrintStream(console, true, StandardCharsets.UTF_8));

        Replay.Result r = replay.run(new Replay.Options("RIVN", DAY, Session.EXTENDED, 0, false, false, null));

        assertEquals(0, r.signalsHeldByCooldown());
        String[] lines = console.toString(StandardCharsets.UTF_8).split("\\R");
        assertEquals(237, lines.length);
        assertEquals("14:29 SHORT SELL 9.83 9.91", lines[236]);
    }

    @Test
    void regularSessionDropsPreMarketBarsFromTheWindows() throws Exception {
        Replay replay = new Replay(client, new PrintStream(console, true, StandardCharsets.UTF_8));

        Replay.Result r = replay.run(new Replay.Options("RIVN", DAY, Session.REGULAR, false, false, null));

        // Same answers here because the previous day was flat at 10, but only 390 minutes per day exist
        // and the 16:00 closing print falls outside the session.
        assertEquals(240, r.signals().size());
        assertEquals(Optional.of(Action.SHORT_SELL), r.signals().get(3).action());
        assertEquals(19, r.barsClosed());
        assertEquals(390 - 19, r.emptyMinutes());
    }

    @Test
    void verboseVerifyAndCsv(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("rivn.csv");
        Replay replay = new Replay(client, new PrintStream(console, true, StandardCharsets.UTF_8));

        Replay.Result r = replay.run(new Replay.Options("RIVN", DAY, true, true, csv));

        for (Replay.OracleCheck c : r.oracleChecks()) {
            assertEquals(960, c.barsCompared(), c.toString());
            assertEquals(960, c.barsMatched(), c.toString());
            assertTrue(c.pass(), c.toString());
        }
        assertTrue(r.allOraclesPass());

        String out = console.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("10:30 none                       px=10.0200    sma20=10.0010    sma50=10.0004    sma200=10.0001"), out);
        assertTrue(out.contains("10:31 none                       px=9.0000"), out);
        assertTrue(out.contains("10:32 down                       px=8.0000"), out);
        assertTrue(out.contains("10:33 down SHORT SELL            px=9.8300"), out);
        assertTrue(out.contains("10:34 down SHORT SELL (cooldown) px=9.8300"), out);
        assertTrue(out.contains("minutes, up 0, down "), out);
        assertTrue(out.contains("held by cooldown"), out);
        assertTrue(out.contains("960/960  bars match"), out);
        assertEquals(960, r.barsClosed());
        assertFalse(out.contains("FAIL"), out);

        List<String> lines = Files.readAllLines(csv);
        assertEquals("time_et,sip_timestamp_ns,price,size,sma20,sma50,sma200,trend,action", lines.get(0));
        assertEquals(21, lines.size());
        assertTrue(lines.get(16).startsWith("10:30:00.100,"), lines.get(16));
        assertTrue(lines.get(16).endsWith(",none,"), lines.get(16));
        assertTrue(lines.get(17).endsWith(",none,"), lines.get(17));
        assertTrue(lines.get(18).endsWith(",down,"), lines.get(18));
        assertTrue(lines.get(19).endsWith(",down,SHORT SELL"), lines.get(19));
    }

    @Test
    void verifyReportsMismatchesWithExamples() throws Exception {
        server.removeContext("/v1/indicators/sma/RIVN");
        server.createContext("/v1/indicators/sma/RIVN", ex -> reply(ex,
                "{\"results\":{\"values\":[{\"timestamp\":" + T_1030 + ",\"value\":99.0}]}}"));
        Replay replay = new Replay(client, new PrintStream(console, true, StandardCharsets.UTF_8));

        Replay.Result r = replay.run(new Replay.Options("RIVN", DAY, false, true, null));

        assertFalse(r.allOraclesPass());
        Replay.OracleCheck sma20 = r.oracleChecks().get(0);
        assertEquals(1, sma20.barsCompared());
        assertEquals(0, sma20.barsMatched());
        assertEquals(959, sma20.barsMissingFromMassive());
        assertEquals(T_1030, sma20.examples().get(0).barStartMs());
        assertTrue(console.toString(StandardCharsets.UTF_8).contains("10:30 engine=10.001000 massive=99.000000"), console.toString());
    }

    private static void reply(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
