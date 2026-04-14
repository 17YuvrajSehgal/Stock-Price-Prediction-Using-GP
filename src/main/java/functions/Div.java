package functions;

import ec.EvolutionState;
import ec.Problem;
import ec.gp.ADFStack;
import ec.gp.GPData;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import terminal.DoubleData;


public class Div extends GPNode {
    private static final double EPSILON = 1.0e-9;
	
	public String toString() { return "/"; }

	public int expectedChildren() { return 2; }

	public void eval(final EvolutionState state,
                 final int thread,
                 final GPData input,
                 final ADFStack stack,
                 final GPIndividual individual,
                 final Problem problem) {
		DoubleData rd = ((DoubleData)(input));

	    children[0].eval(state,thread,input,stack,individual,problem);
        double numerator = rd.x;

	    children[1].eval(state,thread,input,stack,individual,problem);
        double denominator = rd.x;

	    rd.x = Math.abs(denominator) < EPSILON ? 1.0 : numerator / denominator;
    }
}

