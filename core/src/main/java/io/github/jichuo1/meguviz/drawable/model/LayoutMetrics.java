package io.github.jichuo1.meguviz.drawable.model;

/** Fixed pixel metrics; an intrinsic dimension of -1 means unspecified. */
public final class LayoutMetrics {
    private final int intrinsicWidth, intrinsicHeight, minimumWidth, minimumHeight;
    private final int paddingLeft, paddingTop, paddingRight, paddingBottom;
    private final int opticalInsetLeft, opticalInsetTop, opticalInsetRight, opticalInsetBottom;

    public LayoutMetrics(int intrinsicWidth, int intrinsicHeight, int minimumWidth, int minimumHeight,
            int paddingLeft, int paddingTop, int paddingRight, int paddingBottom,
            int opticalInsetLeft, int opticalInsetTop, int opticalInsetRight, int opticalInsetBottom) {
        if (intrinsicWidth < -1 || intrinsicHeight < -1) {
            throw new IllegalArgumentException("intrinsic dimensions must be -1 or nonnegative");
        }
        if (minimumWidth < 0 || minimumHeight < 0 || paddingLeft < 0 || paddingTop < 0
                || paddingRight < 0 || paddingBottom < 0 || opticalInsetLeft < 0
                || opticalInsetTop < 0 || opticalInsetRight < 0 || opticalInsetBottom < 0) {
            throw new IllegalArgumentException("minimum dimensions and insets must be nonnegative");
        }
        this.intrinsicWidth = intrinsicWidth; this.intrinsicHeight = intrinsicHeight;
        this.minimumWidth = minimumWidth; this.minimumHeight = minimumHeight;
        this.paddingLeft = paddingLeft; this.paddingTop = paddingTop;
        this.paddingRight = paddingRight; this.paddingBottom = paddingBottom;
        this.opticalInsetLeft = opticalInsetLeft; this.opticalInsetTop = opticalInsetTop;
        this.opticalInsetRight = opticalInsetRight; this.opticalInsetBottom = opticalInsetBottom;
    }
    /**
     * Unspecified intrinsic size, no minimum, padding or insets.
     *
     * Each call returns a new instance: identity was never part of the published 1.0.0 contract,
     * and a shared singleton would silently change the meaning of {@code ==} for existing callers.
     * Compare with {@link #equals(Object)} or the accessors instead. {@code DrawingRecipe} keeps a
     * private default instance for {@code of(...)}, so the zero-extra-allocation path is preserved
     * without exposing identity as a contract.
     */
    public static LayoutMetrics unspecified() {
        return new LayoutMetrics(-1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
    public int intrinsicWidth() { return intrinsicWidth; }
    public int intrinsicHeight() { return intrinsicHeight; }
    public int minimumWidth() { return minimumWidth; }
    public int minimumHeight() { return minimumHeight; }
    public int paddingLeft() { return paddingLeft; }
    public int paddingTop() { return paddingTop; }
    public int paddingRight() { return paddingRight; }
    public int paddingBottom() { return paddingBottom; }
    public int opticalInsetLeft() { return opticalInsetLeft; }
    public int opticalInsetTop() { return opticalInsetTop; }
    public int opticalInsetRight() { return opticalInsetRight; }
    public int opticalInsetBottom() { return opticalInsetBottom; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LayoutMetrics)) return false;
        LayoutMetrics that = (LayoutMetrics) other;
        return intrinsicWidth == that.intrinsicWidth && intrinsicHeight == that.intrinsicHeight
                && minimumWidth == that.minimumWidth && minimumHeight == that.minimumHeight
                && paddingLeft == that.paddingLeft && paddingTop == that.paddingTop
                && paddingRight == that.paddingRight && paddingBottom == that.paddingBottom
                && opticalInsetLeft == that.opticalInsetLeft && opticalInsetTop == that.opticalInsetTop
                && opticalInsetRight == that.opticalInsetRight && opticalInsetBottom == that.opticalInsetBottom;
    }
    @Override public int hashCode() {
        int hash = intrinsicWidth;
        hash = 31 * hash + intrinsicHeight; hash = 31 * hash + minimumWidth; hash = 31 * hash + minimumHeight;
        hash = 31 * hash + paddingLeft; hash = 31 * hash + paddingTop;
        hash = 31 * hash + paddingRight; hash = 31 * hash + paddingBottom;
        hash = 31 * hash + opticalInsetLeft; hash = 31 * hash + opticalInsetTop;
        hash = 31 * hash + opticalInsetRight; return 31 * hash + opticalInsetBottom;
    }
}
