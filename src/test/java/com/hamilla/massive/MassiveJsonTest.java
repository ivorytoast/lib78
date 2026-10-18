package com.hamilla.massive;

import com.hamilla.market.DailyBar;
import com.hamilla.market.PriceEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MassiveJsonTest {

    /** Trimmed from a real /v3/trades/SPY?timestamp=2026-10-16 response. */
    private static final String TRADES = """
            {"results":[
              {"conditions":[12,37],"exchange":8,"id":"52983525027891","participant_timestamp":1792137600000168437,
               "price":772.83,"sequence_number":3088,"sip_timestamp":1792137600013823361,"size":10,"tape":2},
              {"conditions":[12,37],"exchange":8,"id":"52983525027892","participant_timestamp":1792137600000168437,
               "price":772.2,"sequence_number":3089,"sip_timestamp":1792137600013868438,"size":24,"tape":2}
            ],"status":"OK","request_id":"abc","next_url":"https://api.massive.com/v3/trades/SPY?cursor=XYZ"}
            """;

    /** Trimmed from a real /v2/aggs/ticker/SPY/range/1/day response. */
    private static final String DAILY = """
            {"ticker":"SPY","queryCount":2,"resultsCount":2,"adjusted":true,"results":[
              {"v":43261639.865002,"vw":769.7449,"o":766.25,"c":771.79,"h":772.87,"l":764.43,"t":1792036800000,"n":654381},
              {"v":64384579.969755,"vw":766.2497,"o":773.24,"c":763.25,"h":773.25,"l":760.64,"t":1792123200000,"n":740060}
            ],"status":"OK","request_id":"def"}
            """;

    /** Trimmed from a real /v1/indicators/sma/SPY?window=20&timestamp=2026-10-16&limit=1 response. */
    private static final String SMA = """
            {"results":{"underlying":{"url":"https://api.massive.com/v2/aggs/ticker/SPY/range/1/day/1783483200000/1792195200000?limit=93&sort=desc"},
             "values":[{"timestamp":1792123200000,"value":771.1840000000002}]},"status":"OK","request_id":"ghi"}
            """;

    @Test
    void parsesTradesWithSipTimestampsAndNextUrl() {
        var json = MassiveJson.parse(TRADES);
        List<PriceEvent> events = MassiveJson.trades("SPY", json);

        assertEquals(2, events.size());
        PriceEvent first = events.get(0);
        assertEquals("SPY", first.symbol());
        assertEquals(1792137600013823361L, first.sipTimestampNanos());
        assertEquals(772.83, first.price());
        assertEquals(10.0, first.size());
        assertEquals("04:00:00.013", first.easternTime().toLocalTime().toString().substring(0, 12));
        assertEquals(LocalDate.of(2026, 10, 16), first.easternTime().toLocalDate());
        assertEquals("https://api.massive.com/v3/trades/SPY?cursor=XYZ", MassiveJson.nextUrl(json).orElseThrow());
    }

    @Test
    void parsesDailyBarsIntoEasternDates() {
        List<DailyBar> bars = MassiveJson.dailyBars(MassiveJson.parse(DAILY));

        assertEquals(2, bars.size());
        assertEquals(LocalDate.of(2026, 10, 15), bars.get(0).date());
        assertEquals(LocalDate.of(2026, 10, 16), bars.get(1).date());
        assertEquals(763.25, bars.get(1).close());
        assertEquals(773.25, bars.get(1).high());
        assertTrue(MassiveJson.nextUrl(MassiveJson.parse(DAILY)).isEmpty());
    }

    @Test
    void parsesSmaValueAndItsDate() {
        var json = MassiveJson.parse(SMA);
        OptionalDouble value = MassiveJson.smaValue(json);

        assertEquals(771.1840000000002, value.orElseThrow());
        assertEquals(LocalDate.of(2026, 10, 16), MassiveJson.smaDate(json).orElseThrow());
    }

    @Test
    void emptyResultsAreTolerated() {
        var json = MassiveJson.parse("{\"status\":\"OK\",\"results\":[]}");
        assertTrue(MassiveJson.trades("SPY", json).isEmpty());
        assertTrue(MassiveJson.dailyBars(json).isEmpty());
        assertTrue(MassiveJson.nextUrl(json).isEmpty());

        var noResults = MassiveJson.parse("{\"status\":\"OK\"}");
        assertTrue(MassiveJson.trades("SPY", noResults).isEmpty());
        assertTrue(MassiveJson.smaValue(noResults).isEmpty());
        assertTrue(MassiveJson.smaDate(noResults).isEmpty());
    }
}
