package io.github.jichuo1.meguviz.drawable.core;

import io.github.jichuo1.meguviz.drawable.model.StateColorList;
import io.github.jichuo1.meguviz.drawable.model.StateSpec;
import io.github.jichuo1.meguviz.drawable.core.StateMatcher.PreparedMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Independent differential tests for the compiled state matcher.
 *
 * <p>The default run is a compact, fixed corpus: representative masks plus a small seeded
 * random sample, with the full edge/boundary/fallback checks kept intact. It preserves every
 * semantic equivalence class (first-match order, wildcard order, positive/negative conflict,
 * spec/state zero terminators, unknown/custom IDs, null and Integer.MIN_VALUE, K16/K17 and
 * R32767/32768 fallback, K16R64/65 table budget) while staying well under the contract budget.
 *
 * <p>The original broad exhaustive/randomized corpora are preserved verbatim under the opt-in
 * stress entry point, selected by the {@code meguviz.test.stress} system property (or
 * {@code main --stress}). They are never part of the default Gradle check.
 */
public final class StateMatcherFastPathTest {
    private static int checks;

    public static void main(String[] args) {
        if (args.length > 0 && "--stress".equals(args[0])) {
            System.setProperty("meguviz.test.stress", "true");
        }
        System.out.println("StateMatcherFastPathTest: " + run() + " checks passed"
                + (Boolean.getBoolean("meguviz.test.stress") ? " (stress)" : " (default)"));
    }

    /**
     * Runs the compact default corpus, or the full stress corpus when
     * {@code meguviz.test.stress} is set. Returns the number of checks performed. The counter
     * is reset on every call so repeated invocations never accumulate.
     */
    public static int run() {
        checks = 0;
        if (Boolean.getBoolean("meguviz.test.stress")) {
            stressCorpus();
        } else {
            defaultCorpus();
        }
        return checks;
    }

    private static void defaultCorpus() {
        defaultSmallBitSpace();
        edgeCases();
        defaultInvariants();
        defaultDirectMatchesReference();
        if (Boolean.getBoolean("meguviz.test.stress")) {
            boundaryAndUnknownIds();
            budgetBoundary();
            defaultRandomizedRuleLists();
            defaultLargeAndCustomIds();
            defaultWideIdFallback();
        }
    }

    private static void stressCorpus() {
        exhaustiveSmallBitSpace();
        randomizedRuleLists();
        largeAndCustomIds();
        wideIdFallback();
        edgeCases();
        boundaryAndUnknownIds();
        budgetBoundary();
        invariants();
        directMatchesReference();
    }

    /**
     * Representative masks for the 8-bit universe: empty, full, each single bit, each
     * all-minus-one, adjacent pairs, and a few extremes. Two rule lists cover positive,
     * negative, wildcard, first-match order and the spec terminator. Terminator variants are
     * exercised on a small subset so state0 handling stays covered without a 3x cost.
     */
    private static void defaultSmallBitSpace() {
        int[] universe = {1, 2, 3, 4, 5, 6, 7, 8};
        List<StateColorList> ruleLists = new ArrayList<StateColorList>();
        ruleLists.add(rules(list(spec(1), spec(-2), spec(1, -2), spec(8, -3), spec())));
        ruleLists.add(rules(list(spec(1, 2, 3), spec(-1, 2), spec(3), spec(-8), spec())));
        int[] masks = {0, 255};
        int[] terminatorMasks = {0};
        for (StateColorList rules : ruleLists) {
            PreparedMatcher prepared = StateMatcher.prepare(rules);
            for (int mask : masks) {
                verify(rules, prepared, stateFromMask(universe, mask), "default mask " + mask);
            }
            for (int mask : terminatorMasks) {
                int[] state = stateFromMask(universe, mask);
                verify(rules, prepared, trimToTerminator(state), "default no terminator " + mask);
                verify(rules, prepared, withTrailing(state), "default trailing " + mask);
            }
        }
    }

