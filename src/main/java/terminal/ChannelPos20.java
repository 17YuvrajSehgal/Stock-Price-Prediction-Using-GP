package terminal;

import feature.RowContext;

public class ChannelPos20 extends FeatureTerminal {
    @Override public String toString() { return "Chan_Pos_20"; }
    @Override protected double value(RowContext ctx) { return ctx.channelPos20; }
}
