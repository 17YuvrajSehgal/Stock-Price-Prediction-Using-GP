package backtest;

/**
 * Per-bar output of the backtester, suitable for CSV export.
 */
public final class BarRecord {
    public final String signalTimestamp;
    public final String executionTimestamp;
    public final double entryPrice;
    public final int signal;
    public final double rawReturn;
    public final double grossStrategyReturn;
    public final double costRate;
    public final double netStrategyReturn;
    public final double pnl;
    public final double equity;
    public final boolean tradeOpenedHere;
    public final boolean tradeClosedHere;

    BarRecord(String signalTimestamp, String executionTimestamp, double entryPrice, int signal,
              double rawReturn, double grossStrategyReturn, double costRate, double netStrategyReturn,
              double pnl, double equity, boolean tradeOpenedHere, boolean tradeClosedHere) {
        this.signalTimestamp = signalTimestamp;
        this.executionTimestamp = executionTimestamp;
        this.entryPrice = entryPrice;
        this.signal = signal;
        this.rawReturn = rawReturn;
        this.grossStrategyReturn = grossStrategyReturn;
        this.costRate = costRate;
        this.netStrategyReturn = netStrategyReturn;
        this.pnl = pnl;
        this.equity = equity;
        this.tradeOpenedHere = tradeOpenedHere;
        this.tradeClosedHere = tradeClosedHere;
    }
}
