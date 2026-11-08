package io.github.jichuo1.meguviz.drawable.model;

/** Immutable recipe for one built-in shape with a fill and optional centered stroke. */
public final class DrawingRecipe {
    private final ShapeSpec shape;
    private final FillSpec fill;
    private final StrokeSpec stroke;
    private final LayoutMetrics metrics;
    /** Lazily cached {@link #hashCode()}; 0 means not computed yet (the String idiom, benign race). */
    private int hash;

    public DrawingRecipe(ShapeSpec shape, FillSpec fill, StrokeSpec stroke, LayoutMetrics metrics) {
        if (shape == null || fill == null || metrics == null) {
            throw new NullPointerException("shape, fill and metrics are required");
        }
        this.shape = shape;
        this.fill = fill;
        this.stroke = stroke;
        this.metrics = metrics;
    }
    /**
     * Shape and fill only: no stroke and {@link LayoutMetrics#unspecified() unspecified} metrics.
     * Equal to {@code new DrawingRecipe(shape, fill, null, LayoutMetrics.unspecified())} and
     * allocates nothing beyond the returned recipe.
     */
    public static DrawingRecipe of(ShapeSpec shape, FillSpec fill) {
        return new DrawingRecipe(shape, fill, null, LayoutMetrics.unspecified());
    }
    /** A recipe equal to this one with {@code stroke} (null removes the stroke). This recipe is unchanged. */
    public DrawingRecipe withStroke(StrokeSpec stroke) {
        return new DrawingRecipe(shape, fill, stroke, metrics);
    }
    /** A recipe equal to this one with {@code metrics}. This recipe is unchanged. */
    public DrawingRecipe withMetrics(LayoutMetrics metrics) {
        return new DrawingRecipe(shape, fill, stroke, metrics);
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
        if (hash != 0 && that.hash != 0 && hash != that.hash) return false;
        return shape.equals(that.shape) && fill.equals(that.fill) && metrics.equals(that.metrics)
                && (stroke == null ? that.stroke == null : stroke.equals(that.stroke));
    }
    @Override public int hashCode() {
        int cached = this.hash;
        if (cached != 0) return cached;
        int hash = 31 * shape.hashCode() + fill.hashCode();
        hash = 31 * hash + (stroke == null ? 0 : stroke.hashCode());
        hash = 31 * hash + metrics.hashCode();
        this.hash = hash;
        return hash;
    }
}
