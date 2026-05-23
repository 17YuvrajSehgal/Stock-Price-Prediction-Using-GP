package backtest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktesterRiskControlsTest {

    private static BarSample bar(String day, int signal, double rawReturn, double size) {
        return new BarSample(day, day + "+1", signal, rawReturn, 100.0,
                Double.NaN, Double.NaN, size, day);
    }

    @Test
    void maxHoldingBarsForcesExitAfterLimit() {
        // 5 bars long, max hold = 3 bars.
        BacktestConfig cfg = BacktestConfig.builder()
                .commissionBps(0).slippageBps(0)
                .mode(ExecutionMode.POSITION_AWARE)
                .maxHoldingBars(3)
                .build();

        List<BarSample> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) bars.add(bar("D" + i, +1, 0.01, 1.0));

        BacktestResult r = Backtester.run(cfg, bars);
        // Trade closed at bar 3 (after 3 bars held), reopened bar 4 — actually no, because requestedSignal becomes 0 only when held >= maxHoldingBars AND requestedSignal == currentPosition.
        // Bar 0: position 0 -> +1 (open, held=1)
        // Bar 1: +1 -> +1 (held=2)
        // Bar 2: +1 -> +1 (held=3)
        // Bar 3: held>=3 and signal==current -> forced flat (close trade). After flat, currentPosition=0.
        // Bar 4: position 0, signal +1 -> open new trade (held=1)
        // Total trades: 2 (one closed by force-exit + final open trade flushed at end).
        assertEquals(1, r.forcedExits);
        assertEquals(2, r.trades);
    }

    @Test
    void dailyLossLimitHaltsTradingForRestOfDay() {
        BacktestConfig cfg = BacktestConfig.builder()
                .initialCapital(100_000.0)
                .commissionBps(0).slippageBps(0)
                .mode(ExecutionMode.POSITION_AWARE)
                .dailyLossLimitPct(0.02)  // 2% intraday limit
                .build();

        // Single trading day with 5 bars. First bar loses 3% (long, -0.03 return).
        // After bar 0 the day PnL = -3% > 2% limit → kill switch fires, all subsequent bars stay flat.
        List<BarSample> bars = new ArrayList<>();
        bars.add(bar("2026-01-01", +1, -0.03, 1.0));
        bars.add(bar("2026-01-01", +1, 0.05, 1.0));     // would have profited but killed
        bars.add(bar("2026-01-01", +1, 0.02, 1.0));
        bars.add(bar("2026-01-01", -1, 0.04, 1.0));     // killed
        // New day -> kill switch resets.
        bars.add(bar("2026-01-02", +1, 0.01, 1.0));     // allowed again

        BacktestResult r = Backtester.run(cfg, bars);
        assertEquals(1, r.dailyKillSwitchTriggers);

        // Equity progression:
        // bar0: 100000 * (1 - 0.03) = 97000 (kill triggers here)
        // bars 1..3: flat (killed) -> 97000
        // bar4: 97000 * 1.01 = 97970
        assertEquals(97970.0, r.finalEquity, 1.0e-6);
    }

    @Test
    void sizeMultiplierScalesGrossReturn() {
        BacktestConfig cfg = BacktestConfig.builder()
                .commissionBps(0).slippageBps(0)
                .mode(ExecutionMode.POSITION_AWARE)
                .build();

        // Long with size 0.5 — should earn half the underlying return.
        List<BarSample> bars = new ArrayList<>();
        bars.add(bar("D0", +1, 0.10, 0.5));

        BacktestResult r = Backtester.run(cfg, bars);
        // gross = 1 * 0.10 * 0.5 = 0.05
        assertEquals(100_000.0 * 1.05, r.finalEquity, 1.0e-6);
    }

    @Test
    void maxLeverageClipsOversizedRequests() {
        BacktestConfig cfg = BacktestConfig.builder()
                .commissionBps(0).slippageBps(0)
                .mode(ExecutionMode.POSITION_AWARE)
                .maxLeverage(2.0)
                .build();

        // Strategy asks for 5x size, capped at 2x.
        List<BarSample> bars = new ArrayList<>();
        bars.add(bar("D0", +1, 0.10, 5.0));

        BacktestResult r = Backtester.run(cfg, bars);
        // Effective size = min(5 * 1.0, 2.0) = 2.0
        // gross = 1 * 0.10 * 2.0 = 0.20
        assertEquals(100_000.0 * 1.20, r.finalEquity, 1.0e-6);
    }

    @Test
    void killSwitchDoesNotFireInAlwaysFlatOvernightMode() {
        // ALWAYS_FLAT_OVERNIGHT must ignore daily-loss-limit and max-holding-bars
        // since those are POSITION_AWARE features.
        BacktestConfig cfg = BacktestConfig.builder()
                .commissionBps(0).slippageBps(0)
                .mode(ExecutionMode.ALWAYS_FLAT_OVERNIGHT)
                .dailyLossLimitPct(0.001)  // tiny — would fire immediately if respected
                .maxHoldingBars(1)
                .build();

        List<BarSample> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) bars.add(bar("2026-01-01", +1, -0.01, 1.0));

        BacktestResult r = Backtester.run(cfg, bars);
        assertEquals(0, r.dailyKillSwitchTriggers);
        assertEquals(0, r.forcedExits);
        assertEquals(5, r.trades);
    }

    @Test
    void sizeMultiplierIsClampedToZeroIfNegative() {
        BacktestConfig cfg = BacktestConfig.builder()
                .commissionBps(0).slippageBps(0)
                .mode(ExecutionMode.POSITION_AWARE)
                .build();

        List<BarSample> bars = new ArrayList<>();
        bars.add(bar("D0", +1, 0.10, -0.5));    // negative size — treat as 0

        BacktestResult r = Backtester.run(cfg, bars);
        // Effective size = max(0, -0.5) * 1.0 = 0 -> grossReturn = 0
        assertTrue(Math.abs(r.finalEquity - 100_000.0) < 1.0e-6,
                "negative size multiplier should be clamped to 0");
    }
}