    /** Small fixed-seed random corpus: one rule list, representative masks and one state. */
    private static void defaultRandomizedRuleLists() {
        Random random = new Random(20261104L);
        int[] universe = {1, 2, 3, 4, 5, 6, 7, 8};
        int ruleCount = 1 + random.nextInt(8);
        List<StateSpec> specs = new ArrayList<StateSpec>();
        for (int r = 0; r < ruleCount; r++) {
            int length = random.nextInt(5);
            int[] conditions = new int[length];
            for (int i = 0; i < length; i++) {
                int id = universe[random.nextInt(universe.length)];
                conditions[i] = random.nextInt(8) == 0 ? 0 : random.nextBoolean() ? id : -id;
            }
            specs.add(new StateSpec(conditions));
        }
        specs.add(new StateSpec());
        StateColorList rules = new StateColorList(specs, new int[specs.size()]);
        PreparedMatcher prepared = StateMatcher.prepare(rules);
        for (int mask : new int[] {0, 255}) {
            verify(rules, prepared, stateFromMask(universe, mask), "default random rules mask " + mask);
        }
        int[] state = new int[13];
        for (int i = 0; i < state.length - 1; i++) {
            state[i] = random.nextInt(8) == 0 ? 0 : universe[random.nextInt(universe.length)];
        }
        state[state.length - 1] = 0;
        verify(rules, prepared, state, "default random rules state");
    }

    /** One large/custom ID rule list with unknown, custom, MIN_VALUE and zero states. */
    private static void defaultLargeAndCustomIds() {
        Random random = new Random(97531L);
        int[] pool = {0x7f010001, 0x7f010021, 0x7f020003, 0x70000001, 0x70000009, 0x7f030007,
                0x101000a, 0x1010000, 0x1010004, 0x1010009};
        int ruleCount = 1 + random.nextInt(10);
        List<StateSpec> specs = new ArrayList<StateSpec>();
        for (int r = 0; r < ruleCount; r++) {
            int length = random.nextInt(5);
            int[] conditions = new int[length];
            for (int i = 0; i < length; i++) {
                int id = pool[random.nextInt(pool.length)];
                conditions[i] = random.nextInt(8) == 0 ? 0 : random.nextBoolean() ? id : -id;
            }
            specs.add(new StateSpec(conditions));
        }
        StateColorList rules = new StateColorList(specs, new int[specs.size()]);
        PreparedMatcher prepared = StateMatcher.prepare(rules);
        for (int q = 0; q < 2; q++) {
            int length = random.nextInt(10);
            int[] state = new int[length + 1];
            for (int i = 0; i < length; i++) {
                int pick = random.nextInt(5);
                if (pick == 0) state[i] = pool[random.nextInt(pool.length)];
                else if (pick == 1) state[i] = 0x70000000 + random.nextInt(100000);
                else if (pick == 2) state[i] = -pool[random.nextInt(pool.length)];
                else if (pick == 3) state[i] = Integer.MIN_VALUE;
                else state[i] = 0;
            }
            state[length] = 0;
            verify(rules, prepared, state, "default large ids state " + q);
        }
    }

    /** K16/K17 fallback: 16 distinct IDs still build the table, 17 delegate to direct. */
    private static void defaultWideIdFallback() {
        Random random = new Random(24680L);
        for (int distinct : new int[] {16, 17}) {
            int[] ids = new int[distinct];
            for (int i = 0; i < distinct; i++) {
                ids[i] = 0x7f000000 + i * 101;
            }
            List<StateSpec> specs = new ArrayList<StateSpec>();
            for (int r = 0; r < 8; r++) {
                int length = 1 + random.nextInt(4);
                int[] conditions = new int[length];
                for (int i = 0; i < length; i++) {
                    int id = ids[random.nextInt(distinct)];
                    conditions[i] = random.nextBoolean() ? id : -id;
                }
                specs.add(new StateSpec(conditions));
            }
            specs.add(new StateSpec());
            StateColorList rules = new StateColorList(specs, new int[specs.size()]);
            PreparedMatcher prepared = StateMatcher.prepare(rules);
            for (int q = 0; q < 2; q++) {
                int length = random.nextInt(8);
                int[] state = new int[length + 1];
                for (int i = 0; i < length; i++) {
                    state[i] = random.nextInt(4) == 0 ? 0 : ids[random.nextInt(distinct)];
                }
                state[length] = 0;
                verify(rules, prepared, state, "default wide " + distinct + " state " + q);
            }
        }
    }

