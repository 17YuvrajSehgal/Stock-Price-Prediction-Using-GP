package feature;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * Causal, no-lookahead feature engineering pipeline.
 *
 * <p>The pipeline takes the raw OHLCV history (rows of
 * {@code [open, high, low, close, adjustedClose, volume]}) plus aligned timestamps,
 * and precomputes per-bar indicator arrays. Each indicator value at row {@code i}
 * depends only on rows {@code 0..i} (inclusive). Insufficient-history values are
 * {@link Double#NaN}.
 *
 * <p>Usage:
 * <pre>{@code
 *   FeatureSeries series = FeaturePipeline.compute(ohlcv, timestamps);
 *   RowContext ctx = series.contextAt(i);
 *   double rsi = ctx.rsi14;
 * }</pre>
 */
public final class FeaturePipeline {

    public static final int OPEN_INDEX = 0;
    public static final int HIGH_INDEX = 1;
    public static final int LOW_INDEX = 2;
    public static final int CLOSE_INDEX = 3;
    public static final int ADJUSTED_CLOSE_INDEX = 4;
    public static final int VOLUME_INDEX = 5;

    private static final double EPS = 1.0e-12;

    private FeaturePipeline() {
    }

    public static FeatureSeries compute(double[][] data, String[] timestamps) {
        if (data == null) throw new IllegalArgumentException("data is null");
        if (timestamps == null) throw new IllegalArgumentException("timestamps is null");
        if (data.length != timestamps.length) {
            throw new IllegalArgumentException("data and timestamps length mismatch");
        }
        int n = data.length;

        double[] open = column(data, OPEN_INDEX);
        double[] high = column(data, HIGH_INDEX);
        double[] low = column(data, LOW_INDEX);
        double[] close = column(data, CLOSE_INDEX);
        double[] adjustedClose = column(data, ADJUSTED_CLOSE_INDEX);
        double[] volume = column(data, VOLUME_INDEX);

        double[] previousOpen = lagged(open, 1);
        double[] previousClose = lagged(close, 1);
        double[] dailyRange = subtract(high, low);
        double[] intradayChange = subtract(close, open);
        double[] laggedOpenReturn = laggedReturn(open);
        double[] laggedCloseReturn = laggedReturn(close);

        double[] movingTenDayAvg = sma(adjustedClose, 10);
        double[] movingFiftyDayAvg = sma(adjustedClose, 50);
        double[] volumeTenDayAvg = sma(volume, 10);

        double[] closeVolatilityTenDay = rollingReturnVol(close, 10);
        double[] closeVolatilityTwentyDay = rollingReturnVol(close, 20);

        double[] rsi14 = rsi(close, 14);

        double[] ema12 = ema(close, 12);
        double[] ema26 = ema(close, 26);
        double[] macd = subtract(ema12, ema26);
        double[] macdSignal = ema(macd, 9);
        double[] macdHist = subtract(macd, macdSignal);

        double[] bollingerZ20 = bollingerZ(close, 20);

        double[] atr14 = atr(high, low, close, 14);
        double[] atr14Pct = ratio(atr14, close);

        double[] stochK14 = stochasticK(high, low, close, 14);
        double[] stochD14 = sma(stochK14, 3);

        double[] adx14 = adx(high, low, close, 14);
        double[] obvSlope10 = slopeOf(obv(close, volume), 10);

        double[] mom5 = momentum(close, 5);
        double[] mom10 = momentum(close, 10);
        double[] mom20 = momentum(close, 20);
        double[] mom60 = momentum(close, 60);
        double[] mom120 = momentum(close, 120);

        double[] channelPos20 = channelPosition(close, high, low, 20);
        double[] channelPos55 = channelPosition(close, high, low, 55);
        double[] vwapDist20 = vwapDistance(close, volume, 20);

        double[] dowSin = new double[n];
        double[] dowCos = new double[n];
        double[] daysToMonthEnd = new double[n];
        calendarFeatures(timestamps, dowSin, dowCos, daysToMonthEnd);

        RowContext[] contexts = new RowContext[n];
        for (int i = 0; i < n; i++) {
            contexts[i] = RowContext.builder()
                    .row(i)
                    .timestamp(timestamps[i])
                    .ohlcv(open[i], high[i], low[i], close[i], adjustedClose[i], volume[i])
                    .previousOpen(previousOpen[i])
                    .previousClose(previousClose[i])
                    .dailyRange(dailyRange[i])
                    .intradayChange(intradayChange[i])
                    .laggedOpenReturn(laggedOpenReturn[i])
                    .laggedCloseReturn(laggedCloseReturn[i])
                    .movingTenDayAvg(movingTenDayAvg[i])
                    .movingFiftyDayAvg(movingFiftyDayAvg[i])
                    .volumeTenDayAvg(volumeTenDayAvg[i])
                    .closeVolatilityTenDay(closeVolatilityTenDay[i])
                    .closeVolatilityTwentyDay(closeVolatilityTwentyDay[i])
                    .rsi14(rsi14[i])
                    .macd(macd[i])
                    .macdSignal(macdSignal[i])
                    .macdHist(macdHist[i])
                    .bollingerZ20(bollingerZ20[i])
                    .atr14Pct(atr14Pct[i])
                    .stochK14(stochK14[i])
                    .stochD14(stochD14[i])
                    .adx14(adx14[i])
                    .obvSlope10(obvSlope10[i])
                    .mom5(mom5[i])
                    .mom10(mom10[i])
                    .mom20(mom20[i])
                    .mom60(mom60[i])
                    .mom120(mom120[i])
                    .channelPos20(channelPos20[i])
                    .channelPos55(channelPos55[i])
                    .vwapDist20(vwapDist20[i])
                    .dowSin(dowSin[i])
                    .dowCos(dowCos[i])
                    .daysToMonthEnd(daysToMonthEnd[i])
                    .build();
        }
        return new FeatureSeries(contexts);
    }

    // --- column extraction ---------------------------------------------------

    private static double[] column(double[][] data, int col) {
        double[] out = new double[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = data[i][col];
        }
        return out;
    }

    // --- elementary transforms -----------------------------------------------

    private static double[] lagged(double[] series, int k) {
        int n = series.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = i >= k ? series[i - k] : Double.NaN;
        }
        return out;
    }

    private static double[] subtract(double[] a, double[] b) {
        int n = a.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = a[i] - b[i];
        }
        return out;
    }

    private static double[] ratio(double[] a, double[] b) {
        int n = a.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double denom = Math.abs(b[i]) < EPS ? Double.NaN : b[i];
            out[i] = a[i] / denom;
        }
        return out;
    }

    private static double[] laggedReturn(double[] series) {
        int n = series.length;
        double[] out = new double[n];
        out[0] = Double.NaN;
        for (int i = 1; i < n; i++) {
            double prev = series[i - 1];
            if (Math.abs(prev) < EPS) {
                out[i] = Double.NaN;
            } else {
                out[i] = (series[i] - prev) / Math.abs(prev);
            }
        }
        return out;
    }

    // --- SMA -----------------------------------------------------------------

    static double[] sma(double[] series, int window) {
        int n = series.length;
        double[] out = new double[n];
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += series[i];
            if (i >= window) {
                sum -= series[i - window];
            }
            out[i] = i >= window - 1 ? sum / window : Double.NaN;
        }
        return out;
    }

    // --- EMA -----------------------------------------------------------------

    static double[] ema(double[] series, int window) {
        int n = series.length;
        double[] out = new double[n];
        if (n < window) {
            java.util.Arrays.fill(out, Double.NaN);
            return out;
        }
        double alpha = 2.0 / (window + 1.0);
        // Seed with SMA of the first `window` values.
        double seed = 0.0;
        for (int i = 0; i < window; i++) {
            seed += series[i];
        }
        seed /= window;
        for (int i = 0; i < window - 1; i++) {
            out[i] = Double.NaN;
        }
        out[window - 1] = seed;
        for (int i = window; i < n; i++) {
            out[i] = alpha * series[i] + (1.0 - alpha) * out[i - 1];
        }
        return out;
    }

    // --- Rolling realized vol of (lagged) returns ---------------------------

    static double[] rollingReturnVol(double[] series, int window) {
        int n = series.length;
        double[] ret = laggedReturn(series);
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        for (int i = window; i < n; i++) {
            double mean = 0.0;
            for (int k = i - window + 1; k <= i; k++) {
                mean += ret[k];
            }
            mean /= window;
            double variance = 0.0;
            for (int k = i - window + 1; k <= i; k++) {
                double diff = ret[k] - mean;
                variance += diff * diff;
            }
            variance /= window;
            out[i] = Math.sqrt(variance);
        }
        return out;
    }

    // --- RSI (Wilder smoothing) ---------------------------------------------

    static double[] rsi(double[] close, int period) {
        int n = close.length;
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        if (n <= period) return out;

        double avgGain = 0.0;
        double avgLoss = 0.0;
        for (int i = 1; i <= period; i++) {
            double diff = close[i] - close[i - 1];
            if (diff > 0) avgGain += diff;
            else avgLoss += -diff;
        }
        avgGain /= period;
        avgLoss /= period;
        out[period] = rsiFromAverages(avgGain, avgLoss);

        for (int i = period + 1; i < n; i++) {
            double diff = close[i] - close[i - 1];
            double gain = diff > 0 ? diff : 0.0;
            double loss = diff < 0 ? -diff : 0.0;
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
            out[i] = rsiFromAverages(avgGain, avgLoss);
        }
        return out;
    }

    private static double rsiFromAverages(double avgGain, double avgLoss) {
        if (avgLoss < EPS) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - 100.0 / (1.0 + rs);
    }

    // --- Bollinger band z-score ---------------------------------------------

    static double[] bollingerZ(double[] series, int window) {
        int n = series.length;
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        for (int i = window - 1; i < n; i++) {
            double mean = 0.0;
            for (int k = i - window + 1; k <= i; k++) mean += series[k];
            mean /= window;
            double variance = 0.0;
            for (int k = i - window + 1; k <= i; k++) {
                double diff = series[k] - mean;
                variance += diff * diff;
            }
            double std = Math.sqrt(variance / window);
            out[i] = std < EPS ? 0.0 : (series[i] - mean) / std;
        }
        return out;
    }

    // --- True Range and ATR --------------------------------------------------

    static double[] trueRange(double[] high, double[] low, double[] close) {
        int n = high.length;
        double[] out = new double[n];
        out[0] = high[0] - low[0];
        for (int i = 1; i < n; i++) {
            double a = high[i] - low[i];
            double b = Math.abs(high[i] - close[i - 1]);
            double c = Math.abs(low[i] - close[i - 1]);
            out[i] = Math.max(a, Math.max(b, c));
        }
        return out;
    }

    static double[] atr(double[] high, double[] low, double[] close, int period) {
        int n = high.length;
        double[] tr = trueRange(high, low, close);
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        if (n < period) return out;

        double sum = 0.0;
        for (int i = 0; i < period; i++) sum += tr[i];
        out[period - 1] = sum / period;
        for (int i = period; i < n; i++) {
            out[i] = (out[i - 1] * (period - 1) + tr[i]) / period;
        }
        return out;
    }

    // --- Stochastic %K -------------------------------------------------------

    static double[] stochasticK(double[] high, double[] low, double[] close, int window) {
        int n = high.length;
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        for (int i = window - 1; i < n; i++) {
            double hi = Double.NEGATIVE_INFINITY;
            double lo = Double.POSITIVE_INFINITY;
            for (int k = i - window + 1; k <= i; k++) {
                if (high[k] > hi) hi = high[k];
                if (low[k] < lo) lo = low[k];
            }
            double range = hi - lo;
            out[i] = range < EPS ? 50.0 : 100.0 * (close[i] - lo) / range;
        }
        return out;
    }

    // --- ADX (Wilder) --------------------------------------------------------

    static double[] adx(double[] high, double[] low, double[] close, int period) {
        int n = high.length;
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        if (n < period * 2) return out;

        double[] plusDM = new double[n];
        double[] minusDM = new double[n];
        double[] tr = trueRange(high, low, close);
        for (int i = 1; i < n; i++) {
            double up = high[i] - high[i - 1];
            double down = low[i - 1] - low[i];
            plusDM[i] = (up > down && up > 0) ? up : 0.0;
            minusDM[i] = (down > up && down > 0) ? down : 0.0;
        }

        // Wilder-smoothed +DM, -DM, TR
        double smPlus = 0.0, smMinus = 0.0, smTr = 0.0;
        for (int i = 1; i <= period; i++) {
            smPlus += plusDM[i];
            smMinus += minusDM[i];
            smTr += tr[i];
        }
        double[] dx = new double[n];
        java.util.Arrays.fill(dx, Double.NaN);

        for (int i = period; i < n; i++) {
            if (i > period) {
                smPlus = smPlus - smPlus / period + plusDM[i];
                smMinus = smMinus - smMinus / period + minusDM[i];
                smTr = smTr - smTr / period + tr[i];
            }
            double plusDI = smTr < EPS ? 0.0 : 100.0 * smPlus / smTr;
            double minusDI = smTr < EPS ? 0.0 : 100.0 * smMinus / smTr;
            double sum = plusDI + minusDI;
            dx[i] = sum < EPS ? 0.0 : 100.0 * Math.abs(plusDI - minusDI) / sum;
        }

        // Wilder-smoothed DX -> ADX
        int firstAdx = period * 2 - 1;
        if (firstAdx >= n) return out;
        double seed = 0.0;
        for (int i = period; i <= firstAdx; i++) seed += dx[i];
        out[firstAdx] = seed / period;
        for (int i = firstAdx + 1; i < n; i++) {
            out[i] = (out[i - 1] * (period - 1) + dx[i]) / period;
        }
        return out;
    }

    // --- OBV and its slope ---------------------------------------------------

    static double[] obv(double[] close, double[] volume) {
        int n = close.length;
        double[] out = new double[n];
        out[0] = 0.0;
        for (int i = 1; i < n; i++) {
            double sign = Double.compare(close[i], close[i - 1]);
            out[i] = out[i - 1] + sign * volume[i];
        }
        return out;
    }

    /** Slope of the last `window` values from a simple linear regression (slope per bar). */
    static double[] slopeOf(double[] series, int window) {
        int n = series.length;
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        if (window < 2 || n < window) return out;

        double xMean = (window - 1) / 2.0;
        double xVar = 0.0;
        for (int k = 0; k < window; k++) {
            xVar += (k - xMean) * (k - xMean);
        }
        for (int i = window - 1; i < n; i++) {
            double yMean = 0.0;
            for (int k = 0; k < window; k++) {
                yMean += series[i - window + 1 + k];
            }
            yMean /= window;
            double cov = 0.0;
            for (int k = 0; k < window; k++) {
                cov += (k - xMean) * (series[i - window + 1 + k] - yMean);
            }
            out[i] = xVar < EPS ? 0.0 : cov / xVar;
        }
        return out;
    }

    // --- momentum, channel position, VWAP distance --------------------------

    static double[] momentum(double[] close, int lag) {
        int n = close.length;
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        for (int i = lag; i < n; i++) {
            double prev = close[i - lag];
            if (Math.abs(prev) < EPS) {
                out[i] = Double.NaN;
            } else {
                out[i] = (close[i] - prev) / Math.abs(prev);
            }
        }
        return out;
    }

    static double[] channelPosition(double[] close, double[] high, double[] low, int window) {
        int n = close.length;
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        for (int i = window - 1; i < n; i++) {
            double hi = Double.NEGATIVE_INFINITY;
            double lo = Double.POSITIVE_INFINITY;
            for (int k = i - window + 1; k <= i; k++) {
                if (high[k] > hi) hi = high[k];
                if (low[k] < lo) lo = low[k];
            }
            double range = hi - lo;
            out[i] = range < EPS ? 0.5 : (close[i] - lo) / range;
        }
        return out;
    }

    static double[] vwapDistance(double[] close, double[] volume, int window) {
        int n = close.length;
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        for (int i = window - 1; i < n; i++) {
            double pvSum = 0.0;
            double vSum = 0.0;
            for (int k = i - window + 1; k <= i; k++) {
                pvSum += close[k] * volume[k];
                vSum += volume[k];
            }
            if (vSum < EPS) {
                out[i] = 0.0;
            } else {
                double vwap = pvSum / vSum;
                out[i] = Math.abs(vwap) < EPS ? 0.0 : (close[i] - vwap) / vwap;
            }
        }
        return out;
    }

    // --- Calendar features ---------------------------------------------------

    static void calendarFeatures(String[] timestamps, double[] dowSin, double[] dowCos, double[] daysToMonthEnd) {
        for (int i = 0; i < timestamps.length; i++) {
            LocalDate date = tryParseDate(timestamps[i]);
            if (date == null) {
                dowSin[i] = Double.NaN;
                dowCos[i] = Double.NaN;
                daysToMonthEnd[i] = Double.NaN;
                continue;
            }
            int dow = date.getDayOfWeek().getValue(); // Monday=1..Sunday=7
            double angle = 2.0 * Math.PI * (dow - 1) / 7.0;
            dowSin[i] = Math.sin(angle);
            dowCos[i] = Math.cos(angle);
            int lastDay = YearMonth.from(date).lengthOfMonth();
            daysToMonthEnd[i] = lastDay - date.getDayOfMonth();
        }
    }

    private static LocalDate tryParseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String date = value.length() >= 10 ? value.substring(0, 10) : value;
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
