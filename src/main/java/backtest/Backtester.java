package backtest;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, stateless backtester. Consumes a chronological list of {@link BarSample}
 * (each carrying the signal in effect and the realized bar return), applies
 * commissions and slippage according to {@link ExecutionMode}, and reports
 * risk-adjusted performance.
 *
 * <p>The class is intentionally free of any GP / dataset coupling — both the
 * existing {@code Stock} regression problem and the new
 * {@code TradingStrategyProblem} feed it the same way.
 *
 * <p>In {@link ExecutionMode#POSITION_AWARE}, the backtester also enforces
 * three risk controls when configured:
 * <ul>
 *   <li>{@code maxHoldingBars} — force-flatten an open position after N bars.</li>
 *   <li>{@code dailyLossLimitPct} — once cumulative intraday PnL crosses
 *       {@code -limit * dayStartEquity}, force flat and refuse new entries
 *       until the next day key.</li>
 *   <li>Per-bar {@code sizeMultiplier} clipped to {@code maxLeverage}.</li>
 * </ul>
 */
public final class Backtester {

    private static final double EPS = 1.0e-12;

    private Backtester() {
    }

    public static BacktestResult run(BacktestConfig config, List<BarSample> bars) {
        if (config == null) throw new IllegalArgumentException("config is null");
        if (bars == null) throw new IllegalArgumentException("bars is null");

        double equity = config.initialCapital;
        double peakEquity = equity;
        double maxDrawdown = 0.0;
        double grossProfit = 0.0;
        double grossLoss = 0.0;
        double totalNetReturn = 0.0;
        double totalCostRate = 0.0;

        int trades = 0;
        int wins = 0;
        int longTrades = 0;
        int shortTrades = 0;
        int flatSignals = 0;
        int positionChanges = 0;
        int holdingBarsSum = 0;
        int forcedExits = 0;
        int dailyKillSwitchTriggers = 0;

        int currentPosition = 0;
        int currentHoldingBars = 0;
        double currentTradeNetReturn = 0.0;

        List<BarRecord> records = new ArrayList<>(bars.size());
        List<EquityPoint> equityCurve = new ArrayList<>(bars.size());
        double[] perBarNet = new double[bars.size()];

        String currentDay = null;
        double dayStartEquity = equity;
        boolean dayKilled = false;

        for (int i = 0; i < bars.size(); i++) {
            BarSample bar = bars.get(i);

            // Daily bookkeeping (POSITION_AWARE only — kill switch + day-start equity)
            if (config.mode == ExecutionMode.POSITION_AWARE) {
                String dayKey = bar.dayKey != null ? bar.dayKey : "";
                if (!dayKey.equals(currentDay)) {
                    currentDay = dayKey;
                    dayStartEquity = equity;
                    dayKilled = false;
                }
            }

            int requestedSignal = clampSignal(bar.signal);
            boolean forcedCloseThisBar = false;

            if (config.mode == ExecutionMode.POSITION_AWARE) {
                if (dayKilled) {
                    requestedSignal = 0;
                }
                if (config.maxHoldingBars > 0
                        && currentPosition != 0
                        && currentHoldingBars >= config.maxHoldingBars
                        && requestedSignal == currentPosition) {
                    requestedSignal = 0;
                    forcedCloseThisBar = true;
                }
            }

            double sizeMul = effectiveSizeMultiplier(bar, config);

            double grossStrategyReturn;
            double costRate;
            boolean opened = false;
            boolean closed = false;

            if (config.mode == ExecutionMode.ALWAYS_FLAT_OVERNIGHT) {
                if (requestedSignal == 0) {
                    grossStrategyReturn = 0.0;
                    costRate = 0.0;
                } else {
                    grossStrategyReturn = requestedSignal * bar.rawReturn * sizeMul;
                    costRate = config.roundTripCostRate() * sizeMul;
                    opened = true;
                    closed = true;
                    positionChanges++;
                }
            } else { // POSITION_AWARE
                int transition = transitionCost(currentPosition, requestedSignal);
                costRate = transition * config.oneWayCostRate() * sizeMul;
                grossStrategyReturn = requestedSignal == 0 ? 0.0 : requestedSignal * bar.rawReturn * sizeMul;
                if (transition > 0) positionChanges++;
                opened = (currentPosition == 0 && requestedSignal != 0)
                        || (currentPosition != 0 && requestedSignal != 0 && requestedSignal != currentPosition);
                closed = (currentPosition != 0 && requestedSignal == 0)
                        || (currentPosition != 0 && requestedSignal != 0 && requestedSignal != currentPosition);
            }

            double netStrategyReturn = grossStrategyReturn - costRate;
            double pnl = equity * netStrategyReturn;
            equity += pnl;
            peakEquity = Math.max(peakEquity, equity);
            maxDrawdown = Math.max(maxDrawdown, (peakEquity - equity) / Math.max(peakEquity, EPS));

            perBarNet[i] = netStrategyReturn;

            // Trade accounting
            if (config.mode == ExecutionMode.ALWAYS_FLAT_OVERNIGHT) {
                if (requestedSignal == 0) {
                    flatSignals++;
                } else {
                    trades++;
                    totalCostRate += costRate;
                    totalNetReturn += netStrategyReturn;
                    if (requestedSignal > 0) longTrades++; else shortTrades++;
                    if (netStrategyReturn > 0) {
                        wins++;
                        grossProfit += netStrategyReturn;
                    } else if (netStrategyReturn < 0) {
                        grossLoss += netStrategyReturn;
                    }
                    holdingBarsSum += 1;
                }
            } else { // POSITION_AWARE
                if (requestedSignal == 0) {
                    flatSignals++;
                }
                if (closed) {
                    trades++;
                    totalNetReturn += currentTradeNetReturn;
                    totalCostRate += costRate;
                    if (currentPosition > 0) longTrades++; else shortTrades++;
                    if (currentTradeNetReturn > 0) {
                        wins++;
                        grossProfit += currentTradeNetReturn;
                    } else if (currentTradeNetReturn < 0) {
                        grossLoss += currentTradeNetReturn;
                    }
                    holdingBarsSum += currentHoldingBars;
                    if (forcedCloseThisBar) forcedExits++;
                    currentTradeNetReturn = 0.0;
                    currentHoldingBars = 0;
                }
                if (requestedSignal != 0) {
                    currentTradeNetReturn += netStrategyReturn;
                    currentHoldingBars++;
                }
                currentPosition = requestedSignal;
            }

            equityCurve.add(new EquityPoint(bar.executionTimestamp, equity));
            records.add(new BarRecord(
                    bar.signalTimestamp, bar.executionTimestamp, bar.price, requestedSignal,
                    bar.rawReturn, grossStrategyReturn, costRate, netStrategyReturn, pnl, equity,
                    opened, closed));

            // Daily loss kill switch — check after applying this bar's PnL.
            if (config.mode == ExecutionMode.POSITION_AWARE
                    && config.dailyLossLimitPct > 0.0
                    && !dayKilled
                    && dayStartEquity > EPS
                    && (equity - dayStartEquity) / dayStartEquity < -config.dailyLossLimitPct) {
                dayKilled = true;
                dailyKillSwitchTriggers++;
            }
        }

        // Flush an open POSITION_AWARE trade at end of series.
        if (config.mode == ExecutionMode.POSITION_AWARE && currentPosition != 0 && currentHoldingBars > 0) {
            trades++;
            totalNetReturn += currentTradeNetReturn;
            if (currentPosition > 0) longTrades++; else shortTrades++;
            if (currentTradeNetReturn > 0) {
                wins++;
                grossProfit += currentTradeNetReturn;
            } else if (currentTradeNetReturn < 0) {
                grossLoss += currentTradeNetReturn;
            }
            holdingBarsSum += currentHoldingBars;
        }

        double totalReturnPct = percentage(equity - config.initialCapital, config.initialCapital);
        double winRatePct = trades == 0 ? 0.0 : 100.0 * wins / trades;
        double avgNetTradeReturn = trades == 0 ? 0.0 : totalNetReturn / trades;
        double avgHoldingBars = trades == 0 ? 0.0 : (double) holdingBarsSum / trades;
        double profitFactor = Math.abs(grossLoss) < EPS ? grossProfit : grossProfit / Math.abs(grossLoss);

        double sharpe = annualizedSharpe(perBarNet, config.barsPerYear);
        double sortino = annualizedSortino(perBarNet, config.barsPerYear);
        double maxDrawdownPct = maxDrawdown * 100.0;
        double cagr = cagr(config.initialCapital, equity, bars.size(), config.barsPerYear);
        double calmar = maxDrawdown < EPS ? (cagr > 0 ? Double.POSITIVE_INFINITY : 0.0) : cagr / maxDrawdown;
        double turnover = bars.isEmpty() ? 0.0 : (double) positionChanges / bars.size();

        return new BacktestResult(
                config.initialCapital, equity, totalReturnPct, maxDrawdownPct,
                trades, wins, longTrades, shortTrades, flatSignals,
                winRatePct, avgNetTradeReturn, avgHoldingBars, totalCostRate, profitFactor,
                sharpe, sortino, calmar, cagr, turnover,
                forcedExits, dailyKillSwitchTriggers,
                bars.size(), records, equityCurve);
    }

    // -- helpers --------------------------------------------------------------

    private static double effectiveSizeMultiplier(BarSample bar, BacktestConfig config) {
        double mul = Double.isFinite(bar.sizeMultiplier) ? bar.sizeMultiplier : 1.0;
        if (mul < 0.0) mul = 0.0;
        double scaled = mul * config.positionFraction;
        if (config.maxLeverage > 0 && scaled > config.maxLeverage) scaled = config.maxLeverage;
        return scaled;
    }

    private static int clampSignal(int signal) {
        if (signal > 0) return 1;
        if (signal < 0) return -1;
        return 0;
    }

    /** Number of one-way legs incurred transitioning from {@code from} to {@code to}. */
    private static int transitionCost(int from, int to) {
        if (from == to) return 0;
        if (from == 0 || to == 0) return 1;   // open or close
        return 2;                              // flip pays close + reopen
    }

    static double annualizedSharpe(double[] returns, int barsPerYear) {
        if (returns.length < 2) return 0.0;
        double mean = mean(returns);
        double std = stddev(returns, mean);
        if (std < EPS) return 0.0;
        return mean / std * Math.sqrt(barsPerYear);
    }

    static double annualizedSortino(double[] returns, int barsPerYear) {
        if (returns.length < 2) return 0.0;
        double mean = mean(returns);
        double downsideVar = 0.0;
        int downsideCount = 0;
        for (double r : returns) {
            if (r < 0) {
                downsideVar += r * r;
                downsideCount++;
            }
        }
        if (downsideCount == 0) return 0.0;
        double downsideStd = Math.sqrt(downsideVar / downsideCount);
        if (downsideStd < EPS) return 0.0;
        return mean / downsideStd * Math.sqrt(barsPerYear);
    }

    static double cagr(double initial, double finalEquity, int barCount, int barsPerYear) {
        if (initial <= 0 || finalEquity <= 0 || barCount <= 0 || barsPerYear <= 0) return 0.0;
        double years = (double) barCount / barsPerYear;
        if (years < EPS) return 0.0;
        return Math.pow(finalEquity / initial, 1.0 / years) - 1.0;
    }

    private static double mean(double[] xs) {
        double s = 0.0;
        for (double x : xs) s += x;
        return s / xs.length;
    }

    private static double stddev(double[] xs, double mean) {
        double s = 0.0;
        for (double x : xs) {
            double d = x - mean;
            s += d * d;
        }
        return Math.sqrt(s / xs.length);
    }

    private static double percentage(double numerator, double denominator) {
        if (Math.abs(denominator) < EPS) return 0.0;
        return 100.0 * numerator / denominator;
    }
}
