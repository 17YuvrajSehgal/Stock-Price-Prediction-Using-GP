package backtest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktesterTest {

    private static final double TOL = 1.0e-9;

    @Test
    void alwaysFlatOvernight_paysRoundTripOnEverySignaledBar() {
        BacktestConfig cfg = BacktestConfig.builder()
                .initialCapital(100_000.0)
                .positionFraction(1.0)
                .commissionBps(1.0)   // 1 bp
                .slippageBps(2.0)     // 2 bp
                .barsPerYear(252)
                .mode(ExecutionMode.ALWAYS_FLAT_OVERNIGHT)
                .build();

        // Round-trip cost = 2*(1+2)/10000 = 0.0006 per signaled bar.
        // Long on bar 0 with +1% raw return -> net = 0.01 - 0.0006 = 0.0094
        // Flat on bar 1 -> 0
        // Short on bar 2 with -1% raw return -> +0.01 gross, net = 0.01 - 0.0006 = 0.0094
        List<BarSample> bars = new ArrayList<>();
        bars.add(new BarSample("D0", "D1", +1, 0.01, 100.0));
        bars.add(new BarSample("D1", "D2", 0, 0.005, 101.0));
        bars.add(new BarSample("D2", "D3", -1, -0.01, 100.5));

        BacktestResult r = Backtester.run(cfg, bars);

        assertEquals(2, r.trades);
        assertEquals(1, r.longTrades);
        assertEquals(1, r.shortTrades);
        assertEquals(1, r.flatSignals);
        assertEquals(2, r.wins);
        assertEquals(100.0, r.winRatePct, TOL);

        // Per-bar net returns: [0.0094, 0, 0.0094]
        // Equity progression compounding: 100000 -> 100940 -> 100940 -> 101888.836
        double afterBar0 = 100_000.0 * (1 + 0.0094);
        double afterBar1 = afterBar0;
        double afterBar2 = afterBar1 * (1 + 0.0094);
        assertEquals(afterBar2, r.finalEquity, 1.0e-6);
    }

    @Test
    void positionAware_chargesCostOnlyOnTransitions() {
        BacktestConfig cfg = BacktestConfig.builder()
                .initialCapital(100_000.0)
                .positionFraction(1.0)
                .commissionBps(1.0)
                .slippageBps(2.0)
                .barsPerYear(252)
                .mode(ExecutionMode.POSITION_AWARE)
                .build();

        // One-way cost = (1+2)/10000 = 0.0003.
        // Bar 0: open long (cost 0.0003), gross 0.01 -> net = 0.0097.
        // Bar 1: hold long (no cost), gross 0.01 -> net = 0.01.
        // Bar 2: hold long (no cost), gross -0.005 -> net = -0.005.
        // Bar 3: close (flat) (cost 0.0003), gross 0 -> net = -0.0003.
        List<BarSample> bars = new ArrayList<>();
        bars.add(new BarSample("D0", "D1", +1, 0.01, 100.0));
        bars.add(new BarSample("D1", "D2", +1, 0.01, 101.0));
        bars.add(new BarSample("D2", "D3", +1, -0.005, 102.01));
        bars.add(new BarSample("D3", "D4", 0, 0.0, 101.5));

        BacktestResult r = Backtester.run(cfg, bars);

        // Exactly one closed trade (3 bars long, then exit).
        assertEquals(1, r.trades);
        assertEquals(1, r.longTrades);
        assertEquals(3.0, r.averageHoldingBars, TOL);

        double expectedNet0 = 0.01 - 0.0003;
        double expectedNet1 = 0.01;
        double expectedNet2 = -0.005;
        double expectedNet3 = -0.0003;
        double equity = 100_000.0;
        equity *= (1 + expectedNet0);
        equity *= (1 + expectedNet1);
        equity *= (1 + expectedNet2);
        equity *= (1 + expectedNet3);
        assertEquals(equity, r.finalEquity, 1.0e-6);
    }

    @Test
    void maxDrawdownPctMatchesManualWalk() {
        BacktestConfig cfg = BacktestConfig.builder()
                .initialCapital(100.0)
                .commissionBps(0.0)
                .slippageBps(0.0)
                .barsPerYear(252)
                .mode(ExecutionMode.POSITION_AWARE)
                .build();

        // Long every bar (one transition at the start). Returns: +20%, -50%, +10%.
        // Equity: 100 -> 120 -> 60 -> 66. Peak = 120, trough = 60. MaxDD = 50%.
        List<BarSample> bars = new ArrayList<>();
        bars.add(new BarSample("D0", "D1", +1, 0.20, 1.0));
        bars.add(new BarSample("D1", "D2", +1, -0.50, 1.2));
        bars.add(new BarSample("D2", "D3", +1, 0.10, 0.6));

        BacktestResult r = Backtester.run(cfg, bars);
        assertEquals(50.0, r.maxDrawdownPct, 1.0e-6);
    }

    @Test
    void sharpeFormulaMatchesManualCalculation() {
        // Returns: +1%, -0.5%, +0.5%, +0%
        // Mean = 0.0025; population stddev across 4 -> sqrt( ((0.01-.0025)^2 + (-0.005-.0025)^2 + (0.005-.0025)^2 + (0-.0025)^2)/4 )
        double[] rets = {0.01, -0.005, 0.005, 0.0};
        double mean = (0.01 - 0.005 + 0.005 + 0.0) / 4.0;
        double var = 0.0;
        for (double r : rets) var += (r - mean) * (r - mean);
        var /= rets.length;
        double std = Math.sqrt(var);
        double expectedSharpe = mean / std * Math.sqrt(252);

        assertEquals(expectedSharpe, Backtester.annualizedSharpe(rets, 252), 1.0e-9);
    }

    @Test
    void sortinoIgnoresUpsideVolatility() {
        // Only downside contributes to denominator. Pure-positive series -> Sortino = 0 (no downside).
        double[] all_positive = {0.01, 0.02, 0.005};
        assertEquals(0.0, Backtester.annualizedSortino(all_positive, 252), TOL);

        double[] mixed = {0.01, -0.01, 0.01, -0.02};
        // Downside vals: -0.01, -0.02 -> downside std = sqrt(((-0.01)^2 + (-0.02)^2)/2) = sqrt(0.000125) ≈ 0.01118
        double mean = (0.01 - 0.01 + 0.01 - 0.02) / 4.0;
        double dvar = (0.01 * 0.01 + 0.02 * 0.02) / 2.0;
        double dstd = Math.sqrt(dvar);
        double expected = mean / dstd * Math.sqrt(252);
        assertEquals(expected, Backtester.annualizedSortino(mixed, 252), 1.0e-9);
    }

    @Test
    void cagrFormulaMatchesCompoundedYearlyGrowth() {
        // 10% return in half a year -> annualized = (1.10)^(1/0.5) - 1 = 0.21.
        double cagr = Backtester.cagr(100.0, 110.0, 126, 252);
        assertEquals(0.21, cagr, 1.0e-9);
    }

    @Test
    void profitFactorCountsGrossWinsOverGrossLosses() {
        BacktestConfig cfg = BacktestConfig.builder()
                .initialCapital(100_000.0)
                .commissionBps(0.0).slippageBps(0.0).barsPerYear(252)
                .mode(ExecutionMode.ALWAYS_FLAT_OVERNIGHT)
                .build();
        // Three wins of +1% each, two losses of -0.5% each.
        List<BarSample> bars = new ArrayList<>();
        bars.add(new BarSample("D0", "D1", +1, 0.01, 100));
        bars.add(new BarSample("D1", "D2", +1, 0.01, 100));
        bars.add(new BarSample("D2", "D3", +1, 0.01, 100));
        bars.add(new BarSample("D3", "D4", +1, -0.005, 100));
        bars.add(new BarSample("D4", "D5", +1, -0.005, 100));

        BacktestResult r = Backtester.run(cfg, bars);
        // wins sum (gross) = 3*0.01 = 0.03; losses sum (gross) = 2*0.005 = 0.01; PF = 3.0
        assertEquals(3.0, r.profitFactor, 1.0e-6);
    }

    @Test
    void emptyBarsProducesZeroMetricsWithoutErrors() {
        BacktestConfig cfg = BacktestConfig.builder().build();
        BacktestResult r = Backtester.run(cfg, new ArrayList<>());
        assertEquals(0, r.trades);
        assertEquals(cfg.initialCapital, r.finalEquity, TOL);
        assertEquals(0.0, r.maxDrawdownPct, TOL);
        assertEquals(0.0, r.sharpe, TOL);
        assertTrue(r.bars.isEmpty());
    }

    @Test
    void turnoverEqualsTransitionRatePerBar() {
        BacktestConfig cfg = BacktestConfig.builder()
                .commissionBps(0).slippageBps(0)
                .mode(ExecutionMode.POSITION_AWARE).build();
        // Signals: 0, +1, +1, 0, -1, 0 -> transitions at bars 1, 3, 4, 5 -> 4 transitions over 6 bars
        int[] sigs = {0, 1, 1, 0, -1, 0};
        List<BarSample> bars = new ArrayList<>();
        for (int i = 0; i < sigs.length; i++) {
            bars.add(new BarSample("S" + i, "E" + i, sigs[i], 0.0, 100));
        }
        BacktestResult r = Backtester.run(cfg, bars);
        assertEquals(4.0 / 6.0, r.turnover, 1.0e-9);
    }
}
