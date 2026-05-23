package feature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Core invariant: the value of every feature at row {@code i} must depend only
 * on rows {@code 0..i} (inclusive) — never future rows.
 *
 * <p>We verify this empirically by computing features on the full series, then
 * truncating the input to {@code rows[0..k]} for several checkpoint indices and
 * recomputing. The feature value at row {@code k} must be identical in both runs
 * (within floating-point tolerance). If a feature secretly used future data, the
 * truncated computation would differ.
 */
class NoLookaheadTest {

    private static final double TOL = 1.0e-9;

    @Test
    void allRowContextFieldsAreIdenticalWhenComputedOnTruncatedHistory() {
        int n = 260;
        double[][] full = FeaturePipelineTest.syntheticOhlcv(n);
        String[] timestamps = FeaturePipelineTest.syntheticDailyTimestamps(n);
        FeatureSeries fullSeries = FeaturePipeline.compute(full, timestamps);

        int[] checkpoints = {60, 100, 150, 200, 259};
        for (int k : checkpoints) {
            double[][] truncated = new double[k + 1][];
            String[] truncatedTimestamps = new String[k + 1];
            for (int i = 0; i <= k; i++) {
                truncated[i] = full[i].clone();
                truncatedTimestamps[i] = timestamps[i];
            }
            FeatureSeries truncatedSeries = FeaturePipeline.compute(truncated, truncatedTimestamps);

            RowContext fromFull = fullSeries.contextAt(k);
            RowContext fromTruncated = truncatedSeries.contextAt(k);

            assertSameContext("row=" + k, fromFull, fromTruncated);
        }
    }

    private static void assertSameContext(String tag, RowContext a, RowContext b) {
        assertSame(tag, "rsi14", a.rsi14, b.rsi14);
        assertSame(tag, "macd", a.macd, b.macd);
        assertSame(tag, "macdSignal", a.macdSignal, b.macdSignal);
        assertSame(tag, "macdHist", a.macdHist, b.macdHist);
        assertSame(tag, "bollingerZ20", a.bollingerZ20, b.bollingerZ20);
        assertSame(tag, "atr14Pct", a.atr14Pct, b.atr14Pct);
        assertSame(tag, "stochK14", a.stochK14, b.stochK14);
        assertSame(tag, "stochD14", a.stochD14, b.stochD14);
        assertSame(tag, "adx14", a.adx14, b.adx14);
        assertSame(tag, "obvSlope10", a.obvSlope10, b.obvSlope10);
        assertSame(tag, "mom5", a.mom5, b.mom5);
        assertSame(tag, "mom10", a.mom10, b.mom10);
        assertSame(tag, "mom20", a.mom20, b.mom20);
        assertSame(tag, "mom60", a.mom60, b.mom60);
        assertSame(tag, "mom120", a.mom120, b.mom120);
        assertSame(tag, "channelPos20", a.channelPos20, b.channelPos20);
        assertSame(tag, "channelPos55", a.channelPos55, b.channelPos55);
        assertSame(tag, "vwapDist20", a.vwapDist20, b.vwapDist20);
        assertSame(tag, "movingTenDayAvg", a.movingTenDayAvg, b.movingTenDayAvg);
        assertSame(tag, "movingFiftyDayAvg", a.movingFiftyDayAvg, b.movingFiftyDayAvg);
        assertSame(tag, "volumeTenDayAvg", a.volumeTenDayAvg, b.volumeTenDayAvg);
        assertSame(tag, "closeVolatilityTenDay", a.closeVolatilityTenDay, b.closeVolatilityTenDay);
        assertSame(tag, "closeVolatilityTwentyDay", a.closeVolatilityTwentyDay, b.closeVolatilityTwentyDay);
        assertSame(tag, "laggedOpenReturn", a.laggedOpenReturn, b.laggedOpenReturn);
        assertSame(tag, "laggedCloseReturn", a.laggedCloseReturn, b.laggedCloseReturn);
        assertSame(tag, "dailyRange", a.dailyRange, b.dailyRange);
        assertSame(tag, "intradayChange", a.intradayChange, b.intradayChange);
        assertSame(tag, "previousClose", a.previousClose, b.previousClose);
        assertSame(tag, "previousOpen", a.previousOpen, b.previousOpen);
        assertSame(tag, "dowSin", a.dowSin, b.dowSin);
        assertSame(tag, "dowCos", a.dowCos, b.dowCos);
        assertSame(tag, "daysToMonthEnd", a.daysToMonthEnd, b.daysToMonthEnd);
    }

    private static void assertSame(String tag, String field, double expected, double actual) {
        boolean bothNaN = Double.isNaN(expected) && Double.isNaN(actual);
        if (bothNaN) return;
        assertEquals(expected, actual, TOL, () -> tag + " field=" + field + " differs (lookahead?)");
    }

    @Test
    void previousCloseAtRowZeroIsNaN_NotAliasedToCurrentClose() {
        int n = 5;
        double[][] data = FeaturePipelineTest.syntheticOhlcv(n);
        String[] ts = FeaturePipelineTest.syntheticDailyTimestamps(n);
        FeatureSeries series = FeaturePipeline.compute(data, ts);
        RowContext row0 = series.contextAt(0);
        assertTrue(Double.isNaN(row0.previousClose), "row 0 previousClose should be NaN, not aliased to close");
        assertTrue(Double.isNaN(row0.previousOpen), "row 0 previousOpen should be NaN, not aliased to open");
        assertTrue(Double.isNaN(row0.laggedCloseReturn), "row 0 laggedCloseReturn should be NaN");
    }
}
