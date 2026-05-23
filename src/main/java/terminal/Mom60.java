package terminal;

import feature.RowContext;

public class Mom60 extends FeatureTerminal {
    @Override public String toString() { return "Mom_60"; }
    @Override protected double value(RowContext ctx) { return ctx.mom60; }
}
