package com.hamilla.backtest;

import com.hamilla.market.MinuteBar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Real RIVN one-minute bars from Massive's aggregates endpoint: 2026-10-15 from 16:00 Eastern (102 bars)
 * and all of 2026-10-16 (648 bars). Enough history for every 200 bar window on 10/16 from 09:45 on.
 */
final class Bars {

    static final long T_0400_1016 = 1792137600000L;

    private Bars() {
    }

    static List<MinuteBar> rivnOct15and16() {
        List<MinuteBar> bars = new ArrayList<>();
        try (var in = Bars.class.getResourceAsStream("/rivn-bars-2026-10-15-16.csv");
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (!"t,c,h,l".equals(header)) {
                throw new IllegalStateException("unexpected header " + header);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split(",");
                double c = Double.parseDouble(f[1]);
                bars.add(new MinuteBar(Long.parseLong(f[0]), c, Double.parseDouble(f[2]), Double.parseDouble(f[3]), c, 100));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        if (bars.size() != 750) {
            throw new IllegalStateException("fixture changed: " + bars.size());
        }
        return bars;
    }

    static long at(String hhmm) {
        return T_0400_1016 + (Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3)) - 240) * 60_000L;
    }
}
