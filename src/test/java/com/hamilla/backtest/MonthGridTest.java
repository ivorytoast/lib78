package com.hamilla.backtest;

import com.hamilla.market.MinuteBar;
import com.hamilla.massive.MassiveClient;
import com.hamilla.sma.Session;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonthGridTest {

    private HttpServer server;
    private MassiveClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The month has one completed trading day for this mock: 2026-10-16.
        server.createContext("/v2/aggs/ticker/RIVN/range/1/day/", ex -> reply(ex,
                "{\"results\":[{\"o\":13.1,\"h\":13.25,\"l\":12.36,\"c\":12.39,\"t\":1792123200000,\"v\":1}]}"));
        server.createContext("/v2/aggs/ticker/RIVN/range/1/minute/", ex -> {
            List<String> objs = new ArrayList<>();
            for (MinuteBar b : Bars.rivnOct15and16()) {
                objs.add(String.format(Locale.US, "{\"o\":%s,\"h\":%s,\"l\":%s,\"c\":%s,\"t\":%d,\"v\":%s}",
                        b.open(), b.high(), b.low(), b.close(), b.startMs(), b.volume()));
            }
            reply(ex, "{\"results\":[" + String.join(",", objs) + "]}");
        });
        server.start();
        client = new MassiveClient(HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort(), "k");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void parsesMonthsAndLevels() {
        assertEquals(YearMonth.of(2026, 10), MonthGrid.parseMonth("2026-10"));
        assertEquals(YearMonth.of(2026, 10), MonthGrid.parseMonth("10/2026"));
        assertEquals(YearMonth.of(2026, 1), MonthGrid.parseMonth("1/2026"));
        assertThrows(IllegalArgumentException.class, () -> MonthGrid.parseMonth("October"));
        assertEquals(List.of(0.08, 0.15, BracketSimulator.NONE), MonthGrid.parseLevels("0.08, 0.15, none"));
        assertThrows(IllegalArgumentException.class, () -> MonthGrid.parseLevels("0.08,-1"));
        assertEquals(0.05, MonthGrid.parsePair("0.05,0.25")[0]);
        assertEquals(BracketSimulator.NONE, MonthGrid.parsePair("none,0.09")[0]);
        assertThrows(IllegalArgumentException.class, () -> MonthGrid.parsePair("0.05"));
    }

    @Test
    void runsAMonthEndToEnd() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MonthGrid grid = new MonthGrid(client, new PrintStream(out, true, StandardCharsets.UTF_8));

        Grid.Result r = grid.run(new MonthGrid.Options("RIVN", YearMonth.of(2026, 10), Session.EXTENDED, 6, 0,
                List.of(0.08, 0.15), List.of(0.12, 0.40)));

        assertEquals(12, r.signals());
        assertEquals(2000, r.sizing().fixedShares());          // RIVN's configured size
        assertEquals(2480.0, r.pnl()[0][0], 1e-6);
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("RIVN 2026-10: 1 trading days (2026-10-16 to 2026-10-16), 12 signals"), text);
        assertTrue(text.contains("recommendation: stop "), text);
    }

    @Test
    void runsARangeOfMonths() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MonthGrid grid = new MonthGrid(client, new PrintStream(out, true, StandardCharsets.UTF_8));

        // September has no bars in the mock, so it contributes no signals; October has its one day.
        MonthGrid.Outcome o = grid.runAny(new MonthGrid.Options("RIVN", YearMonth.of(2026, 9), YearMonth.of(2026, 10),
                Session.EXTENDED, 6, 0, List.of(0.08, 0.15), List.of(0.12, 0.40), List.of(new double[]{0.05, 0.25})));

        assertTrue(o.single() == null && o.multi() != null);
        Grid.MultiResult r = o.multi();
        assertEquals(List.of(YearMonth.of(2026, 9), YearMonth.of(2026, 10)), r.months());
        assertEquals(2480.0, r.total()[0][0], 1e-6);
        assertEquals(1, r.monthsPositive()[0][0]);
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("RIVN 2026-09 to 2026-10: 1 trading days"), text);
        assertTrue(text.contains("months positive (of 2)"), text);
        assertTrue(text.contains("detail: stop 0.05, target 0.25, 2000 shares"), text);
        assertEquals(1, r.shown().size());

        // The same range sized by risk: 380 / 0.05 = 7,600 shares on the 5 cent row.
        out.reset();
        MonthGrid.Outcome byRisk = grid.runAny(new MonthGrid.Options("RIVN", YearMonth.of(2026, 9), YearMonth.of(2026, 10),
                Session.EXTENDED, com.hamilla.sma.SignalWindow.DEFAULT, 6, 0, 380, List.of(0.05, 0.08), List.of(0.12, 0.40), List.of()));
        assertEquals(7600, byRisk.multi().shares(0.05));
        assertEquals(4750, byRisk.multi().shares(0.08));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("380 risked per trade"), out.toString(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> new MonthGrid.Options("RIVN", YearMonth.of(2026, 10),
                YearMonth.of(2026, 9), Session.EXTENDED, 6, 0, null, null));
    }

    private static void reply(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
