# lib78

Tracks the 20, 50 and 200 period simple moving averages of one-minute bars for
US equities and ETFs, trade by trade, first by replaying a past day and later
from the live feed. Market data comes from [Massive](https://massive.com).

## How the averages are computed

Each average is the mean of the last N one-minute bar closes, where the bar now
forming closes at the latest price. The engine keeps the previous N-1 completed
closes and their sum, so every trade updates all three averages with one
addition and one division. When a trade arrives in a new minute, the previous
bar is closed at its last price and rolled into the windows.

The bars are built the way charting platforms build them, which was pinned
down against a chart and against Massive's own minute data:

- The session is 04:00 to 20:00 Eastern by default (`--session` changes it),
  so pre-market and after-hours bars count and the first bars of a day look
  back into the previous day. A minute with no trade has no bar.
- A trade only moves the close if its condition codes allow it. Odd lots,
  average-price prints and similar are ignored, which is why a bar's close can
  differ from the very last print in it. See `TradeConditions`.

Massive's minute SMA endpoint uses a slightly different series, with a bar for
every minute of the session repeating the previous close through empty
minutes. The replay's `--verify` mode runs the engine on that series and checks
it bar by bar against the endpoint, as a test of the engine itself.

Replay and live mode feed the same engine, so a replayed day reproduces what
the live run would have shown.

## Build

Requires Java 21 or newer and Maven. Gson is the only runtime dependency.

```
mvn package
```

produces `target/lib78.jar` with all dependencies included.

## Price and SMAs at one moment

```
export MASSIVE_API_KEY=...
java -jar target/lib78.jar at RIVN 10/16/2026-12:12
```

prints the last close-updating trade at or before that minute (Eastern time)
and the three averages as the engine stood on that trade (`--session` applies
here too):

```
RIVN 10/16/2026-12:12 ET (last trade 12:11:56.830, 200 prior bars)
Price: 12.69
20: 12.71
50: 12.75
200: 12.94
```

Several tickers can be given with commas. A time with no trade yet, such as
a weekend or before the 04:00 pre-market open, is reported as an error.

## Replay a past day

```
java -jar target/lib78.jar replay RIVN 2026-10-16
java -jar target/lib78.jar replay SPY,QQQ,IWM --verbose --verify --csv out.csv
```

With no date the most recent completed trading day is replayed. The run loads
the previous week of minute bars so the 200 bar window is full from the first
trade, then streams every trade of the day in time order. Directly after each
minute closes, from 09:45 through 15:59 Eastern, it classifies the minute:

| zone | meaning |
|---|---|
| up   | 200 below 50, 50 below 20 by at least 3 cents, and the price above the 20 |
| down | 200 above 50, 50 above 20 by at least 3 cents, and the price below the 20 |
| none | anything else, ties and a 20 and 50 closer than 3 cents included |

and prints the minute only when the zone gives an entry signal:

| printed | when |
|---|---|
| `BUY LONG`   | up zone and the price is at most 2 cents above the 20 |
| `SHORT SELL` | down zone and the price is at most 2 cents below the 20 |

```
10:02 SHORT SELL 12.995 13.075
10:10 SHORT SELL 12.98 13.06
10:16 SHORT SELL 12.975 13.055
...
15:57 SHORT SELL 12.375 12.455
```

Each line ends with the price at that minute's close and, for symbols with an
offset configured in `SignalPrices` (currently RIVN at 8 cents), the price plus
that offset.

Once a signal prints, no signal prints until 6 minutes have passed (`--cooldown`
changes the number; 0 prints every signal). The price and averages used for a
minute are those at its close, meaning the last close-updating trade in it.
Minutes before 09:45, including pre-market, still feed the averages; they are
just never printed.

`expected/RIVN-2026-10-16.txt` holds the full expected output for that day,
derived independently from every trade of the day, so
`replay RIVN 2026-10-16 | diff - expected/RIVN-2026-10-16.txt`
should print nothing. RIVN fell all day, so it has 20 `SHORT SELL` lines and
no `BUY LONG` (45 minutes qualified; the cooldown holds back 25).

Options:

- `--session HH:MM-HH:MM` sets the daily window of bars, e.g. `07:00-20:00`
  for a platform whose extended hours start at 07:00, or `09:30-16:00` for
  regular hours only.
- `--cooldown <minutes>` sets the quiet period after a printed signal
  (default 6).
- `--verbose` prints every minute with its zone, any signal (marked
  `(cooldown)` when held back), the price and the three averages, followed by
  a summary line.
- `--verify` runs on Massive's filled series instead and compares the
  engine's three values at every completed bar with Massive's own
  `/v1/indicators/sma` minute values, reporting per window how many bars
  matched, the largest difference and the first few mismatches. The exit code
  is 1 if any window fails.
- `--csv <path>` writes every close-updating trade with its averages and trend.

## Tests

```
mvn test
```

The engine is tested against real RIVN data: the 200 one-minute bars ending
at the 12:11 bar on 2026-10-16 in `src/test/resources/rivn-minute-2026-10-16.csv`
and the SMA values Massive reported for that bar on its filled series. The trade-condition rule is
tested against the trades and bars Massive returned for the same day. The HTTP
client is tested against a local server for pagination, authentication and
retry behaviour.
