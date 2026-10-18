package com.hamilla.replay;

import com.hamilla.market.PriceEvent;
import com.hamilla.market.PriceSource;
import com.hamilla.massive.MassiveClient;

import java.io.IOException;
import java.time.LocalDate;
import java.util.function.Consumer;

/** Replays one symbol's historical trades for one day as a {@link PriceSource}. */
public final class ReplaySource implements PriceSource {

    private final MassiveClient client;
    private final String symbol;
    private final LocalDate date;

    public ReplaySource(MassiveClient client, String symbol, LocalDate date) {
        this.client = client;
        this.symbol = symbol;
        this.date = date;
    }

    @Override
    public void stream(Consumer<PriceEvent> sink) throws IOException, InterruptedException {
        client.trades(symbol, date, sink);
    }
}
