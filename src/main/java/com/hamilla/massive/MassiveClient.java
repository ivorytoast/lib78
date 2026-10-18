package com.hamilla.massive;

import com.google.gson.JsonObject;
import com.hamilla.market.DailyBar;
import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Thin REST client for the Massive endpoints this project needs. Authenticates with a bearer
 * token so paginated {@code next_url} links can be followed verbatim.
 */
public final class MassiveClient {

    public static final String DEFAULT_BASE_URL = "https://api.massive.com";
    public static final String API_KEY_ENV = "MASSIVE_API_KEY";
    public static final String BASE_URL_ENV = "MASSIVE_BASE_URL";

    /** Massive's maximum page size for trades and aggregates. */
    static final int PAGE_SIZE = 50_000;
    /** Massive's maximum page size for indicator values. */
    static final int INDICATOR_PAGE_SIZE = 5_000;
    private static final int MAX_ATTEMPTS = 6;

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;

    public MassiveClient(HttpClient http, String baseUrl, String apiKey) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    /** Reads {@code MASSIVE_API_KEY} (required) and {@code MASSIVE_BASE_URL} (optional) from the environment. */
    public static MassiveClient fromEnvironment() {
        String key = System.getenv(API_KEY_ENV);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("set the " + API_KEY_ENV + " environment variable");
        }
        String base = System.getenv(BASE_URL_ENV);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new MassiveClient(http, base == null || base.isBlank() ? DEFAULT_BASE_URL : base, key);
    }

    /** Completed daily bars for {@code ticker} between the two dates inclusive, oldest first. */
    public List<DailyBar> dailyBars(String ticker, LocalDate from, LocalDate to) throws IOException, InterruptedException {
        String url = baseUrl + "/v2/aggs/ticker/" + ticker + "/range/1/day/" + from + "/" + to
                + "?adjusted=true&sort=asc&limit=" + PAGE_SIZE;
        return MassiveJson.dailyBars(get(url));
    }

    /**
     * One-minute bars for {@code ticker} between the two dates inclusive, oldest first, following
     * pagination. Covers the full 04:00 to 20:00 Eastern session; minutes without trades have no bar.
     */
    public List<MinuteBar> minuteBars(String ticker, LocalDate from, LocalDate to) throws IOException, InterruptedException {
        String url = baseUrl + "/v2/aggs/ticker/" + ticker + "/range/1/minute/" + from + "/" + to
                + "?adjusted=true&sort=asc&limit=" + PAGE_SIZE;
        List<MinuteBar> bars = new ArrayList<>();
        while (url != null) {
            JsonObject page = get(url);
            bars.addAll(MassiveJson.minuteBars(page));
            url = MassiveJson.nextUrl(page).orElse(null);
        }
        return bars;
    }

    /**
     * Streams every trade for {@code ticker} on {@code date}, oldest first, following pagination.
     * A full day for a liquid name is several hundred thousand trades, so results are pushed
     * to {@code sink} page by page rather than collected.
     */
    public void trades(String ticker, LocalDate date, Consumer<PriceEvent> sink) throws IOException, InterruptedException {
        String url = baseUrl + "/v3/trades/" + ticker + "?timestamp=" + date
                + "&order=asc&sort=timestamp&limit=" + PAGE_SIZE;
        while (url != null) {
            JsonObject page = get(url);
            for (PriceEvent e : MassiveJson.trades(ticker, page)) {
                sink.accept(e);
            }
            url = MassiveJson.nextUrl(page).orElse(null);
        }
    }

    /**
     * The most recent trade for {@code ticker} on trading day {@code date} at or before {@code at}
     * that updates the close (so odd lots and similar prints are skipped). Empty when there was no
     * such trade yet: before 04:00 Eastern, or not a trading day.
     */
    public Optional<PriceEvent> lastUpdatingTradeAtOrBefore(String ticker, LocalDate date, Instant at)
            throws IOException, InterruptedException {
        long nanos = Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000_000L), at.getNano());
        String url = baseUrl + "/v3/trades/" + ticker + "?timestamp.gte=" + date + "&timestamp.lte=" + nanos
                + "&order=desc&sort=timestamp&limit=200";
        while (url != null) {
            JsonObject page = get(url);
            for (PriceEvent e : MassiveJson.trades(ticker, page)) {
                if (e.updatesClose()) {
                    return Optional.of(e);
                }
            }
            url = MassiveJson.nextUrl(page).orElse(null);
        }
        return Optional.empty();
    }

    /**
     * Massive's own {@code window}-bar SMA of one-minute closes for every bar on {@code date},
     * keyed by bar start in epoch milliseconds, oldest first. This is the oracle the replay checks against.
     */
    public TreeMap<Long, Double> minuteSma(String ticker, int window, LocalDate date) throws IOException, InterruptedException {
        long dayStart = date.atStartOfDay(PriceEvent.EASTERN).toInstant().toEpochMilli();
        long dayEnd = date.plusDays(1).atStartOfDay(PriceEvent.EASTERN).toInstant().toEpochMilli();
        String url = baseUrl + "/v1/indicators/sma/" + ticker + "?timespan=minute&adjusted=true&window=" + window
                + "&series_type=close&timestamp.gte=" + dayStart + "&timestamp.lt=" + dayEnd
                + "&order=asc&limit=" + INDICATOR_PAGE_SIZE;
        TreeMap<Long, Double> values = new TreeMap<>();
        while (url != null) {
            JsonObject page = get(url);
            values.putAll(MassiveJson.smaValues(page));
            url = MassiveJson.nextUrl(page).orElse(null);
        }
        return values;
    }

    private JsonObject get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                last = e;
                backoff(attempt);
                continue;
            }
            int status = response.statusCode();
            if (status == 200) {
                return MassiveJson.parse(response.body());
            }
            if (status == 429 || status >= 500) {
                last = new IOException("HTTP " + status + " from " + url + ": " + truncate(response.body()));
                backoff(attempt);
                continue;
            }
            throw new IOException("HTTP " + status + " from " + url + ": " + truncate(response.body()));
        }
        throw new IOException("giving up after " + MAX_ATTEMPTS + " attempts", last);
    }

    private static void backoff(int attempt) throws InterruptedException {
        Thread.sleep(Math.min(30_000L, 500L << (attempt - 1)));
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}
