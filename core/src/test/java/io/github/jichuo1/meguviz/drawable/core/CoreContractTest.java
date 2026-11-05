package io.github.jichuo1.meguviz.drawable.core;

import io.github.jichuo1.meguviz.drawable.model.StateColorList;
import io.github.jichuo1.meguviz.drawable.model.StateSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/** Dependency-free executable behavioral tests for the initial core contracts. */
public final class CoreContractTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        checks = 0;
        immutabilityAndOrdering();
        stateCorpus();
        geometry();
        checks += CoreAdditionalContractTest.run();
        checks += BindingContractTest.run();
        checks += ProviderExtensionContractTest.run();
        checks += FlightCacheContractTest.run();
        checks += AsyncPreparationContractTest.run();
        checks += StateMatcherFastPathTest.run();
        checks += StateMatcherCacheContractTest.run();
        checks += TicketRetentionContractTest.run();
        if (!Boolean.getBoolean("meguviz.test.stress") && checks >= 150) {
            throw new AssertionError("Default contract budget exceeded: " + checks + "; keep broad corpora opt-in");
        }
        System.out.println("CoreContractTest: " + checks + " checks passed"
                + (Boolean.getBoolean("meguviz.test.stress") ? " (stress)" : " (default)"));
    }

    private static void immutabilityAndOrdering() {
        int[] input = {1, -2, 0, 17};
        StateSpec first = new StateSpec(input);
        input[0] = 99;
        int[] exported = first.conditions();
        exported[0] = 98;
        check(first.equals(new StateSpec(1, -2, 0, 17))
                && first.hashCode() == new StateSpec(1, -2, 0, 17).hashCode(),
                "state defensive copies and value hash");
        ArrayList<StateSpec> specs = new ArrayList<StateSpec>(Arrays.asList(first, new StateSpec()));
        int[] colors = {0x00000000, 0xffabcdef};
        StateColorList rules = new StateColorList(specs, colors);
        specs.clear();
        colors[0] = 123;
        check(rules.size() == 2 && rules.colorAt(0) == 0
                && rules.equals(new StateColorList(Arrays.asList(new StateSpec(1, -2, 0, 17),
                        new StateSpec()), new int[] {0, 0xffabcdef})),
                "rule defensive copies and value equality");
        StateColorList noWildcard = new StateColorList(Arrays.asList(new StateSpec(9)), new int[] {7});
        check(StateMatcher.matchIndex(rules, new int[] {1}) == 0
                && StateMatcher.matchIndex(rules, new int[] {1, 2}) == 1
                && StateMatcher.matchIndex(noWildcard, new int[] {1}) == -1,
                "first match, negative condition and explicit no match");
        check(StateMatcher.matches(new StateSpec(1, 0, 2), new int[] {1})
                && !StateMatcher.matches(new StateSpec(2), new int[] {1, 0, 2})
                && !StateMatcher.matches(new StateSpec(0x7f010001), new int[] {0x7f010021}),
                "spec/state terminators and non-bitmask IDs");
        expectFailure(new Runnable() { public void run() { new StateSpec(Integer.MIN_VALUE); }});
    }

    private static void stateCorpus() {
        Random random = new Random(20261026L);
        int[] ids = {1, 2, 3, 4, 5, 6, 0x7f010001, 0x7f010021, 0x70000001};
        int samples = Boolean.getBoolean("meguviz.test.stress") ? 10000 : 3;
        for (int trial = 0; trial < samples; trial++) {
            int[] spec = new int[random.nextInt(8)];
            int[] state = new int[random.nextInt(12)];
            for (int i = 0; i < spec.length; i++) {
                int id = ids[random.nextInt(ids.length)];
                spec[i] = random.nextInt(8) == 0 ? 0 : random.nextBoolean() ? id : -id;
            }
            for (int i = 0; i < state.length; i++) {
                state[i] = random.nextInt(8) == 0 ? 0 : ids[random.nextInt(ids.length)];
            }
            check(StateMatcher.matches(new StateSpec(spec), state) == reference(spec, state), "corpus state match");
        }
    }

    private static boolean reference(int[] spec, int[] state) {
        java.util.Set<Integer> present = new java.util.HashSet<Integer>();
        for (int value : state) {
            if (value == 0) break;
            present.add(value);
        }
        for (int condition : spec) {
            if (condition == 0) break;
            if (condition > 0 && !present.contains(condition)) return false;
            if (condition < 0 && present.contains(-condition)) return false;
        }
        return true;
    }

    private static void geometry() {
        float[] unequal = {90, 20, 10, 20, 0, 0, 0, 0};
        float[] source = {80, 20, 80, 20, 0, 0, 0, 0};
        check(Arrays.equals(unequal, CornerRadii.normalize(100, 100, unequal))
                && Arrays.equals(CornerRadii.normalize(100, 100, source),
                        new float[] {50, 12.5f, 50, 12.5f, 0, 0, 0, 0})
                && source[0] == 80,
                "no per-corner clamp, uniform scaling and input unchanged");
        check(Arrays.equals(CornerRadii.normalize(100, 100, new float[] {-1, 2, 2, 0, 3, 4, 5, 6}),
                new float[] {0, 0, 0, 0, 3, 4, 5, 6})
                && Arrays.equals(CornerRadii.normalize(0, 2, source), new float[8]),
                "nonpositive corner pairs and zero bounds");
        expectFailure(new Runnable() { public void run() { CornerRadii.normalize(Float.NaN, 1, new float[8]); }});
        expectFailure(new Runnable() { public void run() { CornerRadii.normalize(1, 1, new float[] {0, 0, 0, 0, 0, 0, 0, Float.POSITIVE_INFINITY}); }});
        Random random = new Random(42);
        int samples = Boolean.getBoolean("meguviz.test.stress") ? 10000 : 3;
        for (int trial = 0; trial < samples; trial++) {
            float w = random.nextFloat() * 1000;
            float h = random.nextFloat() * 1000;
            float[] input = new float[8];
            for (int i = 0; i < 8; i++) input[i] = random.nextFloat() * 2000;
            float[] r = CornerRadii.normalize(w, h, input);
            check((double) r[0] + r[2] <= w && (double) r[4] + r[6] <= w
                    && (double) r[3] + r[5] <= h && (double) r[7] + r[1] <= h,
                    "all adjacent edges fit");
        }
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
