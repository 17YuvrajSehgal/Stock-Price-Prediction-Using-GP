package backtest;

/**
 * Static configuration for a {@link Backtester} run.
 *
 * <p>Use {@link #builder()} for readable construction. All percentages are
 * expressed as fractions (e.g. {@code 0.01 = 1%}). Basis-point fields use
 * the usual one-bp = 0.0001 convention.
 *
 * <p>Risk controls (max holding bars, daily loss limit, max leverage) take
 * effect in {@link ExecutionMode#POSITION_AWARE} only. A value of zero / one
 * means "disabled".
 */
public final class BacktestConfig {

    public final double initialCapital;
    public final double positionFraction;
    public final double commissionBps;
    public final double slippageBps;
    public final int barsPerYear;
    public final ExecutionMode mode;

    public final int maxHoldingBars;
    public final double dailyLossLimitPct;
    public final double maxLeverage;

    private BacktestConfig(Builder b) {
        this.initialCapital = b.initialCapital;
        this.positionFraction = b.positionFraction;
        this.commissionBps = b.commissionBps;
        this.slippageBps = b.slippageBps;
        this.barsPerYear = b.barsPerYear;
        this.mode = b.mode;
        this.maxHoldingBars = b.maxHoldingBars;
        this.dailyLossLimitPct = b.dailyLossLimitPct;
        this.maxLeverage = b.maxLeverage;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Round-trip cost as a fraction (e.g. 1bp commission + 2bp slippage → 0.0006). */
    public double roundTripCostRate() {
        return 2.0 * (commissionBps + slippageBps) / 10_000.0;
    }

    /** One-way cost as a fraction. */
    public double oneWayCostRate() {
        return (commissionBps + slippageBps) / 10_000.0;
    }

    public static final class Builder {
        private double initialCapital = 100_000.0;
        private double positionFraction = 1.0;
        private double commissionBps = 1.0;
        private double slippageBps = 2.0;
        private int barsPerYear = 252;
        private ExecutionMode mode = ExecutionMode.ALWAYS_FLAT_OVERNIGHT;
        private int maxHoldingBars = 0;
        private double dailyLossLimitPct = 0.0;
        private double maxLeverage = 1.0;

        public Builder initialCapital(double v) { this.initialCapital = v; return this; }
        public Builder positionFraction(double v) { this.positionFraction = v; return this; }
        public Builder commissionBps(double v) { this.commissionBps = v; return this; }
        public Builder slippageBps(double v) { this.slippageBps = v; return this; }
        public Builder barsPerYear(int v) { this.barsPerYear = v; return this; }
        public Builder mode(ExecutionMode v) { this.mode = v; return this; }
        public Builder maxHoldingBars(int v) { this.maxHoldingBars = v; return this; }
        public Builder dailyLossLimitPct(double v) { this.dailyLossLimitPct = v; return this; }
        public Builder maxLeverage(double v) { this.maxLeverage = v; return this; }
        public BacktestConfig build() { return new BacktestConfig(this); }
    }
}
