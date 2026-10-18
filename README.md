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
minute closes, inside the signal window (10:30 to 14:30 Eastern by default,
`--window` changes it), it classifies the minute:

| zone | meaning |
|---|---|
| up   | 200 below 50, 50 below 20, price above the 20; the 50 at least 3 cents and the 200 at least 6 cents below the 20 |
| down | 200 above 50, 50 above 20, price below the 20; the 50 at least 3 cents and the 200 at least 6 cents above the 20 |
| none | anything else, ties and averages closer than those gaps included |

and prints the minute only when the zone gives an entry signal:

| printed | when |
|---|---|
| `BUY LONG`   | up zone and the price is at most 2 cents above the 20 |
| `SHORT SELL` | down zone and the price is at most 2 cents below the 20 |

```
10:31 SHORT SELL 12.93 13.01
11:12 SHORT SELL 12.82 12.90
11:19 SHORT SELL 12.805 12.885
...
14:15 SHORT SELL 12.51 12.59
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
should print nothing. RIVN fell all day, so it has 12 `SHORT SELL` lines and
no `BUY LONG`.

Options:

- `--session HH:MM-HH:MM` sets the daily window of bars, e.g. `07:00-20:00`
  for a platform whose extended hours start at 07:00, or `09:30-16:00` for
  regular hours only.
- `--window HH:MM-HH:MM` sets the minutes whose closes may produce a signal;
  `--window 10:30-12:30` acts on the 10:30 through 12:29 closes and opens
  nothing from 12:30 on. Positions still close at 15:55 in the grid.
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

## Find the best stop and target for a month

```
java -jar target/lib78.jar grid RIVN 2026-10
```

generates every completed trading day's signals for the month, simulates each
one with every combination of stop and target, and prints the total P&L table
followed by a recommendation:

```
RIVN 2026-10: 12 trading days (2026-10-01 to 2026-10-16), 94 signals
RIVN: 94 signals, 2000 shares per trade, typical regular-hours minute range 0.03

stop \ target      0.05      0.06      0.09      0.12      0.18      0.24      none
0.03          ...
...

recommendation: stop 0.15, target 0.40
  P&L 8,347.00 over 94 trades: 10 targets, 23 stops, 61 held to 15:55; worst trade -300.00
  chosen for its neighbourhood: the surrounding cells average 8,102, so it sits on a plateau, not a spike
  risk/reward 2.67 to 1: breakeven win rate 27%, actual 30%
  this is a trend bet: ...
```

Each trade enters at the signal price when the next minute opens. A later
minute whose range reaches the stop fills it at the stop; one that reaches the
target fills at the target; whichever comes first wins, and a minute touching
both counts as the stop. Anything still open closes at the 15:55 price, and
signals from 15:54 on are skipped. Signals are taken inside the same
`--window` as the replay, 10:30 to 14:30 by default. Signals come from minute bars rather than
trades, which gives the same answers as the replay (the tests prove it on a
real day) in seconds instead of minutes.

The recommendation is the finite cell whose 3-by-3 neighbourhood averages
highest, so it sits on a plateau rather than a one-cell spike. The printout
also states the breakeven win rate, whether the pair is a trend bet or a
scalp, and warnings when one day dominates the result or the sample is small.

With a second month the same levels are applied to every month in the range,
and three tables are printed per stop-and-target pair: the total over all
months, the worst month, and how many months were positive. The
recommendation is then chosen by mean monthly P&L minus its standard
deviation, smoothed over neighbouring cells, so a pair that earns steadily
beats one that earned a lot once. Its month-by-month results and a breakdown
by entry time follow:

```
java -jar target/lib78.jar grid RIVN 2026-01 2026-10
```

When no cell is steadily positive, the recommendation says so and lists the
three strongest totals instead. `--show 0.05,0.25` (repeatable) prints any
pair's month-by-month P&L and time-of-day breakdown, whether or not the grid
contains it.

`--risk 380` sizes every trade as 380 divided by its stop distance instead of
a fixed share count, so a stopped trade loses about $380 in every cell and the
table compares brackets at equal risk; the header lists the share count each
stop implies. Rows with no stop fall back to the fixed size.

`--cap 50000` never puts more than $50,000 in one position: each trade buys
the smaller of its risk-sized count and the cap divided by its own entry price,
so a high-priced stock with a tight stop risks less than the budget rather than
breaching the cap. The header quotes the resulting counts at the average signal
price. Works with `--risk` or `--shares`.

Options: `--shares N` (default: the symbol's configured size in
`SymbolSettings`, else 100), `--stops 0.05,0.10,none` and
`--targets 0.10,0.20,none` to test your own levels (defaults are multiples of
the month's typical one-minute range), plus `--cooldown`, `--session` and
`--window` as for the replay.

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
