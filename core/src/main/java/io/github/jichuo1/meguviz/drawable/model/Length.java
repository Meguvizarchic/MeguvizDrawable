package io.github.jichuo1.meguviz.drawable.model;

/** An unresolved length. A bounds fraction explicitly names its reference axis. */
public final class Length {
    public enum Unit { PX, DP, BOUNDS_FRACTION }
    public enum Axis { WIDTH, HEIGHT }

    private final Unit unit;
    private final float value;
    private final Axis axis;

    private Length(Unit unit, float value, Axis axis) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException("length must be finite");
        }
        this.unit = unit;
        this.value = value == 0f ? 0f : value;
        this.axis = axis;
    }

    public static Length px(float value) { return new Length(Unit.PX, value, null); }
    public static Length dp(float value) { return new Length(Unit.DP, value, null); }
    /** A ratio in the inclusive interval [0, 1]. */
    public static Length boundsFraction(float value, Axis axis) {
        if (axis == null) throw new NullPointerException("axis");
        if (Float.isNaN(value) || Float.isInfinite(value) || value < 0f || value > 1f) {
            throw new IllegalArgumentException("bounds fraction must be in [0,1]");
        }
        return new Length(Unit.BOUNDS_FRACTION, value, axis);
    }
    public Unit unit() { return unit; }
    public float value() { return value; }
    /** Returns null for PX and DP. */
    public Axis axis() { return axis; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Length)) return false;
        Length that = (Length) other;
        return unit == that.unit && axis == that.axis
                && Float.floatToIntBits(value) == Float.floatToIntBits(that.value);
    }
    @Override public int hashCode() {
        int result = 31 * unit.hashCode() + Float.floatToIntBits(value);
        return 31 * result + (axis == null ? 0 : axis.hashCode());
    }
}
