package terminal;

import feature.RowContext;

public class Atr14Pct extends FeatureTerminal {
    @Override public String toString() { return "ATR_14_Pct"; }
    @Override protected double value(RowContext ctx) { return ctx.atr14Pct; }
}
