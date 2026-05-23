package validation;

import backtest.BacktestConfig;
import backtest.BarSample;
import backtest.ExecutionMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorTest {

    @Test
    void normalCdfMatchesKnownValues() {
        assertEquals(0.5, Validator.normalCdf(0.0), 1.0e-7);
        assertEquals(0.84134, Validator.normalCdf(1.0), 1.0e-4);
        assertEquals(0.97725, Validator.normalCdf(2.0), 1.0e-4);
        assertEquals(1.0 - 0.84134, Validator.normalCdf(-1.0), 1.0e-4);
    }

    @Test
    void inverseNormalCdfMatchesKnownValues() {
        assertEquals(0.0, Validator.inverseNormalCdf(0.5), 1.0e-7);
        assertEquals(1.0, Validator.inverseNormalCdf(0.84134), 5.0e-4);
        assertEquals(-1.0, Validator.inverseNormalCdf(1.0 - 0.84134), 5.0e-4);
    }

    @Test
    void blockBootstrapCIBracketsObservedSharpeOnNoise() {
        // White-noise per-bar returns; observed Sharpe ≈ 0; CI should bracket 0.
        Random rng = new Random(7);
        double[] rets = new double[500];
        for (int i = 0; i < rets.length; i++) rets[i] = (rng.nextGaussian()) * 0.01;
        Validator.BootstrapResult b = Validator.blockBootstrapSharpe(rets, 5, 500, 252, 42L);
        assertTrue(b.sharpeP05 <= b.sharpeMedian);
        assertTrue(b.sharpeMedian <= b.sharpeP95);
        // For white noise with N=500 and 252 annualization, p95-p05 should be wide.
        assertTrue(b.sharpeP95 - b.sharpeP05 > 0.3,
                "bootstrap CI width should reflect uncertainty, got " + (b.sharpeP95 - b.sharpeP05));
    }

    @Test
    void permutationTestReturnsHighPValueWhenSignalIsRandom() {
        // Random signals, random returns -> observed Sharpe ~ 0 -> p-value should be near 0.5
        Random rng = new Random(11);
        List<BarSample> bars = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int signal = rng.nextInt(3) - 1; // -1, 0, +1
            double ret = rng.nextGaussian() * 0.01;
            bars.add(new BarSample("D" + i, "D" + (i + 1), signal, ret, 100.0));
        }
        BacktestConfig cfg = BacktestConfig.builder()
                .commissionBps(0).slippageBps(0)
                .mode(ExecutionMode.POSITION_AWARE).build();
        Validator.PermutationResult r = Validator.permutationTest(bars, cfg, 200, 99L);
        assertTrue(r.pValue > 0.15 && r.pValue < 0.85,
                "p-value for random data should be near 0.5, got " + r.pValue);
    }

    @Test
    void permutationTestReturnsLowPValueForStrongSignal() {
        // Perfect signal: sign matches return -> observed Sharpe huge; almost no permutation beats it.
        List<BarSample> bars = new ArrayList<>();
        Random rng = new Random(3);
        for (int i = 0; i < 200; i++) {
            double ret = rng.nextGaussian() * 0.01;
            int signal = ret > 0 ? 1 : -1;
            bars.add(new BarSample("D" + i, "D" + (i + 1), signal, ret, 100.0));
        }
        BacktestConfig cfg = BacktestConfig.builder()
                .commissionBps(0).slippageBps(0)
                .mode(ExecutionMode.POSITION_AWARE).build();
        Validator.PermutationResult r = Validator.permutationTest(bars, cfg, 200, 7L);
        assertTrue(r.pValue < 0.05, "strong-signal p-value should be tiny, got " + r.pValue);
    }

    @Test
    void deflatedSharpeShrinksAsTrialsGrow() {
        // Same observed Sharpe, more trials -> lower DSR confidence.
        double observedSR = 1.5;
        int sample = 1000;
        double dsrFewTrials = Validator.deflatedSharpe(observedSR, 10, sample, 0.0, 0.0);
        double dsrManyTrials = Validator.deflatedSharpe(observedSR, 10_000, sample, 0.0, 0.0);
        assertTrue(dsrFewTrials > dsrManyTrials,
                "DSR should decrease as we test more strategies, got " + dsrFewTrials + " -> " + dsrManyTrials);
    }

    @Test
    void probabilisticSharpeIsMonotonicInObservedSharpe() {
        double psrLow = Validator.probabilisticSharpe(0.2, 0.0, 500, 0.0, 0.0);
        double psrHigh = Validator.probabilisticSharpe(2.0, 0.0, 500, 0.0, 0.0);
        assertTrue(psrLow < psrHigh);
        assertTrue(psrHigh > 0.99, "very high Sharpe in 500 bars should be near-certainly real, got " + psrHigh);
    }

    @Test
    void sampleSkewAndExcessKurtosisHandleGaussianData() {
        Random rng = new Random(123);
        double[] xs = new double[5000];
        for (int i = 0; i < xs.length; i++) xs[i] = rng.nextGaussian();
        assertEquals(0.0, Validator.sampleSkew(xs), 0.15);
        assertEquals(0.0, Validator.sampleExcessKurtosis(xs), 0.30);
    }
}
