package com.hamilla.query;

import com.hamilla.massive.MassiveClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointInTimeTest {

    /** What Massive returned for SPY trades at or before 2026-10-16 12:12:00 ET, newest first, limit 1. */
    private static final String TRADE_AT_1211 = """
            {"results":[{"conditions":[37],"exchange":4,"id":"71701479247169","participant_timestamp":1792167119760610000,
              "price":767.28,"sequence_number":2091897,"sip_timestamp":1792167119761309656,"size":20,"tape":2}]}
            """;

    private HttpServer server;
    private MassiveClient client;
    private String lastTradesQuery;
    private boolean noTrades;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/aggs/ticker/SPY/range/1/day/", ex -> {
            String[] parts = ex.getRequestURI().getPath().split("/");
            reply(ex, "{\"results\":[" + fixtureBars(LocalDate.parse(parts[parts.length - 2]),
                    LocalDate.parse(parts[parts.length - 1])) + "]}");
        });
        server.createContext("/v3/trades/SPY", ex -> {
            lastTradesQuery = ex.getRequestURI().getQuery();
            reply(ex, noTrades ? "{\"results\":[]}" : TRADE_AT_1211);
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
        // 12:12 EDT is 16:12 UTC
        assertEquals(1792167120L, t.toEpochSecond());

        assertThrows(IllegalArgumentException.class, () -> PointInTime.parse("2026-10-16 12:12"));
        assertThrows(IllegalArgumentException.class, () -> PointInTime.parse("10/16/2026"));
    }

    @Test
    void returnsLastTradeAndAveragesAtThatMoment() throws Exception {
        PointInTime.Answer a = new PointInTime(client).lookup("SPY", PointInTime.parse("10/16/2026-12:12"));

        assertTrue(lastTradesQuery.contains("timestamp.gte=2026-10-16"), lastTradesQuery);
        assertTrue(lastTradesQuery.contains("timestamp.lte=1792167120000000000"), lastTradesQuery);
        assertTrue(lastTradesQuery.contains("order=desc"), lastTradesQuery);
        assertTrue(lastTradesQuery.contains("limit=1"), lastTradesQuery);

        assertEquals(767.28, a.trade().price());
        assertEquals(1792167119761309656L, a.trade().sipTimestampNanos());

        // Expected values straight from the fixture: last N-1 closes before 10-16 plus the trade price.
        List<Double> prior = fixtureClosesBefore(LocalDate.of(2026, 10, 16));
        assertEquals(mean(prior, 19, 767.28), a.snapshot().sma20(), 1e-9);
        assertEquals(mean(prior, 49, 767.28), a.snapshot().sma50(), 1e-9);
        assertEquals(mean(prior, 199, 767.28), a.snapshot().sma200(), 1e-9);

        String text = PointInTime.format(a);
        String[] lines = text.split("\\R");
        assertEquals("SPY 10/16/2026-12:12 ET (last trade 12:11:59.761, " + prior.size() + " prior closes)", lines[0]);
        assertEquals("Price: 767.28", lines[1]);
        assertEquals(String.format("20: %.2f", a.snapshot().sma20()), lines[2]);
        assertEquals(String.format("50: %.2f", a.snapshot().sma50()), lines[3]);
        assertEquals(String.format("200: %.2f", a.snapshot().sma200()), lines[4]);
        assertEquals(5, lines.length);
    }

    @Test
    void noTradeYetIsAnError() {
        noTrades = true;
        IOException e = assertThrows(IOException.class,
                () -> new PointInTime(client).lookup("SPY", PointInTime.parse("10/10/2026-12:12")));
        assertTrue(e.getMessage().contains("no SPY trade on 2026-10-10"), e.getMessage());
    }

    private static double mean(List<Double> prior, int lastN, double price) {
        double sum = price;
        for (int i = prior.size() - lastN; i < prior.size(); i++) {
            sum += prior.get(i);
        }
        return sum / (lastN + 1);
    }

    private static void reply(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static List<String[]> fixtureRows() {
        try (var in = PointInTimeTest.class.getResourceAsStream("/spy-daily.csv");
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines().skip(1).map(l -> l.split(",")).toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LocalDate dateOf(String[] row) {
        return java.time.Instant.ofEpochMilli(Long.parseLong(row[6]))
                .atZone(com.hamilla.market.PriceEvent.EASTERN).toLocalDate();
    }

    private static String fixtureBars(LocalDate from, LocalDate to) {
        List<String> objs = new ArrayList<>();
        for (String[] f : fixtureRows()) {
            LocalDate d = dateOf(f);
            if (!d.isBefore(from) && !d.isAfter(to)) {
                objs.add("{\"o\":" + f[2] + ",\"c\":" + f[3] + ",\"h\":" + f[4] + ",\"l\":" + f[5] + ",\"t\":" + f[6] + "}");
            }
        }
        return String.join(",", objs);
    }

    private static List<Double> fixtureClosesBefore(LocalDate date) {
        List<Double> closes = new ArrayList<>();
        for (String[] f : fixtureRows()) {
            if (dateOf(f).isBefore(date)) {
                closes.add(Double.parseDouble(f[3]));
            }
        }
        return closes;
    }
}
