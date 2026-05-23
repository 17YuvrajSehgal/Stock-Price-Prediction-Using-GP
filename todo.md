# GP Quant Trading System — Build Plan

A staged plan to turn the existing single-tree price-regression GP into a fully functional, edge-generating quant trading system. Each phase is independently mergeable and leaves the project runnable.

---

## Current State (baseline)

**Strengths**
- ECJ Koza-style GP, walk-forward or holdout validation, complexity penalty, ephemeral constants.
- OHLCV feature set (lagged returns, 10/50d MAs, daily range, intraday change, rolling vol).
- End-to-end pipeline: data fetch → train → backtest → JSON/CSV exports → multi-symbol HTML dashboard → TradingView/Alpaca payloads.
- Per-row backtest with commission + slippage in bps.

**Gaps blocking a real edge**
- Fitness = mean normalized error, not PnL — GP optimizes the wrong objective.
- Single tree predicts a price; trading logic is bolted on afterward.
- No transaction-cost awareness during evolution.
- No regime detection or cross-sectional view.
- Limited technicals (no RSI/MACD/BB/ATR/ADX/Stoch, no cross-asset, no calendar features).
- Single-objective + single champion → final winner is the most overfit to training noise.
- No risk controls (stops, vol targeting, daily loss circuit-breaker).
- Backtest is fixed one-bar close→next-open with no variable holding period.
- No permutation test, deflated Sharpe, bootstrap CIs, or cross-symbol generalization check.
- No live-execution loop (signals exported but not consumed by a broker bridge).

**Bugs to fix during rebuild**
- `previousClose`/`previousOpen` silently alias to current row when `row == 0`.
- Test range starts at `testingStartIndex - 1` → one row of train/test overlap.
- `Ephemeral` constants are uniform `[0,1)` — poor prior for price-magnitude features.

---

## Target Architecture

```
                       ┌──────────────────────┐
                       │   FeaturePipeline    │  causal, no-lookahead
                       │  (Java + tests)      │  RSI, MACD, BB, ATR, ADX, OBV,
                       └──────────┬───────────┘  Stoch, momentum bundle,
                                  │              channel position, VWAP-dist,
                                  │              SPY/VIX cross-asset, calendar
              ┌───────────────────┴────────────────────┐
              │                                        │
     ┌────────▼─────────┐                    ┌─────────▼──────────┐
     │ PredictionProblem│  (existing Stock)  │TradingStrategyProb │  NEW
     │  fitness = MSE-  │                    │  multi-tree:        │
     │  style + complex │                    │  long / short /     │
     └────────┬─────────┘                    │  exit / size        │
              │                              │  fitness = backtest │
              │                              │  Sharpe/Calmar -    │
              │                              │  turnover penalty   │
              │                              └─────────┬──────────┘
              │                                        │
              └────────────────┬───────────────────────┘
                               │
                  ┌────────────▼──────────────┐
                  │      Backtester           │  vectorized, cost-aware,
                  │  walk-forward stitched    │  vol-targeted sizing,
                  │  out-of-sample equity     │  ATR stops, daily kill-switch
                  └────────────┬──────────────┘
                               │
              ┌────────────────┼────────────────┐
              │                │                │
     ┌────────▼──────┐  ┌──────▼──────┐  ┌─────▼─────────┐
     │ Robustness    │  │ Ensembler   │  │ LiveBridge    │
     │ - permutation │  │ Pareto top-K│  │ Alpaca/IBKR   │
     │ - block       │  │ vote/mean   │  │ scheduler,    │
     │   bootstrap   │  │ persisted   │  │ audit log,    │
     │ - DSR / PSR   │  │ as JSON     │  │ drift monitor │
     │ - cross-sym   │  └─────────────┘  └───────────────┘
     │   holdout     │
     └───────────────┘
```

### Components

- **`feature/FeaturePipeline.java`** — single source of truth for all derived features. Lookahead-test per feature. Replaces ad-hoc fields on `Stock`.
- **New terminals** (one per indicator/window): `Rsi14`, `Macd`, `MacdSignal`, `MacdHist`, `BollingerZ20`, `Atr14Pct`, `StochK14`, `StochD14`, `Adx14`, `ObvSlope10`, `Mom5`, `Mom10`, `Mom20`, `Mom60`, `Mom120`, `ChannelPos20`, `ChannelPos55`, `VwapDist`, `SpyMom20`, `VixLevel`, `DowSin`, `DowCos`, `MonthEndDist`.
- **New functions**: `IfPositive(cond,a,b)`, `Gt`, `Lt`, `And`, `Or`, `Not`, `Sigmoid`, `Tanh`, `Clip`. Optionally strongly-typed GP (`gp.type.a`) to separate booleans from doubles.
- **`StockPredictor/TradingStrategyProblem.java`** — new `GPProblem`, `numtrees = 4` (long, short, exit, size). Fitness:
  ```
  score = W_sharpe*sharpe_oos
        + W_calmar*(cagr/max_dd)
        - W_turn*turnover_penalty
        - W_complex*tree_nodes
        - W_minTrades*penalty_if(trades < N)
  ```
