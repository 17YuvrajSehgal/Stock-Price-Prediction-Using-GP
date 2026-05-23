package terminal;

import feature.RowContext;

public class DaysToMonthEnd extends FeatureTerminal {
    @Override public String toString() { return "Days_To_Month_End"; }
    @Override protected double value(RowContext ctx) { return ctx.daysToMonthEnd; }
}
