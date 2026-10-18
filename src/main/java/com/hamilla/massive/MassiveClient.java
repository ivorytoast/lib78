package com.hamilla.massive;

import com.google.gson.JsonObject;
import com.hamilla.market.DailyBar;
import com.hamilla.market.PriceEvent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;

/**
 * Thin REST client for the Massive endpoints this project needs. Authenticates with a bearer
 * token so paginated {@code next_url} links can be followed verbatim.
 */
public final class MassiveClient {

    public static final String DEFAULT_BASE_URL = "https://api.massive.com";
    public static final String API_KEY_ENV = "MASSIVE_API_KEY";
    public static final String BASE_URL_ENV = "MASSIVE_BASE_URL";

    /** Massive's maximum page size for trades. */
    static final int TRADES_PAGE_SIZE = 50_000;
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

    /** Completed daily bars for {@code ticker} between the two dates inclusive, oldest first, as Massive adjusts them. */
    public List<DailyBar> dailyBars(String ticker, LocalDate from, LocalDate to) throws IOException, InterruptedException {
        String url = baseUrl + "/v2/aggs/ticker/" + ticker + "/range/1/day/" + from + "/" + to
                + "?adjusted=true&sort=asc&limit=50000";
        return MassiveJson.dailyBars(get(url));
    }

    /**
     * Streams every trade for {@code ticker} on {@code date}, oldest first, following pagination.
     * A full day for a liquid name is several hundred thousand trades, so results are pushed
     * to {@code sink} page by page rather than collected.
     */
    public void trades(String ticker, LocalDate date, Consumer<PriceEvent> sink) throws IOException, InterruptedException {
        String url = baseUrl + "/v3/trades/" + ticker + "?timestamp=" + date
                + "&order=asc&sort=timestamp&limit=" + TRADES_PAGE_SIZE;
        while (url != null) {
            JsonObject page = get(url);
            for (PriceEvent e : MassiveJson.trades(ticker, page)) {
                sink.accept(e);
            }
            url = MassiveJson.nextUrl(page).orElse(null);
        }
    }

    /**
     * Massive's own daily SMA for {@code ticker} on {@code date}, computed from completed daily closes.
     * Empty if Massive has no value for exactly that date.
     */
    public OptionalDouble sma(String ticker, int window, LocalDate date) throws IOException, InterruptedException {
        String url = baseUrl + "/v1/indicators/sma/" + ticker + "?timespan=day&adjusted=true&window=" + window
                + "&series_type=close&timestamp=" + date + "&order=desc&limit=1";
        JsonObject response = get(url);
        Optional<LocalDate> valueDate = MassiveJson.smaDate(response);
        if (valueDate.isEmpty() || !valueDate.get().equals(date)) {
            return OptionalDouble.empty();
        }
        return MassiveJson.smaValue(response);
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
