package terminal;

import feature.RowContext;

public class MacdSignal extends FeatureTerminal {
    @Override public String toString() { return "MACD_Signal"; }
    @Override protected double value(RowContext ctx) { return ctx.macdSignal; }
}
