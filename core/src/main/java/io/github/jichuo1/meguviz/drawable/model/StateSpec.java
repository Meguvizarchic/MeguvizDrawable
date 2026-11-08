package io.github.jichuo1.meguviz.drawable.model;

import java.util.Arrays;

/** An immutable ordered state specification using positive and negative state IDs. */
public final class StateSpec {
    private final int[] conditions;
    /** Lazily cached {@link #hashCode()}; 0 means not computed yet (the String idiom, benign race). */
    private int hash;

    public StateSpec(int... conditions) {
        if (conditions == null) {
            throw new NullPointerException("conditions");
        }
        this.conditions = conditions.clone();
        for (int condition : this.conditions) {
            if (condition == 0) {
                break;
            }
            if (condition == Integer.MIN_VALUE) {
                throw new IllegalArgumentException("state ID cannot be represented as a positive int");
            }
        }
    }

    /** Returns a copy, including any values following the zero terminator. */
    public int[] conditions() {
        return conditions.clone();
    }

    public int size() {
        return conditions.length;
    }

    public int conditionAt(int index) {
        return conditions[index];
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof StateSpec)) return false;
        StateSpec that = (StateSpec) other;
        if (hash != 0 && that.hash != 0 && hash != that.hash) return false;
        return Arrays.equals(conditions, that.conditions);
    }

    @Override
    public int hashCode() {
        int cached = this.hash;
        if (cached != 0) return cached;
        int hash = Arrays.hashCode(conditions);
        this.hash = hash;
        return hash;
    }
}
