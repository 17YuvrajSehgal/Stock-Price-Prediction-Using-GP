package StockPredictor;

import ec.EvolutionState;
import ec.Individual;
import ec.gp.GPProblem;
import ec.simple.SimpleProblemForm;

import java.util.Date;

public class Stock extends GPProblem implements SimpleProblemForm {

    Date date;
    public double open, high, low, close, adjustedClose;
    public long volume;
    @Override
    public void evaluate(EvolutionState evolutionState, Individual individual, int i, int i1) {

    }
}
