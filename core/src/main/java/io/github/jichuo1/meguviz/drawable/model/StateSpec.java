package io.github.jichuo1.meguviz.drawable.model;

import java.util.Arrays;

/** An immutable ordered state specification using positive and negative state IDs. */
public final class StateSpec {
    private final int[] conditions;

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
        return other instanceof StateSpec && Arrays.equals(conditions, ((StateSpec) other).conditions);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(conditions);
    }
}
