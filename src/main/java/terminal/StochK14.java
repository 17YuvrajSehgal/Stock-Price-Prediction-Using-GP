package terminal;

import feature.RowContext;

public class StochK14 extends FeatureTerminal {
    @Override public String toString() { return "StochK_14"; }
    @Override protected double value(RowContext ctx) { return ctx.stochK14; }
}
