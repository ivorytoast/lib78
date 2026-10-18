package com.hamilla.massive;

import com.hamilla.market.DailyBar;
import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MassiveJsonTest {

    /** Trimmed from a real /v3/trades/RIVN response for the 12:11 bar on 2026-10-16. */
    private static final String TRADES = """
            {"results":[
              {"exchange":4,"id":"16465","participant_timestamp":1792167060512281000,"price":12.69,"sequence_number":5983730,
               "sip_timestamp":1792167060512645135,"size":100,"tape":3},
              {"conditions":[10,37,41],"exchange":4,"id":"16466","price":12.6843,"sip_timestamp":1792167062242389118,"size":34,"tape":3}
            ],"status":"OK","request_id":"abc","next_url":"https://api.massive.com/v3/trades/RIVN?cursor=XYZ"}
            """;

    private static final String DAILY = """
            {"ticker":"SPY","results":[
              {"v":43261639.865002,"vw":769.7449,"o":766.25,"c":771.79,"h":772.87,"l":764.43,"t":1792036800000,"n":654381},
              {"v":64384579.969755,"vw":766.2497,"o":773.24,"c":763.25,"h":773.25,"l":760.64,"t":1792123200000,"n":740060}
            ],"status":"OK"}
            """;

    /** Trimmed from a real /v2/aggs/ticker/RIVN/range/1/minute response. */
    private static final String MINUTE = """
            {"ticker":"RIVN","results":[
              {"v":28799.960789,"vw":12.6854,"o":12.6801,"c":12.685,"h":12.69,"l":12.68,"t":1792167000000,"n":133},
              {"v":34720.245624,"vw":12.6887,"o":12.69,"c":12.69,"h":12.695,"l":12.685,"t":1792167060000,"n":189}
            ],"status":"OK"}
            """;

    /** Trimmed from a real /v1/indicators/sma/RIVN?timespan=minute&window=20 response. */
    private static final String SMA = """
            {"results":{"underlying":{"url":"https://api.massive.com/v2/aggs/ticker/RIVN/range/1/minute/1792162620000/1792167060000"},
             "values":[{"timestamp":1792167000000,"value":12.710739999999998},{"timestamp":1792167060000,"value":12.708989999999998}]},
             "status":"OK","next_url":"https://api.massive.com/v1/indicators/sma/RIVN?cursor=abc"}
            """;

    @Test
    void parsesTradesWithConditionsAndNextUrl() {
        var json = MassiveJson.parse(TRADES);
        List<PriceEvent> events = MassiveJson.trades("RIVN", json);

        assertEquals(2, events.size());
        PriceEvent plain = events.get(0);
        assertEquals(1792167060512645135L, plain.sipTimestampNanos());
        assertEquals(12.69, plain.price());
        assertEquals(100.0, plain.size());
        assertTrue(plain.conditions().isEmpty());
        assertTrue(plain.updatesClose());

        PriceEvent oddLot = events.get(1);
        assertEquals(List.of(10, 37, 41), oddLot.conditions());
        assertFalse(oddLot.updatesClose());
        assertEquals("https://api.massive.com/v3/trades/RIVN?cursor=XYZ", MassiveJson.nextUrl(json).orElseThrow());
    }

    @Test
    void parsesDailyBarsIntoEasternDates() {
        List<DailyBar> bars = MassiveJson.dailyBars(MassiveJson.parse(DAILY));
        assertEquals(LocalDate.of(2026, 10, 15), bars.get(0).date());
        assertEquals(LocalDate.of(2026, 10, 16), bars.get(1).date());
        assertEquals(763.25, bars.get(1).close());
    }

    @Test
    void parsesMinuteBars() {
        List<MinuteBar> bars = MassiveJson.minuteBars(MassiveJson.parse(MINUTE));
        assertEquals(2, bars.size());
        assertEquals(1792167060000L, bars.get(1).startMs());
        assertEquals(12.69, bars.get(1).close());
        assertEquals(12.695, bars.get(1).high());
        assertEquals(34720.245624, bars.get(1).volume());
    }

    @Test
    void parsesSmaValuesKeyedByBar() {
        var json = MassiveJson.parse(SMA);
        Map<Long, Double> values = MassiveJson.smaValues(json);
        assertEquals(2, values.size());
        assertEquals(12.708989999999998, values.get(1792167060000L));
        assertEquals("https://api.massive.com/v1/indicators/sma/RIVN?cursor=abc", MassiveJson.nextUrl(json).orElseThrow());
    }

    @Test
    void emptyResultsAreTolerated() {
        var json = MassiveJson.parse("{\"status\":\"OK\",\"results\":[]}");
        assertTrue(MassiveJson.trades("SPY", json).isEmpty());
        assertTrue(MassiveJson.minuteBars(json).isEmpty());
        assertTrue(MassiveJson.nextUrl(json).isEmpty());

        var noResults = MassiveJson.parse("{\"status\":\"OK\"}");
        assertTrue(MassiveJson.trades("SPY", noResults).isEmpty());
        assertTrue(MassiveJson.smaValues(noResults).isEmpty());
    }
}
