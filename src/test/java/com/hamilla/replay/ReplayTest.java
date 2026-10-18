package com.hamilla.replay;

import com.hamilla.massive.MassiveClient;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the whole replay against a local server with a hand-built day. The previous day is flat at 10,
 * the replay day trades 10 once a minute from 09:30 to 09:44, then 11 at 09:45, 9 at 09:46, 8 at 09:47,
 * nothing until the 16:00 closing print at 8.5. Massive's minute SMA values are computed in the test
 * from the same series, so with --verify every bar must match.
 */
class ReplayTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final long T_0400 = 1792137600000L;
    private static final long T_0930 = T_0400 + 330 * 60_000L;
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
            if (t < T_0930) {
                c = 10.0;                                      // filled from the previous day
            } else if (t < T_0930 + 15 * 60_000L) {
                c = 10.0;                                      // 09:30 .. 09:44
            } else if (t == T_0930 + 15 * 60_000L) {
                c = 11.0;                                      // 09:45
            } else if (t == T_0930 + 16 * 60_000L) {
                c = 9.0;                                       // 09:46
            } else if (t < T_1600) {
                c = 8.0;                                       // 09:47 then filled
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
                trades.add(trade(T_0930 + i * 60_000L + 100, 10.0, false));
            }
            trades.add(trade(T_0930 + 15 * 60_000L + 100, 11.0, false));
            trades.add(trade(T_0930 + 15 * 60_000L + 200, 50.0, true));        // odd lot, ignored
            trades.add(trade(T_0930 + 16 * 60_000L + 100, 9.0, false));
            trades.add(trade(T_0930 + 17 * 60_000L + 100, 8.0, false));
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
    void chartModePrintsOneTrendPerMinuteAndLeavesWindowsAloneOnEmptyMinutes() throws Exception {
        Replay replay = new Replay(client, new PrintStream(console, true, StandardCharsets.UTF_8));

        Replay.Result r = replay.run(new Replay.Options("RIVN", DAY, false, false, null));

        assertEquals(375, r.signals().size());                 // 09:45 .. 15:59
        assertEquals(T_0930 + 15 * 60_000L, r.signals().get(0).barStartMs());
        assertEquals(T_1600 - 60_000L, r.signals().get(374).barStartMs());
        // 09:45: 20=10.05 50=10.02 200=10.005, price 11 -> up
        assertEquals(Trend.UP, r.signals().get(0).trend());
        assertEquals(10.05, r.signals().get(0).atClose().sma20(), 1e-9);
        // 09:46: all three averages are exactly 10 -> ties -> none
        assertEquals(Trend.NONE, r.signals().get(1).trend());
        // 09:47: 20=9.9 50=9.96 200=9.99, price 8 -> down
        assertEquals(Trend.DOWN, r.signals().get(2).trend());
        // No trades until 16:00: every later minute repeats the 09:47 state, nothing rolls into the windows.
        assertEquals(Trend.DOWN, r.signals().get(10).trend());
        assertEquals(Trend.DOWN, r.signals().get(374).trend());
        assertEquals(9.9, r.signals().get(374).atClose().sma20(), 1e-9);
        assertEquals(19, r.barsClosed());                       // 09:30 .. 09:47 and 16:00
        assertEquals(960 - 19, r.emptyMinutes());
        assertEquals(21, r.tradesSeen());
        assertEquals(19, r.tradesApplied());
        assertTrue(r.oracleChecks().isEmpty());

        String[] lines = console.toString(StandardCharsets.UTF_8).split("\\R");
        assertEquals("09:45 up", lines[0]);
        assertEquals("09:46 none", lines[1]);
        assertEquals("09:47 down", lines[2]);
        assertEquals("15:59 down", lines[374]);
        assertEquals("RIVN 2026-10-16: 375 minutes, up 1, down 373, none 1 (21 trades, 19 applied, 19 bars, 941 empty minutes)", lines[375]);
        assertEquals(376, lines.length);
        assertFalse(console.toString(StandardCharsets.UTF_8).contains("09:44"));
    }

    @Test
    void regularSessionDropsPreMarketBarsFromTheWindows() throws Exception {
        Replay replay = new Replay(client, new PrintStream(console, true, StandardCharsets.UTF_8));

        Replay.Result r = replay.run(new Replay.Options("RIVN", DAY, Session.REGULAR, false, false, null));

        // Same answers here because the previous day was flat at 10, but only 390 minutes per day exist
        // and the 16:00 closing print falls outside the session.
        assertEquals(375, r.signals().size());
        assertEquals(Trend.UP, r.signals().get(0).trend());
        assertEquals(18, r.barsClosed());
        assertEquals(390 - 18, r.emptyMinutes());
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
        assertTrue(out.contains("09:45 up    px=11.0000    sma20=10.0500    sma50=10.0200    sma200=10.0050"), out);
        assertTrue(out.contains("960/960  bars match"), out);
        assertEquals(960, r.barsClosed());
        assertFalse(out.contains("FAIL"), out);

        List<String> lines = Files.readAllLines(csv);
        assertEquals("time_et,sip_timestamp_ns,price,size,sma20,sma50,sma200,trend", lines.get(0));
        assertEquals(20, lines.size());
        assertTrue(lines.get(16).startsWith("09:45:00.100,"), lines.get(16));
        assertTrue(lines.get(16).endsWith(",up"), lines.get(16));
    }

    @Test
    void verifyReportsMismatchesWithExamples() throws Exception {
        server.removeContext("/v1/indicators/sma/RIVN");
        server.createContext("/v1/indicators/sma/RIVN", ex -> reply(ex,
                "{\"results\":{\"values\":[{\"timestamp\":" + (T_0930 + 15 * 60_000L) + ",\"value\":99.0}]}}"));
        Replay replay = new Replay(client, new PrintStream(console, true, StandardCharsets.UTF_8));

        Replay.Result r = replay.run(new Replay.Options("RIVN", DAY, false, true, null));

        assertFalse(r.allOraclesPass());
        Replay.OracleCheck sma20 = r.oracleChecks().get(0);
        assertEquals(1, sma20.barsCompared());
        assertEquals(0, sma20.barsMatched());
        assertEquals(959, sma20.barsMissingFromMassive());
        assertEquals(T_0930 + 15 * 60_000L, sma20.examples().get(0).barStartMs());
        assertTrue(console.toString(StandardCharsets.UTF_8).contains("09:45 engine=10.050000 massive=99.000000"), console.toString());
    }

    private static void reply(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
