package com.hamilla.massive;

import com.hamilla.market.DailyBar;
import com.hamilla.market.PriceEvent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the client against a local HTTP server to cover pagination, auth headers and retries. */
class MassiveClientTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> authHeaders = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private MassiveClient client() {
        return new MassiveClient(HttpClient.newHttpClient(), baseUrl, "test-key");
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    @Test
    void tradesFollowsNextUrlAcrossPagesInOrder() throws Exception {
        // Page one lives at the real path; page two is wherever next_url points, as Massive does it.
        respond("/v3/trades/SPY", 200, """
                {"results":[
                  {"sip_timestamp":1000,"price":1.0,"size":1},
                  {"sip_timestamp":2000,"price":2.0,"size":1}
                ],"next_url":"%s/page2"}
                """.formatted(baseUrl));
        respond("/page2", 200, """
                {"results":[{"sip_timestamp":3000,"price":3.0,"size":5}]}
                """);

        List<PriceEvent> got = new ArrayList<>();
        client().trades("SPY", LocalDate.of(2026, 10, 16), got::add);

        assertEquals(List.of(1000L, 2000L, 3000L), got.stream().map(PriceEvent::sipTimestampNanos).toList());
        assertEquals(5.0, got.get(2).size());
        assertEquals(2, authHeaders.size());
        assertTrue(authHeaders.stream().allMatch("Bearer test-key"::equals), "every request must carry the key");
    }

    @Test
    void lastTradeAtOrBeforeAsksForOneNewestTradeUpToTheInstant() throws Exception {
        List<String> queries = new ArrayList<>();
        server.createContext("/v3/trades/QQQ", exchange -> {
            queries.add(exchange.getRequestURI().getQuery());
            byte[] bytes = "{\"results\":[{\"sip_timestamp\":1792167119761309656,\"price\":600.5,\"size\":7}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        var at = java.time.Instant.ofEpochSecond(1792167120L, 5);
        var trade = client().lastTradeAtOrBefore("QQQ", LocalDate.of(2026, 10, 16), at).orElseThrow();

        assertEquals(600.5, trade.price());
        assertEquals("QQQ", trade.symbol());
        assertEquals("timestamp.gte=2026-10-16&timestamp.lte=1792167120000000005&order=desc&sort=timestamp&limit=1",
                queries.get(0));
    }

    @Test
    void lastTradeAtOrBeforeIsEmptyWhenNothingTraded() throws Exception {
        respond("/v3/trades/IWM", 200, "{\"results\":[],\"status\":\"OK\"}");
        assertTrue(client().lastTradeAtOrBefore("IWM", LocalDate.of(2026, 10, 10), java.time.Instant.EPOCH).isEmpty());
    }

    @Test
    void dailyBarsParsesRange() throws Exception {
        respond("/v2/aggs/ticker/SPY/range/1/day/2026-10-15/2026-10-16", 200, """
                {"results":[
                  {"o":766.25,"c":771.79,"h":772.87,"l":764.43,"t":1792036800000,"v":1},
                  {"o":773.24,"c":763.25,"h":773.25,"l":760.64,"t":1792123200000,"v":2}
                ]}
                """);
        List<DailyBar> bars = client().dailyBars("SPY", LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));
        assertEquals(2, bars.size());
        assertEquals(LocalDate.of(2026, 10, 16), bars.get(1).date());
    }

    @Test
    void smaReturnsEmptyWhenMassiveAnswersForADifferentDay() throws Exception {
        // Asking for a Saturday: Massive returns the latest value at or before it, which is Friday's.
        respond("/v1/indicators/sma/SPY", 200, """
                {"results":{"values":[{"timestamp":1792123200000,"value":771.184}]}}
                """);
        OptionalDouble friday = client().sma("SPY", 20, LocalDate.of(2026, 10, 16));
        OptionalDouble saturday = client().sma("SPY", 20, LocalDate.of(2026, 10, 17));
        assertEquals(771.184, friday.orElseThrow());
        assertTrue(saturday.isEmpty());
    }

    @Test
    void retriesOnRateLimitThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v2/aggs/ticker/QQQ/range/1/day/2026-10-16/2026-10-16", exchange -> {
            int n = calls.incrementAndGet();
            byte[] bytes = (n == 1 ? "{\"error\":\"slow down\"}"
                    : "{\"results\":[{\"o\":1,\"c\":2,\"h\":3,\"l\":0.5,\"t\":1792123200000}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(n == 1 ? 429 : 200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        List<DailyBar> bars = client().dailyBars("QQQ", LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 16));
        assertEquals(2, calls.get());
        assertEquals(2.0, bars.get(0).close());
    }

    @Test
    void clientErrorsAreNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v2/aggs/ticker/BAD/range/1/day/2026-10-16/2026-10-16", exchange -> {
            calls.incrementAndGet();
            byte[] bytes = "{\"status\":\"NOT_AUTHORIZED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> client().dailyBars("BAD", LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 16)));
        assertTrue(e.getMessage().contains("HTTP 403"));
        assertEquals(1, calls.get());
    }
}
