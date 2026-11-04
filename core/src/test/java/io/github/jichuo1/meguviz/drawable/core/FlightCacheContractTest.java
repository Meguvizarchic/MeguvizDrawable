package io.github.jichuo1.meguviz.drawable.core;

import io.github.jichuo1.meguviz.drawable.cache.FlightCache;
import io.github.jichuo1.meguviz.drawable.cache.FlightCache.EvictionReason;
import io.github.jichuo1.meguviz.drawable.cache.FlightCache.Status;
import io.github.jichuo1.meguviz.drawable.cache.FlightCache.Ticket;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Single-flight, cancellation, generation, bounds and shutdown contracts of {@link FlightCache}. */
final class FlightCacheContractTest {
    private static int checks;

    static int run() throws Exception {
        missThenHit();
        concurrentRequestsShareOneFlight();
        cancellation();
        staleGenerationIsDroppedButCached();
        failuresAreSharedAndNotCached();
        flightLimitAndAbandonedWork();
        rejectionPathLeavesNothingToCancel();
        droppedFlightWithoutAssignedTaskStillMakesRoom();
        evictionAndWeight();
        computeNowContracts();
        closeContracts();
        executorRefusal();
        posterRefusal();
        abandonRunningProducers();
        queuedTasksAreRemovedFromThreadPools();
        stampede();
        randomizedBounds();
        return checks;
    }

