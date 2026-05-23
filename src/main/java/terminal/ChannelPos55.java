package terminal;

import feature.RowContext;

public class ChannelPos55 extends FeatureTerminal {
    @Override public String toString() { return "Chan_Pos_55"; }
    @Override protected double value(RowContext ctx) { return ctx.channelPos55; }
}
