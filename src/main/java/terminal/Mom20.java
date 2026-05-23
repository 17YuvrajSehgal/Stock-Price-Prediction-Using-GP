package terminal;

import feature.RowContext;

public class Mom20 extends FeatureTerminal {
    @Override public String toString() { return "Mom_20"; }
    @Override protected double value(RowContext ctx) { return ctx.mom20; }
}
