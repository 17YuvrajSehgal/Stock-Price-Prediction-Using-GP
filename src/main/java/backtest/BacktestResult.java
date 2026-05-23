package backtest;

import java.util.Collections;
import java.util.List;

/**
 * Output of a {@link Backtester} run: scalar metrics plus per-bar records and
 * the resulting equity curve.
 */
public final class BacktestResult {

    public final double initialCapital;
    public final double finalEquity;
    public final double totalReturnPct;
    public final double maxDrawdownPct;

    public final int trades;
    public final int wins;
    public final int longTrades;
    public final int shortTrades;
    public final int flatSignals;
    public final double winRatePct;
    public final double averageNetTradeReturn;
    public final double averageHoldingBars;
    public final double totalCostRate;
    public final double profitFactor;

    public final double sharpe;
    public final double sortino;
    public final double calmar;
    public final double cagr;
    public final double turnover;

    public final int forcedExits;
    public final int dailyKillSwitchTriggers;

    public final int barCount;
    public final List<BarRecord> bars;
    public final List<EquityPoint> equityCurve;

    BacktestResult(double initialCapital, double finalEquity, double totalReturnPct, double maxDrawdownPct,
                   int trades, int wins, int longTrades, int shortTrades, int flatSignals,
                   double winRatePct, double averageNetTradeReturn, double averageHoldingBars,
                   double totalCostRate, double profitFactor, double sharpe, double sortino,
                   double calmar, double cagr, double turnover, int forcedExits, int dailyKillSwitchTriggers,
                   int barCount, List<BarRecord> bars, List<EquityPoint> equityCurve) {
        this.initialCapital = initialCapital;
        this.finalEquity = finalEquity;
        this.totalReturnPct = totalReturnPct;
        this.maxDrawdownPct = maxDrawdownPct;
        this.trades = trades;
        this.wins = wins;
        this.longTrades = longTrades;
        this.shortTrades = shortTrades;
        this.flatSignals = flatSignals;
        this.winRatePct = winRatePct;
        this.averageNetTradeReturn = averageNetTradeReturn;
        this.averageHoldingBars = averageHoldingBars;
        this.totalCostRate = totalCostRate;
        this.profitFactor = profitFactor;
        this.sharpe = sharpe;
        this.sortino = sortino;
        this.calmar = calmar;
        this.cagr = cagr;
        this.turnover = turnover;
        this.forcedExits = forcedExits;
        this.dailyKillSwitchTriggers = dailyKillSwitchTriggers;
        this.barCount = barCount;
        this.bars = Collections.unmodifiableList(bars);
        this.equityCurve = Collections.unmodifiableList(equityCurve);
    }
}
