package com.hamilla.sma;

/**
 * How close is "at the 20" and how far apart the averages must be for a zone, in dollars. The original
 * values ({@link #DEFAULT}) were set on RIVN, a stock that moves about five cents a minute; on a stock
 * that moves twenty cents a minute the same cents are four times as strict. {@link #fromRange} scales
 * all three to a symbol's typical one-minute range in the proportions RIVN had, so every symbol is
 * judged by its own noise.
 *
 * @param band     how far from the 20 the price may close for an entry
 * @param gap2050  how far the 50 must be from the 20 for a zone to exist
 * @param gap20200 how far the 200 must be from the 20 for a zone to exist
 */
public record Thresholds(double band, double gap2050, double gap20200) {

    /** RIVN's values: a two-cent band, the 50 three cents and the 200 six cents from the 20. */
    public static final Thresholds DEFAULT = new Thresholds(0.02, 0.03, 0.06);
    /** The band as a fraction of the typical minute range: RIVN's two cents on a five-cent range. */
    public static final double BAND_PER_RANGE = 0.4;

    public Thresholds {
        if (!(band > 0) || !(gap2050 > 0) || !(gap20200 > 0)) {
            throw new IllegalArgumentException("thresholds must be positive, got " + band + "/" + gap2050 + "/" + gap20200);
        }
    }

    /** Thresholds with this band and the gaps in the default proportion: 1.5 and 3 times the band. */
    public static Thresholds scaledTo(double band) {
        return new Thresholds(band, band * 1.5, band * 3);
    }

    /** Thresholds for a symbol whose average regular-session minute spans {@code typicalRange} dollars. */
    public static Thresholds fromRange(double typicalRange) {
        if (!(typicalRange > 0)) {
            return DEFAULT;
        }
        return scaledTo(Math.max(0.01, Math.round(typicalRange * BAND_PER_RANGE * 1000) / 1000.0));
    }

    public boolean isDefault() {
        return equals(DEFAULT);
    }

    @Override
    public String toString() {
        return String.format("band %.3f, gaps %.3f/%.3f", band, gap2050, gap20200);
    }
}
