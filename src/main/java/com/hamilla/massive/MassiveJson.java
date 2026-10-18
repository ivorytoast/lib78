package com.hamilla.massive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hamilla.market.DailyBar;
import com.hamilla.market.PriceEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/** Pure functions that turn Massive's JSON responses into our records. Kept separate so they are testable offline. */
public final class MassiveJson {

    private MassiveJson() {
    }

    public static JsonObject parse(String body) {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    /** {@code next_url} from a paginated response, if there is another page. */
    public static Optional<String> nextUrl(JsonObject response) {
        JsonElement next = response.get("next_url");
        if (next == null || next.isJsonNull()) {
            return Optional.empty();
        }
        return Optional.of(next.getAsString());
    }

    /** Results of {@code /v2/aggs/ticker/{t}/range/1/day/...}. The bar timestamp is midnight Eastern of the trading day. */
    public static List<DailyBar> dailyBars(JsonObject response) {
        List<DailyBar> bars = new ArrayList<>();
        for (JsonElement e : resultsArray(response)) {
            JsonObject o = e.getAsJsonObject();
            LocalDate date = Instant.ofEpochMilli(o.get("t").getAsLong())
                    .atZone(PriceEvent.EASTERN).toLocalDate();
            bars.add(new DailyBar(date,
                    o.get("o").getAsDouble(),
                    o.get("h").getAsDouble(),
                    o.get("l").getAsDouble(),
                    o.get("c").getAsDouble(),
                    o.has("v") ? o.get("v").getAsDouble() : 0.0));
        }
        return bars;
    }

    /** Results of {@code /v3/trades/{t}}. */
    public static List<PriceEvent> trades(String symbol, JsonObject response) {
        List<PriceEvent> events = new ArrayList<>();
        for (JsonElement e : resultsArray(response)) {
            JsonObject o = e.getAsJsonObject();
            events.add(new PriceEvent(symbol,
                    o.get("sip_timestamp").getAsLong(),
                    o.get("price").getAsDouble(),
                    o.has("size") ? o.get("size").getAsDouble() : 0.0));
        }
        return events;
    }

    /** The single value from {@code /v1/indicators/sma/{t}} when queried with limit=1. */
    public static OptionalDouble smaValue(JsonObject response) {
        JsonElement results = response.get("results");
        if (results == null || !results.isJsonObject()) {
            return OptionalDouble.empty();
        }
        JsonElement values = results.getAsJsonObject().get("values");
        if (values == null || !values.isJsonArray() || values.getAsJsonArray().isEmpty()) {
            return OptionalDouble.empty();
        }
        JsonObject first = values.getAsJsonArray().get(0).getAsJsonObject();
        return OptionalDouble.of(first.get("value").getAsDouble());
    }

    /** Timestamp (ms) of the aggregate the SMA value belongs to, so callers can confirm it is the day they asked for. */
    public static Optional<LocalDate> smaDate(JsonObject response) {
        JsonElement results = response.get("results");
        if (results == null || !results.isJsonObject()) {
            return Optional.empty();
        }
        JsonElement values = results.getAsJsonObject().get("values");
        if (values == null || !values.isJsonArray() || values.getAsJsonArray().isEmpty()) {
            return Optional.empty();
        }
        long ms = values.getAsJsonArray().get(0).getAsJsonObject().get("timestamp").getAsLong();
        return Optional.of(Instant.ofEpochMilli(ms).atZone(PriceEvent.EASTERN).toLocalDate());
    }

    private static JsonArray resultsArray(JsonObject response) {
        JsonElement results = response.get("results");
        if (results == null || results.isJsonNull()) {
            return new JsonArray();
        }
        return results.getAsJsonArray();
    }
}
