package validation;

import backtest.BacktestConfig;
import backtest.BacktestResult;
import backtest.Backtester;
import backtest.BarSample;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Robustness statistics for a trading strategy: block-bootstrap Sharpe
 * confidence intervals, signal-shuffle permutation tests, Probabilistic Sharpe
 * Ratio (Bailey & López de Prado), and Deflated Sharpe Ratio.
 *
 * <p>All methods are pure functions — they take input data and return value
 * objects. Nothing here mutates global or problem state.
 */
public final class Validator {

    private static final double EPS = 1.0e-12;

    private Validator() {
    }

    // -- Block bootstrap on the per-bar net return series ---------------------

    public static BootstrapResult blockBootstrapSharpe(double[] perBarNet, int blockSize,
                                                      int resamples, int barsPerYear, long seed) {
        if (perBarNet == null || perBarNet.length < blockSize || blockSize < 1 || resamples < 1) {
            return new BootstrapResult(Double.NaN, Double.NaN, Double.NaN, 0);
        }
        Random rng = new Random(seed);
        double[] sharpes = new double[resamples];
        for (int r = 0; r < resamples; r++) {
            double[] resample = blockResample(perBarNet, blockSize, rng);
            sharpes[r] = annualizedSharpe(resample, barsPerYear);
        }
        Arrays.sort(sharpes);
        double p05 = percentile(sharpes, 0.05);
        double p50 = percentile(sharpes, 0.50);
        double p95 = percentile(sharpes, 0.95);
        return new BootstrapResult(p05, p50, p95, resamples);
    }

    static double[] blockResample(double[] series, int blockSize, Random rng) {
        int n = series.length;
        double[] out = new double[n];
        int written = 0;
        while (written < n) {
            int start = rng.nextInt(n - blockSize + 1);
            int copy = Math.min(blockSize, n - written);
            System.arraycopy(series, start, out, written, copy);
            written += copy;
        }
        return out;
    }

    // -- Signal-shuffle permutation test --------------------------------------

    /**
     * Shuffle the signal column relative to the bar returns and count how often
     * the permuted strategy's Sharpe is at least as good as the observed one.
     * Returns the observed Sharpe and the p-value.
     */
    public static PermutationResult permutationTest(List<BarSample> bars, BacktestConfig config,
                                                    int resamples, long seed) {
        if (bars == null || bars.isEmpty() || resamples < 1) {
            return new PermutationResult(Double.NaN, 1.0, 0);
        }
        BacktestResult observed = Backtester.run(config, bars);
        double observedSharpe = observed.sharpe;

        int[] signals = new int[bars.size()];
        for (int i = 0; i < bars.size(); i++) signals[i] = bars.get(i).signal;

        Random rng = new Random(seed);
        int hits = 0;
        int[] shuffled = signals.clone();
        for (int r = 0; r < resamples; r++) {
            shuffleInPlace(shuffled, rng);
            List<BarSample> permuted = rebuildWithSignals(bars, shuffled);
            double permutedSharpe = Backtester.run(config, permuted).sharpe;
            if (permutedSharpe >= observedSharpe) hits++;
        }
        double pValue = (hits + 1.0) / (resamples + 1.0);
        return new PermutationResult(observedSharpe, pValue, resamples);
    }

    static void shuffleInPlace(int[] xs, Random rng) {
        for (int i = xs.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = xs[i];
            xs[i] = xs[j];
            xs[j] = tmp;
        }
    }

    static List<BarSample> rebuildWithSignals(List<BarSample> bars, int[] newSignals) {
        List<BarSample> out = new ArrayList<>(bars.size());
        for (int i = 0; i < bars.size(); i++) {
            BarSample b = bars.get(i);
            out.add(new BarSample(
                    b.signalTimestamp, b.executionTimestamp, newSignals[i], b.rawReturn, b.price,
                    b.barHigh, b.barLow, b.sizeMultiplier, b.dayKey));
        }
        return out;
    }

    // -- Probabilistic / Deflated Sharpe Ratio --------------------------------

    /**
     * Probabilistic Sharpe Ratio — probability that the true Sharpe exceeds the
     * benchmark, given the observed sample. Closer to 1 = stronger evidence.
     */
    public static double probabilisticSharpe(double observedSR, double benchmarkSR, int sampleSize,
                                             double skew, double excessKurtosis) {
        if (sampleSize < 2) return 0.5;
        double denom = Math.sqrt(Math.max(EPS,
                1.0 - skew * observedSR + (excessKurtosis / 4.0) * observedSR * observedSR));
        double z = (observedSR - benchmarkSR) * Math.sqrt(sampleSize - 1) / denom;
        return normalCdf(z);
    }

