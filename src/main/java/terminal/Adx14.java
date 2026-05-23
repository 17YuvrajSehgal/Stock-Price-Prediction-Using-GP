package terminal;

import feature.RowContext;

public class Adx14 extends FeatureTerminal {
    @Override public String toString() { return "ADX_14"; }
    @Override protected double value(RowContext ctx) { return ctx.adx14; }
}
