package terminal;

import feature.RowContext;

public class Mom5 extends FeatureTerminal {
    @Override public String toString() { return "Mom_5"; }
    @Override protected double value(RowContext ctx) { return ctx.mom5; }
}
