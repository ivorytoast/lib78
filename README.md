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

The bars follow Massive's conventions, which were verified against its minute
aggregates and its minute SMA endpoint:

- Pre-market and after-hours bars count. A day runs 04:00 to 20:00 Eastern, and
  the first bars of a day look back into the previous day.
- Minutes with no trades produce no bar.
- A trade only moves the close if its condition codes allow it. Odd lots,
  average-price prints and similar are ignored, which is why a bar's close can
  differ from the very last print in it. See `TradeConditions`.

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
and the three averages as the engine stood on that trade:

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
minute closes, from 09:45 through 15:59 Eastern, it prints the minute and one
of three words:

| word | meaning |
|---|---|
| `up`   | 200 below 50, 50 below 20, and the price above the 20 |
| `down` | 200 above 50, 50 above 20, and the price below the 20 |
| `none` | anything else, ties included |

```
09:45 down
09:46 down
...
10:00 none
10:01 none
10:02 down
...
15:59 none
RIVN 2026-10-16: 375 minutes, up 0, down 262, none 113 (99,581 trades, 46,848 applied, 960 bars)
```

`expected/RIVN-2026-10-16.txt` holds the full expected output for that day,
derived independently from every trade of the day and Massive's minute SMA
values, so `replay RIVN 2026-10-16 | diff - expected/RIVN-2026-10-16.txt`
should print nothing.

The price and averages used for a minute are those at its close, meaning the
last close-updating trade in it. Minutes before 09:45, including pre-market,
still feed the averages; they are just not printed.

Options:

- `--verbose` appends the price and the three averages to each line.
- `--verify` also compares the engine's three values at every completed bar
  with Massive's own `/v1/indicators/sma` minute values and reports, per
  window, how many bars matched, the largest difference and the first few
  mismatches. The exit code is 1 if any window fails.
- `--csv <path>` writes every close-updating trade with its averages and trend.

## Tests

```
mvn test
```

The engine is tested against real RIVN data: the 200 one-minute closes ending
at the 12:11 bar on 2026-10-16 in `src/test/resources/rivn-minute-2026-10-16.csv`
and the SMA values Massive reported for that bar. The trade-condition rule is
tested against the trades and bars Massive returned for the same day. The HTTP
client is tested against a local server for pagination, authentication and
retry behaviour.
