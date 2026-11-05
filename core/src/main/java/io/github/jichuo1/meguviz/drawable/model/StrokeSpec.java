package io.github.jichuo1.meguviz.drawable.model;

/** A centered stroke. Transparent colors still retain their stroke width. */
public final class StrokeSpec {
    private final Length width;
    private final int color;

    public StrokeSpec(Length width, int color) {
        if (width == null) throw new NullPointerException("width");
        if (width.value() < 0) throw new IllegalArgumentException("negative stroke width");
        this.width = width;
        this.color = color;
    }
    public Length width() { return width; }
    public int color() { return color; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof StrokeSpec)) return false;
        StrokeSpec that = (StrokeSpec) other;
        return width.equals(that.width) && color == that.color;
    }
    @Override public int hashCode() { return 31 * width.hashCode() + color; }
}
