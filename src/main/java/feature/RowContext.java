package feature;

/**
 * Immutable snapshot of every feature value at a single bar.
 * Values are NaN where there is insufficient history to compute the feature.
 */
public final class RowContext {
    public final int row;
    public final String timestamp;

    public final double open;
    public final double high;
    public final double low;
    public final double close;
    public final double adjustedClose;
    public final double volume;

    public final double previousOpen;
    public final double previousClose;
    public final double dailyRange;
    public final double intradayChange;

    public final double laggedOpenReturn;
    public final double laggedCloseReturn;

    public final double movingTenDayAvg;
    public final double movingFiftyDayAvg;
    public final double volumeTenDayAvg;

    public final double closeVolatilityTenDay;
    public final double closeVolatilityTwentyDay;

    public final double rsi14;
    public final double macd;
    public final double macdSignal;
    public final double macdHist;
    public final double bollingerZ20;
    public final double atr14Pct;
    public final double stochK14;
    public final double stochD14;
    public final double adx14;
    public final double obvSlope10;

    public final double mom5;
    public final double mom10;
    public final double mom20;
    public final double mom60;
    public final double mom120;

    public final double channelPos20;
    public final double channelPos55;
    public final double vwapDist20;

    public final double dowSin;
    public final double dowCos;
    public final double daysToMonthEnd;

    private RowContext(Builder builder) {
        this.row = builder.row;
        this.timestamp = builder.timestamp;
        this.open = builder.open;
        this.high = builder.high;
        this.low = builder.low;
        this.close = builder.close;
        this.adjustedClose = builder.adjustedClose;
        this.volume = builder.volume;
        this.previousOpen = builder.previousOpen;
        this.previousClose = builder.previousClose;
        this.dailyRange = builder.dailyRange;
        this.intradayChange = builder.intradayChange;
        this.laggedOpenReturn = builder.laggedOpenReturn;
        this.laggedCloseReturn = builder.laggedCloseReturn;
        this.movingTenDayAvg = builder.movingTenDayAvg;
        this.movingFiftyDayAvg = builder.movingFiftyDayAvg;
        this.volumeTenDayAvg = builder.volumeTenDayAvg;
        this.closeVolatilityTenDay = builder.closeVolatilityTenDay;
        this.closeVolatilityTwentyDay = builder.closeVolatilityTwentyDay;
        this.rsi14 = builder.rsi14;
        this.macd = builder.macd;
        this.macdSignal = builder.macdSignal;
        this.macdHist = builder.macdHist;
        this.bollingerZ20 = builder.bollingerZ20;
        this.atr14Pct = builder.atr14Pct;
        this.stochK14 = builder.stochK14;
        this.stochD14 = builder.stochD14;
        this.adx14 = builder.adx14;
        this.obvSlope10 = builder.obvSlope10;
        this.mom5 = builder.mom5;
        this.mom10 = builder.mom10;
        this.mom20 = builder.mom20;
        this.mom60 = builder.mom60;
        this.mom120 = builder.mom120;
        this.channelPos20 = builder.channelPos20;
        this.channelPos55 = builder.channelPos55;
        this.vwapDist20 = builder.vwapDist20;
        this.dowSin = builder.dowSin;
        this.dowCos = builder.dowCos;
        this.daysToMonthEnd = builder.daysToMonthEnd;
    }