- **`backtest/Backtester.java`** — vectorized, supports variable holding period, ATR trailing stops, vol-targeted sizing, daily loss circuit breaker, volume-scaled slippage, per-bar PnL + stitched walk-forward equity.
- **`validation/Validator.java`** — block bootstrap of trade returns (CIs on Sharpe), permutation test for signal/return pairing, Deflated Sharpe correcting for trial count, cross-symbol holdout.
- **`ensemble/ChampionPool.java`** — top-K by fitness with pairwise signal correlation < 0.7, persisted to `champions.json`.
- **`live/AlpacaBridge.java` + `tools/run_live.py`** — daily scheduled job: refresh data → load champions → emit signal → place paper orders → write audit log. Honors `KILL_SWITCH` file + daily loss limit.
- **`monitor/DriftMonitor.java`** — compares live realized PnL distribution to bootstrap CI from validation; raises flag when live performance is below the 5th percentile of expected.
- **`pom.xml`** — wire JUnit 5; project has `pom.xml` but no tests today.

---

## Phased Roadmap

### Phase 1 — Feature & test foundation ✅
- [x] Wire JUnit 5 into `pom.xml` (+ standalone runner `tools/run_tests.ps1` for the no-Maven workflow).
- [x] Create `src/main/java/feature/FeaturePipeline.java` as the single source of truth for per-bar features.
- [x] Add `RowContext` + `FeatureSeries` value objects (immutable per-bar feature snapshot).
- [x] Implement indicators in `FeaturePipeline`: RSI(14), MACD(12,26,9) line/signal/hist, Bollinger z-score(20,2), ATR(14)/Close, Stochastic %K(14)/%D(3), ADX(14), OBV slope(10), momentum(5/10/20/60/120), channel position(20/55), VWAP distance(20), calendar (DoW sin/cos, days-to-month-end).
- [x] Add 21 new terminal nodes (`FeatureTerminal` base + concrete subclasses) wired to `FeaturePipeline` via the `Stock.activeRow` context.
- [ ] Cross-asset terminals (`SpyMom20`, `VixLevel`) — deferred to a P1.5 follow-up; needs a per-bar SPY/VIX joiner in the pipeline.
- [x] Create `stock-rich.params` opting into the new function set (existing profiles unchanged).
- [x] Fix `previousClose`/`previousOpen` row-0 alias → now `NaN`; row 0 is skipped because `RowContext.isComplete()` requires finite values.
- [x] Fix train/test overlap: testing/backtest now start at `testingStartIndex`.
- [x] Replace `Ephemeral` uniform `[0,1)` with mixture: 50% uniform `[-1,1]`, 30% log-uniform `[1e-3, 1e2]`, 20% small integers `{-5..5}`.
- [x] JUnit 5 tests under `src/test/java`:
  - `FeaturePipelineTest` — golden values for SMA/EMA/RSI/Bollinger/ATR/Stochastic/Momentum/ChannelPosition/Calendar.
  - `NoLookaheadTest` — recomputes every feature on `rows[0..k]` and asserts identical values to the full-series computation at `k`; also asserts `previousClose` at row 0 is NaN.
  - `EphemeralPriorTest` — distribution checks (negatives, magnitudes >10, integer branch).
  - (WalkForwardTest deferred — the overlap fix in `Stock.java` is now obvious from code; a regression test can be added when the backtest is extracted in P2.)
- [x] README updated with `stock-rich.params` usage and indicator-library table.
- [x] **Validation**: all 14 tests pass; smoke-run of `stock-rich.params` on MSFT.csv completes 2 generations without errors.

