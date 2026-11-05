package io.github.jichuo1.meguviz.drawable.model;

/** Immutable recipe for one built-in shape with a fill and optional centered stroke. */
public final class DrawingRecipe {
    private final ShapeSpec shape;
    private final FillSpec fill;
    private final StrokeSpec stroke;
    private final LayoutMetrics metrics;

    public DrawingRecipe(ShapeSpec shape, FillSpec fill, StrokeSpec stroke, LayoutMetrics metrics) {
        if (shape == null || fill == null || metrics == null) {
            throw new NullPointerException("shape, fill and metrics are required");
        }
        this.shape = shape;
        this.fill = fill;
        this.stroke = stroke;
        this.metrics = metrics;
    }
    public ShapeSpec shape() { return shape; }
    public FillSpec fill() { return fill; }
    /** Returns null when there is no stroke. */
    public StrokeSpec stroke() { return stroke; }
    public LayoutMetrics metrics() { return metrics; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DrawingRecipe)) return false;
        DrawingRecipe that = (DrawingRecipe) other;
        return shape.equals(that.shape) && fill.equals(that.fill) && metrics.equals(that.metrics)
                && (stroke == null ? that.stroke == null : stroke.equals(that.stroke));
    }
    @Override public int hashCode() {
        int hash = 31 * shape.hashCode() + fill.hashCode();
        hash = 31 * hash + (stroke == null ? 0 : stroke.hashCode());
        return 31 * hash + metrics.hashCode();
    }
}
