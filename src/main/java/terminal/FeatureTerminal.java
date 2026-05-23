package terminal;

import StockPredictor.Stock;
import ec.EvolutionState;
import ec.Problem;
import ec.gp.ADFStack;
import ec.gp.GPData;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import feature.RowContext;

/**
 * Base class for terminals that read a single scalar field from the current
 * {@link RowContext} held by the {@link Stock} problem.
 *
 * <p>Subclasses implement {@link #value(RowContext)} and override
 * {@link #toString()} for the symbolic label used when printing trees.
 */
public abstract class FeatureTerminal extends GPNode {

    @Override
    public final int expectedChildren() {
        return 0;
    }

    @Override
    public final void eval(EvolutionState state,
                           int thread,
                           GPData input,
                           ADFStack stack,
                           GPIndividual individual,
                           Problem problem) {
        DoubleData rd = (DoubleData) input;
        RowContext ctx = ((Stock) problem).activeRow;
        rd.x = ctx == null ? 0.0 : value(ctx);
    }

    protected abstract double value(RowContext ctx);
}