    /** Runs tasks only when the test says so. */
    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
        boolean refuse;
        @Override public synchronized void execute(Runnable task) {
            if (refuse) throw new RejectedExecutionException("refused");
            queue.add(task);
        }
        synchronized int pending() { return queue.size(); }
        void runAll() {
            while (true) {
                Runnable next;
                synchronized (this) { next = queue.poll(); }
                if (next == null) return;
                next.run();
            }
        }
    }

    /** Stands in for the owner (main) thread: deliveries run when the test drains. */
    private static final class ManualPoster implements FlightCache.OwnerPoster {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
        volatile boolean accepting = true;
        @Override public synchronized boolean post(Runnable task) {
            if (!accepting) return false;
            queue.add(task);
            return true;
        }
        synchronized int pending() { return queue.size(); }
        void drain() {
            while (true) {
                Runnable next;
                synchronized (this) { next = queue.poll(); }
                if (next == null) return;
                next.run();
            }
        }
    }

    private static final class Probe implements FlightCache.Subscriber<String> {
        final String name;
        volatile boolean current = true;
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        Probe(String name) { this.name = name; }
        @Override public boolean isCurrent() { return current; }
        @Override public void onReady(String value) { events.add("ready:" + value); }
        @Override public void onFailed(Throwable failure) { events.add("failed:" + failure.getMessage()); }
    }

    private static final class Env {
        final ManualExecutor workers = new ManualExecutor();
        final ManualPoster owner = new ManualPoster();
        final AtomicInteger produced = new AtomicInteger();
        final List<String> evictions = Collections.synchronizedList(new ArrayList<String>());

        FlightCache.Builder<String, String> builder() {
            return FlightCache.<String, String>builder(workers, owner).evictionListener(
                    new FlightCache.EvictionListener<String, String>() {
                        @Override public void onEvicted(String key, String value, EvictionReason reason) {
                            evictions.add(key + ":" + reason);
                        }
                    });
        }

        FlightCache.Producer<String, String> producer() {
            return new FlightCache.Producer<String, String>() {
                @Override public String produce(String key, FlightCache.Cancellation cancellation) {
                    produced.incrementAndGet();
                    return "v-" + key;
                }
            };
        }
    }

    private static void missThenHit() {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().build();
        Probe first = new Probe("first");
        Ticket<String> ticket = cache.request("a", env.producer(), first);
        check(ticket.status() == Status.STARTED && ticket.value() == null, "a miss starts a flight");
        check(cache.peek("a") == null && env.produced.get() == 0, "peek never computes");
        env.workers.runAll();
        check(env.produced.get() == 1 && first.events.isEmpty(), "the producer ran off the owner thread, delivery waits for the owner");
        env.owner.drain();
        check(first.events.equals(listOf("ready:v-a")), "the owner receives the value");
        check("v-a".equals(cache.peek("a")), "the value is cached");
        Ticket<String> hit = cache.request("a", env.producer(), new Probe("late"));
        check(hit.status() == Status.READY && "v-a".equals(hit.value()) && !hit.cancel(), "a hit is answered synchronously, with nothing to cancel");
        env.workers.runAll();
        env.owner.drain();
        check(env.produced.get() == 1, "a hit does not produce again");
        FlightCache.Stats stats = cache.stats();
        check(stats.misses == 1 && stats.hits >= 2 && stats.produced == 1 && stats.delivered == 1 && stats.version == 1
                && stats.entries == 1 && stats.flights == 0, "counters describe what happened: " + stats);
    }

    private static void concurrentRequestsShareOneFlight() {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().build();
        Probe one = new Probe("one"), two = new Probe("two");
        check(cache.request("k", env.producer(), one).status() == Status.STARTED, "first request starts");
        check(cache.request("k", env.producer(), two).status() == Status.JOINED, "second request joins");
        check(env.workers.pending() == 1, "one task for the key");
        env.workers.runAll();
        env.owner.drain();
        check(env.produced.get() == 1 && one.events.equals(listOf("ready:v-k")) && two.events.equals(listOf("ready:v-k")),
                "one production, both subscribers served");
        check(cache.stats().joins == 1, "the join was counted");
    }

    private static void cancellation() {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().build();
        Probe only = new Probe("only");
        Ticket<String> ticket = cache.request("k", env.producer(), only);
        check(ticket.cancel() && !ticket.cancel(), "cancel succeeds once");
        env.workers.runAll();
        env.owner.drain();
        check(env.produced.get() == 0 && only.events.isEmpty() && cache.stats().abandonedDropped == 1 && cache.stats().flights == 0,
                "a flight nobody waits for is dropped without running");

        Ticket<String> abandoned = cache.request("k", env.producer(), new Probe("gone"));
        abandoned.cancel();
        Probe reviver = new Probe("reviver");
        check(cache.request("k", env.producer(), reviver).status() == Status.JOINED && env.workers.pending() == 1,
                "a new request revives the abandoned queued flight instead of queuing a second one");
        env.workers.runAll();
        env.owner.drain();
        check(env.produced.get() == 1 && reviver.events.equals(listOf("ready:v-k")), "the revived flight ran once for the new subscriber");

        Probe stays = new Probe("stays"), leaves = new Probe("leaves");
        cache.request("m", env.producer(), stays);
        Ticket<String> leaving = cache.request("m", env.producer(), leaves);
        leaving.cancel();
        env.workers.runAll();
        env.owner.drain();
        check(stays.events.equals(listOf("ready:v-m")) && leaves.events.isEmpty(), "cancelling one subscriber leaves the other");

        Probe late = new Probe("late");
        Ticket<String> posted = cache.request("n", env.producer(), late);
        env.workers.runAll();
        check(env.owner.pending() == 1, "the delivery is already posted");
        check(posted.cancel(), "cancel after the post but before the delivery still wins");
        env.owner.drain();
        check(late.events.isEmpty() && "v-n".equals(cache.peek("n")), "nothing is delivered after cancel, the value is still cached");
    }

    private static void staleGenerationIsDroppedButCached() {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().build();
        Probe stale = new Probe("stale"), fresh = new Probe("fresh");
        cache.request("k", env.producer(), stale);
        cache.request("k", env.producer(), fresh);
        stale.current = false;
        env.workers.runAll();
        env.owner.drain();
        check(stale.events.isEmpty() && fresh.events.equals(listOf("ready:v-k")), "a subscriber that is no longer current is skipped");
        check("v-k".equals(cache.peek("k")) && cache.stats().droppedStale == 1, "its work is still cached and the drop counted");
    }

    private static void failuresAreSharedAndNotCached() {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().build();
        final AtomicInteger attempts = new AtomicInteger();
        FlightCache.Producer<String, String> flaky = new FlightCache.Producer<String, String>() {
            @Override public String produce(String key, FlightCache.Cancellation c) throws Exception {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("render failed");
                return "ok";
            }
        };
        Probe one = new Probe("one"), two = new Probe("two");
        cache.request("k", flaky, one);
        cache.request("k", flaky, two);
        env.workers.runAll();
        env.owner.drain();
        check(one.events.equals(listOf("failed:render failed")) && two.events.equals(listOf("failed:render failed")), "every waiter gets the failure");
        check(cache.peek("k") == null && cache.stats().flights == 0 && cache.stats().failures == 1, "a failure is not cached and leaves no flight");
        Probe retry = new Probe("retry");
        check(cache.request("k", flaky, retry).status() == Status.STARTED, "the next request tries again");
        env.workers.runAll();
        env.owner.drain();
        check(retry.events.equals(listOf("ready:ok")), "and succeeds");

        Probe nullResult = new Probe("null");
        cache.request("n", new FlightCache.Producer<String, String>() {
            @Override public String produce(String key, FlightCache.Cancellation c) { return null; }
        }, nullResult);
        env.workers.runAll();
        env.owner.drain();
        check(nullResult.events.size() == 1 && nullResult.events.get(0).startsWith("failed:") && cache.peek("n") == null,
                "a null result is a failure, not a cached null");
    }

    private static void flightLimitAndAbandonedWork() {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().maxFlights(2).build();
        Ticket<String> a = cache.request("a", env.producer(), new Probe("a"));
        cache.request("b", env.producer(), new Probe("b"));
        check(cache.request("c", env.producer(), new Probe("c")).status() == Status.REJECTED_CAPACITY, "past the flight limit nothing is queued");
        check(env.workers.pending() == 2 && cache.stats().rejectedCapacity == 1, "the limit holds the queue at two");
        a.cancel();
        Probe c = new Probe("c");
        check(cache.request("c", env.producer(), c).status() == Status.STARTED, "an abandoned queued flight gives way to a new request");
        env.workers.runAll();
        env.owner.drain();
        check(env.produced.get() == 2 && c.events.equals(listOf("ready:v-c")) && cache.peek("a") == null, "the abandoned key was never produced");
        check(cache.stats().abandonedDropped >= 1 && cache.stats().flights == 0, "and the bookkeeping is clean");
    }

    /**
     * The rejection path registers no subscriber: the ticket has nothing to cancel and the refused
     * subscriber is never called. Pins the observable side of the lazily created subscriber record.
     */
    private static void rejectionPathLeavesNothingToCancel() {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().maxFlights(1).build();
        cache.request("taken", env.producer(), new Probe("taken"));
        Probe refused = new Probe("refused");
        Ticket<String> rejected = cache.request("other", env.producer(), refused);
        check(rejected.status() == Status.REJECTED_CAPACITY && rejected.value() == null,
                "past the flight limit the request is rejected");
        check(!rejected.cancel(), "a rejection registered no subscriber, so there is nothing to cancel");
        check(refused.events.isEmpty() && cache.stats().cancelledSubscribers == 0
                && cache.stats().rejectedCapacity == 1 && cache.stats().flights == 1,
                "the refused subscriber is never called and no cancellation is counted: " + cache.stats());
        env.workers.runAll();
        env.owner.drain();
        check(refused.events.isEmpty() && env.produced.get() == 1 && cache.peek("taken") != null,
                "the occupying flight is untouched and still produces once");
    }

    /**
     * A queued flight whose task is not yet assigned exists only in the nanoseconds between the map
     * insertion and the task assignment, so the window is reached directly. Dropping such a flight
     * must still make room for a new request (it did before the lazy unqueue refactor) instead of
     * rejecting it; the unassigned task is simply not handed to any pool for removal.
     */
    private static void droppedFlightWithoutAssignedTaskStillMakesRoom() throws Exception {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().maxFlights(1).build();
        Ticket<String> taken = cache.request("taken", env.producer(), new Probe("taken"));
        check(taken.cancel(), "cancelling leaves the flight queued and subscriber-less");
        Field flights = FlightCache.class.getDeclaredField("flights");
        flights.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ?> map = (Map<String, ?>) flights.get(cache);
        Object flight = map.get("taken");
        Field task = flight.getClass().getDeclaredField("task");
        task.setAccessible(true);
        task.set(flight, null);
        Probe next = new Probe("next");
        check(cache.request("next", env.producer(), next).status() == Status.STARTED,
                "a dropped flight whose task was never assigned still makes room");
        check(cache.stats().abandonedDropped == 1 && cache.stats().rejectedCapacity == 0
                && cache.stats().flights == 1, "the drop is counted and nothing is rejected: " + cache.stats());
        env.workers.runAll();
        env.owner.drain();
        check(next.events.equals(listOf("ready:v-next")) && env.produced.get() == 1,
                "the new flight runs for its subscriber and the superseded task does nothing");
    }

    private static void evictionAndWeight() {
        Env env = new Env();
        FlightCache<String, String> byEntries = env.builder().maxEntries(2).build();
        for (String key : new String[] {"a", "b", "c"}) {
            byEntries.request(key, env.producer(), new Probe(key));
            env.workers.runAll();
            env.owner.drain();
        }
        check(byEntries.peek("a") == null && byEntries.peek("b") != null && byEntries.peek("c") != null, "least recently used goes first");
        check(env.evictions.equals(listOf("a:CAPACITY")), "the listener hears about it: " + env.evictions);

        Env weighted = new Env();
        FlightCache<String, String> byWeight = weighted.builder().maxWeight(10).weigher(new FlightCache.Weigher<String>() {
            @Override public long weigh(String value) { return value.length(); }
        }).build();
        for (String key : new String[] {"aaa", "bbb", "ccc"}) {   // each value "v-xxx" weighs 5
            byWeight.request(key, weighted.producer(), new Probe(key));
            weighted.workers.runAll();
            weighted.owner.drain();
        }
        check(byWeight.stats().weight <= 10 && byWeight.stats().entries == 2 && byWeight.peek("aaa") == null, "total weight stays under the cap");
        byWeight.request("this-key-is-long", weighted.producer(), new Probe("big"));
        weighted.workers.runAll();
        weighted.owner.drain();
        check(byWeight.stats().weight <= 10, "a value heavier than the cap does not break the bound");

        Env bad = new Env();
        FlightCache<String, String> negative = bad.builder().weigher(new FlightCache.Weigher<String>() {
            @Override public long weigh(String value) { return -1; }
        }).build();
        Probe failing = new Probe("neg");
        negative.request("k", bad.producer(), failing);
        bad.workers.runAll();
        bad.owner.drain();
        check(failing.events.size() == 1 && failing.events.get(0).startsWith("failed:") && negative.peek("k") == null,
                "a broken weigher fails the flight instead of corrupting the accounting");
        expect(IllegalStateException.class, new Runnable() { public void run() {
            FlightCache.<String, String>builder(new ManualExecutor(), new ManualPoster()).maxWeight(5).build(); }});
    }

    private static void computeNowContracts() throws Exception {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().build();
        env.workers.refuse = true;   // proves the synchronous path never touches the executor
        check("v-x".equals(cache.computeNow("x", env.producer())) && env.produced.get() == 1, "computeNow produces on the calling thread");
        check("v-x".equals(cache.peek("x")) && "v-x".equals(cache.computeNow("x", env.producer())) && env.produced.get() == 1,
                "and caches, so the next call is a hit");
        env.workers.refuse = false;

        Probe waiting = new Probe("waiting");
        cache.request("y", env.producer(), waiting);
        check(env.workers.pending() == 1, "a flight for y is queued");
        check("v-y".equals(cache.computeNow("y", env.producer())), "computeNow does not wait for the queued flight");
        env.owner.drain();
        check(waiting.events.equals(listOf("ready:v-y")), "the queued flight's subscriber is completed with that value");
        env.workers.runAll();
        env.owner.drain();
        check(env.produced.get() == 2 && waiting.events.size() == 1, "the superseded task does nothing");

        expect(IllegalArgumentException.class, new Runnable() { public void run() {
            try {
                FlightCache<String, String> other = new Env().builder().build();
                other.computeNow("z", new FlightCache.Producer<String, String>() {
                    @Override public String produce(String key, FlightCache.Cancellation c) throws Exception { throw new Exception("export failed"); }
                });
            } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new IllegalArgumentException(e); }
        }});
    }

    private static void closeContracts() {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().build();
        cache.request("done", env.producer(), new Probe("done"));
        env.workers.runAll();
        env.owner.drain();
        Probe pending = new Probe("pending");
        cache.request("queued", env.producer(), pending);
        cache.close();
        cache.close();
        check(cache.isClosed() && cache.peek("done") == null && env.evictions.contains("done:CLOSED"), "close empties the cache and tells the listener");
        env.workers.runAll();
        env.owner.drain();
        check(pending.events.isEmpty() && env.produced.get() == 1, "queued work never runs and nobody is called after close");
        check(cache.request("again", env.producer(), new Probe("x")).status() == Status.REJECTED_CLOSED, "requests are refused");
        expect(IllegalStateException.class, new Runnable() { public void run() {
            try { cache.computeNow("z", env.producer()); } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new AssertionError(e); } }});
    }

    private static void executorRefusal() {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().build();
        env.workers.refuse = true;
        Probe refused = new Probe("refused");
        check(cache.request("k", env.producer(), refused).status() == Status.REJECTED_CAPACITY, "a refusing executor is a rejection");
        check(cache.stats().flights == 0 && refused.events.isEmpty(), "no flight is left behind and the refused caller is not called");
        env.workers.refuse = false;
        check(cache.request("k", env.producer(), new Probe("again")).status() == Status.STARTED, "the key is usable afterwards");
    }

    private static void posterRefusal() {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().build();
        Probe orphan = new Probe("orphan");
        cache.request("k", env.producer(), orphan);
        env.owner.accepting = false;
        env.workers.runAll();
        check(orphan.events.isEmpty() && cache.stats().droppedStale == 1 && "v-k".equals(cache.peek("k")),
                "if the owner is gone the delivery is dropped, the value stays cached");
    }

    private static void abandonRunningProducers() throws Exception {
        for (final boolean abandon : new boolean[] {false, true}) {
            final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                ManualPoster owner = new ManualPoster();
                FlightCache<String, String> cache = FlightCache.<String, String>builder(pool, owner).abandonRunning(abandon).build();
                final CountDownLatch started = new CountDownLatch(1), proceed = new CountDownLatch(1);
                final boolean[] sawCancel = new boolean[1];
                Ticket<String> ticket = cache.request("k", new FlightCache.Producer<String, String>() {
                    @Override public String produce(String key, FlightCache.Cancellation cancellation) throws Exception {
                        started.countDown();
                        proceed.await(5, TimeUnit.SECONDS);
                        sawCancel[0] = cancellation.isCancelled();
                        return "late";
                    }
                }, new Probe("k"));
                check(started.await(5, TimeUnit.SECONDS), "the producer is running");
                ticket.cancel();
                proceed.countDown();
                pool.shutdown();
                check(pool.awaitTermination(5, TimeUnit.SECONDS), "the producer finished");
                check(sawCancel[0] == abandon, "abandonRunning=" + abandon + " controls what a running producer sees");
                check("late".equals(cache.peek("k")), "a started flight is cached either way");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static void queuedTasksAreRemovedFromThreadPools() throws Exception {
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        try {
            final CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
            pool.execute(new Runnable() { public void run() {
                blocked.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }});
            check(blocked.await(5, TimeUnit.SECONDS), "the only worker is busy");
            ManualPoster owner = new ManualPoster();
            Env env = new Env();
            FlightCache<String, String> cache = FlightCache.<String, String>builder(pool, owner).maxFlights(2).build();
            Ticket<String> a = cache.request("a", env.producer(), new Probe("a"));
            cache.request("b", env.producer(), new Probe("b"));
            check(pool.getQueue().size() == 2, "both flights wait in the pool queue");
            a.cancel();
            cache.request("c", env.producer(), new Probe("c"));
            check(pool.getQueue().size() == 2, "the abandoned task left the pool queue when its slot was reused");
            cache.close();
            check(pool.getQueue().isEmpty(), "closing empties the pool queue of its tasks");
            release.countDown();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void stampede() throws Exception {
        for (int round = 0; round < 20; round++) {
            final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                final ManualPoster owner = new ManualPoster();
                final AtomicInteger produced = new AtomicInteger();
                final FlightCache<String, String> cache = FlightCache.<String, String>builder(pool, owner).maxFlights(8).build();
                final int callers = 8;
                final CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(callers);
                final List<Probe> probes = new ArrayList<Probe>();
                for (int i = 0; i < callers; i++) probes.add(new Probe("p" + i));
                for (int i = 0; i < callers; i++) {
                    final Probe probe = probes.get(i);
                    new Thread(new Runnable() { public void run() {
                        try {
                            go.await();
                            cache.request("tile", new FlightCache.Producer<String, String>() {
                                @Override public String produce(String key, FlightCache.Cancellation c) throws Exception {
                                    produced.incrementAndGet();
                                    Thread.sleep(2);
                                    return "tile";
                                }
                            }, probe);
                        } catch (InterruptedException ignored) {
                        } finally { done.countDown(); }
                    }}).start();
                }
                go.countDown();
                check(done.await(10, TimeUnit.SECONDS), "callers finished");
                pool.shutdown();
                check(pool.awaitTermination(10, TimeUnit.SECONDS), "workers finished");
                owner.drain();
                int ready = 0, hitsOnly = 0;
                for (Probe probe : probes) {
                    if (probe.events.equals(listOf("ready:tile"))) ready++;
                    else if (probe.events.isEmpty()) hitsOnly++;
                }
                // A caller arriving after publication gets READY from the cache and no callback; all others one callback.
                check(produced.get() == 1 && ready + hitsOnly == callers && ready >= 1 && cache.stats().flightsStarted == 1,
                        "eight concurrent requests produce once (round " + round + ", " + cache.stats() + ")");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static void randomizedBounds() throws Exception {
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            final ManualPoster owner = new ManualPoster();
            final FlightCache<Integer, Integer> cache = FlightCache.<Integer, Integer>builder(pool, owner)
                    .maxEntries(8).maxWeight(40).maxFlights(6).weigher(new FlightCache.Weigher<Integer>() {
                        @Override public long weigh(Integer value) { return 1 + value % 5; }
                    }).build();
            final AtomicInteger violations = new AtomicInteger();
            final CountDownLatch done = new CountDownLatch(4);
            for (int t = 0; t < 4; t++) {
                final int seed = t;
                new Thread(new Runnable() { public void run() {
                    Random random = new Random(seed);
                    try {
                        for (int i = 0; i < 2000; i++) {
                            final int key = random.nextInt(30);
                            FlightCache.Subscriber<Integer> quiet = new FlightCache.Subscriber<Integer>() {
                                @Override public boolean isCurrent() { return true; }
                                @Override public void onReady(Integer value) { }
                                @Override public void onFailed(Throwable failure) { }
                            };
                            Ticket<Integer> ticket = cache.request(key, new FlightCache.Producer<Integer, Integer>() {
                                @Override public Integer produce(Integer k, FlightCache.Cancellation c) { return k; }
                            }, quiet);
                            if (random.nextInt(3) == 0) ticket.cancel();
                            FlightCache.Stats stats = cache.stats();
                            if (stats.entries > 8 || stats.weight > 40 || stats.flights > 6) violations.incrementAndGet();
                        }
                    } finally { done.countDown(); }
                }}).start();
            }
            check(done.await(30, TimeUnit.SECONDS), "random load finished");
            pool.shutdown();
            check(pool.awaitTermination(30, TimeUnit.SECONDS), "and the pool drained");
            owner.drain();
            FlightCache.Stats stats = cache.stats();
            check(violations.get() == 0, "no bound was ever exceeded");
            check(stats.flights == 0 && stats.entries <= 8 && stats.weight <= 40, "at rest nothing is in flight: " + stats);
            check(stats.produced + stats.failures + stats.abandonedDropped == stats.flightsStarted,
                    "every started flight ended in exactly one counted way: " + stats);
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<String> listOf(String... values) {
        List<String> list = new ArrayList<String>();
        Collections.addAll(list, values);
        return list;
    }

    private static void expect(Class<? extends Throwable> expected, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) { checks++; return; }
            throw new AssertionError("unexpected exception: " + error, error);
        }
        throw new AssertionError("expected " + expected.getName());
    }

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
}
