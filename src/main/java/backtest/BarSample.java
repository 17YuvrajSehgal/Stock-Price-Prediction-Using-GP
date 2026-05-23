package backtest;

/**
 * One bar of input to the {@link Backtester}.
 *
 * <p>Carries the signal in effect at this bar, the realized underlying return,
 * a reference entry price (for logging), and — optionally — intra-bar high/low,
 * a per-bar size multiplier, and a calendar-day key. The optional fields default
 * to {@code NaN} / {@code 1.0} / a date prefix of the signal timestamp; they
 * exist so risk-control features (vol-targeted sizing, daily kill switch) have
 * a stable data path without breaking single-tree callers.
 */
public final class BarSample {

    public final String signalTimestamp;
    public final String executionTimestamp;
    public final int signal;
    public final double rawReturn;
    public final double price;

    public final double barHigh;
    public final double barLow;
    public final double sizeMultiplier;
    public final String dayKey;

    public BarSample(String signalTimestamp, String executionTimestamp,
                     int signal, double rawReturn, double price) {
        this(signalTimestamp, executionTimestamp, signal, rawReturn, price,
                Double.NaN, Double.NaN, 1.0, dayKeyFromTimestamp(signalTimestamp));
    }

    public BarSample(String signalTimestamp, String executionTimestamp,
                     int signal, double rawReturn, double price,
                     double barHigh, double barLow,
                     double sizeMultiplier, String dayKey) {
        this.signalTimestamp = signalTimestamp;
        this.executionTimestamp = executionTimestamp;
        this.signal = signal;
        this.rawReturn = rawReturn;
        this.price = price;
        this.barHigh = barHigh;
        this.barLow = barLow;
        this.sizeMultiplier = sizeMultiplier;
        this.dayKey = dayKey != null ? dayKey : dayKeyFromTimestamp(signalTimestamp);
    }

    private static String dayKeyFromTimestamp(String ts) {
        if (ts == null) return "";
        return ts.length() >= 10 ? ts.substring(0, 10) : ts;
    }
}
