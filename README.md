# Stock Price Prediction Using GP

A research-grade quantitative trading system that uses **genetic programming** ([ECJ 23](https://cs.gmu.edu/~eclab/projects/ecj/)) to evolve trading strategies from OHLCV data. The system grows symbolic decision trees over a rich library of technical indicators, evaluates them against a realistic transaction-cost-aware backtester, and runs a full robustness suite (bootstrap, permutation, deflated Sharpe, cross-symbol holdout) so you can tell a genuine edge from a data-mined fluke.

```
                  ┌──────────────────────┐
                  │   FeaturePipeline    │  causal, no-lookahead
                  │  (RSI, MACD, BB,     │  per-bar RowContext
                  │   ATR, ADX, OBV…)    │
                  └──────────┬───────────┘
                             │
         ┌───────────────────┴────────────────────┐
         │                                        │
┌────────▼─────────┐                    ┌─────────▼──────────┐
│ Stock            │   regression       │ TradingStrategy    │  trading-aware GP
│ (MSE fitness +   │   (predict price)  │ MultiTreeStrategy  │  (Sharpe-based fitness,
│ trade backtest)  │                    │                    │   POSITION_AWARE,
└────────┬─────────┘                    │                    │   risk controls)
         │                              └─────────┬──────────┘
         │                                        │
         └────────────────┬───────────────────────┘
                          │
             ┌────────────▼──────────────┐
             │     Backtester engine     │  pure, cost-aware,
             │  ALWAYS_FLAT_OVERNIGHT or │  Sharpe/Sortino/Calmar/
             │  POSITION_AWARE           │  CAGR/turnover/profit factor
             └────────────┬──────────────┘
                          │
       ┌──────────────────┼──────────────────┐
       │                  │                  │
┌──────▼──────┐    ┌──────▼──────┐    ┌──────▼──────┐
│ Validator   │    │ ChampionPool│    │ Exports     │
│ bootstrap,  │    │ decorrelated│    │ JSON / CSV  │
│ permutation,│    │ top-K of    │    │ for dash-   │
│ PSR / DSR,  │    │ final gen   │    │ board, TV,  │
│ cross-symbol│    └─────────────┘    │ Alpaca hint │
└─────────────┘                       └─────────────┘
```

---

## Table of Contents

- [What it does](#what-it-does)
- [Why genetic programming](#why-genetic-programming)
- [Quick start](#quick-start)
- [Profiles](#profiles)
- [Core concepts](#core-concepts)
  - [Feature pipeline](#feature-pipeline)
  - [Function set and ephemeral constants](#function-set-and-ephemeral-constants)
  - [Validation modes](#validation-modes)
  - [Backtester](#backtester)
  - [Trading-aware fitness](#trading-aware-fitness)
  - [Multi-tree decomposition](#multi-tree-decomposition)
  - [Robustness suite](#robustness-suite)
  - [Champion pool](#champion-pool)
  - [Cross-symbol holdout](#cross-symbol-holdout)
- [Output artifacts](#output-artifacts)
- [Tests](#tests)
- [Project layout](#project-layout)
- [Roadmap](#roadmap)
- [References](#references)

---

## What it does

Given a CSV of `Date,Open,High,Low,Close,Adj Close,Volume`:

1. Computes a panel of **causal technical features** per bar (RSI, MACD, Bollinger z-score, ATR/Close, Stochastic %K/%D, ADX, OBV slope, multi-horizon momentum, channel position, VWAP distance, calendar features). Every value at row `i` depends only on rows `0..i` — guarded by a no-lookahead unit test.
2. Runs **genetic programming** to evolve symbolic decision trees over those features. Two evolution modes:
   - **Regression** (`Stock.java`) — predict the next-bar price or return; fitness is MSE-style.
   - **Trading strategy** (`TradingStrategyProblem` / `MultiTreeStrategyProblem`) — evolve the strategy itself; fitness is a composite of risk-adjusted backtest metrics net of costs.
3. **Backtests** each candidate strategy through a pure, vectorized engine that models commission, slippage, position sizing, max holding period, and a daily-loss kill switch.
4. **Validates** the champion: bootstrap Sharpe confidence interval, signal-shuffle permutation test, Probabilistic Sharpe Ratio, Deflated Sharpe Ratio (corrected for the trial count `population × generations`), and cross-symbol holdout to detect overfitting.
5. **Exports** a decorrelated pool of top-K champions (signal-vector Pearson correlation capped) plus dashboard-friendly JSON/CSV (`*-summary.json`, `*-walkforward-summary.json`, `*-validation.json`, `*-champions.json`, `*-equity.csv`, `*-signals.csv`, Alpaca order hint, TradingView webhook payload).

Outputs are designed to plug into a multi-symbol HTML dashboard (`tools/run_batch_dashboard.py`) and downstream automation.

---

## Why genetic programming

GP is well-suited to systematic trading research because:

- **Symbolic and interpretable.** Champions are arithmetic expressions you can read, audit, and translate into any broker DSL.
- **Hyper-parameter light.** No layer counts, learning rates, or batch sizes to babysit; population size, generations, and tree depth do most of the work.
- **Cost-aware natively.** The fitness function is a full backtest, so commissions, slippage, holding periods, and turnover are first-class — the optimizer can't ignore frictions the way a regression-on-returns can.
- **Strategy decomposition.** A four-tree individual (long-entry / short-entry / exit / size) mirrors how human discretionary traders actually think.
- **Diversifiable.** Many distinct expressions can produce similar fitness; the champion pool keeps the top-K with low pairwise signal correlation so an ensemble has real variance.

The flip side — GP overfits eagerly — is why the robustness suite is non-negotiable, not an afterthought.

---

## Quick start

**Prerequisites:** JDK 17+, PowerShell (Windows), Python 3 with `pandas`/`yfinance`/`plotly` (for the data fetcher and dashboard).

Refresh data and compile:

```powershell
python src/main/data/fetchData.py MSFT AAPL --start 2020-01-01
javac -cp ecj.23.jar -d target/manual-classes (Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object { $_.FullName })
```

Smoke run (10 generations, holdout validation):

```powershell
java -cp "target/manual-classes;ecj.23.jar" ec.Evolve -file src/main/resources/stock-quick.params
```

Full trading-strategy run with cross-symbol robustness:

```powershell
java -cp "target/manual-classes;ecj.23.jar" ec.Evolve `
  -file src/main/resources/stock-multistrategy.params `
  -p eval.problem.dataset=src/main/data/MSFT.csv `
  -p eval.problem.validation.cross-symbol-datasets=src/main/data/AAPL.csv,src/main/data/GOOG.csv `
  -p eval.problem.export-prefix=MSFT-multi
```

Outputs land in `src/main/results/` (or wherever `eval.problem.export-dir` points). Run the tests with:

```powershell
powershell -ExecutionPolicy Bypass -File tools/run_tests.ps1
```

(The script downloads `junit-platform-console-standalone` once into `lib/` so you don't need Maven installed.)

Multi-symbol batch dashboard:

```powershell
python tools/run_batch_dashboard.py
```

---

## Profiles

Each `.params` file is an ECJ parameter list that inherits from a parent profile. They form a stack:

```
ec.params  →  simple.params  →  koza.params  →  stock.params
                                                    │
                                                    ├── stock-quick.params       (10-gen smoke test)
                                                    ├── stock-full.params        (75-gen walk-forward regression)
                                                    │       │
                                                    │       └── stock-return.params  (return target, not price)
                                                    │
                                                    ├── stock-trading.params     (regression + backtest exports)
                                                    │
                                                    └── stock-rich.params        (full + Phase 1 indicator library)
                                                                │
                                                                └── stock-strategy.params  (trading-aware fitness)
                                                                        │
                                                                        └── stock-multistrategy.params  (4-tree + risk controls)
```

| File | Problem class | Fitness | When to use |
| --- | --- | --- | --- |
| `stock-quick.params` | `StockPredictor.Stock` | MSE | Iteration / sanity-check |
| `stock-full.params` | `StockPredictor.Stock` | MSE, walk-forward | Long regression runs |
| `stock-return.params` | `StockPredictor.Stock` | MSE on returns | Return-target studies |
| `stock-trading.params` | `StockPredictor.Stock` | MSE + backtest export | Dashboard runs |
| `stock-rich.params` | `StockPredictor.Stock` | MSE | Regression with full indicator set |
| `stock-strategy.params` | `StockPredictor.TradingStrategyProblem` | Sharpe + Calmar − costs | Single-tree alpha evolution |
| `stock-multistrategy.params` | `StockPredictor.MultiTreeStrategyProblem` | Sharpe + Calmar − costs | Four-tree strategy with risk controls |

Override any parameter from the command line with `-p key=value`. Examples:

```powershell
# Bigger population, more generations
-p pop.subpop.0.size=2000 -p generations=100

# Different dataset and tighter signal threshold
-p eval.problem.dataset=src/main/data/NVDA.csv -p eval.problem.backtest.signal-threshold=0.005

# Heavier turnover penalty in the trading profile
-p eval.problem.fitness.weight-turnover=30
```

---

## Core concepts

### Feature pipeline

`src/main/java/feature/FeaturePipeline.java` is the single source of truth for per-bar features. It takes the raw OHLCV history plus aligned timestamps and precomputes an array of `RowContext` snapshots. Every value at row `i` depends only on `rows[0..i]` — verified by `NoLookaheadTest.allRowContextFieldsAreIdenticalWhenComputedOnTruncatedHistory`, which recomputes each feature on a truncated history and asserts identical values to the full-history computation at index `i`.

Per-bar features include:

| Feature | Notes |
| --- | --- |
| OHLCV + previous Open/Close, intraday change, daily range | Trivial derivations |
| Lagged open/close return | `(p[i] − p[i−1]) / |p[i−1]|` |
| 10-day / 50-day moving averages | Of `Adj Close` |
| 10-day volume average | |
| 10-day / 20-day realized return volatility | Population stdev |
| **RSI(14)** | Wilder smoothing |
| **MACD(12, 26, 9)** line / signal / histogram | EMA seeded with SMA of first window |
| **Bollinger z-score(20)** | `(close − SMA20) / std20` |
| **ATR(14) / Close** | Volatility normalized to price |
| **Stochastic %K(14) / %D(3)** | |
| **ADX(14)** | Wilder smoothing |
| **OBV slope(10)** | Linear-regression slope of OBV over last 10 bars |
| **Momentum 5 / 10 / 20 / 60 / 120** | `(close[i] − close[i−n]) / |close[i−n]|` |
| **Channel position 20 / 55** | `(close − rollingLow) / (rollingHigh − rollingLow)` |
| **VWAP distance (20-bar)** | `(close − rollingVWAP) / rollingVWAP` |
| **Calendar** | `sin/cos` of day-of-week, days to month end |

The pipeline emits NaN where there isn't enough warm-up history. Stock's evaluation loop skips any bar whose `RowContext.isComplete()` is false, so the GP never sees half-formed features.

### Function set and ephemeral constants

The default function set (`koza.params`) includes:

- Arithmetic: `Add`, `Sub`, `Mul`, `Div` (epsilon-guarded), `Min`, `Max`
- Unary: `Abs`, `Log` (epsilon-guarded), `Sqrt`
- Trig (off by default): `Sin`, `Cos`, `Tan`, `Cot`, `Sec`, `Cosec`
- **Ephemeral random constant** with a mixture prior:
  - 50% uniform in `[-1, 1]`
  - 30% log-uniform in `[10⁻³, 10²]` with a random sign
  - 20% small integers in `{-5..5}`

The mixture prior is meaningful — uniform `[0, 1)` (ECJ's stock prior) is a terrible match for price magnitudes; the new prior covers the dynamic range of OHLCV and indicator features. `EphemeralPriorTest` enforces this distribution shape.

The Phase 1 rich profile (`stock-rich.params`) extends the function set with 21 indicator terminals — see `koza.params` for the registered list and table above for the indicators.

### Validation modes

- **Holdout** — `training-split = 0.8` by default. Fitness uses rows `[0, trainingEndIndex)`; the rest is held out for `describe()` reporting.
- **Walk-forward** — rolls a `(training-rows, test-rows, step-rows)` window through the training segment. Fitness for the strategy profiles is computed by **stitching the OOS slices** of every window into a single equity stream and scoring that stream. This produces an honest OOS Sharpe even though the strategy is being evolved.

Bugs fixed during the build:
- `previousClose` / `previousOpen` no longer alias to the current row at row 0 — they return NaN and that row is skipped.
- Testing range starts at `testingStartIndex` (not `testingStartIndex − 1`) — no one-row train/test overlap.

### Backtester

`src/main/java/backtest/Backtester.java` is a pure, stateless engine. Inputs:

- A list of `BarSample` (signal, raw bar return, reference price, optional intra-bar high/low, optional size multiplier, day key).
- A `BacktestConfig` (initial capital, position fraction, commission/slippage in bps, bars/year, execution mode, risk controls).

Two execution modes:

| Mode | Cost model | Holding period |
| --- | --- | --- |
| `ALWAYS_FLAT_OVERNIGHT` | Round-trip on every signaled bar | Always one bar |
| `POSITION_AWARE` | One-way on transitions only | Position persists until signal flips or risk control triggers |

Risk controls (POSITION_AWARE only):

| Knob | Default | Behavior |
| --- | --- | --- |
| `maxHoldingBars` | 0 (off) | After N consecutive bars in a position, force flat. Records `forcedExits`. |
| `dailyLossLimitPct` | 0 (off) | If intraday loss crosses `−limit × dayStartEquity`, force flat for the rest of the day. Records `dailyKillSwitchTriggers`. Resets at the next `dayKey`. |
| `maxLeverage` | 1.0 | Clip the effective `sizeMultiplier × positionFraction`. |

Reported metrics: total return, max drawdown, trades, wins, long/short counts, win rate, average net trade return, average holding bars, total cost rate, profit factor, **annualized Sharpe**, **Sortino**, **Calmar**, **CAGR**, **turnover**, plus the per-bar equity curve and per-bar records.

### Trading-aware fitness

`TradingStrategyProblem` reframes evolution from "predict a price well" to "make money after costs". For each candidate:

1. Walk the training range (holdout) or stitched walk-forward OOS windows.
2. Evaluate the tree to get an alpha score per bar. Sign-and-threshold → `{−1, 0, +1}` signal.
3. Compute realized close-to-next-open (or close-to-close return for return targets) per bar.
4. Run the backtester with the configured execution mode and risk controls.
5. Compose a score:

```
score = w_sharpe   * clip(Sharpe, −5, +5)
      + w_calmar   * clip(Calmar, −10, +10)
      − w_turnover * turnover
      − w_complex  * (tree_nodes / 100)
      − w_trades   * max(0, min_trades − trades) / min_trades

standardized = max(0, offset − score)   ← ECJ minimizes this
```

Sharpe and Calmar are clipped to suppress freak short-window numbers (e.g. a single profitable bar makes Sharpe meaningless without bounds). The offset shifts the standardized fitness above zero. Defaults: `w_sharpe=100`, `w_calmar=20`, `w_turnover=50` (10 in multi-strategy), `w_complex=1.0`, `w_trades=100`, `min_trades=30`, `offset=1000`.

The strategy profiles set `quit-on-run-complete = false` so a freak generation doesn't end the run early.

### Multi-tree decomposition

`MultiTreeStrategyProblem` (numtrees = 4) decomposes the strategy into four interpretable functions:

| Tree | Output | Interpretation |
| --- | --- | --- |
| `tree[0]` | alpha score | Long signal active when output > 0 |
| `tree[1]` | alpha score | Short signal active when output > 0 |
| `tree[2]` | alpha score | Exit signal — when > 0, forces flat (overrides entries) |
| `tree[3]` | size raw | Mapped via `|tanh(x)|` to `[0, 1]`, passed as `sizeMultiplier` to the backtester, then clipped to `maxLeverage` |

Resolution rules: exit dominates. Otherwise long XOR short fires; ambiguous (both fire or neither fires) stays flat.

Typing is loose ("positive output = true") rather than strongly typed via `gp.type.a`. A P3.5 follow-up will introduce a dedicated typed function set (`Gt`, `Lt`, `And`, `Or`, `Not`, `IfBool`) to eliminate semantic noise at the language level.

### Robustness suite

`validation/Validator.java` runs four statistical tests on the champion's stitched OOS equity:

1. **Block bootstrap on the per-bar net return series.** Moving-block resample (default 5-bar blocks, 1000 resamples) → Sharpe CI {p05, median, p95}. Tells you the uncertainty band around the observed Sharpe.
2. **Signal-shuffle permutation test.** Shuffle the signal column relative to the bar returns, rerun the backtest, count how often the permuted Sharpe matches or beats the observed one. p-value = `(hits + 1) / (resamples + 1)`. A low p-value means the strategy is statistically distinguishable from a random walk on the same bars.
3. **Probabilistic Sharpe Ratio (PSR).** Bailey & López de Prado — the probability that the *true* Sharpe exceeds zero given the observed sample, sample length, skew, and excess kurtosis. PSR close to 1 = strong evidence.
4. **Deflated Sharpe Ratio (DSR).** PSR with the benchmark raised to the expected maximum Sharpe under `trials = population × generations` random strategies. This is the multiple-testing correction: with 50,000 candidates evaluated, some will look great by chance. DSR shrinks toward 0 unless your observed Sharpe genuinely beats that bar.

Implementation details:
- Normal CDF via Abramowitz & Stegun 26.2.17, inverse normal via Acklam's algorithm (both 1e-7-ish accurate over the working range).
- Bootstrap, permutation, PSR, DSR are seeded so reruns are reproducible.

**How to read the suite:**

| Pattern | Interpretation |
| --- | --- |
| Permutation p < 0.05, DSR > 0.5, cross-symbol Sharpe in the same ballpark | Promising — worth deeper investigation |
| Permutation p < 0.05, DSR ≈ 0, zero cross-symbol trades | Canonical overfit signature — the model is statistically distinguishable from random but doesn't generalize and won't survive trial-count correction |
| Bootstrap CI straddles 0 widely | Too few bars / too noisy — extend the data or relax fitness penalties |
| High in-sample Sharpe + low PSR | Returns are so heavy-tailed (high kurtosis) that the Sharpe ratio is unreliable |

### Champion pool

`ensemble/ChampionPool.java` records a decorrelated set of top performers from the final generation. Algorithm:

1. Sort the population by standardized fitness (best first).
2. Take the top `validation.champion-pool-size` candidates (default 50).
3. For each candidate, compute its OOS signal vector + backtest result.
4. **Greedy admission** — keep the rank-1 candidate; accept the next-best whose pairwise Pearson correlation with every already-accepted champion is below `validation.champion-max-correlation` (default 0.7).
5. Stop at `validation.champion-desired-count` (default 5) or pool exhausted.

The output `*-champions.json` includes each champion's rank, fitness, total node count, key metrics, max correlation with prior champions, and the full set of tree expressions. This is the data layer for an ensemble live-signal generator (planned Phase 5).

### Cross-symbol holdout

Set `validation.cross-symbol-datasets=path/to/A.csv,path/to/B.csv,…` and the champion is replayed on each dataset: the FeaturePipeline is rebuilt for that symbol, signals are re-derived from the same trees, and the backtester runs with identical config. The `*-validation.json` `cross_symbol` array reports per-symbol bars / trades / Sharpe / total return / drawdown.

This is the cheapest, fastest overfit detector: if the strategy only works on the symbol it was trained on, it almost certainly isn't a real edge.

---

## Output artifacts

For each run, the following files land in `eval.problem.export-dir` (default `src/main/results/`) using `eval.problem.export-prefix` as a name prefix:

| File | Source | Description |
| --- | --- | --- |
| `<prefix>-summary.json` | All profiles | Held-out backtest metrics, model expression, latest live signal (with Alpaca order hint) |
| `<prefix>-signals.csv` | All profiles | One row per evaluated bar with predicted/actual return, signal, gross/net return, PnL, equity |
| `<prefix>-equity.csv` | All profiles | `execution_timestamp, equity` series |
| `<prefix>-latest-signal.json` | All profiles | Most recent bar's signal + Alpaca order hint |
| `<prefix>-tradingview-webhook.json` | All profiles | Ready-to-use TradingView webhook payload |
| `<prefix>-walkforward-summary.json` | Strategy profiles in walk-forward mode | Stitched-OOS metrics across all walk-forward windows — **read this for trading strategies** |
| `<prefix>-walkforward-equity.csv` | Same | Stitched OOS equity curve |
| `<prefix>-walkforward-signals.csv` | Same | Per-bar OOS signal records |
| `<prefix>-validation.json` | Strategy profiles | Bootstrap CI, permutation p-value, PSR, DSR, return skew/kurtosis, cross-symbol scoreboard, trial count |
| `<prefix>-champions.json` | Strategy profiles | Decorrelated top-K champions with tree expressions + metrics |
| `out.stat` / `out2.stat` | All profiles | ECJ's standard generation stats |

For trading-strategy profiles, **`*-walkforward-summary.json`** is the authoritative OOS performance number. The held-out `*-summary.json` fields are still computed by the regression pipeline (`Stock.backtestTestingRange`), which interprets `tree[0]` as a price predictor — meaningful for the regression problem, noise for the strategy profiles. A P3.5 cleanup will replace that with a strategy-signal backtest.

---

## Tests

JUnit 5 tests live under `src/test/java`. Currently **37 tests across 9 suites**:

| Suite | What it covers |
| --- | --- |
| `FeaturePipelineTest` (11) | SMA, EMA, RSI saturation, Bollinger on flat input, ATR on constant range, Stochastic, momentum, channel position, calendar features |
| `NoLookaheadTest` (2) | **The lookahead invariant** — every feature at row `k` matches the value computed on a truncated `rows[0..k]` series. Plus row-0 NaN guard |
| `BacktesterTest` (9) | Both execution modes, commission/slippage application, max drawdown calc, Sharpe/Sortino/CAGR formulas, profit factor, turnover, empty input |
| `BacktesterRiskControlsTest` (6) | Max holding force-exit, daily-loss kill switch, day-boundary reset, size multiplier scaling, max leverage clipping, ignored in ALWAYS_FLAT_OVERNIGHT |
| `EphemeralPriorTest` (1) | Random-constant prior covers negatives + magnitudes > 10 + integer branch |
| `ValidatorTest` (8) | Normal CDF / inverse CDF accuracy, bootstrap CI on synthetic noise, permutation p ≈ 0.5 for random / tiny p for perfect signals, DSR monotonicity, PSR sanity, Gaussian moment estimators |

Run with either:

```powershell
# No-Maven runner (downloads junit-platform-console-standalone into lib/ on first run)
powershell -ExecutionPolicy Bypass -File tools/run_tests.ps1

# Or with Maven
mvn test
```

---

## Project layout

```
ecj.23.jar                            ECJ engine (system-scoped dependency)
pom.xml                               JUnit 5 + Surefire (Maven users)
todo.md                               Phase-by-phase build plan + roadmap

docs/                                 ECJ manuals (PDF)

src/main/java/
  StockPredictor/
    Main.java                         Entry point (sets stats files, jobs)
    Stock.java                        Regression GP problem (MSE fitness)
    TradingStrategyProblem.java       Single-tree trading GP (Sharpe fitness)
    MultiTreeStrategyProblem.java     4-tree GP (long/short/exit/size)
  feature/
    FeaturePipeline.java              Causal feature engineering
    FeatureSeries.java                Aligned view across all bars
    RowContext.java                   Immutable per-bar snapshot
  backtest/
    Backtester.java                   Pure backtest engine
    BacktestConfig.java               Builder for run config
    BacktestResult.java               Output metrics + records
    BarSample.java                    Per-bar input (signal, return, size mult)
    BarRecord.java                    Per-bar output record
    EquityPoint.java                  (timestamp, equity)
    ExecutionMode.java                ALWAYS_FLAT_OVERNIGHT | POSITION_AWARE
  validation/
    Validator.java                    Bootstrap, permutation, PSR, DSR
  ensemble/
    ChampionPool.java                 Decorrelated top-K selector
  functions/
    Add/Sub/Mul/Div/Min/Max/Abs/Log/Sqrt/...   GP function nodes
    Ephemeral.java                    Mixture-prior random constant
  terminal/
    Open/High/Low/Close/Volume/...    Existing OHLCV terminals
    FeatureTerminal.java              Base class for indicator terminals
    Rsi14/Macd/BollingerZ20/Atr14Pct/...       21 indicator terminals
    DoubleData.java                   ECJ GPData carrier

src/main/resources/
  ec.params / simple.params / koza.params      ECJ defaults
  stock.params                                  Base problem config
  stock-quick / stock-full / stock-return /
  stock-trading / stock-rich /
  stock-strategy / stock-multistrategy.params   Profile stack

src/main/data/
  fetchData.py                        Yahoo Finance pull + CSV refresh
  *.csv                               Per-symbol OHLCV history

src/test/java/                        JUnit 5 test suites (37 tests)

tools/
  run_tests.ps1                       JUnit runner (no Maven required)
  run_batch_dashboard.py              Multi-symbol HTML dashboard

reports/trading-dashboard/<run-id>/   Dashboard outputs (one folder per run)
```

---

## Roadmap

Built (see `todo.md` for details):

- ✅ **Phase 1** — Feature pipeline + 21 indicator terminals, no-lookahead invariant, mixture-prior ephemerals, row-0 / train-test overlap fixes
- ✅ **Phase 2** — Pure `Backtester` engine, risk-adjusted metrics (Sharpe / Sortino / Calmar / CAGR / turnover), trading-aware GP problem with composite fitness, walk-forward stitched OOS export
- ✅ **Phase 3** — Multi-tree (4-tree) strategy decomposition, POSITION_AWARE backtest, max-holding-bars + daily-loss kill switch + size multiplier + max-leverage
- ✅ **Phase 4** — Robustness suite (block bootstrap, signal-shuffle permutation, PSR, DSR), decorrelated champion pool, cross-symbol holdout

Planned:

- **Phase 5** — Live execution & monitoring. Alpaca paper-trading bridge, daily scheduled job, audit log, kill-switch file, drift monitor comparing live PnL to the bootstrap CI.
- **P3.5** — Strongly-typed GP (`gp.type.a` for `double`/`bool`, dedicated `Gt`/`Lt`/`And`/`Or`/`Not`/`IfBool` function set), ATR-based hard + trailing stops (use the existing `barHigh`/`barLow` slots in `BarSample`), strategy-signal held-out backtest override so `*-summary.json` aligns with the strategy semantics.
- **P4.5** — NSGA-II Pareto-front evolution (Sharpe / drawdown / complexity / turnover as separate objectives) and dashboard panels for the validation suite.

---

## References

- ECJ 23 — Sean Luke, George Mason University: <https://cs.gmu.edu/~eclab/projects/ecj/>
- Bailey & López de Prado, "The Deflated Sharpe Ratio: Correcting for Selection Bias, Backtest Overfitting, and Non-Normality" (2014)
- Bailey & López de Prado, "The Probability of Backtest Overfitting" (2014)
- López de Prado, *Advances in Financial Machine Learning* (2018) — chapters on combinatorial purged cross-validation, deflated metrics, and meta-labeling informed Phases 2–4
- Politis & Romano, "The Stationary Bootstrap" (1994) — block-bootstrap foundation
