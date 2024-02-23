package terminal;

import ec.gp.GPData;


/**
 * This class represents GP data i.e. the terminal data of the GP tree
 */
public class DoubleData extends GPData {
    public double x;

    @Override
    public void copyTo(final GPData gpd) {
        ((DoubleData)gpd).x = x;
    }
}

