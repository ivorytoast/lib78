package com.hamilla.sma;

/**
 * After a signal is taken, holds back further signals until a number of minutes has passed.
 * Measured in bar time, so replay and live behave identically. Reset at the start of each day.
 */
public final class Cooldown {

    private final int minutes;
    private long lastTakenMs = Long.MIN_VALUE;

    public Cooldown(int minutes) {
        if (minutes < 0) {
            throw new IllegalArgumentException("cooldown must be 0 or more minutes, got " + minutes);
        }
        this.minutes = minutes;
    }

    public int minutes() {
        return minutes;
    }

    /** True if a signal at {@code barStartMs} may be taken; if so, records it as taken. */
    public boolean take(long barStartMs) {
        if (lastTakenMs != Long.MIN_VALUE && barStartMs - lastTakenMs < minutes * Session.MINUTE_MS) {
            return false;
        }
        lastTakenMs = barStartMs;
        return true;
    }

    public void reset() {
        lastTakenMs = Long.MIN_VALUE;
    }
}
