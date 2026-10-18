package com.hamilla.massive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hamilla.market.DailyBar;
import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** Results of {@code /v2/aggs/ticker/{t}/range/1/minute/...}. */
    public static List<MinuteBar> minuteBars(JsonObject response) {
        List<MinuteBar> bars = new ArrayList<>();
        for (JsonElement e : resultsArray(response)) {
            JsonObject o = e.getAsJsonObject();
            bars.add(new MinuteBar(o.get("t").getAsLong(),
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
            List<Integer> conditions = new ArrayList<>();
            JsonElement c = o.get("conditions");
            if (c != null && c.isJsonArray()) {
                for (JsonElement id : c.getAsJsonArray()) {
                    conditions.add(id.getAsInt());
                }
            }
            events.add(new PriceEvent(symbol,
                    o.get("sip_timestamp").getAsLong(),
                    o.get("price").getAsDouble(),
                    o.has("size") ? o.get("size").getAsDouble() : 0.0,
                    conditions));
        }
        return events;
    }

    /** All values from {@code /v1/indicators/sma/{t}}, keyed by the timestamp of the bar each belongs to, in response order. */
    public static Map<Long, Double> smaValues(JsonObject response) {
        Map<Long, Double> values = new LinkedHashMap<>();
        JsonElement results = response.get("results");
        if (results == null || !results.isJsonObject()) {
            return values;
        }
        JsonElement array = results.getAsJsonObject().get("values");
        if (array == null || !array.isJsonArray()) {
            return values;
        }
        for (JsonElement e : array.getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            values.put(o.get("timestamp").getAsLong(), o.get("value").getAsDouble());
        }
        return values;
    }

    private static JsonArray resultsArray(JsonObject response) {
        JsonElement results = response.get("results");
        if (results == null || results.isJsonNull()) {
            return new JsonArray();
        }
        return results.getAsJsonArray();
    }
}
