package io.github.jichuo1.meguviz.drawable.model;

import java.util.Arrays;

/** Immutable built-in shapes. Rounded corners use TL, TR, BR, BL x/y pairs. */
public final class ShapeSpec {
    public enum Kind { RECTANGLE, ELLIPSE, ROUND_RECT }
    private final Kind kind;
    private final Length[] radii;

    private ShapeSpec(Kind kind, Length[] radii) {
        this.kind = kind;
        this.radii = radii.clone();
    }
    public static ShapeSpec rectangle() { return new ShapeSpec(Kind.RECTANGLE, new Length[0]); }
    public static ShapeSpec ellipse() { return new ShapeSpec(Kind.ELLIPSE, new Length[0]); }
    public static ShapeSpec roundRect(Length radius) {
        if (radius == null) throw new NullPointerException("radius");
        return roundRect(radius, radius, radius, radius, radius, radius, radius, radius);
    }
    public static ShapeSpec roundRect(Length... radii) {
        if (radii == null || radii.length != 8) {
            throw new IllegalArgumentException("eight corner components required");
        }
        Length[] copy = radii.clone();
        for (Length radius : copy) {
            if (radius == null) throw new NullPointerException("radius");
            if (radius.value() < 0) throw new IllegalArgumentException("negative radius");
        }
        return new ShapeSpec(Kind.ROUND_RECT, copy);
    }
    public Kind kind() { return kind; }
    public Length[] radii() { return radii.clone(); }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ShapeSpec)) return false;
        ShapeSpec that = (ShapeSpec) other;
        return kind == that.kind && Arrays.equals(radii, that.radii);
    }
    @Override public int hashCode() { return 31 * kind.hashCode() + Arrays.hashCode(radii); }
}
