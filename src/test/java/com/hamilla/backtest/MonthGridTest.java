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
    }

    @Test
    void runsAMonthEndToEnd() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MonthGrid grid = new MonthGrid(client, new PrintStream(out, true, StandardCharsets.UTF_8));

        Grid.Result r = grid.run(new MonthGrid.Options("RIVN", YearMonth.of(2026, 10), Session.EXTENDED, 6, 0,
                List.of(0.08, 0.15), List.of(0.12, 0.40)));

        assertEquals(20, r.signals());
        assertEquals(2000, r.shares());                       // RIVN's configured size
        assertEquals(3856.20, r.pnl()[0][0], 1e-6);
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("RIVN 2026-10: 1 trading days (2026-10-16 to 2026-10-16), 20 signals"), text);
        assertTrue(text.contains("recommendation: stop "), text);
    }

    private static void reply(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
