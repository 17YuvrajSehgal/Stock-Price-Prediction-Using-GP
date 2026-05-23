package backtest;

public final class EquityPoint {
    public final String executionTimestamp;
    public final double equity;

    public EquityPoint(String executionTimestamp, double equity) {
        this.executionTimestamp = executionTimestamp;
        this.equity = equity;
    }
}
