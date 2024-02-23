package terminal;

import StockPredictor.Stock;
import ec.EvolutionState;
import ec.Problem;
import ec.gp.ADFStack;
import ec.gp.GPData;
import ec.gp.GPIndividual;
import ec.gp.GPNode;

public class Open extends GPNode {
    public String toString() {
        return "Open";
    }

    public int expectedChildren() {
        return 0;
    }

    @Override
    public void eval(final EvolutionState state,
                     final int thread,
                     final GPData input,
                     final ADFStack stack,
                     final GPIndividual individual,
                     final Problem problem) {
        DoubleData rd = ((DoubleData) (input));
        rd.x = ((Stock) problem).open;
    }
}