### Phase 2 — Trading-aware fitness ✅
- [x] Extract `backtest/Backtester.java` — pure function over a sequence of `BarSample` (signal, raw bar return, price). Supports `ALWAYS_FLAT_OVERNIGHT` (existing behavior) and `POSITION_AWARE` (cost only on transitions) execution modes.
- [x] New value objects: `BacktestConfig` (builder), `BarSample`, `BarRecord`, `EquityPoint`, `BacktestResult`.
- [x] Risk-adjusted metrics inside `Backtester`: annualized Sharpe, Sortino, Calmar, CAGR, turnover, profit factor, win rate, average holding bars.
- [x] Refactor `Stock.java` to delegate to `Backtester` — existing dashboard exports preserved; summary JSON now also emits `sharpe`, `sortino`, `calmar`, `cagr`, `turnover`, `average_holding_bars`.
- [x] `StockPredictor/TradingStrategyProblem.java` — `extends Stock`, overrides `evaluate()` with composite fitness `W_sharpe*sharpe + W_calmar*calmar − W_turnover*turnover − W_complexity*(nodes/100) − W_minTrades*shortfall`. Sharpe clipped to `[-5, 5]` and Calmar to `[-10, 10]` to suppress tiny-sample noise. Standardized = `max(0, offset − score)`.
- [x] `src/main/resources/stock-strategy.params` — selects the new problem, sets sensible weights, disables `quit-on-run-complete` so we don't early-exit on a fluke score.
- [x] Walk-forward stitched OOS equity export — `TradingStrategyProblem.describe()` writes `*-walkforward-summary.json`, `*-walkforward-equity.csv`, `*-walkforward-signals.csv` in addition to the held-out backtest.
- [x] `BacktesterTest` — 9 unit tests covering both execution modes, PnL math, commission/slippage, max drawdown, Sharpe/Sortino formulas, CAGR, profit factor, turnover, empty-input edge case.
- [x] **Validation**: all 23 tests pass; smoke-runs of `stock-trading.params` (regression) and `stock-strategy.params` (trading) complete cleanly and emit expected JSON/CSV artifacts.

Deferred / out of scope for P2 (logged here for visibility):
- Walk-forward OOS export for the regression `Stock` flow — only `TradingStrategyProblem` writes the stitched artifacts. The regression problem keeps the held-out backtest export as before.
- `turnover` weight in the default strategy profile is harsh for `ALWAYS_FLAT_OVERNIGHT` (every signaled bar = one transition). Revisit defaults once Phase 3 introduces `POSITION_AWARE` strategies, where turnover penalty becomes more meaningful.

### Phase 3 — Multi-tree + risk controls ✅
- [x] `MultiTreeStrategyProblem extends TradingStrategyProblem` with `numtrees = 4`: tree 0 long-entry alpha, tree 1 short-entry alpha, tree 2 exit signal, tree 3 raw size (mapped via `|tanh(x)|` to `[0, 1]`, then clipped by `risk.max-leverage`). Boolean interpretation uses the "positive output = true" convention. Exit tree dominates entries; ambiguous (long+short) bars stay flat.
- [x] `Backtester` risk controls (POSITION_AWARE only):
  - `maxHoldingBars` — force-flatten an open position after N bars (tracked in `BacktestResult.forcedExits`).
  - `dailyLossLimitPct` — once intraday PnL crosses `-limit * dayStartEquity`, force flat and refuse new entries until the next day key (tracked in `BacktestResult.dailyKillSwitchTriggers`).
  - Per-bar `sizeMultiplier` clipped to `maxLeverage`.
- [x] `BarSample` extended with optional `barHigh`, `barLow`, `sizeMultiplier`, `dayKey` (backward-compatible — old single-arg constructor still works).
- [x] `BacktestConfig` extended with `maxHoldingBars`, `dailyLossLimitPct`, `maxLeverage`.
- [x] `stock-multistrategy.params` profile — POSITION_AWARE execution, defaults `maxHoldingBars=20`, `dailyLossLimitPct=0.03`, `maxLeverage=1.0`, lower `weight-turnover=10` (turnover penalty is naturally smaller in POSITION_AWARE).
- [x] Tests (6 new — `BacktesterRiskControlsTest`): max-holding triggers, daily-loss kill switch halts/resets at day boundary, size multiplier scales gross return, max leverage clips oversized requests, risk controls ignored in ALWAYS_FLAT_OVERNIGHT mode, negative size clamped to 0.
- [x] **Validation**: 29/29 tests pass; smoke run of `stock-multistrategy.params` on MSFT completes 4 generations, evaluates 4-tree individuals, applies risk controls, and emits the walk-forward stitched OOS export.

Deferred to **P3.5** (logged below):
- Strongly-typed GP (`gp.type.a` for `double`/`bool`, dedicated strategy function set with `Gt`/`Lt`/`And`/`Or`/`Not`/`IfBool`). Loose-typed sign-of-output convention works but admits semantic noise.
- ATR-based hard + trailing stops in `Backtester` (need intra-bar high/low data path — `BarSample` already has the slot; problem class needs to populate it).
- Override the legacy held-out backtest in `TradingStrategyProblem`/`MultiTreeStrategyProblem` so `*-summary.json` reports the strategy-signal backtest instead of treating tree[0] as a price predictor. For now, users of the strategy profiles should consult `*-walkforward-summary.json` for the meaningful OOS view.

