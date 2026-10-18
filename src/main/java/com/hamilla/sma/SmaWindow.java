package com.hamilla.sma;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * A simple moving average over {@code length} daily closes, where the most recent "close"
 * is whatever the price is right now. Holds the {@code length - 1} previous completed closes
 * and their sum, so the live value for any price is one addition and one division.
 */
public final class SmaWindow {

    private final int length;
    private final Deque<Double> previousCloses = new ArrayDeque<>();
    private double previousSum;

    /**
     * @param length       number of days in the average, e.g. 20
     * @param priorCloses  completed daily closes, oldest first. Only the last {@code length - 1} are kept.
     */
    public SmaWindow(int length, List<Double> priorCloses) {
        if (length < 1) {
            throw new IllegalArgumentException("length must be >= 1, got " + length);
        }
        this.length = length;
        int skip = Math.max(0, priorCloses.size() - (length - 1));
        for (double close : priorCloses.subList(skip, priorCloses.size())) {
            previousCloses.addLast(close);
        }
        recomputeSum();
    }

    public int length() {
        return length;
    }

    /** True once enough completed days are known to produce a full-length average. */
    public boolean ready() {
        return previousCloses.size() == length - 1;
    }

    /** The average as it stands if the current day were to close at {@code price}. NaN when not ready. */
    public double valueAt(double price) {
        if (!ready()) {
            return Double.NaN;
        }
        return (previousSum + price) / length;
    }

    /** Rolls the window forward one day: {@code close} becomes the newest completed close. */
    public void closeDay(double close) {
        previousCloses.addLast(close);
        while (previousCloses.size() > length - 1) {
            previousCloses.removeFirst();
        }
        recomputeSum();
    }

    private void recomputeSum() {
        double sum = 0.0;
        for (double c : previousCloses) {
            sum += c;
        }
        previousSum = sum;
    }
}