    public boolean isComplete() {
        return Double.isFinite(previousClose)
                && Double.isFinite(movingTenDayAvg)
                && Double.isFinite(movingFiftyDayAvg)
                && Double.isFinite(volumeTenDayAvg)
                && Double.isFinite(closeVolatilityTenDay)
                && Double.isFinite(closeVolatilityTwentyDay);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        int row;
        String timestamp = "";
        double open, high, low, close, adjustedClose, volume;
        double previousOpen = Double.NaN;
        double previousClose = Double.NaN;
        double dailyRange = Double.NaN;
        double intradayChange = Double.NaN;
        double laggedOpenReturn = Double.NaN;
        double laggedCloseReturn = Double.NaN;
        double movingTenDayAvg = Double.NaN;
        double movingFiftyDayAvg = Double.NaN;
        double volumeTenDayAvg = Double.NaN;
        double closeVolatilityTenDay = Double.NaN;
        double closeVolatilityTwentyDay = Double.NaN;
        double rsi14 = Double.NaN;
        double macd = Double.NaN;
        double macdSignal = Double.NaN;
        double macdHist = Double.NaN;
        double bollingerZ20 = Double.NaN;
        double atr14Pct = Double.NaN;
        double stochK14 = Double.NaN;
        double stochD14 = Double.NaN;
        double adx14 = Double.NaN;
        double obvSlope10 = Double.NaN;
        double mom5 = Double.NaN;
        double mom10 = Double.NaN;
        double mom20 = Double.NaN;
        double mom60 = Double.NaN;
        double mom120 = Double.NaN;
        double channelPos20 = Double.NaN;
        double channelPos55 = Double.NaN;
        double vwapDist20 = Double.NaN;
        double dowSin = Double.NaN;
        double dowCos = Double.NaN;
        double daysToMonthEnd = Double.NaN;

        public Builder row(int v) { this.row = v; return this; }
        public Builder timestamp(String v) { this.timestamp = v; return this; }
        public Builder ohlcv(double o, double h, double l, double c, double ac, double v) {
            this.open = o; this.high = h; this.low = l;
            this.close = c; this.adjustedClose = ac; this.volume = v;
            return this;
        }
        public Builder previousOpen(double v) { this.previousOpen = v; return this; }
        public Builder previousClose(double v) { this.previousClose = v; return this; }
        public Builder dailyRange(double v) { this.dailyRange = v; return this; }
        public Builder intradayChange(double v) { this.intradayChange = v; return this; }
        public Builder laggedOpenReturn(double v) { this.laggedOpenReturn = v; return this; }
        public Builder laggedCloseReturn(double v) { this.laggedCloseReturn = v; return this; }
        public Builder movingTenDayAvg(double v) { this.movingTenDayAvg = v; return this; }
        public Builder movingFiftyDayAvg(double v) { this.movingFiftyDayAvg = v; return this; }
        public Builder volumeTenDayAvg(double v) { this.volumeTenDayAvg = v; return this; }
        public Builder closeVolatilityTenDay(double v) { this.closeVolatilityTenDay = v; return this; }
        public Builder closeVolatilityTwentyDay(double v) { this.closeVolatilityTwentyDay = v; return this; }
        public Builder rsi14(double v) { this.rsi14 = v; return this; }
        public Builder macd(double v) { this.macd = v; return this; }
        public Builder macdSignal(double v) { this.macdSignal = v; return this; }
        public Builder macdHist(double v) { this.macdHist = v; return this; }
        public Builder bollingerZ20(double v) { this.bollingerZ20 = v; return this; }
        public Builder atr14Pct(double v) { this.atr14Pct = v; return this; }
        public Builder stochK14(double v) { this.stochK14 = v; return this; }
        public Builder stochD14(double v) { this.stochD14 = v; return this; }
        public Builder adx14(double v) { this.adx14 = v; return this; }
        public Builder obvSlope10(double v) { this.obvSlope10 = v; return this; }
        public Builder mom5(double v) { this.mom5 = v; return this; }
        public Builder mom10(double v) { this.mom10 = v; return this; }
        public Builder mom20(double v) { this.mom20 = v; return this; }
        public Builder mom60(double v) { this.mom60 = v; return this; }
        public Builder mom120(double v) { this.mom120 = v; return this; }
        public Builder channelPos20(double v) { this.channelPos20 = v; return this; }
        public Builder channelPos55(double v) { this.channelPos55 = v; return this; }
        public Builder vwapDist20(double v) { this.vwapDist20 = v; return this; }
        public Builder dowSin(double v) { this.dowSin = v; return this; }
        public Builder dowCos(double v) { this.dowCos = v; return this; }
        public Builder daysToMonthEnd(double v) { this.daysToMonthEnd = v; return this; }

        public RowContext build() { return new RowContext(this); }
    }
}
