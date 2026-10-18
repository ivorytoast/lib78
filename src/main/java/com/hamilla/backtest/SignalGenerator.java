package com.hamilla.backtest;

import com.hamilla.market.MinuteBar;
import com.hamilla.market.PriceEvent;
import com.hamilla.sma.Action;
import com.hamilla.sma.Cooldown;
import com.hamilla.sma.MinuteBarTracker;
import com.hamilla.sma.MovingAverages;
import com.hamilla.sma.Session;
import com.hamilla.sma.SignalWindow;
import com.hamilla.sma.SmaSnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Produces the signals the replay would print, from one-minute bars instead of trades. The engine's
 * bar closes are exactly Massive's minute aggregates (both apply the same trade-condition rules), so
 * feeding each bar's close through the same tracker, zone, entry and cooldown logic yields the same
 * signals a trade-by-trade replay does, in a fraction of the time.
 */
public final class SignalGenerator {

    private final Session session;
    private final int cooldownMinutes;

    public SignalGenerator(Session session, int cooldownMinutes) {
        this.session = session;
        this.cooldownMinutes = cooldownMinutes;
    }

    /**
     * @param bars  bars sorted by start, including enough history before the first wanted day for
     *              the 200 bar window (a week is plenty)
     * @param dates the days whose signals are wanted
     */
    public List<TradeSignal> generate(String symbol, List<MinuteBar> bars, Set<LocalDate> dates) {
        List<TradeSignal> signals = new ArrayList<>();
        MovingAverages engine = new MovingAverages(symbol, List.of());
        Cooldown cooldown = new Cooldown(cooldownMinutes);
        LocalDate[] current = {null};

        MinuteBarTracker tracker = new MinuteBarTracker(engine, session, false, (barStartMs, close, atClose, empty) -> {
            var et = Instant.ofEpochMilli(barStartMs).atZone(PriceEvent.EASTERN);
            LocalDate day = et.toLocalDate();
            if (!day.equals(current[0])) {
                current[0] = day;
                cooldown.reset();
            }
            if (!SignalWindow.contains(barStartMs) || !dates.contains(day)) {
                return;
            }
            Optional<Action> action = Action.of(atClose);
            if (action.isPresent() && cooldown.take(barStartMs)) {
                signals.add(new TradeSignal(barStartMs, action.get(), atClose.price()));
            }
        });

        for (MinuteBar bar : bars) {
            // One synthetic trade per bar, at its close, stamped at the bar's last millisecond.
            long nanos = (bar.startMs() + Session.MINUTE_MS - 1) * 1_000_000L;
            tracker.onTrade(new PriceEvent(symbol, nanos, bar.close(), bar.volume()));
        }
        tracker.finishSession();
        return signals;
    }

    /** Exposed for tests: the averages the generator computes are the engine's. */
    static SmaSnapshot snapshot(MovingAverages engine, long nanos, double price) {
        return engine.snapshotAt(nanos, price);
    }
}
