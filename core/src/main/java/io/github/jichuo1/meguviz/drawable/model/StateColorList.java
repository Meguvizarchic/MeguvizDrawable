package io.github.jichuo1.meguviz.drawable.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable ordered state-to-color rules. A color may contain any ARGB bits. */
public final class StateColorList {
    private final List<StateSpec> specs;
    private final int[] colors;
    /** Lazily cached {@link #hashCode()}; 0 means not computed yet (the String idiom, benign race). */
    private int hash;

    public StateColorList(List<StateSpec> specs, int[] colors) {
        if (specs == null || colors == null) {
            throw new NullPointerException("specs and colors are required");
        }
        if (specs.size() != colors.length) {
            throw new IllegalArgumentException("each state spec needs one color");
        }
        ArrayList<StateSpec> copy = new ArrayList<StateSpec>(specs.size());
        for (StateSpec spec : specs) {
            if (spec == null) {
                throw new NullPointerException("state spec");
            }
            copy.add(spec);
        }
        this.specs = Collections.unmodifiableList(copy);
        this.colors = colors.clone();
    }

    public int size() {
        return colors.length;
    }

    public StateSpec specAt(int index) {
        return specs.get(index);
    }

    public int colorAt(int index) {
        return colors[index];
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof StateColorList)) {
            return false;
        }
        StateColorList that = (StateColorList) other;
        if (hash != 0 && that.hash != 0 && hash != that.hash) return false;
        return specs.equals(that.specs) && java.util.Arrays.equals(colors, that.colors);
    }

    @Override
    public int hashCode() {
        int cached = this.hash;
        if (cached != 0) return cached;
        int hash = 31 * specs.hashCode() + java.util.Arrays.hashCode(colors);
        this.hash = hash;
        return hash;
    }
}
