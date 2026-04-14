package functions;

import ec.EvolutionState;
import ec.Problem;
import ec.gp.ADFStack;
import ec.gp.GPData;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import terminal.DoubleData;

public class Log extends GPNode {
    private static final double EPSILON = 1.0e-9;

    public String toString() { return "log"; }

    public int expectedChildren() { return 1; }

    public void eval(final EvolutionState state,
                     final int thread,
                     final GPData input,
                     final ADFStack stack,
                     final GPIndividual individual,
                     final Problem problem) {
        DoubleData rd = ((DoubleData)(input));

        children[0].eval(state, thread, input, stack, individual, problem);
        rd.x = Math.log(Math.max(Math.abs(rd.x), EPSILON));
    }
}
