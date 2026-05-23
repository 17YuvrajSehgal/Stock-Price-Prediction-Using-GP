package terminal;

import feature.RowContext;

public class Mom10 extends FeatureTerminal {
    @Override public String toString() { return "Mom_10"; }
    @Override protected double value(RowContext ctx) { return ctx.mom10; }
}
