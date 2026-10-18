package com.hamilla.market;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Something that produces a stream of prices in time order. The replay source and the
 * live source both implement this, so the engine does not know which one it is fed by.
 */
public interface PriceSource {

    /** Pushes every event to {@code sink}, in timestamp order, and returns when the source is exhausted. */
    void stream(Consumer<PriceEvent> sink) throws IOException, InterruptedException;
}
