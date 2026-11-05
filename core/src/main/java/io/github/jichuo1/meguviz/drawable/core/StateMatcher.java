package io.github.jichuo1.meguviz.drawable.core;

import io.github.jichuo1.meguviz.drawable.model.StateSpec;
import io.github.jichuo1.meguviz.drawable.model.StateColorList;
import java.util.Arrays;

/** Direct state matching; no assumptions are made about the number or magnitude of IDs. */
public final class StateMatcher {
    /**
     * Distinct condition IDs at or below this count are compiled into a lookup table.
     * Larger ID sets fall back to direct matching, so compilation never grows without bound.
     */
    private static final int MAX_TABLE_IDS = 16;

    /**
     * Rule lists longer than this cannot use the compact table (a rule index would not fit
     * in a signed short) and fall back to direct matching.
     */
    private static final int MAX_TABLE_RULES = Short.MAX_VALUE;

    /**
     * Upper bound on the table-fill work, counted as {@code (1 << distinct IDs) * rules}.
     * Fills that would exceed this fall back to direct matching, bounding only the table
     * build. The earlier ID scan is not bounded by this budget; its length depends on the
     * input rule list.
     */
    private static final long MAX_PREPARATION_OPERATIONS = 1L << 22;

    private StateMatcher() {}

    public static boolean matches(StateSpec spec, int[] state) {
        if (spec == null || state == null) {
            throw new NullPointerException("spec and state are required");
        }
        for (int i = 0; i < spec.size(); i++) {
            int condition = spec.conditionAt(i);
            if (condition == 0) {
                break;
            }
            int id = condition > 0 ? condition : -condition;
            boolean present = false;
            for (int entry : state) {
                if (entry == 0) {
                    break;
                }
                if (entry == id) {
                    present = true;
                    break;
                }
            }
            if (present != (condition > 0)) {
                return false;
            }
        }
        return true;
    }

    /** Returns the first matching rule index, or -1 when none matches. */
    public static int matchIndex(StateColorList rules, int[] state) {
        if (rules == null || state == null) {
            throw new NullPointerException("rules and state are required");
        }
        for (int i = 0; i < rules.size(); i++) {
            if (matches(rules.specAt(i), state)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Compiles an ordered rule list into a reusable matcher. The returned instance is not
     * cached; callers that match the same rules repeatedly should keep it and reuse it.
     * Rules referencing more than {@link #MAX_TABLE_IDS} distinct condition IDs, more than
     * {@link #MAX_TABLE_RULES} rules, or a table fill that would exceed
     * {@link #MAX_PREPARATION_OPERATIONS} checks use direct matching.
     *
     * <p>The prepared result is pointwise identical to {@link #matchIndex}: same first-match
     * order, same positive/negative conditions, same zero terminators, same -1 when nothing
     * matches, and the same null checks. Unlike AOSP {@code StateListDrawable}, this library
     * does not perform a second wildcard pass; an empty rule is just an ordered rule.
     */
    public static PreparedMatcher prepare(StateColorList rules) {
        if (rules == null) {
            throw new NullPointerException("rules is required");
        }
        return new PreparedMatcher(rules);
    }

    /**
     * Reusable compiled form of one immutable rule list. A compact lookup table covers every
     * combination of the distinct condition IDs when there are at most
     * {@link #MAX_TABLE_IDS} of them; otherwise queries delegate to direct matching.
     *
     * <p>Encoding a state costs O(state length * distinct IDs) and the table lookup is a
     * constant-time array read. The table stores one signed short per combination, so its
     * fixed payload upper bound is 2 * 2^16 = 128 KiB (an {@code int} table would be 256 KiB).
     * The table fill is capped by {@link #MAX_PREPARATION_OPERATIONS}; the ID scan before it
     * stops as soon as a 17th distinct ID appears.
     */
    public static final class PreparedMatcher {
        private final int[] ids;
        private final short[] table;
        private final StateColorList rules;

        private PreparedMatcher(StateColorList rules) {
            int count = rules.size();
            if (count > MAX_TABLE_RULES) {
                this.ids = null;
                this.table = null;
                this.rules = rules;
                return;
            }
            int[] idList = new int[16];
            int distinct = 0;
            boolean tooManyIds = false;
            for (int i = 0; i < count && !tooManyIds; i++) {
                StateSpec spec = rules.specAt(i);
                for (int j = 0; j < spec.size(); j++) {
                    int condition = spec.conditionAt(j);
                    if (condition == 0) {
                        break;
                    }
                    int id = condition > 0 ? condition : -condition;
                    boolean seen = false;
                    for (int k = 0; k < distinct; k++) {
                        if (idList[k] == id) {
                            seen = true;
                            break;
                        }
                    }
                    if (!seen) {
                        if (distinct == MAX_TABLE_IDS) {
                            tooManyIds = true;
                            break;
                        }
                        idList[distinct++] = id;
                    }
                }
            }
            if (tooManyIds || ((1L << distinct) * count) > MAX_PREPARATION_OPERATIONS) {
                this.ids = null;
                this.table = null;
                this.rules = rules;
                return;
            }
            int[] ids = Arrays.copyOf(idList, distinct);
            int[] requiredMasks = new int[count];
            int[] forbiddenMasks = new int[count];
            for (int i = 0; i < count; i++) {
                StateSpec spec = rules.specAt(i);
                int required = 0;
                int forbidden = 0;
                for (int j = 0; j < spec.size(); j++) {
                    int condition = spec.conditionAt(j);
                    if (condition == 0) {
                        break;
                    }
                    int bit = bitOf(ids, distinct, condition > 0 ? condition : -condition);
                    if (condition > 0) {
                        required |= 1 << bit;
                    } else {
                        forbidden |= 1 << bit;
                    }
                }
                requiredMasks[i] = required;
                forbiddenMasks[i] = forbidden;
            }
            int size = 1 << distinct;
            short[] table = new short[size];
            for (int mask = 0; mask < size; mask++) {
                int result = -1;
                for (int i = 0; i < count; i++) {
                    if ((mask & requiredMasks[i]) == requiredMasks[i]
                            && (mask & forbiddenMasks[i]) == 0) {
                        result = i;
                        break;
                    }
                }
                table[mask] = (short) result;
            }
            this.ids = ids;
            this.table = table;
            this.rules = null;
        }

        /**
         * Distinct-ID count of the compiled table, or -1 when this matcher delegates to direct
         * matching. Package-private: used by {@link StateMatcherCache} for admission and weight.
         */
        int tableIdCount() {
            return table == null ? -1 : ids.length;
        }

        private static int bitOf(int[] ids, int count, int id) {
            for (int i = 0; i < count; i++) {
                if (ids[i] == id) {
                    return i;
                }
            }
            return -1;
        }

        /** Returns the first matching rule index, or -1 when none matches. */
        public int matchIndex(int[] state) {
            if (state == null) {
                throw new NullPointerException("state is required");
            }
            short[] table = this.table;
            if (table == null) {
                return StateMatcher.matchIndex(rules, state);
            }
            int mask = 0;
            int[] ids = this.ids;
            for (int i = 0; i < state.length; i++) {
                int entry = state[i];
                if (entry == 0) {
                    break;
                }
                for (int bit = 0; bit < ids.length; bit++) {
                    if (ids[bit] == entry) {
                        mask |= 1 << bit;
                        break;
                    }
                }
            }
            return table[mask];
        }
    }
}
