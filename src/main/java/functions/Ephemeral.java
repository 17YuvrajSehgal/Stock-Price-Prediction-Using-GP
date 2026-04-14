package functions;

import ec.EvolutionState;
import ec.Problem;
import ec.gp.ADFStack;
import ec.gp.ERC;
import ec.gp.GPData;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import ec.util.Code;
import ec.util.DecodeReturn;
import terminal.DoubleData;

public class Ephemeral extends ERC {
    private double value;

    @Override
    public String toString() {
        return "erc";
    }

    @Override
    public String toStringForHumans() {
        return Double.toString(value);
    }

    @Override
    public void resetNode(EvolutionState state, int thread) {
        value = state.random[thread].nextDouble();
    }

    @Override
    public int nodeHashCode() {
        long bits = Double.doubleToLongBits(value);
        return getClass().hashCode() + (int) (bits ^ (bits >>> 32));
    }

    @Override
    public boolean nodeEquals(GPNode node) {
        return node instanceof Ephemeral
                && Double.doubleToLongBits(((Ephemeral) node).value) == Double.doubleToLongBits(value);
    }

    @Override
    public String encode() {
        return Code.encode(value);
    }

    @Override
    public boolean decode(DecodeReturn dret) {
        int originalPos = dret.pos;
        String originalData = dret.data;

        Code.decode(dret);
        if (dret.type != DecodeReturn.T_DOUBLE) {
            dret.data = originalData;
            dret.pos = originalPos;
            return false;
        }

        value = dret.d;
        return true;
    }

    @Override
    public void eval(EvolutionState state, int thread, GPData input, ADFStack stack, GPIndividual individual, Problem problem) {
        ((DoubleData) input).x = value;
    }
}

