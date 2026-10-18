package com.hamilla.query;

import com.hamilla.market.MinuteBar;
import com.hamilla.massive.MassiveClient;
import com.hamilla.sma.Fixture;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointInTimeTest {

    /** What Massive returned for RIVN trades at or before 2026-10-16 12:12:00 ET, newest first: odd lots, then the real close. */
    private static final String TRADES_BEFORE_1212 = """
            {"results":[
              {"conditions":[37],"price":12.685,"sip_timestamp":1792167119603049308,"size":37},
              {"conditions":[37],"price":12.6898,"sip_timestamp":1792167118791007967,"size":7},
              {"conditions":[10,37,41],"price":12.688622,"sip_timestamp":1792167118594927197,"size":26},
              {"price":12.69,"sip_timestamp":1792167116830639117,"size":400}
            ]}
            """;

    private HttpServer server;
    private MassiveClient client;
    private String lastTradesQuery;
    private boolean noTrades;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/aggs/ticker/RIVN/range/1/minute/", ex -> {
            String[] parts = ex.getRequestURI().getPath().split("/");
            assertEquals("2026-10-09", parts[parts.length - 2]);
            assertEquals("2026-10-16", parts[parts.length - 1]);
            reply(ex, "{\"results\":[" + barsJson(Fixture.rivnBars()) + "]}");
        });
        server.createContext("/v3/trades/RIVN", ex -> {
            lastTradesQuery = ex.getRequestURI().getQuery();
            reply(ex, noTrades ? "{\"results\":[]}" : TRADES_BEFORE_1212);
        });
        server.start();
        client = new MassiveClient(HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort(), "k");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void parsesTheRequestedFormatAsEasternTime() {
        ZonedDateTime t = PointInTime.parse("10/16/2026-12:12");
        assertEquals(LocalDate.of(2026, 10, 16), t.toLocalDate());
        assertEquals("12:12", t.toLocalTime().toString());
        assertEquals("America/New_York", t.getZone().getId());
        assertEquals(1792167120L, t.toEpochSecond()); // 12:12 EDT is 16:12 UTC

        assertThrows(IllegalArgumentException.class, () -> PointInTime.parse("2026-10-16 12:12"));
        assertThrows(IllegalArgumentException.class, () -> PointInTime.parse("10/16/2026"));
    }

    @Test
    void returnsLastUpdatingTradeAndMassivesAveragesAtThatMoment() throws Exception {
        PointInTime.Answer a = new PointInTime(client).lookup("RIVN", PointInTime.parse("10/16/2026-12:12"));

        assertTrue(lastTradesQuery.contains("timestamp.gte=2026-10-16"), lastTradesQuery);
        assertTrue(lastTradesQuery.contains("timestamp.lte=1792167120000000000"), lastTradesQuery);

        assertEquals(12.69, a.trade().price());
        assertEquals(1792167116830639117L, a.trade().sipTimestampNanos());
        assertEquals(200, a.barsLoaded());
        assertEquals(Fixture.MASSIVE_SMA20_AT_1211, a.snapshot().sma20(), 1e-9);
        assertEquals(Fixture.MASSIVE_SMA50_AT_1211, a.snapshot().sma50(), 1e-9);
        assertEquals(Fixture.MASSIVE_SMA200_AT_1211, a.snapshot().sma200(), 1e-9);

        String[] lines = PointInTime.format(a).split("\\R");
        assertEquals("RIVN 10/16/2026-12:12 ET (last trade 12:11:56.830, 200 prior bars)", lines[0]);
        assertEquals("Price: 12.69", lines[1]);
        assertEquals("20: 12.71", lines[2]);
        assertEquals("50: 12.75", lines[3]);
        assertEquals("200: 12.94", lines[4]);
        assertEquals(5, lines.length);
    }

    @Test
    void noTradeYetIsAnError() {
        noTrades = true;
        IOException e = assertThrows(IOException.class,
                () -> new PointInTime(client).lookup("RIVN", PointInTime.parse("10/10/2026-12:12")));
        assertTrue(e.getMessage().contains("no RIVN trade on 2026-10-10"), e.getMessage());
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
            objs.add(String.format(Locale.US, "{\"o\":%s,\"h\":%s,\"l\":%s,\"c\":%s,\"t\":%d}",
                    b.open(), b.high(), b.low(), b.close(), b.startMs()));
        }
        return String.join(",", objs);
    }
}
