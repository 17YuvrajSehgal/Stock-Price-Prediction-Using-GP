package terminal;

import feature.RowContext;

public class Mom120 extends FeatureTerminal {
    @Override public String toString() { return "Mom_120"; }
    @Override protected double value(RowContext ctx) { return ctx.mom120; }
}
