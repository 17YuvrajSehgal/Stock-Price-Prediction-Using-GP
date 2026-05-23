package terminal;

import feature.RowContext;

public class Macd extends FeatureTerminal {
    @Override public String toString() { return "MACD"; }
    @Override protected double value(RowContext ctx) { return ctx.macd; }
}
