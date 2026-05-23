package terminal;

import feature.RowContext;

public class DowSin extends FeatureTerminal {
    @Override public String toString() { return "DoW_Sin"; }
    @Override protected double value(RowContext ctx) { return ctx.dowSin; }
}
