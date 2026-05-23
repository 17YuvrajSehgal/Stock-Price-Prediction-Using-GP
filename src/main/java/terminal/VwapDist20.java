package terminal;

import feature.RowContext;

public class VwapDist20 extends FeatureTerminal {
    @Override public String toString() { return "VWAP_Dist_20"; }
    @Override protected double value(RowContext ctx) { return ctx.vwapDist20; }
}
