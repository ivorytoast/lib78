# lib78

Tracks the 20, 50 and 200 day simple moving averages of US equities and ETFs
trade by trade, first by replaying a past day and later from the live feed.
Market data comes from [Massive](https://massive.com).

## How the averages are computed

An N day SMA during the session is the average of the last N-1 completed daily
closes and the current price. The engine loads those N-1 closes once, keeps
their sum, and recomputes all three averages on every trade with one addition
and one division each. Replay and live mode feed the same engine, so a replayed
day reproduces what the live run would have shown.

## Build

Requires Java 21 and Maven. Gson is the only runtime dependency.

```
mvn package
```

produces `target/lib78.jar` with all dependencies included.

## Stage one: replay a past day

```
export MASSIVE_API_KEY=...
java -jar target/lib78.jar replay SPY 2026-10-16
java -jar target/lib78.jar replay SPY,QQQ,IWM --regular-hours --print-every 15 --csv out.csv
```

With no date the most recent completed trading day is replayed. The run

1. loads about 200 trading days of daily closes before the replay date,
2. streams every trade of the day in time order (04:00 to 20:00 Eastern unless
   `--regular-hours` is set) and updates the three averages per trade,
3. prints a console line every `--print-every` minutes and, with `--csv`,
   writes every trade and its SMA values to a file,
4. checks the engine's value at the official close against Massive's own
   `/v1/indicators/sma` endpoint for the same day and prints PASS or FAIL.

The exit code is 0 when all three oracle checks pass.

## Tests

```
mvn test
```

The engine is tested against real SPY data: the daily closes in
`src/test/resources/spy-daily.csv` and the SMA values Massive reported for
2026-10-16. The HTTP client is tested against a local server for pagination,
authentication and retry behaviour.
