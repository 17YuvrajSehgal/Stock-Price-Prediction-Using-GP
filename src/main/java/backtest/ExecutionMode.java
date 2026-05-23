package backtest;

/**
 * How the backtester translates per-bar signals into transaction costs.
 */
public enum ExecutionMode {
    /**
     * Costs are charged on every bar with a non-zero signal — the position is
     * assumed to enter at this bar and exit by the next, paying round-trip
     * commission + slippage. This matches the legacy {@code Stock} backtest:
     * "enter at current close, exit at next open".
     */
    ALWAYS_FLAT_OVERNIGHT,

    /**
     * Position is held across bars while the signal stays the same direction;
     * costs are charged only on transitions (entry, exit, or flip). One-way
     * cost per leg. A flip pays two one-way costs (close + reopen).
     */
    POSITION_AWARE
}
