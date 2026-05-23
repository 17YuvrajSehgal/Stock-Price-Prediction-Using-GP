package terminal;

import feature.RowContext;

public class ObvSlope10 extends FeatureTerminal {
    @Override public String toString() { return "OBV_Slope_10"; }
    @Override protected double value(RowContext ctx) { return ctx.obvSlope10; }
}
