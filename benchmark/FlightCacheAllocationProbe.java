package io.github.jichuo1.meguviz.drawable.benchmark;

import io.github.jichuo1.meguviz.drawable.cache.FlightCache;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.Executor;
import com.sun.management.ThreadMXBean;

/**
 * Exploratory host allocation probe, not an Android/device performance test. Measures the bytes
 * allocated per {@link FlightCache#request} call on the hit, join and rejection paths with
 * {@link ThreadMXBean#getThreadAllocatedBytes}, plus {@link FlightCache#peek} as a zero-allocation
 * reference. The fixture is fixed: eight pre-built keys, a producer and a subscriber created once,
 * one queued flight for the join path and a full flight budget for the rejection path; the measured
 * loops allocate nothing themselves. Each round performs one million calls after four warmup
 * rounds; seven measured rounds are reported per fork.
 *
 * <p>Allocation counts are deterministic integers, so rounds within a fork agree exactly; forks
 * only confirm that. Run once with the default JVM and once with {@code -XX:-DoEscapeAnalysis} and
 * record both: escape analysis can scalar-replace objects that do not escape a method, which makes
 * the default view an optimization-aware number and the second view the raw allocation number.
 * Neither view is an Android/ART measurement; see docs/engineering/PERFORMANCE_GUIDE.md.
 *
 * <p>Usage: {@code java -cp <classes> io.github.jichuo1.meguviz.drawable.benchmark.FlightCacheAllocationProbe <fork>}
 */
public final class FlightCacheAllocationProbe {
    private static final int WARMUP = 4;
    private static final int ROUNDS = 7;
    private static final int OPS = 1_000_000;

    /** Queues tasks and never runs them, so a flight stays queued with its subscribers attached. */
    private static final class HoldingExecutor implements Executor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
        @Override public synchronized void execute(Runnable task) { queue.add(task); }
    }

    public static void main(String[] args) throws Exception {
        final int fork = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        final ThreadMXBean mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long tid = Thread.currentThread().getId();

        System.out.println("meta,fork," + fork + ",java," + System.getProperty("java.version")
                + ",vm," + System.getProperty("java.vm.name") + ",arch," + System.getProperty("os.arch")
                + ",cores," + Runtime.getRuntime().availableProcessors()
                + ",escape_analysis," + (!isRawView()));
        System.out.println("columns,fork,round,scenario,operations,allocated_bytes,bytes_per_call,checksum");

        // Hit path: eight cached keys.
        final CountingProducer counting = new CountingProducer();
        final FlightCache.Producer<String, String> producer = counting;
        final QuietSubscriber subscriber = new QuietSubscriber();
        final FlightCache<String, String> hitCache = FlightCache.<String, String>builder(
                Runnable::run, task -> true).maxEntries(64).build();
        final String[] keys = new String[8];
        for (int i = 0; i < 8; i++) {
            keys[i] = "k" + i;
            hitCache.computeNow(keys[i], (key, cancellation) -> "v-" + key);
        }
        counting.reset();
        measure(mx, tid, fork, "hit", i -> hitCache.request(keys[i & 7], producer, subscriber));
        check(counting.produced == 0, "hit fixture must not produce again");

        // Join path: one queued flight that never runs; every call joins it.
        final HoldingExecutor holding = new HoldingExecutor();
        final FlightCache<String, String> joinCache = FlightCache.<String, String>builder(
                holding, task -> true).maxEntries(64).build();
        joinCache.request("j", producer, subscriber);
        counting.reset();
        measure(mx, tid, fork, "join", i -> joinCache.request("j", producer, subscriber));
        check(counting.produced == 0 && joinCache.stats().joins == OPS * (WARMUP + ROUNDS),
                "join fixture must join one flight without producing");

        // Rejection path: the single flight budget is taken by a queued flight with subscribers.
        final HoldingExecutor tight = new HoldingExecutor();
        final FlightCache<String, String> rejectCache = FlightCache.<String, String>builder(
                tight, task -> true).maxFlights(1).maxEntries(64).build();
        rejectCache.request("taken", producer, subscriber);
        counting.reset();
        measure(mx, tid, fork, "reject", i -> rejectCache.request("other", producer, subscriber));
        check(counting.produced == 0 && rejectCache.stats().rejectedCapacity == OPS * (WARMUP + ROUNDS),
                "reject fixture must reject every call");

        // Reference: peek allocates nothing in any version.
        counting.reset();
        measure(mx, tid, fork, "peek_reference", i -> hitCache.peek(keys[i & 7]));
    }

    private interface Call { void once(int i); }

    private static void measure(ThreadMXBean mx, long tid, int fork, String scenario, Call call) {
        double[] measured = new double[ROUNDS];
        long checksum = 0;
        for (int round = -WARMUP; round < ROUNDS; round++) {
            long before = mx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < OPS; i++) call.once(i);
            long after = mx.getThreadAllocatedBytes(tid);
            double bytesPerCall = (double) (after - before) / OPS;
            checksum += after - before;
            if (round < 0) {
                System.out.println("warmup," + fork + "," + (-round) + "," + scenario + "," + OPS + ","
                        + (after - before) + "," + fmt(bytesPerCall) + "," + checksum);
            } else {
                measured[round] = bytesPerCall;
                System.out.println("sample," + fork + "," + (round + 1) + "," + scenario + "," + OPS + ","
                        + (after - before) + "," + fmt(bytesPerCall) + "," + checksum);
            }
        }
        System.out.println("median," + fork + "," + scenario + ",bytes_per_call," + fmt(median(measured)));
    }

    private static boolean isRawView() {
        String value = System.getProperty("probe.rawAllocation");
        return "true".equals(value);
    }

    private static double median(double[] values) {
        double[] copy = values.clone();
        java.util.Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Counts producer invocations so a fixture drift fails loudly instead of measuring nothing. */
    private static final class CountingProducer implements FlightCache.Producer<String, String> {
        int produced;
        void reset() { produced = 0; }
        @Override public String produce(String key, FlightCache.Cancellation cancellation) {
            produced++;
            return "v-" + key;
        }
    }

    /** A subscriber that is always current and records nothing; only its identity is needed. */
    private static final class QuietSubscriber implements FlightCache.Subscriber<String> {
        @Override public boolean isCurrent() { return true; }
        @Override public void onReady(String value) { }
        @Override public void onFailed(Throwable failure) { }
    }
}
