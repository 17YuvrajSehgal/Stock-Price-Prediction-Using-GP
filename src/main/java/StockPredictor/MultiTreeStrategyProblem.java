package StockPredictor;

import backtest.BarSample;
import ec.EvolutionState;
import ec.gp.GPIndividual;
import terminal.DoubleData;

import java.util.ArrayList;
import java.util.List;

/**
 * Four-tree decomposition of a trading strategy. Each individual carries:
 *
 * <ol>
 *   <li>{@code tree[0]} — long-entry alpha. Positive output activates a long.</li>
 *   <li>{@code tree[1]} — short-entry alpha. Positive output activates a short.</li>
 *   <li>{@code tree[2]} — exit signal. Positive output forces flat (overrides
 *       any entry decision).</li>
 *   <li>{@code tree[3]} — raw size. Mapped via {@code |tanh(x)|} to {@code [0, 1]}
 *       and passed to the backtester as a per-bar {@code sizeMultiplier}. The
 *       backtester then clips by {@code risk.max-leverage}.</li>
 * </ol>
 *
 * <p>If both long and short trees fire simultaneously the bar is treated as
 * flat (ambiguous). The exit tree dominates both entry trees.
 *
 * <p>Typing is loose — every tree shares the parent function set and produces
 * a double. Boolean interpretation uses the "positive = true" convention. A
 * follow-up (P3.5) will introduce strongly-typed GP so the boolean and double
 * function spaces are separated at the language level.
 */
public class MultiTreeStrategyProblem extends TradingStrategyProblem {

    @Override
    protected List<BarSample> buildBarsForRange(EvolutionState state, GPIndividual gp, int threadNum, int startRow, int endExclusive) {
        DoubleData input = (DoubleData) this.input;
        List<BarSample> bars = new ArrayList<>();
        int start = Math.max(0, startRow);
        int end = Math.min(endExclusive, stockData.length - 1);

        if (gp.trees.length < 4) {
            state.output.fatal("MultiTreeStrategyProblem requires numtrees=4 (got " + gp.trees.length + ")");
            return bars;
        }

        for (int row = start; row < end; row++) {
            if (!loadRowContext(stockData, row)) continue;

            double entryPrice = stockData[row][backtestEntryColumnIndex];
            if (entryPrice <= 0.0) continue;

            double longAlpha = evalTree(gp, 0, state, threadNum, input);
            double shortAlpha = evalTree(gp, 1, state, threadNum, input);
            double exitAlpha = evalTree(gp, 2, state, threadNum, input);
            double sizeRaw = evalTree(gp, 3, state, threadNum, input);

            if (!Double.isFinite(longAlpha) || !Double.isFinite(shortAlpha)
                    || !Double.isFinite(exitAlpha) || !Double.isFinite(sizeRaw)) {
                continue;
            }

            int signal = resolveSignal(longAlpha, shortAlpha, exitAlpha);
            double sizeMul = signal == 0 ? 0.0 : Math.abs(Math.tanh(sizeRaw));
            double rawReturn = realizedTradeReturn(row, entryPrice);

            bars.add(new BarSample(
                    stockTimestamps[row],
                    row + 1 < stockTimestamps.length ? stockTimestamps[row + 1] : stockTimestamps[row],
                    signal,
                    rawReturn,
                    entryPrice,
                    activeRow != null ? activeRow.high : Double.NaN,
                    activeRow != null ? activeRow.low : Double.NaN,
                    sizeMul,
                    stockTimestamps[row]));
        }
        return bars;
    }

    /**
     * Resolves the per-bar signal from the four trees.
     * <ul>
     *   <li>Exit tree positive → flat (overrides both entries).</li>
     *   <li>Only long fires → +1.</li>
     *   <li>Only short fires → -1.</li>
     *   <li>Both or neither fire → 0.</li>
     * </ul>
     */
    protected int resolveSignal(double longAlpha, double shortAlpha, double exitAlpha) {
        if (exitAlpha > 0) return 0;
        boolean wantLong = longAlpha > 0;
        boolean wantShort = shortAlpha > 0;
        if (wantLong && !wantShort) return 1;
        if (wantShort && !wantLong) return -1;
        return 0;
    }

    private double evalTree(GPIndividual gp, int treeIndex, EvolutionState state, int threadNum, DoubleData input) {
        gp.trees[treeIndex].child.eval(state, threadNum, input, this.stack, gp, this);
        return input.x;
    }
}
