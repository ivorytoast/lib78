package com.hamilla.massive;

import com.hamilla.market.MinuteBar;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the client against a local HTTP server to cover pagination, auth headers and retries. */
class MassiveClientTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> authHeaders = new ArrayList<>();
    private final List<String> queries = new ArrayList<>();

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
            queries.add(exchange.getRequestURI().getQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    @Test
    void tradesFollowsNextUrlAcrossPagesInOrder() throws Exception {
        respond("/v3/trades/SPY", 200, """
                {"results":[
                  {"sip_timestamp":1000,"price":1.0,"size":1},
                  {"sip_timestamp":2000,"price":2.0,"size":1,"conditions":[37]}
                ],"next_url":"%s/page2"}
                """.formatted(baseUrl));
        respond("/page2", 200, """
                {"results":[{"sip_timestamp":3000,"price":3.0,"size":5}]}
                """);

        List<PriceEvent> got = new ArrayList<>();
        client().trades("SPY", LocalDate.of(2026, 10, 16), got::add);

        assertEquals(List.of(1000L, 2000L, 3000L), got.stream().map(PriceEvent::sipTimestampNanos).toList());
        assertEquals(List.of(37), got.get(1).conditions());
        assertEquals(2, authHeaders.size());
        assertTrue(authHeaders.stream().allMatch("Bearer test-key"::equals), "every request must carry the key");
    }

    @Test
    void minuteBarsFollowsPaginationAndAsksForTheWholeRange() throws Exception {
        respond("/v2/aggs/ticker/RIVN/range/1/minute/2026-10-09/2026-10-16", 200, """
                {"results":[{"o":1,"h":1,"l":1,"c":1.5,"t":1792137600000,"v":10}],"next_url":"%s/bars2"}
                """.formatted(baseUrl));
        respond("/bars2", 200, """
                {"results":[{"o":2,"h":2,"l":2,"c":2.5,"t":1792137660000}]}
                """);
        List<MinuteBar> bars = client().minuteBars("RIVN", LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 16));
        assertEquals(2, bars.size());
        assertEquals(2.5, bars.get(1).close());
        assertEquals("adjusted=true&sort=asc&limit=50000", queries.get(0));
    }

    @Test
    void lastUpdatingTradeSkipsOddLotsAcrossPages() throws Exception {
        respond("/v3/trades/RIVN", 200, """
                {"results":[
                  {"sip_timestamp":1792167119603049308,"price":12.685,"size":37,"conditions":[37]},
                  {"sip_timestamp":1792167118791007967,"price":12.6898,"size":7,"conditions":[37]}
                ],"next_url":"%s/older"}
                """.formatted(baseUrl));
        respond("/older", 200, """
                {"results":[{"sip_timestamp":1792167116830639117,"price":12.69,"size":400}]}
                """);
        var at = Instant.ofEpochSecond(1792167120L);
        PriceEvent trade = client().lastUpdatingTradeAtOrBefore("RIVN", LocalDate.of(2026, 10, 16), at).orElseThrow();

        assertEquals(12.69, trade.price());
        assertEquals(1792167116830639117L, trade.sipTimestampNanos());
        assertEquals("timestamp.gte=2026-10-16&timestamp.lte=1792167120000000000&order=desc&sort=timestamp&limit=200",
                queries.get(0));
    }

    @Test
    void lastUpdatingTradeIsEmptyWhenNothingQualifies() throws Exception {
        respond("/v3/trades/IWM", 200, "{\"results\":[{\"sip_timestamp\":5,\"price\":1,\"size\":1,\"conditions\":[37]}]}");
        assertTrue(client().lastUpdatingTradeAtOrBefore("IWM", LocalDate.of(2026, 10, 10), Instant.EPOCH).isEmpty());
    }

    @Test
    void minuteSmaCoversTheEasternDayAndFollowsPagination() throws Exception {
        respond("/v1/indicators/sma/RIVN", 200, """
                {"results":{"values":[{"timestamp":1792137600000,"value":13.1}]},"next_url":"%s/sma2"}
                """.formatted(baseUrl));
        respond("/sma2", 200, """
                {"results":{"values":[{"timestamp":1792137660000,"value":13.2}]}}
                """);
        TreeMap<Long, Double> values = client().minuteSma("RIVN", 20, LocalDate.of(2026, 10, 16));
        assertEquals(2, values.size());
        assertEquals(13.2, values.get(1792137660000L));
        // 2026-10-16 00:00 ET is 04:00 UTC = 1792123200000; the next midnight is 1792209600000.
        assertEquals("timespan=minute&adjusted=true&window=20&series_type=close&timestamp.gte=1792123200000"
                + "&timestamp.lt=1792209600000&order=asc&limit=5000", queries.get(0));
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
        var bars = client().dailyBars("QQQ", LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 16));
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
        IOException e = assertThrows(IOException.class,
                () -> client().dailyBars("BAD", LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 16)));
        assertTrue(e.getMessage().contains("HTTP 403"));
        assertEquals(1, calls.get());
    }
}
