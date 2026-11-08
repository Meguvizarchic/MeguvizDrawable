import io.github.jichuo1.meguviz.drawable.cache.FlightCache;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-JVM timing of the FlightCache read paths a draw uses: peek hit and request hit. With
 * -Dprobe.contend=true a second thread keeps publishing other keys through computeNow (taking the
 * same lock), the owner-vs-worker shape of a tile cache. Prints CSV: metric,round,ns_per_op.
 * Run old and new class directories in interleaved JVMs; compare distributions, not one round.
 */
public final class FlightCacheHitLatencyProbe {
    private static final int ROUNDS = 7, WARMUP = 4, N = 2_000_000;
    static volatile Object sink;

    public static void main(String[] args) throws Exception {
        Executor inline = new Executor() { @Override public void execute(Runnable r) { r.run(); } };
        FlightCache.OwnerPoster poster = new FlightCache.OwnerPoster() {
            @Override public boolean post(Runnable task) { task.run(); return true; }
        };
        final FlightCache<Integer, String> cache = FlightCache.<Integer, String>builder(inline, poster)
                .maxEntries(64).build();
        final FlightCache.Producer<Integer, String> producer = new FlightCache.Producer<Integer, String>() {
            @Override public String produce(Integer key, FlightCache.Cancellation c) { return "v" + key; }
        };
        FlightCache.Subscriber<String> subscriber = new FlightCache.Subscriber<String>() {
            @Override public boolean isCurrent() { return true; }
            @Override public void onReady(String value) { }
            @Override public void onFailed(Throwable failure) { }
        };
        final Integer[] keys = new Integer[8];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = Integer.valueOf(1000 + i);
            cache.computeNow(keys[i], producer);
        }
        final AtomicBoolean stop = new AtomicBoolean();
        Thread contender = null;
        if (Boolean.getBoolean("probe.contend")) {
            contender = new Thread(new Runnable() {
                @Override public void run() {
                    int k = 0;
                    try {
                        while (!stop.get()) {
                            // Distinct keys outside the hit set: each call misses, produces and publishes,
                            // evicting old contender keys (maxEntries 64 keeps the 8 hit keys recent enough
                            // only if they are touched; the measuring loop touches them continuously).
                            cache.computeNow(Integer.valueOf(1_000_000 + (k++ & 1023)), producer);
                        }
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
            });
            contender.setDaemon(true);
            contender.start();
        }
        for (int round = -WARMUP; round < ROUNDS; round++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) sink = cache.peek(keys[i & 7]);
            long t1 = System.nanoTime();
            for (int i = 0; i < N; i++) sink = cache.request(keys[i & 7], producer, subscriber);
            long t2 = System.nanoTime();
            if (round < 0) continue;
            System.out.println("peek_hit_ns," + round + "," + (t1 - t0) / (double) N);
            System.out.println("request_hit_ns," + round + "," + (t2 - t1) / (double) N);
        }
        stop.set(true);
        if (contender != null) contender.join();
        FlightCache.Stats stats = cache.stats();
        // Contended runs may evict a hit key now and then; report it instead of hiding it.
        System.out.println("stats_hits_per_measured_op,0," + stats.hits / (double) (2L * N * (ROUNDS + WARMUP)));
        cache.close();
    }
}
