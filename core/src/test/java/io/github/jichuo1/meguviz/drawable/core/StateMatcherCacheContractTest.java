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
        oversizedTableIsRejectedBeforeBuilding();
        tinyBudgetRejectsInsteadOfChurning();
        lruKeepsTheRecentlyUsedEntry();
        sharedTableStillReadsTheCallersOwnColors();
        weightBoundEvictsLeastRecentlyUsed();
        clearResetsWeight();
        cachedTableMatchesDirect();
        try {
            concurrentAcquireKeepsOneEntryAndOneWeight();
        } catch (Exception error) {
            throw new AssertionError("concurrent acquire contract failed", error);
        }
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

    private static void oversizedTableIsRejectedBeforeBuilding() {
        // K=16 costs exactly maxWeight/8 for the default 1 MiB budget, so it is admitted by the
        // table-payload rule; a smaller budget must reject it without building.
        int[] ids = new int[16];
        for (int i = 0; i < ids.length; i++) ids[i] = 0x01010000 + i;
        StateMatcherCache tight = new StateMatcherCache(1L << 19);
        check(tight.acquire(rules(1, 2, ids)) == null, "an oversized table is rejected");
        check(tight.size() == 0 && tight.weight() == 0, "a rejected table leaves no entry");

        StateMatcherCache roomy = new StateMatcherCache(1L << 20);
        check(roomy.acquire(rules(1, 2, ids)) != null, "K=16 fits the default budget");
        check(roomy.size() == 1, "K=16 is cached at the default budget");
    }

    private static void tinyBudgetRejectsInsteadOfChurning() {
        // A budget smaller than one entry would build and immediately evict on every call.
        int[] ids = {0x01010000, 0x01010001};
        StateMatcherCache cache = new StateMatcherCache(1);
        check(cache.acquire(rules(1, 2, ids)) == null, "a table larger than the whole budget is refused");
        check(cache.size() == 0 && cache.weight() == 0, "no churn entry is left behind");
    }

    private static void lruKeepsTheRecentlyUsedEntry() {
        // Each K=6 table costs 128 B payload + key bytes (~168 B). A budget of 400 B holds two,
        // so A -> B -> hit A -> insert C must evict B (the least recently used).
        int[] a = new int[6];
        int[] b = new int[6];
        int[] c = new int[6];
        for (int i = 0; i < 6; i++) {
            a[i] = 0x01010000 + i;
            b[i] = 0x01020000 + i;
            c[i] = 0x01030000 + i;
        }
        StateMatcherCache cache = new StateMatcherCache(400);
        StateMatcher.PreparedMatcher matcherA = cache.acquire(rules(1, 2, a));
        cache.acquire(rules(1, 2, b));
        check(cache.size() == 2, "the budget holds both entries");
        check(cache.acquire(rules(1, 2, a)) == matcherA, "a hit returns the cached instance");
        cache.acquire(rules(1, 2, c));
        check(cache.size() == 2, "inserting a third entry evicts one");
        check(cache.acquire(rules(1, 2, c)) != null && cache.acquire(rules(1, 2, a)) != null,
                "the newest and the recently used entries survive");
    }

    private static void sharedTableStillReadsTheCallersOwnColors() {
        // Two lists share one compiled table; each caller must resolve the index against its own
        // color array, so the shared table must not capture colors.
        int pressed = 0x01010000;
        StateColorList red = new StateColorList(Arrays.asList(new StateSpec(pressed), new StateSpec(-pressed)),
                new int[] { 0xFFFF0000, 0xFF0000FF });
        StateColorList green = new StateColorList(Arrays.asList(new StateSpec(pressed), new StateSpec(-pressed)),
                new int[] { 0xFF00FF00, 0xFF000000 });
        StateMatcherCache cache = new StateMatcherCache(1L << 20);
        StateMatcher.PreparedMatcher shared = cache.acquire(red);
        check(shared == cache.acquire(green), "same conditions share one table");
        int pressedIndex = shared.matchIndex(new int[] { pressed });
        int defaultIndex = shared.matchIndex(new int[0]);
        check(red.colorAt(pressedIndex) == 0xFFFF0000 && red.colorAt(defaultIndex) == 0xFF0000FF
                        && green.colorAt(pressedIndex) == 0xFF00FF00 && green.colorAt(defaultIndex) == 0xFF000000,
                "each caller reads its own colors through the shared table");
    }

    private static void concurrentAcquireKeepsOneEntryAndOneWeight() throws Exception {
        final StateColorList list = rules(1, 2, 0x01010000, -0x01010000);
        final StateMatcherCache cache = new StateMatcherCache(1L << 20);
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        start.await();
                        for (int round = 0; round < 50; round++) {
                            if (cache.acquire(list) == null) throw new AssertionError("unexpected null");
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join(10_000);
        if (failure.get() != null) throw new AssertionError(failure.get());
        check(cache.size() == 1, "concurrent misses keep a single entry");
        check(cache.weight() > 0 && cache.weight() <= (1L << 20), "weight is counted once and within the bound");
        check(cache.acquire(list) != null, "the shared entry is still usable after the race");
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
