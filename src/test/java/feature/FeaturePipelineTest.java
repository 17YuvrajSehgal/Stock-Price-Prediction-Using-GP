package feature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeaturePipelineTest {

    private static final double TOL = 1.0e-9;

    @Test
    void smaProducesNaNUntilWindowFilled_thenMatchesArithmeticMean() {
        double[] in = {1, 2, 3, 4, 5, 6};
        double[] out = FeaturePipeline.sma(in, 3);
        assertTrue(Double.isNaN(out[0]));
        assertTrue(Double.isNaN(out[1]));
        assertEquals(2.0, out[2], TOL);
        assertEquals(3.0, out[3], TOL);
        assertEquals(4.0, out[4], TOL);
        assertEquals(5.0, out[5], TOL);
    }

    @Test
    void emaSeedIsSmaOfFirstWindow_thenRecursive() {
        double[] in = {1, 2, 3, 4, 5};
        double[] out = FeaturePipeline.ema(in, 3);
        assertTrue(Double.isNaN(out[0]));
        assertTrue(Double.isNaN(out[1]));
        assertEquals(2.0, out[2], TOL);                 // seed = SMA(1,2,3)
        double alpha = 2.0 / (3.0 + 1.0);
        double e3 = alpha * 4.0 + (1.0 - alpha) * 2.0;
        double e4 = alpha * 5.0 + (1.0 - alpha) * e3;
        assertEquals(e3, out[3], TOL);
        assertEquals(e4, out[4], TOL);
    }

    @Test
    void rsiSaturatesAt100ForMonotoneIncreasingSeries() {
        double[] in = new double[30];
        for (int i = 0; i < in.length; i++) in[i] = 100.0 + i;
        double[] rsi = FeaturePipeline.rsi(in, 14);
        assertEquals(100.0, rsi[in.length - 1], TOL);
    }

    @Test
    void rsiCollapsesToZeroForMonotoneDecreasingSeries() {
        double[] in = new double[30];
        for (int i = 0; i < in.length; i++) in[i] = 100.0 - i;
        double[] rsi = FeaturePipeline.rsi(in, 14);
        assertEquals(0.0, rsi[in.length - 1], 1.0e-6);
    }

    @Test
    void bollingerZIsZeroForConstantSeries() {
        double[] in = new double[40];
        java.util.Arrays.fill(in, 50.0);
        double[] z = FeaturePipeline.bollingerZ(in, 20);
        assertEquals(0.0, z[39], TOL);
    }

    @Test
    void atrEqualsTrueRangeOnFirstWindowForConstantHighLowRange() {
        // High - Low = 2 every bar, closes flat -> TR = 2 always, ATR = 2.
        int n = 30;
        double[] high = new double[n];
        double[] low = new double[n];
        double[] close = new double[n];
        for (int i = 0; i < n; i++) {
            high[i] = 11.0;
            low[i] = 9.0;
            close[i] = 10.0;
        }
        double[] atr = FeaturePipeline.atr(high, low, close, 14);
        assertEquals(2.0, atr[13], TOL);
        assertEquals(2.0, atr[29], TOL);
    }

    @Test
    void stochKSitsAtFiftyForMidRangeClose() {
        int n = 20;
        double[] high = new double[n];
        double[] low = new double[n];
        double[] close = new double[n];
        for (int i = 0; i < n; i++) {
            high[i] = 110;
            low[i] = 90;
            close[i] = 100;
        }
        double[] k = FeaturePipeline.stochasticK(high, low, close, 14);
        assertEquals(50.0, k[19], TOL);
    }

    @Test
    void momentumIsExactlyNetReturnOverLag() {
        double[] in = {10, 10, 10, 11, 12, 13};
        double[] mom = FeaturePipeline.momentum(in, 3);
        // mom at index 3 -> (11 - 10)/10 = 0.10
        assertEquals(0.10, mom[3], TOL);
        // mom at index 5 -> (13 - 10)/10 = 0.30
        assertEquals(0.30, mom[5], TOL);
    }

    @Test
    void channelPositionIsOneAtTopAndZeroAtBottomOfWindow() {
        int n = 5;
        double[] close = {1, 2, 3, 4, 5};
        double[] high = {1, 2, 3, 4, 5};
        double[] low = {1, 2, 3, 4, 5};
        double[] pos = FeaturePipeline.channelPosition(close, high, low, 5);
        assertEquals(1.0, pos[n - 1], TOL);     // close 5 at the top of [1..5]
        double[] close2 = {5, 4, 3, 2, 1};
        double[] high2 = {5, 4, 3, 2, 1};
        double[] low2 = {5, 4, 3, 2, 1};
        double[] pos2 = FeaturePipeline.channelPosition(close2, high2, low2, 5);
        assertEquals(0.0, pos2[n - 1], TOL);
    }

    @Test
    void calendarFeaturesProduceExpectedDoWAndMonthEndDistances() {
        String[] ts = {"2024-01-01", "2024-01-31", "2024-02-29"}; // Mon, Wed, Thu
        double[] sin = new double[ts.length];
        double[] cos = new double[ts.length];
        double[] dte = new double[ts.length];
        FeaturePipeline.calendarFeatures(ts, sin, cos, dte);

        // Monday → dow = 1 → angle = 0
        assertEquals(0.0, sin[0], TOL);
        assertEquals(1.0, cos[0], TOL);

        // 2024-01-31 → 0 days to month end
        assertEquals(0.0, dte[1], TOL);
        // 2024-02-29 (leap year) → 0 days to month end
        assertEquals(0.0, dte[2], TOL);
    }

    @Test
    void computeBuildsFullSeries_andRow0IsIncomplete() {
        int n = 200;
        double[][] data = syntheticOhlcv(n);
        String[] ts = syntheticDailyTimestamps(n);
        FeatureSeries series = FeaturePipeline.compute(data, ts);
        assertEquals(n, series.size());
        assertNotNull(series.contextAt(0));
        // Row 0 has no previous-close history -> must be incomplete.
        assertFalse(series.contextAt(0).isComplete());
        // Row 150 should be fully populated.
        assertTrue(series.contextAt(150).isComplete());
    }

    static double[][] syntheticOhlcv(int n) {
        double[][] data = new double[n][6];
        double price = 100.0;
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < n; i++) {
            double drift = 0.0005;
            double shock = (rng.nextDouble() - 0.5) * 0.02;
            price = Math.max(1.0, price * (1.0 + drift + shock));
            double open = price * (1.0 + (rng.nextDouble() - 0.5) * 0.005);
            double high = Math.max(open, price) * (1.0 + rng.nextDouble() * 0.005);
            double low = Math.min(open, price) * (1.0 - rng.nextDouble() * 0.005);
            double close = price;
            double adj = close;
            double volume = 1_000_000 + rng.nextInt(500_000);
            data[i][0] = open;
            data[i][1] = high;
            data[i][2] = low;
            data[i][3] = close;
            data[i][4] = adj;
            data[i][5] = volume;
        }
        return data;
    }

    static String[] syntheticDailyTimestamps(int n) {
        String[] ts = new String[n];
        java.time.LocalDate d = java.time.LocalDate.of(2020, 1, 2);
        int written = 0;
        while (written < n) {
            int dow = d.getDayOfWeek().getValue();
            if (dow <= 5) {
                ts[written++] = d.toString();
            }
            d = d.plusDays(1);
        }
        return ts;
    }
}
