package io.github.jichuo1.meguviz.drawable.model;

import java.util.Arrays;

/** sRGB solid, ordered state color, or linear-gradient fill. */
public final class FillSpec {
    public enum Kind { SOLID, STATEFUL_SOLID, LINEAR_GRADIENT }
    private static final int MAX_STOPS = 64;
    private final Kind kind;
    private final int color;
    private final float startX, startY, endX, endY;
    private final int[] colors;
    private final float[] stops;
    private final StateColorList stateColors;
    private static final int[] NO_COLORS = new int[0];
    private static final float[] NO_STOPS = new float[0];
    /** Lazily cached {@link #hashCode()}; 0 means not computed yet (the String idiom, benign race). */
    private int hash;

    private FillSpec(Kind kind, int color, float startX, float startY, float endX,
            float endY, int[] colors, float[] stops, StateColorList stateColors) {
        this.kind = kind;
        this.color = color;
        this.startX = zero(startX);
        this.startY = zero(startY);
        this.endX = zero(endX);
        this.endY = zero(endY);
        // Empty arrays are immutable, so the solid and stateful kinds share one instance of each.
        this.colors = colors.length == 0 ? NO_COLORS : colors.clone();
        this.stops = stops.length == 0 ? NO_STOPS : stops.clone();
        this.stateColors = stateColors;
        for (int i = 0; i < this.stops.length; i++) this.stops[i] = zero(this.stops[i]);
    }
    public static FillSpec solid(int color) {
        return new FillSpec(Kind.SOLID, color, 0, 0, 0, 0, NO_COLORS, NO_STOPS, null);
    }
    /** Uses fallbackColor when no ordered rule matches the current state. */
    public static FillSpec statefulSolid(StateColorList rules, int fallbackColor) {
        if (rules == null) throw new NullPointerException("rules");
        return new FillSpec(Kind.STATEFUL_SOLID, fallbackColor, 0, 0, 0, 0,
                NO_COLORS, NO_STOPS, rules);
    }
    /** Endpoints are bounds-relative coordinates in [0, 1]; stop positions use the same range. */
    public static FillSpec linearGradient(float startX, float startY, float endX, float endY,
            int[] colors, float[] stops) {
        fraction(startX); fraction(startY); fraction(endX); fraction(endY);
        if (startX == endX && startY == endY) throw new IllegalArgumentException("gradient endpoints must differ");
        if (colors == null || stops == null) throw new NullPointerException("colors and stops");
        if (colors.length < 2 || colors.length > MAX_STOPS || colors.length != stops.length) {
            throw new IllegalArgumentException("gradient requires 2..64 color stops");
        }
        int[] colorCopy = colors.clone();
        float[] stopCopy = stops.clone();
        float previous = 0;
        for (int i = 0; i < stopCopy.length; i++) {
            finite(stopCopy[i]);
            if (stopCopy[i] < 0 || stopCopy[i] > 1 || (i > 0 && stopCopy[i] < previous)) {
                throw new IllegalArgumentException("stops must be ordered in [0,1]");
            }
            previous = stopCopy[i];
        }
        return new FillSpec(Kind.LINEAR_GRADIENT, 0, startX, startY, endX, endY, colorCopy, stopCopy, null);
    }
    public Kind kind() { return kind; }
    /** Valid only for a solid fill. */
    public int color() {
        if (kind != Kind.SOLID) throw new IllegalStateException("not a solid fill");
        return color;
    }
    public StateColorList stateColors() {
        if (kind != Kind.STATEFUL_SOLID) throw new IllegalStateException("not a stateful fill");
        return stateColors;
    }
    public int fallbackColor() {
        if (kind != Kind.STATEFUL_SOLID) throw new IllegalStateException("not a stateful fill");
        return color;
    }
    public float startX() { return startX; }
    public float startY() { return startY; }
    public float endX() { return endX; }
    public float endY() { return endY; }
    public int[] colors() { return colors.clone(); }
    public float[] stops() { return stops.clone(); }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FillSpec)) return false;
        FillSpec that = (FillSpec) other;
        if (hash != 0 && that.hash != 0 && hash != that.hash) return false;
        return kind == that.kind && color == that.color
                && Float.floatToIntBits(startX) == Float.floatToIntBits(that.startX)
                && Float.floatToIntBits(startY) == Float.floatToIntBits(that.startY)
                && Float.floatToIntBits(endX) == Float.floatToIntBits(that.endX)
                && Float.floatToIntBits(endY) == Float.floatToIntBits(that.endY)
                && Arrays.equals(colors, that.colors) && Arrays.equals(stops, that.stops)
                && (stateColors == null ? that.stateColors == null : stateColors.equals(that.stateColors));
    }
    @Override public int hashCode() {
        int cached = this.hash;
        if (cached != 0) return cached;
        // Enum ordinal, not Enum.hashCode(): identity hashes differ between runs, and a cached
        // value should be reproducible. Equality is unchanged.
        int hash = 31 * kind.ordinal() + color;
        hash = 31 * hash + Float.floatToIntBits(startX);
        hash = 31 * hash + Float.floatToIntBits(startY);
        hash = 31 * hash + Float.floatToIntBits(endX);
        hash = 31 * hash + Float.floatToIntBits(endY);
        hash = 31 * hash + Arrays.hashCode(colors);
        hash = 31 * hash + Arrays.hashCode(stops);
        hash = 31 * hash + (stateColors == null ? 0 : stateColors.hashCode());
        this.hash = hash;
        return hash;
    }
    private static float zero(float value) { return value == 0f ? 0f : value; }
    private static void finite(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) throw new IllegalArgumentException("value must be finite");
    }
    private static void fraction(float value) {
        finite(value);
        if (value < 0f || value > 1f) throw new IllegalArgumentException("coordinate must be in [0,1]");
    }
}
