package terminal;

import feature.RowContext;

public class DowCos extends FeatureTerminal {
    @Override public String toString() { return "DoW_Cos"; }
    @Override protected double value(RowContext ctx) { return ctx.dowCos; }
}
