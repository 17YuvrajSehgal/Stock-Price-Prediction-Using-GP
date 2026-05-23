package terminal;

import feature.RowContext;

public class StochD14 extends FeatureTerminal {
    @Override public String toString() { return "StochD_14"; }
    @Override protected double value(RowContext ctx) { return ctx.stochD14; }
}
