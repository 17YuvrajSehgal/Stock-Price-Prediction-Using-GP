package functions;

import ec.EvolutionState;
import ec.Problem;
import ec.gp.ADFStack;
import ec.gp.GPData;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import terminal.DoubleData;

import java.util.Random;

public class Ephemeral extends GPNode {

	DoubleData rd = new DoubleData();
	
	public Ephemeral() {
		Random rand = new Random();
    	rd.x = rand.nextDouble();
	}
	
	public String toString() { return rd != null ? String.valueOf(rd.x) : "n"; }

    public int expectedChildren() { return 0; }
	
    @Override
    public void eval(final EvolutionState state,
            final int thread,
            final GPData input,
            final ADFStack stack,
            final GPIndividual individual,
            final Problem problem) {
    	((DoubleData)(input)).x = rd.x;
    }
}