    /**
     * Deflated Sharpe Ratio. Adjusts the benchmark Sharpe upward to account for
     * the expected maximum Sharpe across {@code trials} random strategies, then
     * returns the probability the observed Sharpe is genuinely above that bar.
     *
     * <p>Implementation follows Bailey &amp; López de Prado (2014), "The Deflated
     * Sharpe Ratio: Correcting for Selection Bias, Backtest Overfitting and
     * Non-Normality."
     */
    public static double deflatedSharpe(double observedSR, int trials, int sampleSize,
                                        double skew, double excessKurtosis) {
        if (trials < 2 || sampleSize < 2) return 0.5;
        double euler = 0.5772156649;
        double e = Math.E;
        double bench = (1.0 - euler) * inverseNormalCdf(1.0 - 1.0 / trials)
                + euler * inverseNormalCdf(1.0 - 1.0 / (trials * e));
        return probabilisticSharpe(observedSR, bench, sampleSize, skew, excessKurtosis);
    }

    // -- Distributional moments helpers ---------------------------------------

    public static double sampleSkew(double[] xs) {
        int n = xs.length;
        if (n < 3) return 0.0;
        double mean = mean(xs);
        double m2 = 0.0, m3 = 0.0;
        for (double x : xs) {
            double d = x - mean;
            m2 += d * d;
            m3 += d * d * d;
        }
        m2 /= n;
        m3 /= n;
        double std = Math.sqrt(m2);
        return std < EPS ? 0.0 : m3 / (std * std * std);
    }

    public static double sampleExcessKurtosis(double[] xs) {
        int n = xs.length;
        if (n < 4) return 0.0;
        double mean = mean(xs);
        double m2 = 0.0, m4 = 0.0;
        for (double x : xs) {
            double d = x - mean;
            m2 += d * d;
            m4 += d * d * d * d;
        }
        m2 /= n;
        m4 /= n;
        if (m2 < EPS) return 0.0;
        return m4 / (m2 * m2) - 3.0;
    }

    // -- Normal CDF / inverse CDF ---------------------------------------------

    /** Standard-normal cumulative distribution function (Abramowitz & Stegun 26.2.17). */
    public static double normalCdf(double x) {
        if (Double.isNaN(x)) return Double.NaN;
        if (x < 0) return 1.0 - normalCdf(-x);
        double t = 1.0 / (1.0 + 0.2316419 * x);
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        double phi = (1.0 / Math.sqrt(2 * Math.PI)) * Math.exp(-0.5 * x * x);
        return 1.0 - phi * poly;
    }

    /** Inverse standard-normal CDF using Acklam's algorithm. */
    public static double inverseNormalCdf(double p) {
        if (p <= 0.0) return Double.NEGATIVE_INFINITY;
        if (p >= 1.0) return Double.POSITIVE_INFINITY;
        final double pLow = 0.02425;
        final double pHigh = 1.0 - pLow;
        final double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        final double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01};
        final double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        final double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
                3.754408661907416e+00};
        double q, r;
        if (p < pLow) {
            q = Math.sqrt(-2.0 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
        }
        if (p > pHigh) {
            q = Math.sqrt(-2.0 * Math.log(1.0 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
        }
        q = p - 0.5;
        r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0);
    }

    // -- low-level helpers ----------------------------------------------------

    private static double annualizedSharpe(double[] returns, int barsPerYear) {
        if (returns.length < 2) return 0.0;
        double mean = mean(returns);
        double std = stddev(returns, mean);
        if (std < EPS) return 0.0;
        return mean / std * Math.sqrt(barsPerYear);
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

    static double percentile(double[] sortedAscending, double q) {
        if (sortedAscending.length == 0) return Double.NaN;
        double idx = q * (sortedAscending.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) return sortedAscending[lo];
        double frac = idx - lo;
        return sortedAscending[lo] * (1 - frac) + sortedAscending[hi] * frac;
    }

    // -- Value objects --------------------------------------------------------

    public static final class BootstrapResult {
        public final double sharpeP05;
        public final double sharpeMedian;
        public final double sharpeP95;
        public final int resamples;

        public BootstrapResult(double p05, double p50, double p95, int resamples) {
            this.sharpeP05 = p05;
            this.sharpeMedian = p50;
            this.sharpeP95 = p95;
            this.resamples = resamples;
        }
    }

    public static final class PermutationResult {
        public final double observedSharpe;
        public final double pValue;
        public final int resamples;

        public PermutationResult(double observedSharpe, double pValue, int resamples) {
            this.observedSharpe = observedSharpe;
            this.pValue = pValue;
            this.resamples = resamples;
        }
    }
}
