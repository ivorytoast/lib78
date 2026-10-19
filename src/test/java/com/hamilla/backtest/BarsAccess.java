package com.hamilla.backtest;

/** Lets tests in other packages use the {@link Bars} fixture's clock. */
public final class BarsAccess {
    private BarsAccess() {
    }

    public static long at(String hhmm) {
        return Bars.at(hhmm);
    }
}
