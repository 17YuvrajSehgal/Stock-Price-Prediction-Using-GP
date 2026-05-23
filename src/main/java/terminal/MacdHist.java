package terminal;

import feature.RowContext;

public class MacdHist extends FeatureTerminal {
    @Override public String toString() { return "MACD_Hist"; }
    @Override protected double value(RowContext ctx) { return ctx.macdHist; }
}
