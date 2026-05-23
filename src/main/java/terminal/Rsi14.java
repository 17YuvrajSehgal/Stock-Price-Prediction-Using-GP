package terminal;

import feature.RowContext;

public class Rsi14 extends FeatureTerminal {
    @Override public String toString() { return "RSI_14"; }
    @Override protected double value(RowContext ctx) { return ctx.rsi14; }
}
