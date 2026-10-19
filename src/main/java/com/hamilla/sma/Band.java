package com.hamilla.sma;

/**
 * The {@code --band} option: the entry band in dollars, or {@code auto} to scale it (and the zone gaps)
 * to each symbol's typical one-minute range. The default is the fixed two-cent band RIVN was tuned on.
 *
 * @param auto    scale to the symbol's typical minute range
 * @param dollars the band when not auto
 */
public record Band(boolean auto, double dollars) {

    public static final Band DEFAULT = new Band(false, Thresholds.DEFAULT.band());
    public static final Band AUTO = new Band(true, 0);

    public Band {
        if (!auto && !(dollars > 0)) {
            throw new IllegalArgumentException("band must be positive dollars or 'auto', got " + dollars);
        }
    }

    /** Parses {@code auto} or a dollar amount such as {@code 0.07}. */
    public static Band parse(String text) {
        String t = text.trim().toLowerCase();
        if (t.equals("auto")) {
            return AUTO;
        }
        try {
            return new Band(false, Double.parseDouble(t));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("band must be dollars or 'auto', got '" + text + "'", e);
        }
    }

    /** The thresholds for a symbol whose typical minute range is {@code typicalRange} (NaN if unknown). */
    public Thresholds thresholdsFor(double typicalRange) {
        return auto ? Thresholds.fromRange(typicalRange) : Thresholds.scaledTo(dollars);
    }

    public boolean isDefault() {
        return equals(DEFAULT);
    }

    @Override
    public String toString() {
        return auto ? "auto" : String.format("%.3f", dollars);
    }
}
