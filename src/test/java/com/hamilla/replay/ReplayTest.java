package com.hamilla.replay;

import com.hamilla.market.MinuteBar;
import com.hamilla.massive.MassiveClient;
import com.hamilla.sma.Fixture;
import com.hamilla.sma.SessionMinutes;
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
 * Runs the whole replay against a local server. The "previous day" minute bars are the RIVN fixture
 * shifted back one day, the replay day has a handful of trades, and Massive's minute SMA values are
 * computed in the test from the same filled series, so every bar must match.
 */
class ReplayTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final long DAY_MS = 86_400_000L;
    private static final long T_0400 = 1792137600000L;
    private static final long NS = 1_000_000L;

    /** Replay-day trades: (ms, price, odd lot?). 04:00 has only an odd lot, 04:01 trades, 04:02 is empty, 04:03 trades. */
    private static final double[][] TRADES = {
            {T_0400 + 500, 12.60, 1},
            {T_0400 + 60_000 + 100, 12.70, 0},
            {T_0400 + 60_000 + 200, 12.75, 1},
            {T_0400 + 60_000 + 300, 12.72, 0},
            {T_0400 + 180_000 + 100, 12.80, 0},
    };

    private HttpServer server;
    private MassiveClient client;
    private ByteArrayOutputStream console;
    private List<Double> expectedClosedSeries;

    @BeforeEach
    void start() throws IOException {
        // Previous day: fixture bars shifted back 24h (same wall-clock minutes), filled to 19:59.
        List<MinuteBar> prev = new ArrayList<>();
        for (MinuteBar b : Fixture.rivnBars()) {
            prev.add(new MinuteBar(b.startMs() - DAY_MS, b.open(), b.high(), b.low(), b.close(), b.volume()));
        }
        List<Double> series = new ArrayList<>(SessionMinutes.fillCloses(prev, SessionMinutes.lastBarOfSession(prev.get(0).startMs())));
        // Replay day bars as the engine must produce them: 04:00 empty -> filled with 19:59 close, 04:01 = 12.72, 04:02 filled, 04:03 = 12.80.
        series.add(series.get(series.size() - 1));
        series.add(12.72);
        series.add(12.72);
        series.add(12.80);
        expectedClosedSeries = series;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v2/aggs/ticker/RIVN/range/1/minute/", ex -> reply(ex, "{\"results\":[" + barsJson(prev) + "]}"));
        server.createContext("/v3/trades/RIVN", ex -> {
            StringBuilder sb = new StringBuilder("{\"results\":[");
            for (int i = 0; i < TRADES.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(String.format(Locale.US, "{\"sip_timestamp\":%d,\"price\":%s,\"size\":100%s}",
                        (long) TRADES[i][0] * NS, TRADES[i][1], TRADES[i][2] == 1 ? ",\"conditions\":[37]" : ""));
            }
            reply(ex, sb.append("]}").toString());
        });
        server.createContext("/v1/indicators/sma/RIVN", ex -> {
            String q = ex.getRequestURI().getQuery();
            int window = q.contains("window=20&") ? 20 : q.contains("window=50&") ? 50 : 200;
            // Massive would report a value for every replay-day bar: 04:00 .. 04:03 and then filled bars to 19:59.
            StringBuilder sb = new StringBuilder("{\"results\":{\"values\":[");
            List<Double> full = new ArrayList<>(expectedClosedSeries);
            for (int i = 0; i < 956; i++) {
                full.add(12.80); // 04:04 .. 19:59 repeat the 04:03 close
            }
            int firstReplayIndex = expectedClosedSeries.size() - 4;
            for (int i = firstReplayIndex; i < full.size(); i++) {
                double sum = 0;
                for (int k = i - window + 1; k <= i; k++) {
                    sum += full.get(k);
                }
                if (i > firstReplayIndex) {
                    sb.append(',');
                }
                sb.append(String.format(Locale.US, "{\"timestamp\":%d,\"value\":%s}",
                        T_0400 + (long) (i - firstReplayIndex) * 60_000L, sum / window));
            }
            reply(ex, sb.append("]}}").toString());
        });
        server.start();

        client = new MassiveClient(HttpClient.newHttpClient(), base, "k");
        console = new ByteArrayOutputStream();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void fullDayReplayMatchesMassiveOnEveryBarAndWritesCsv(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("rivn.csv");
        Replay replay = new Replay(client, new PrintStream(console, true, StandardCharsets.UTF_8));

        Replay.Result r = replay.run(new Replay.Options("RIVN", DAY, 30, csv));

        assertEquals(5, r.tradesSeen());
        assertEquals(3, r.tradesApplied());
        assertEquals(960, r.barsClosed());          // every minute 04:00 .. 19:59
        assertEquals(958, r.barsFilled());          // all but 04:01 and 04:03
        assertEquals(12.70, r.first().price());
        assertEquals(12.80, r.last().price());
        for (Replay.OracleCheck c : r.oracleChecks()) {
            assertEquals(960, c.barsCompared(), c.toString());
            assertEquals(960, c.barsMatched(), c.toString());
            assertEquals(0, c.barsMissingFromEngine(), c.toString());
            assertTrue(c.pass(), c.toString());
        }
        assertTrue(r.allOraclesPass());

        List<String> lines = Files.readAllLines(csv);
        assertEquals("time_et,sip_timestamp_ns,price,size,sma20,sma50,sma200", lines.get(0));
        assertEquals(4, lines.size());
        assertTrue(lines.get(1).startsWith("04:01:00.100,1792137660100000000,12.7,100.0,"));

        String out = console.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("5 trades seen, 3 applied to bars, 960 bars closed (958 of them repeated an empty minute)"), out);
        assertTrue(out.contains("960/960  bars match"), out);
        assertFalse(out.contains("FAIL"), out);
    }

    @Test
    void mismatchesAreReportedWithExamples() throws Exception {
        // Replace the SMA oracle with one that is wrong for the 04:01 bar of the 20 window.
        server.removeContext("/v1/indicators/sma/RIVN");
        server.createContext("/v1/indicators/sma/RIVN", ex -> reply(ex,
                "{\"results\":{\"values\":[{\"timestamp\":" + (T_0400 + 60_000) + ",\"value\":99.0}]}}"));
        Replay replay = new Replay(client, new PrintStream(console, true, StandardCharsets.UTF_8));

        Replay.Result r = replay.run(new Replay.Options("RIVN", DAY, 0, null));

        assertFalse(r.allOraclesPass());
        Replay.OracleCheck sma20 = r.oracleChecks().get(0);
        assertEquals(1, sma20.barsCompared());
        assertEquals(0, sma20.barsMatched());
        assertEquals(959, sma20.barsMissingFromMassive());
        assertEquals(1, sma20.examples().size());
        assertEquals(T_0400 + 60_000, sma20.examples().get(0).barStartMs());
        assertTrue(console.toString(StandardCharsets.UTF_8).contains("04:01 engine="), console.toString());
    }

    private static void reply(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String barsJson(List<MinuteBar> bars) {
        List<String> objs = new ArrayList<>();
        for (MinuteBar b : bars) {
            objs.add(String.format(Locale.US, "{\"o\":%s,\"h\":%s,\"l\":%s,\"c\":%s,\"t\":%d,\"v\":%s}",
                    b.open(), b.high(), b.low(), b.close(), b.startMs(), b.volume()));
        }
        return String.join(",", objs);
    }
}
