package com.hamilla.replay;

import com.hamilla.massive.MassiveClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the whole replay against a local server that answers like Massive did for SPY on 2026-10-16:
 * the daily history from the fixture, the day's bar, two pages of trades, and the SMA oracle values.
 */
class ReplayTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final String[] FIXTURE_ROWS = loadFixture();

    private HttpServer server;
    private MassiveClient client;
    private ByteArrayOutputStream console;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/v2/aggs/ticker/SPY/range/1/day/", ex -> {
            String[] parts = ex.getRequestURI().getPath().split("/");
            LocalDate from = LocalDate.parse(parts[parts.length - 2]);
            LocalDate to = LocalDate.parse(parts[parts.length - 1]);
            reply(ex, "{\"results\":[" + barsJson(from, to) + "]}");
        });
        server.createContext("/v3/trades/SPY", ex -> reply(ex, """
                {"results":[
                  {"sip_timestamp":1792137600013823361,"price":772.83,"size":10},
                  {"sip_timestamp":1792157400000000000,"price":773.29,"size":100},
                  {"sip_timestamp":1792159200000000000,"price":770.10,"size":50}
                ],"next_url":"%s/trades-page-2"}
                """.formatted(base)));
        server.createContext("/trades-page-2", ex -> reply(ex, """
                {"results":[
                  {"sip_timestamp":1792180799000000000,"price":763.20,"size":200},
                  {"sip_timestamp":1792195200003234508,"price":763.25,"size":2721956}
                ]}
                """));
        server.createContext("/v1/indicators/sma/SPY", ex -> {
            String q = ex.getRequestURI().getQuery();
            double value = q.contains("window=20&") ? 771.1840000000002
                    : q.contains("window=50&") ? 768.5017999999998 : 725.0145999999993;
            reply(ex, "{\"results\":{\"values\":[{\"timestamp\":1792123200000,\"value\":" + value + "}]}}");
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
    void fullDayReplayPassesOracleAndWritesCsv(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("spy.csv");
        Replay replay = new Replay(client, new PrintStream(console, true, StandardCharsets.UTF_8));

        Replay.Result r = replay.run(new Replay.Options("SPY", DAY, false, 30, csv));

        assertEquals(5, r.trades());
        assertEquals(772.83, r.first().price());
        assertEquals(763.25, r.last().price());
        assertEquals(763.25, r.officialBar().close());
        assertTrue(r.allOraclesPass(), r.oracleChecks().toString());
        assertEquals(771.1840000000002, r.last().sma20(), 1e-6);

        List<String> lines = Files.readAllLines(csv);
        assertEquals("time_et,sip_timestamp_ns,price,size,sma20,sma50,sma200", lines.get(0));
        assertEquals(6, lines.size());
        assertTrue(lines.get(1).startsWith("04:00:00.013,1792137600013823361,772.83,10.0,"));

        String out = console.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("5 trades replayed"), out);
        assertTrue(out.contains("sma20  engine=771.184000"), out);
        assertTrue(out.contains("PASS"), out);
        assertFalse(out.contains("FAIL"), out);
        assertTrue(out.contains("211 prior daily closes loaded") || out.contains("210 prior daily closes loaded"), out);
    }

    @Test
    void regularHoursOnlyDropsPreMarketAndAfterHoursTrades() throws Exception {
        Replay replay = new Replay(client, new PrintStream(console, true, StandardCharsets.UTF_8));

        Replay.Result r = replay.run(new Replay.Options("SPY", DAY, true, 0, null));

        // 04:00 pre-market and the 20:00 print are dropped; 09:30, 10:00 and 15:59:59 remain.
        assertEquals(3, r.trades());
        assertEquals(773.29, r.first().price());
        assertEquals(763.20, r.last().price());
        assertTrue(r.allOraclesPass());
    }

    private static void reply(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Fixture rows (v,vw,o,c,h,l,t,n) whose trading date falls in [from, to], rendered as aggregate JSON objects. */
    private static String barsJson(LocalDate from, LocalDate to) {
        List<String> objs = new ArrayList<>();
        for (String row : FIXTURE_ROWS) {
            String[] f = row.split(",");
            long t = Long.parseLong(f[6]);
            LocalDate d = java.time.Instant.ofEpochMilli(t).atZone(com.hamilla.market.PriceEvent.EASTERN).toLocalDate();
            if (!d.isBefore(from) && !d.isAfter(to)) {
                objs.add("{\"o\":" + f[2] + ",\"c\":" + f[3] + ",\"h\":" + f[4] + ",\"l\":" + f[5] + ",\"t\":" + t + ",\"v\":" + f[0] + "}");
            }
        }
        return String.join(",", objs);
    }

    private static String[] loadFixture() {
        try (var in = ReplayTest.class.getResourceAsStream("/spy-daily.csv");
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines().skip(1).toArray(String[]::new);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