    private static void edgeCases() {
        StateColorList empty = new StateColorList(new ArrayList<StateSpec>(), new int[0]);
        StateColorList wildcardFirst = rules(list(spec(), spec(1)));
        StateColorList wildcardLast = rules(list(spec(1), spec()));
        StateColorList firstMatch = rules(list(spec(1), spec(1, 2), spec()));
        check(StateMatcher.prepare(empty).matchIndex(new int[] {1, 0}) == -1
                && StateMatcher.prepare(empty).matchIndex(new int[0]) == -1
                && StateMatcher.prepare(wildcardFirst).matchIndex(new int[] {1, 0}) == 0
                && StateMatcher.prepare(wildcardLast).matchIndex(new int[] {2, 0}) == 1
                && StateMatcher.prepare(firstMatch).matchIndex(new int[] {1, 2, 0}) == 0,
                "empty rules, wildcard order and first match");

        StateColorList specTerminator = rules(list(spec(1, 0, 2), spec(2)));
        StateColorList stateTerminator = rules(list(spec(2)));
        check(StateMatcher.prepare(specTerminator).matchIndex(new int[] {1, 0}) == 0
                && StateMatcher.prepare(specTerminator).matchIndex(new int[] {2, 0}) == 1
                && StateMatcher.prepare(stateTerminator).matchIndex(new int[] {1, 0, 2}) == -1
                && StateMatcher.prepare(stateTerminator).matchIndex(new int[] {2}) == 0,
                "spec and state terminators");

        StateColorList duplicates = rules(list(spec(1, 1, -2, -2), spec()));
        StateColorList contradiction = rules(list(spec(1, -1), spec(1)));
        StateColorList negative = rules(list(spec(-1), spec()));
        StateColorList minValue = rules(list(spec(1), spec()));
        check(StateMatcher.prepare(duplicates).matchIndex(new int[] {1, 0}) == 0
                && StateMatcher.prepare(contradiction).matchIndex(new int[] {1, 0}) == 1
                && StateMatcher.prepare(negative).matchIndex(new int[] {2, 0}) == 0
                && StateMatcher.prepare(negative).matchIndex(new int[] {1, 0}) == 1
                && StateMatcher.prepare(minValue).matchIndex(new int[] {Integer.MIN_VALUE, 0}) == 1,
                "duplicate, contradictory, negative and MIN_VALUE conditions");

        final StateColorList rules = rules(list(spec(1), spec()));
        final PreparedMatcher prepared = StateMatcher.prepare(rules);
        expectNpeAll(new Runnable() { public void run() { StateMatcher.prepare(null); }},
                new Runnable() { public void run() { prepared.matchIndex(null); }});
    }

    private static void boundaryAndUnknownIds() {
        int checked = 0x0101000a;
        // Exactly at the compact-table rule-count boundary.
        List<StateSpec> atLimit = new ArrayList<StateSpec>();
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            atLimit.add(new StateSpec(checked));
        }
        StateColorList atRules = new StateColorList(atLimit, new int[atLimit.size()]);
        PreparedMatcher atPrepared = StateMatcher.prepare(atRules);
        verify(atRules, atPrepared, new int[] {checked, 0}, "rule count 32767 matches");
        verify(atRules, atPrepared, new int[] {0}, "rule count 32767 no match");

