package io.github.jichuo1.meguviz.drawable.benchmark;

import io.github.jichuo1.meguviz.drawable.core.StateMatcher;
import io.github.jichuo1.meguviz.drawable.core.StateMatcher.PreparedMatcher;
import io.github.jichuo1.meguviz.drawable.model.StateColorList;
import io.github.jichuo1.meguviz.drawable.model.StateSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Host-only probe for the opt-in compiled state matcher. Measures differential correctness,
 * preparation cost, table payload and query throughput against the direct matcher.
 *
 * <p>This is not an Android/ART benchmark and does not claim frame-time or device results.
 * Raw output is CSV on stdout; redirect it under the local ignored build directory.
 */
public final class StateMatcherProbe {
    private static final int WARMUP = 4;
    private static final int ROUNDS = 7;
    private static final int OPS = 1000000;
    private static final int PREP_ITERATIONS = 2000;
    private static final int MAX_TABLE_IDS = 16;
    private static final int MAX_TABLE_RULES = Short.MAX_VALUE;
    private static final long MAX_PREPARATION_OPERATIONS = 1L << 22;
    private static volatile long sink;

    private static final class Fixture {
        final String name;
        final StateColorList rules;
        final int[][] states;
        final int distinctIds;

        Fixture(String name, StateColorList rules, int[][] states, int distinctIds) {
            this.name = name;
            this.rules = rules;
            this.states = states;
            this.distinctIds = distinctIds;
        }
    }

    public static void main(String[] args) {
        List<Fixture> fixtures = new ArrayList<Fixture>();
        fixtures.add(pilot8());
        fixtures.add(page2());
        fixtures.add(real4());
        fixtures.add(custom6());
        fixtures.add(wide20());
        fixtures.add(short1());
        fixtures.add(budgetFallback());

        System.out.println("meta,java," + System.getProperty("java.version")
                + ",vm," + System.getProperty("java.vm.name")
                + ",arch," + System.getProperty("os.arch")
                + ",cores," + Runtime.getRuntime().availableProcessors());
        System.out.println("columns,fixture,distinct_ids,rule_count,state_count,usable,delegates,prep_ns,table_bytes,"
                + "direct_ns,prepared_ns,speedup,break_even_queries,checksum");

        long checksum = 0;
        for (Fixture fixture : fixtures) {
            checksum += verifyDifferential(fixture);
            long prepMedian = measurePreparation(fixture);
            checksum += prepMedian;
            checksum += measureThroughput(fixture, prepMedian);
        }
        System.out.println("sink," + checksum);
    }

    private static long verifyDifferential(Fixture fixture) {
        PreparedMatcher prepared = StateMatcher.prepare(fixture.rules);
        long checksum = 0;
        for (int i = 0; i < fixture.states.length; i++) {
            int[] state = fixture.states[i];
            int expected = StateMatcher.matchIndex(fixture.rules, state);
            int actual = prepared.matchIndex(state);
            if (expected != actual) {
                throw new AssertionError(fixture.name + " state " + i
                        + " expected " + expected + " actual " + actual);
            }
            checksum += expected;
        }
        System.out.println("diff," + fixture.name + "," + fixture.states.length + ",ok");
        return checksum;
    }