### Phase 4 — Robustness & ensembling ✅
- [x] `validation/Validator.java` — pure utility class with:
  - `blockBootstrapSharpe` — moving-block resample of per-bar net returns, returns Sharpe CI {p05, median, p95}.
  - `permutationTest` — shuffle the signal column relative to bar returns; returns observed Sharpe + p-value.
  - `probabilisticSharpe` (PSR) — Bailey/López de Prado probability that the true Sharpe exceeds a benchmark given sample skew + excess kurtosis.
  - `deflatedSharpe` (DSR) — PSR with the benchmark set to the expected max-Sharpe under `trials = population × generations` random strategies.
  - Helpers: `normalCdf` (Abramowitz–Stegun 26.2.17), `inverseNormalCdf` (Acklam), `sampleSkew`, `sampleExcessKurtosis`.
- [x] `ensemble/ChampionPool.java` — sorts the final population by standardized fitness, greedily admits up to {desiredCount} candidates whose pairwise OOS signal Pearson correlation stays below {maxCorrelation}. Records tree expressions, fitness, backtest metrics, signal vectors.
- [x] Cross-symbol holdout in `TradingStrategyProblem` — `validation.cross-symbol-datasets` (comma-separated CSV paths). For each path, swap in a temporary FeaturePipeline, replay the champion's signal logic, run the backtester, report per-symbol metrics.
- [x] `TradingStrategyProblem.describe()` now exports `*-validation.json` (bootstrap CI, permutation p-value, PSR, DSR, return moments, cross-symbol scoreboard, trial count) and `*-champions.json` (top-K decorrelated champions with tree expressions + metrics).
- [x] Validator unit tests (8 new, 37 total): normal CDF / inverse CDF accuracy vs. known values, bootstrap CI bracketing on synthetic noise, permutation p ≈ 0.5 for random data + tiny p for perfect signals, DSR shrinks as trials grow, PSR monotonic in observed Sharpe, sample moments handle Gaussian data.
- [x] **Validation**: 37/37 tests pass; end-to-end smoke run on MSFT with AAPL/GOOG cross-symbol surfaces a real signal-of-life: p=0.0099 on MSFT but DSR=0 + zero trades on cross-symbols, i.e. textbook overfit pattern — exactly what the suite is meant to detect.

Deferred to **P4.5** (tracked):
- NSGA-II Pareto-front evolution (Sharpe / max-drawdown / complexity / turnover objectives) via ECJ's multi-objective support.
- Dashboard panels for validation: bootstrap CI band on the equity curve, permutation p-value badge, DSR readout, cross-symbol scoreboard.
- Ensemble-driven live signal — `champions.json` consumer that averages signals at inference time (this naturally pairs with the Phase 5 live bridge).

### Phase 5 — Live execution & monitoring
- [ ] `live/AlpacaBridge.java` (Java) — wraps Alpaca REST API; paper-trading mode by default; reads champion ensemble and emits orders.
- [ ] `tools/run_live.py` — daily scheduler entry point: refresh data → load champions → emit signals → place orders → write `live/audit/YYYY-MM-DD.json`.
- [ ] Windows Task Scheduler instructions in `README.md`.
- [ ] `KILL_SWITCH` file convention: if present, no orders placed (audit log records skip).
- [ ] Daily loss limit (config), per-symbol position cap, max gross exposure cap.
- [ ] `monitor/DriftMonitor.java` — compares live realized per-trade returns to validation bootstrap CI; writes `reports/drift/<date>.json` and flags below-5th-percentile.
- [ ] Dashboard updates: live PnL panel side-by-side with backtest expectation.

---

## Decisions taken (defaults — easy to revisit)

- **Universe**: equities only for now (matches existing data fetcher).
- **Live broker**: Alpaca paper trading (free API, easiest integration).
- **Typed GP**: yes — kills the "boolean vs. double" garbage class of trees.
- **Objective**: single-objective composite in P2–P3, swap to NSGA-II in P4 for the champion pool.

---

## Status

- [x] Plan written
- [x] Phase 1 — Feature & test foundation
- [x] Phase 2 — Trading-aware fitness
- [x] Phase 3 — Multi-tree + risk controls (P3.5 follow-up: typed GP + ATR stops + held-out backtest override)
- [x] Phase 4 — Robustness & ensembling (P4.5 follow-up: NSGA-II + dashboard panels + ensemble live signal)
- [ ] Phase 5 — Live execution & monitoring