        // One past the boundary delegates to direct matching.
        List<StateSpec> overLimit = new ArrayList<StateSpec>();
        for (int i = 0; i <= Short.MAX_VALUE; i++) {
            overLimit.add(new StateSpec(checked));
        }
        StateColorList overRules = new StateColorList(overLimit, new int[overLimit.size()]);
        PreparedMatcher overPrepared = StateMatcher.prepare(overRules);
        verify(overRules, overPrepared, new int[] {checked, 0}, "rule count 32768 fallback matches");
        verify(overRules, overPrepared, new int[] {0}, "rule count 32768 fallback no match");

        // Unknown and custom IDs never satisfy a condition but must not break encoding.
        StateColorList unknown = rules(list(spec(1), spec()));
        PreparedMatcher unknownPrepared = StateMatcher.prepare(unknown);
        verifyAll(unknown, unknownPrepared, new int[][] {
                {999, 0}, {999, 1, 0}, {0x7f010001, 0x7f010021, 0}, {0, 1}},
                "unknown/custom ids and zero terminator");
    }

    private static void budgetBoundary() {
        int[] ids = new int[16];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = 0x7f000000 + i * 101;
        }
        // 64 rules * 2^16 masks == 1<<22 exactly: the table is built.
        StateColorList atBudget = rules(singleIdRules(ids, 64));
        PreparedMatcher atPrepared = StateMatcher.prepare(atBudget);
        check(tableBuilt(atPrepared), "budget boundary 64 rules builds table");
        verify(atBudget, atPrepared, new int[] {ids[15], 0}, "budget 64 later rule");

        // 65 rules * 2^16 masks > 1<<22: delegates to direct matching.
        StateColorList overBudget = rules(singleIdRules(ids, 65));
        PreparedMatcher overPrepared = StateMatcher.prepare(overBudget);
        check(!tableBuilt(overPrepared), "budget boundary 65 rules delegates");
        verify(overBudget, overPrepared, new int[] {ids[15], 0}, "budget 65 later rule");
    }

    /** One invariant sample: prepared index equals direct index and first-match holds. */
    private static void defaultInvariants() {
        Random random = new Random(13579L);
        int[] universe = {1, 2, 3, 4, 5, 6, 7, 8};
        int ruleCount = 1 + random.nextInt(8);
        List<StateSpec> specs = new ArrayList<StateSpec>();
        for (int r = 0; r < ruleCount; r++) {
            int length = random.nextInt(4);
            int[] conditions = new int[length];
            for (int i = 0; i < length; i++) {
                int id = universe[random.nextInt(universe.length)];
                conditions[i] = random.nextBoolean() ? id : -id;
            }
            specs.add(new StateSpec(conditions));
        }
        StateColorList rules = new StateColorList(specs, new int[specs.size()]);
        PreparedMatcher prepared = StateMatcher.prepare(rules);
        int[] state = stateFromMask(universe, random.nextInt(256));
        int index = prepared.matchIndex(state);
        check(index == StateMatcher.matchIndex(rules, state), "default invariant index");
        boolean rest = true;
        if (index >= 0) {
            rest = StateMatcher.matches(rules.specAt(index), state);
            for (int r = 0; r < index; r++) {
                rest = rest && !StateMatcher.matches(rules.specAt(r), state);
            }
        } else {
            for (int r = 0; r < rules.size(); r++) {
                rest = rest && !StateMatcher.matches(rules.specAt(r), state);
            }
        }
        check(rest, "default invariant first-match set");
    }

    /** Small differential sample of the direct matcher against the reference contract. */
    private static void defaultDirectMatchesReference() {
        Random random = new Random(86420L);
        int[] universe = {1, 2, 3, 4, 5, 6, 0x7f010001, 0x7f010021, 0x70000001};
        for (int trial = 0; trial < 2; trial++) {
            int[] conditions = new int[random.nextInt(8)];
            for (int i = 0; i < conditions.length; i++) {
                int id = universe[random.nextInt(universe.length)];
                conditions[i] = random.nextInt(8) == 0 ? 0 : random.nextBoolean() ? id : -id;
            }
            int[] state = new int[random.nextInt(12)];
            for (int i = 0; i < state.length; i++) {
                state[i] = random.nextInt(8) == 0 ? 0 : universe[random.nextInt(universe.length)];
            }
            check(StateMatcher.matches(new StateSpec(conditions), state)
                            == referenceMatches(new StateSpec(conditions), state),
                    "default direct matches reference");
        }
    }

    // -------------------------------------------------------------------------
    // Stress corpus: the original broad loops, preserved verbatim and opt-in only.
    // -------------------------------------------------------------------------

    private static void exhaustiveSmallBitSpace() {
        int[] universe = {1, 2, 3, 4, 5, 6, 7, 8};
        List<StateColorList> ruleLists = new ArrayList<StateColorList>();
        ruleLists.add(rules(list(spec(1), spec(-2), spec(1, -2), spec(8, -3), spec())));
        ruleLists.add(rules(list(spec(1, 2, 3), spec(-1, 2), spec(3), spec(-8), spec())));
        ruleLists.add(rules(list(spec(), spec(1))));
        ruleLists.add(rules(list(spec(-1, -2, -3, -4, -5, -6, -7, -8), spec())));
        ruleLists.add(rules(list(spec(1, -1), spec(2), spec())));
        for (StateColorList rules : ruleLists) {
            PreparedMatcher prepared = StateMatcher.prepare(rules);
            for (int mask = 0; mask < 256; mask++) {
                int[] state = stateFromMask(universe, mask);
                int[] noTerminator = trimToTerminator(state);
                int[] withTrailing = withTrailing(state);
                verify(rules, prepared, state, "exhaustive mask " + mask);
                verify(rules, prepared, noTerminator, "exhaustive no terminator " + mask);
                verify(rules, prepared, withTrailing, "exhaustive trailing " + mask);
            }
        }
    }

    private static void randomizedRuleLists() {
        Random random = new Random(20261104L);
        int[] universe = {1, 2, 3, 4, 5, 6, 7, 8};
        for (int trial = 0; trial < 400; trial++) {
            int ruleCount = 1 + random.nextInt(8);
            List<StateSpec> specs = new ArrayList<StateSpec>();
            for (int r = 0; r < ruleCount; r++) {
                int length = random.nextInt(5);
                int[] conditions = new int[length];
                for (int i = 0; i < length; i++) {
                    int id = universe[random.nextInt(universe.length)];
                    conditions[i] = random.nextInt(8) == 0 ? 0 : random.nextBoolean() ? id : -id;
                }
                specs.add(new StateSpec(conditions));
            }
            if (random.nextInt(3) == 0) {
                specs.add(new StateSpec());
            }
            StateColorList rules = new StateColorList(specs, new int[specs.size()]);
            PreparedMatcher prepared = StateMatcher.prepare(rules);
            for (int mask = 0; mask < 256; mask++) {
                verify(rules, prepared, stateFromMask(universe, mask), "random rules " + trial + " mask " + mask);
            }
            for (int q = 0; q < 128; q++) {
                int length = random.nextInt(12);
                int[] state = new int[length + 1];
                for (int i = 0; i < length; i++) {
                    state[i] = random.nextInt(8) == 0 ? 0 : universe[random.nextInt(universe.length)];
                }
                state[length] = 0;
                verify(rules, prepared, state, "random rules " + trial + " state " + q);
            }
        }
    }

    private static void largeAndCustomIds() {
        Random random = new Random(97531L);
        int[] pool = {0x7f010001, 0x7f010021, 0x7f020003, 0x70000001, 0x70000009, 0x7f030007,
                0x101000a, 0x1010000, 0x1010004, 0x1010009};
        for (int trial = 0; trial < 600; trial++) {
            int ruleCount = 1 + random.nextInt(10);
            List<StateSpec> specs = new ArrayList<StateSpec>();
            for (int r = 0; r < ruleCount; r++) {
                int length = random.nextInt(5);
                int[] conditions = new int[length];
                for (int i = 0; i < length; i++) {
                    int id = pool[random.nextInt(pool.length)];
                    conditions[i] = random.nextInt(8) == 0 ? 0 : random.nextBoolean() ? id : -id;
                }
                specs.add(new StateSpec(conditions));
            }
            StateColorList rules = new StateColorList(specs, new int[specs.size()]);
            PreparedMatcher prepared = StateMatcher.prepare(rules);
            for (int q = 0; q < 300; q++) {
                int length = random.nextInt(10);
                int[] state = new int[length + 1];
                for (int i = 0; i < length; i++) {
                    int pick = random.nextInt(5);
                    if (pick == 0) state[i] = pool[random.nextInt(pool.length)];
                    else if (pick == 1) state[i] = 0x70000000 + random.nextInt(100000);
                    else if (pick == 2) state[i] = -pool[random.nextInt(pool.length)];
                    else if (pick == 3) state[i] = Integer.MIN_VALUE;
                    else state[i] = 0;
                }
                state[length] = 0;
                verify(rules, prepared, state, "large ids " + trial + " state " + q);
            }
        }
    }

    private static void wideIdFallback() {
        Random random = new Random(24680L);
        for (int distinct : new int[] {16, 17, 20, 24, 32, 40}) {
            int[] ids = new int[distinct];
            for (int i = 0; i < distinct; i++) {
                ids[i] = 0x7f000000 + i * 101;
            }
            List<StateSpec> specs = new ArrayList<StateSpec>();
            for (int r = 0; r < 8; r++) {
                int length = 1 + random.nextInt(4);
                int[] conditions = new int[length];
                for (int i = 0; i < length; i++) {
                    int id = ids[random.nextInt(distinct)];
                    conditions[i] = random.nextBoolean() ? id : -id;
                }
                specs.add(new StateSpec(conditions));
            }
            specs.add(new StateSpec());
            StateColorList rules = new StateColorList(specs, new int[specs.size()]);
            PreparedMatcher prepared = StateMatcher.prepare(rules);
            for (int q = 0; q < 2000; q++) {
                int length = random.nextInt(8);
                int[] state = new int[length + 1];
                for (int i = 0; i < length; i++) {
                    state[i] = random.nextInt(4) == 0 ? 0 : ids[random.nextInt(distinct)];
                }
                state[length] = 0;
                verify(rules, prepared, state, "wide " + distinct + " state " + q);
            }
        }
    }

    private static void invariants() {
        Random random = new Random(13579L);
        int[] universe = {1, 2, 3, 4, 5, 6, 7, 8};
        for (int trial = 0; trial < 200; trial++) {
            int ruleCount = 1 + random.nextInt(8);
            List<StateSpec> specs = new ArrayList<StateSpec>();
            for (int r = 0; r < ruleCount; r++) {
                int length = random.nextInt(4);
                int[] conditions = new int[length];
                for (int i = 0; i < length; i++) {
                    int id = universe[random.nextInt(universe.length)];
                    conditions[i] = random.nextBoolean() ? id : -id;
                }
                specs.add(new StateSpec(conditions));
            }
            StateColorList rules = new StateColorList(specs, new int[specs.size()]);
            PreparedMatcher prepared = StateMatcher.prepare(rules);
            for (int q = 0; q < 100; q++) {
                int[] state = stateFromMask(universe, random.nextInt(256));
                int index = prepared.matchIndex(state);
                check(index == StateMatcher.matchIndex(rules, state), "invariant index");
                if (index >= 0) {
                    check(StateMatcher.matches(rules.specAt(index), state), "invariant selected matches");
                    for (int r = 0; r < index; r++) {
                        check(!StateMatcher.matches(rules.specAt(r), state), "invariant earlier rejects");
                    }
                } else {
                    for (int r = 0; r < rules.size(); r++) {
                        check(!StateMatcher.matches(rules.specAt(r), state), "invariant none match");
                    }
                }
            }
        }
    }

    private static void directMatchesReference() {
        Random random = new Random(86420L);
        int[] universe = {1, 2, 3, 4, 5, 6, 0x7f010001, 0x7f010021, 0x70000001};
        for (int trial = 0; trial < 20000; trial++) {
            int[] conditions = new int[random.nextInt(8)];
            for (int i = 0; i < conditions.length; i++) {
                int id = universe[random.nextInt(universe.length)];
                conditions[i] = random.nextInt(8) == 0 ? 0 : random.nextBoolean() ? id : -id;
            }
            int[] state = new int[random.nextInt(12)];
            for (int i = 0; i < state.length; i++) {
                state[i] = random.nextInt(8) == 0 ? 0 : universe[random.nextInt(universe.length)];
            }
            check(StateMatcher.matches(new StateSpec(conditions), state) == referenceMatches(new StateSpec(conditions), state),
                    "direct matches reference");
        }
    }

    private static List<StateSpec> singleIdRules(int[] ids, int count) {
        List<StateSpec> specs = new ArrayList<StateSpec>();
        for (int i = 0; i < count; i++) {
            specs.add(new StateSpec(ids[i % ids.length]));
        }
        return specs;
    }

    private static boolean tableBuilt(PreparedMatcher prepared) {
        try {
            java.lang.reflect.Field field = PreparedMatcher.class.getDeclaredField("table");
            field.setAccessible(true);
            return field.get(prepared) != null;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void verify(StateColorList rules, PreparedMatcher prepared, int[] state, String label) {
        int expected = StateMatcher.matchIndex(rules, state);
        int actual = prepared.matchIndex(state);
        check(expected == actual, label + " expected " + expected + " actual " + actual);
    }

    /** Counts one check for a group of states that must all agree with the direct matcher. */
    private static void verifyAll(StateColorList rules, PreparedMatcher prepared, int[][] states, String label) {
        boolean all = true;
        for (int[] state : states) {
            all = all && StateMatcher.matchIndex(rules, state) == prepared.matchIndex(state);
        }
        check(all, label);
    }

    private static int[] stateFromMask(int[] universe, int mask) {
        int count = Integer.bitCount(mask);
        int[] state = new int[count + 2];
        int at = 0;
        for (int bit = 0; bit < universe.length; bit++) {
            if ((mask & (1 << bit)) != 0) {
                state[at++] = universe[bit];
            }
        }
        state[at] = 0;
        state[at + 1] = 999;
        return state;
    }

    private static int[] trimToTerminator(int[] state) {
        int length = 0;
        while (length < state.length && state[length] != 0) {
            length++;
        }
        return Arrays.copyOf(state, length);
    }

    private static int[] withTrailing(int[] state) {
        int[] copy = Arrays.copyOf(state, state.length + 2);
        copy[state.length] = 777;
        copy[state.length + 1] = 888;
        return copy;
    }

    private static StateSpec spec(int... conditions) {
        return new StateSpec(conditions);
    }

    private static List<StateSpec> list(StateSpec... specs) {
        return new ArrayList<StateSpec>(Arrays.asList(specs));
    }

    private static StateColorList rules(List<StateSpec> specs) {
        return new StateColorList(specs, new int[specs.size()]);
    }

    /** Reference matching copied from the direct contract, used to validate invariants. */
    private static boolean referenceMatches(StateSpec spec, int[] state) {
        Set<Integer> present = new HashSet<Integer>();
        for (int value : state) {
            if (value == 0) {
                break;
            }
            present.add(value);
        }
        for (int i = 0; i < spec.size(); i++) {
            int condition = spec.conditionAt(i);
            if (condition == 0) {
                break;
            }
            if (condition > 0 && !present.contains(condition)) {
                return false;
            }
            if (condition < 0 && present.contains(-condition)) {
                return false;
            }
        }
        return true;
    }

    /** Counts one check for a group of actions that must all throw NullPointerException. */
    private static void expectNpeAll(Runnable... actions) {
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (NullPointerException expected) {
                continue;
            }
            throw new AssertionError("expected NullPointerException");
        }
        checks++;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
