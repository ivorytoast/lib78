package com.hamilla.sma;

import com.hamilla.market.MinuteBar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Real RIVN one-minute bars from Massive's aggregates endpoint for 2026-10-16: the last 200 bars at or
 * before the 12:11 bar (08:02 to 12:11 Eastern, with gaps where no minute bar exists). Massive's minute
 * SMA endpoint reported these values for the 12:11 bar, computed on the gap-filled series.
 */
public final class Fixture {

    public static final long BAR_1211 = 1792167060000L;
    public static final double MASSIVE_SMA20_AT_1211 = 12.708989999999998;
    public static final double MASSIVE_SMA50_AT_1211 = 12.750417999999993;
    public static final double MASSIVE_SMA200_AT_1211 = 12.936832499999959;
    /** The 12:11 bar's close: the last close-updating trade in it, at 12:11:56.830. Odd lots at 12.685 followed. */
    public static final double CLOSE_1211 = 12.69;

    private Fixture() {
    }

    public static List<MinuteBar> rivnBars() {
        List<MinuteBar> bars = new ArrayList<>();
        try (var in = Fixture.class.getResourceAsStream("/rivn-minute-2026-10-16.csv");
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (!"t,c".equals(header)) {
                throw new IllegalStateException("unexpected header " + header);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split(",");
                double c = Double.parseDouble(f[1]);
                bars.add(new MinuteBar(Long.parseLong(f[0]), c, c, c, c, 0));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        if (bars.size() != 200 || bars.get(199).startMs() != BAR_1211 || bars.get(199).close() != CLOSE_1211) {
            throw new IllegalStateException("fixture changed");
        }
        return bars;
    }

    /** Bars strictly before the 12:11 bar. */
    public static List<MinuteBar> rivnBarsBefore1211() {
        return rivnBars().subList(0, 199);
    }
}
