package com.hamilla.live;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One symbol's stop and target distances in dollars, as chosen from its grid.
 *
 * @param stop   dollars against the entry at which the trade is stopped
 * @param target dollars in favor of the entry at which the trade is taken off
 */
public record Bracket(double stop, double target) {

    public Bracket {
        if (!(stop > 0) || !(target > 0)) {
            throw new IllegalArgumentException("stop and target must be positive, got " + stop + "/" + target);
        }
    }

    /** Parses {@code SYMBOL=stop/target}, e.g. {@code STLD=0.18/1.10}; the symbol is upper-cased. */
    public static Map.Entry<String, Bracket> parseEntry(String text) {
        String[] kv = text.trim().split("=");
        if (kv.length != 2 || kv[0].isBlank()) {
            throw new IllegalArgumentException("bracket must look like SYMBOL=stop/target, got '" + text + "'");
        }
        String[] st = kv[1].split("/");
        if (st.length != 2) {
            throw new IllegalArgumentException("bracket must look like SYMBOL=stop/target, got '" + text + "'");
        }
        try {
            return Map.entry(kv[0].trim().toUpperCase(), new Bracket(Double.parseDouble(st[0]), Double.parseDouble(st[1])));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bracket must look like SYMBOL=stop/target, got '" + text + "'", e);
        }
    }

    /** Parses a comma-separated list of {@code SYMBOL=stop/target} entries into an insertion-ordered map. */
    public static Map<String, Bracket> parseAll(String text) {
        Map<String, Bracket> out = new LinkedHashMap<>();
        for (String part : text.split(",")) {
            if (!part.isBlank()) {
                var e = parseEntry(part);
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return String.format("%.2f/%.2f", stop, target);
    }
}
