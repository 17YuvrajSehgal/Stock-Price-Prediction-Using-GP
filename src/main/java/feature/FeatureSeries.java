package feature;

/**
 * Immutable view of every {@link RowContext} produced by {@link FeaturePipeline}.
 */
public final class FeatureSeries {
    private final RowContext[] contexts;

    public FeatureSeries(RowContext[] contexts) {
        this.contexts = contexts;
    }

    public int size() {
        return contexts.length;
    }

    public RowContext contextAt(int row) {
        return contexts[row];
    }
}
