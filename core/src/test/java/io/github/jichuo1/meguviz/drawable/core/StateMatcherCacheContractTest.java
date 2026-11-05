package io.github.jichuo1.meguviz.drawable.core;

import io.github.jichuo1.meguviz.drawable.model.StateColorList;
import io.github.jichuo1.meguviz.drawable.model.StateSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Contract for the bounded shared state-table cache. Plain Java program, run by
 * {@code :core:contractTest}.
 */
public final class StateMatcherCacheContractTest {
    private static int checks;

    private StateMatcherCacheContractTest() { }

    public static int run() {
        checks = 0;
        sameConditionsShareOneTable();
        differentConditionsDoNotShare();
        fallbackIsNeverCached();
        weightBoundEvictsLeastRecentlyUsed();
        clearResetsWeight();
        cachedTableMatchesDirect();
        System.out.println("StateMatcherCacheContractTest: " + checks + " checks passed");
        return checks;
    }

    private static StateColorList rules(int colorA, int colorB, int... ids) {
        List<StateSpec> specs = new ArrayList<StateSpec>();
        for (int id : ids) specs.add(new StateSpec(id));
        int[] colors = new int[specs.size()];
        for (int i = 0; i < colors.length; i++) colors[i] = (i == 0 ? colorA : colorB) + i;
        return new StateColorList(specs, colors);
    }

    private static void sameConditionsShareOneTable() {
        int pressed = 0x01010000;
        StateMatcherCache cache = new StateMatcherCache(1L << 20);
        StateMatcher.PreparedMatcher first = cache.acquire(rules(1, 2, pressed, -pressed));
        StateMatcher.PreparedMatcher second = cache.acquire(rules(3, 4, pressed, -pressed));
        check(first != null && first == second, "same conditions share one compiled table");
        check(cache.size() == 1, "sharing keeps one cache entry");
    }

    private static void differentConditionsDoNotShare() {
        StateMatcherCache cache = new StateMatcherCache(1L << 20);
        cache.acquire(rules(1, 2, 0x01010000, -0x01010000));
        cache.acquire(rules(1, 2, 0x01010001, -0x01010001));
        check(cache.size() == 2, "different conditions use different entries");
    }

    private static void fallbackIsNeverCached() {
        // 17 distinct ids exceed the table bound, so the matcher delegates to direct matching.
        int[] ids = new int[17];
        for (int i = 0; i < ids.length; i++) ids[i] = 0x01010000 + i;
        StateMatcherCache cache = new StateMatcherCache(1L << 20);
        StateMatcher.PreparedMatcher matcher = cache.acquire(rules(1, 2, ids));
        check(matcher == null, "a delegating matcher is not cached and acquire reports null");
        check(cache.size() == 0, "fallback matchers are not cached");
    }

    private static void weightBoundEvictsLeastRecentlyUsed() {
        // Each K=6 table costs 128 B + key; a 200 B budget keeps one entry at a time.
        StateMatcherCache cache = new StateMatcherCache(200);
        int[] first = new int[6];
        int[] second = new int[6];
        for (int i = 0; i < 6; i++) {
            first[i] = 0x01010000 + i;
            second[i] = 0x01020000 + i;
        }
        cache.acquire(rules(1, 2, first));
        cache.acquire(rules(1, 2, second));
        check(cache.size() == 1, "weight bound evicts down to the budget");
        check(cache.weight() <= 200, "weight stays within the bound");
    }

    private static void clearResetsWeight() {
        StateMatcherCache cache = new StateMatcherCache(1L << 20);
        cache.acquire(rules(1, 2, 0x01010000, -0x01010000));
        cache.clear();
        check(cache.size() == 0 && cache.weight() == 0, "clear drops entries and weight");
    }

    private static void cachedTableMatchesDirect() {
        int pressed = 0x01010000;
        int focused = 0x01010001;
        StateColorList list = new StateColorList(Arrays.asList(
                new StateSpec(pressed, focused), new StateSpec(pressed), new StateSpec(-pressed)),
                new int[] { 1, 2, 3 });
        StateMatcherCache cache = new StateMatcherCache(1L << 20);
        StateMatcher.PreparedMatcher matcher = cache.acquire(list);
        check(matcher != null, "cache returns a compiled table for a small rule list");
        int[][] states = {
                {}, { pressed }, { focused }, { pressed, focused }, { -pressed },
                { pressed, -focused }, { 0x01010009 },
        };
        for (int[] state : states) {
            check(matcher.matchIndex(state) == StateMatcher.matchIndex(list, state),
                    "cached table matches direct for " + Arrays.toString(state));
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
