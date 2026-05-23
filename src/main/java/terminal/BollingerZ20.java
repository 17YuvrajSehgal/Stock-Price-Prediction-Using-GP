package terminal;

import feature.RowContext;

public class BollingerZ20 extends FeatureTerminal {
    @Override public String toString() { return "BB_Z_20"; }
    @Override protected double value(RowContext ctx) { return ctx.bollingerZ20; }
}