    private static long measurePreparation(Fixture fixture) {
        // Warm up the constructor and the fixture before timing.
        for (int i = 0; i < 200; i++) {
            sink ^= StateMatcher.prepare(fixture.rules).matchIndex(fixture.states[i & 7]);
        }
        long[] samples = new long[PREP_ITERATIONS];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            PreparedMatcher prepared = StateMatcher.prepare(fixture.rules);
            samples[i] = System.nanoTime() - start;
            sink ^= prepared.matchIndex(fixture.states[i & 7]);
        }
        long median = median(samples);
        boolean usable = isUsable(fixture);
        long tableBytes = usable ? 2L * (1L << fixture.distinctIds) : 0L;
        System.out.println("prep," + fixture.name + "," + fixture.distinctIds + "," + fixture.rules.size()
                + "," + fixture.states.length + "," + usable + "," + !usable + "," + median + "," + tableBytes);
        return median;
    }

    private static long measureThroughput(Fixture fixture, long medianPrep) {
        final PreparedMatcher prepared = StateMatcher.prepare(fixture.rules);
        final int[][] states = fixture.states;
        final int mask = states.length - 1;
        long[] direct = new long[ROUNDS];
        long[] compiled = new long[ROUNDS];
        for (int round = -WARMUP; round < ROUNDS; round++) {
            long first;
            long second;
            if (((round) & 1) == 0) {
                first = directLoop(fixture.rules, states, mask, OPS);
                second = compiledLoop(prepared, states, mask, OPS);
            } else {
                second = compiledLoop(prepared, states, mask, OPS);
                first = directLoop(fixture.rules, states, mask, OPS);
            }
            if (round >= 0) {
                direct[round] = first;
                compiled[round] = second;
            }
        }
        long directMedian = median(direct);
        long compiledMedian = median(compiled);
        double speedup = (double) directMedian / compiledMedian;
        double perQuerySaving = (double) (directMedian - compiledMedian) / OPS;
        long breakEven = perQuerySaving > 0 ? (long) Math.ceil(medianPrep / perQuerySaving) : -1;
        boolean usable = isUsable(fixture);
        long tableBytes = usable ? 2L * (1L << fixture.distinctIds) : 0L;
        long checksum = directMedian ^ compiledMedian;
        sink ^= checksum;
        // A non-usable fixture delegates to the direct matcher, so its two timings are the
        // same code path and any ratio is JIT noise; report it as n/a rather than a win.
        String speedupText = usable ? String.format("%.3f", speedup) : "n/a";
        String breakEvenText = usable ? Long.toString(breakEven) : "n/a";
        System.out.println("sample," + fixture.name + "," + fixture.distinctIds + "," + fixture.rules.size()
                + "," + states.length + "," + usable + "," + !usable + "," + medianPrep + "," + tableBytes
                + "," + directMedian + "," + compiledMedian + "," + speedupText
                + "," + breakEvenText + "," + checksum);
        return checksum;
    }

    private static long medianPrep(Fixture fixture) {
        long[] samples = new long[500];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            PreparedMatcher prepared = StateMatcher.prepare(fixture.rules);
            samples[i] = System.nanoTime() - start;
            sink ^= prepared.matchIndex(fixture.states[i & 7]);
        }
        return median(samples);
    }

    private static long directLoop(StateColorList rules, int[][] states, int mask, int ops) {
        long sum = 0;
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            sum += StateMatcher.matchIndex(rules, states[(i * 73 + 19) & mask]);
        }
        sink ^= sum;
        return System.nanoTime() - start;
    }

    private static long compiledLoop(PreparedMatcher prepared, int[][] states, int mask, int ops) {
        long sum = 0;
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            sum += prepared.matchIndex(states[(i * 73 + 19) & mask]);
        }
        sink ^= sum;
        return System.nanoTime() - start;
    }

    private static boolean isUsable(Fixture fixture) {
        return fixture.distinctIds <= MAX_TABLE_IDS
                && fixture.rules.size() <= MAX_TABLE_RULES
                && ((1L << fixture.distinctIds) * fixture.rules.size()) <= MAX_PREPARATION_OPERATIONS;
    }

    private static long median(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    // ---------- fixtures ----------

    private static Fixture pilot8() {
        int[] ids = {101, 102, 103, 104, 105, 106, 107, 108};
        List<StateSpec> specs = new ArrayList<StateSpec>();
        for (int i = 0; i < 12; i++) {
            specs.add(new StateSpec(ids[i % 8], -ids[(i + 3) % 8], ids[(i + 5) % 8]));
        }
        specs.add(new StateSpec());
        int[][] states = new int[256][];
        for (int mask = 0; mask < 256; mask++) {
            int[] values = new int[Integer.bitCount(mask) + 2];
            int count = 0;
            for (int bit = 0; bit < ids.length; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    values[count++] = ids[bit];
                }
            }
            values[count] = 0;
            values[count + 1] = 999;
            states[mask] = values;
        }
        return new Fixture("pilot8", new StateColorList(specs, new int[specs.size()]), states, 8);
    }

    private static Fixture page2() {
        int enabled = 0x01010000;
        int checked = 0x0101000a;
        List<StateSpec> specs = new ArrayList<StateSpec>();
        specs.add(new StateSpec(checked));
        specs.add(new StateSpec());
        int[][] states = new int[8][];
        int[][] base = {{enabled, checked, 0}, {enabled, 0}, {checked, 0}, {0},
                {enabled, checked, 0x7f000001, 0}, {enabled, checked, 0}, {enabled, 0}, {0}};
        for (int i = 0; i < states.length; i++) {
            states[i] = base[i];
        }
        return new Fixture("page2", new StateColorList(specs, new int[specs.size()]), states, 2);
    }

    private static Fixture real4() {
        int enabled = 0x01010000;
        int checked = 0x0101000a;
        int pressed = 0x01010004;
        int selected = 0x01010009;
        List<StateSpec> specs = new ArrayList<StateSpec>();
        specs.add(new StateSpec(checked));
        specs.add(new StateSpec(pressed));
        specs.add(new StateSpec(-enabled));
        specs.add(new StateSpec());
        int[][] states = new int[8][];
        int[][] base = {{enabled, checked, 0}, {enabled, pressed, 0}, {checked, 0}, {pressed, selected, 0},
                {0}, {enabled, checked, pressed, selected, 0}, {enabled, pressed, 0}, {checked, 0}};
        for (int i = 0; i < states.length; i++) {
            states[i] = base[i];
        }
        return new Fixture("real4", new StateColorList(specs, new int[specs.size()]), states, 4);
    }

    private static Fixture custom6() {
        int[] ids = {0x7f010001, 0x7f010021, 0x7f020003, 0x70000001, 0x70000009, 0x7f030007};
        Random random = new Random(424242L);
        List<StateSpec> specs = new ArrayList<StateSpec>();
        for (int r = 0; r < 8; r++) {
            int length = 1 + random.nextInt(3);
            int[] conditions = new int[length];
            for (int i = 0; i < length; i++) {
                int id = ids[random.nextInt(ids.length)];
                conditions[i] = random.nextBoolean() ? id : -id;
            }
            specs.add(new StateSpec(conditions));
        }
        specs.add(new StateSpec());
        int[][] states = new int[64][];
        for (int m = 0; m < states.length; m++) {
            int length = random.nextInt(6);
            int[] state = new int[length + 1];
            for (int i = 0; i < length; i++) {
                state[i] = random.nextInt(4) == 0 ? 0 : ids[random.nextInt(ids.length)];
            }
            state[length] = 0;
            states[m] = state;
        }
        return new Fixture("custom6", new StateColorList(specs, new int[specs.size()]), states, 6);
    }

    private static Fixture wide20() {
        int[] ids = new int[20];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = 0x7f000000 + i * 101;
        }
        Random random = new Random(918273L);
        List<StateSpec> specs = new ArrayList<StateSpec>();
        for (int r = 0; r < 8; r++) {
            int length = 1 + random.nextInt(4);
            int[] conditions = new int[length];
            for (int i = 0; i < length; i++) {
                int id = ids[random.nextInt(ids.length)];
                conditions[i] = random.nextBoolean() ? id : -id;
            }
            specs.add(new StateSpec(conditions));
        }
        specs.add(new StateSpec());
        int[][] states = new int[64][];
        for (int m = 0; m < states.length; m++) {
            int length = random.nextInt(8);
            int[] state = new int[length + 1];
            for (int i = 0; i < length; i++) {
                state[i] = random.nextInt(4) == 0 ? 0 : ids[random.nextInt(ids.length)];
            }
            state[length] = 0;
            states[m] = state;
        }
        return new Fixture("wide20", new StateColorList(specs, new int[specs.size()]), states, 20);
    }

    private static Fixture budgetFallback() {
        int[] ids = new int[16];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = 0x7f000000 + i * 101;
        }
        // Contradictory rules never match, so an unguarded fill would scan every rule for
        // every mask. 65 rules * 2^16 masks exceed the operation budget, so this delegates
        // to direct matching (64 rules would still fit).
        List<StateSpec> specs = new ArrayList<StateSpec>();
        for (int i = 0; i < 64; i++) {
            specs.add(new StateSpec(ids[i % ids.length], -ids[i % ids.length]));
        }
        specs.add(new StateSpec());
        int[][] states = new int[64][];
        Random random = new Random(5150L);
        for (int m = 0; m < states.length; m++) {
            int length = random.nextInt(8);
            int[] state = new int[length + 1];
            for (int i = 0; i < length; i++) {
                state[i] = random.nextInt(4) == 0 ? 0 : ids[random.nextInt(ids.length)];
            }
            state[length] = 0;
            states[m] = state;
        }
        return new Fixture("budgetFallback", new StateColorList(specs, new int[specs.size()]), states, 16);
    }

    private static Fixture short1() {
        int checked = 0x0101000a;
        List<StateSpec> specs = new ArrayList<StateSpec>();
        specs.add(new StateSpec(checked));
        int[][] states = new int[8][];
        int[][] base = {{checked, 0}, {0}, {checked, 0}, {0}, {checked, 0}, {0}, {checked, 0}, {0}};
        for (int i = 0; i < states.length; i++) {
            states[i] = base[i];
        }
        return new Fixture("short1", new StateColorList(specs, new int[specs.size()]), states, 1);
    }
}
