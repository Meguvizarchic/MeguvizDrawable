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
        staleGenerationIsDroppedButCached();
        failuresAreSharedAndNotCached();
        executorFailureIsNotARefusal();
        posterErrorIsCountedAndDoesNotLeak();
        evictionListenerErrorIsCountedNotThrown();
        oversizeValueIsDeliveredButNeverCached();
        runningFlightJoinsTheCachedValue();
        if (stress()) {
            cancellation();
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
            retentionFastPath();
        }
        return checks;
    }

    /**
     * A value heavier than the entire budget is delivered but never cached. The previous code put
     * it in and immediately evicted it, telling the listener it was reclaimed before onReady ran.
     */
    private static void oversizeValueIsDeliveredButNeverCached() {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().maxWeight(10)
                .weigher(new FlightCache.Weigher<String>() {
                    @Override public long weigh(String value) { return 11L; }
                }).build();
        Probe probe = new Probe("big");
        cache.request("big", env.producer(), probe);
        env.workers.runAll();
        env.owner.drain();
        check(cache.peek("big") == null && cache.stats().weight == 0
                        && cache.version() == 0 && cache.stats().produced == 0
                        && env.evictions.isEmpty()
                        && probe.events.equals(listOf("ready:v-big")),
                "an oversize value is delivered but never cached or announced as evicted");
    }

    /**
     * Deterministic RUNNING interleave: while a flight's producer runs (no cache lock held),
     * computeNow publishes the same key. The flight must then join that already cached value
     * instead of replacing it, emitting REPLACED, or bumping version a second time.
     */
    private static void runningFlightJoinsTheCachedValue() throws Exception {
        final Env env = new Env();
        final FlightCache<String, String> cache = env.builder().build();
        final String[] joined = new String[1];
        FlightCache.Producer<String, String> flightProducer = new FlightCache.Producer<String, String>() {
            @Override public String produce(String key, FlightCache.Cancellation cancellation) throws Exception {
                joined[0] = cache.computeNow(key, new FlightCache.Producer<String, String>() {
                    @Override public String produce(String k, FlightCache.Cancellation c) { return "v-first"; }
                });
                return "v-second";
            }
        };
        Probe waiting = new Probe("waiting");
        cache.request("k", flightProducer, waiting);
        env.workers.runAll();
        env.owner.drain();
        check("v-first".equals(cache.peek("k")) && "v-first".equals(joined[0])
                        && waiting.events.equals(listOf("ready:v-first"))
                        && cache.version() == 1 && cache.stats().produced == 1
                        && env.evictions.isEmpty(),
                "a superseded running flight joins the cached value without REPLACED or a version bump");
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
        check(ticket.status() == Status.STARTED && ticket.value() == null
                && cache.peek("a") == null && env.produced.get() == 0,
                "a miss starts a flight and peek never computes");
        env.workers.runAll();
        check(env.produced.get() == 1 && first.events.isEmpty(),
                "the producer ran off the owner thread and delivery waits for the owner");
        env.owner.drain();
        Ticket<String> hit = cache.request("a", env.producer(), new Probe("late"));
        check(first.events.equals(listOf("ready:v-a")) && "v-a".equals(cache.peek("a"))
                && hit.status() == Status.READY && "v-a".equals(hit.value()) && !hit.cancel(),
                "the owner receives the value and a hit is answered synchronously");
        env.workers.runAll();
        env.owner.drain();
        FlightCache.Stats stats = cache.stats();
        check(env.produced.get() == 1 && stats.misses == 1 && stats.hits >= 2 && stats.produced == 1
                && stats.delivered == 1 && stats.version == 1 && stats.entries == 1 && stats.flights == 0,
                "a hit does not produce again and the counters describe what happened: " + stats);
    }

    private static void concurrentRequestsShareOneFlight() {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().build();
        Probe one = new Probe("one"), two = new Probe("two");
        check(cache.request("k", env.producer(), one).status() == Status.STARTED
                && cache.request("k", env.producer(), two).status() == Status.JOINED
                && env.workers.pending() == 1, "first request starts, second joins, one task for the key");
        env.workers.runAll();
        env.owner.drain();
        check(env.produced.get() == 1 && one.events.equals(listOf("ready:v-k"))
                && two.events.equals(listOf("ready:v-k")) && cache.stats().joins == 1,
                "one production serves both subscribers and the join is counted");
    }

    private static void cancellation() {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().build();
        Probe only = new Probe("only");
        Ticket<String> ticket = cache.request("k", env.producer(), only);
        check(ticket.cancel() && !ticket.cancel(), "cancel succeeds once");
        env.workers.runAll();
        env.owner.drain();
        check(env.produced.get() == 0 && only.events.isEmpty() && cache.stats().abandonedDropped == 1
                && cache.stats().flights == 0,
                "a flight nobody waits for is dropped without running");
        Ticket<String> abandoned = cache.request("k", env.producer(), new Probe("gone"));
        abandoned.cancel();
        Probe reviver = new Probe("reviver");
        check(cache.request("k", env.producer(), reviver).status() == Status.JOINED
                && env.workers.pending() == 1,
                "a new request revives the abandoned queued flight instead of queueing a second one");
        env.workers.runAll();
        env.owner.drain();
        check(env.produced.get() == 1 && reviver.events.equals(listOf("ready:v-k")),
                "the revived flight ran once for the new subscriber");

        Probe stays = new Probe("stays"), leaves = new Probe("leaves");
        cache.request("m", env.producer(), stays);
        Ticket<String> leaving = cache.request("m", env.producer(), leaves);
        leaving.cancel();
        env.workers.runAll();
        env.owner.drain();
        check(stays.events.equals(listOf("ready:v-m")) && leaves.events.isEmpty(),
                "cancelling one subscriber leaves the other");

        Probe late = new Probe("late");
        Ticket<String> posted = cache.request("n", env.producer(), late);
        env.workers.runAll();
        check(env.owner.pending() == 1 && posted.cancel(),
                "cancel after the post but before the delivery still wins");
        env.owner.drain();
        check(late.events.isEmpty() && "v-n".equals(cache.peek("n")),
                "nothing is delivered after cancel and the value is still cached");
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
        check(stale.events.isEmpty() && fresh.events.equals(listOf("ready:v-k"))
                && "v-k".equals(cache.peek("k")) && cache.stats().droppedStale == 1,
                "a stale subscriber is skipped while its work is still cached and counted");
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
        check(one.events.equals(listOf("failed:render failed")) && two.events.equals(listOf("failed:render failed"))
                && cache.peek("k") == null && cache.stats().flights == 0 && cache.stats().failures == 1,
                "every waiter gets the failure and it is not cached");
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
        check(cache.request("c", env.producer(), new Probe("c")).status() == Status.REJECTED_CAPACITY
                && env.workers.pending() == 2 && cache.stats().rejectedCapacity == 1,
                "past the flight limit nothing is queued");
        a.cancel();
        Probe c = new Probe("c");
        check(cache.request("c", env.producer(), c).status() == Status.STARTED,
                "an abandoned queued flight gives way to a new request");
        env.workers.runAll();
        env.owner.drain();
        check(env.produced.get() == 2 && c.events.equals(listOf("ready:v-c")) && cache.peek("a") == null
                && cache.stats().abandonedDropped >= 1 && cache.stats().flights == 0,
                "the abandoned key was never produced and the bookkeeping is clean");
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
        check(rejected.status() == Status.REJECTED_CAPACITY && rejected.value() == null && !rejected.cancel()
                && refused.events.isEmpty() && cache.stats().cancelledSubscribers == 0
                && cache.stats().rejectedCapacity == 1 && cache.stats().flights == 1,
                "a rejection registers no subscriber and counts no cancellation: " + cache.stats());
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
        check(cache.request("next", env.producer(), next).status() == Status.STARTED
                && cache.stats().abandonedDropped == 1 && cache.stats().rejectedCapacity == 0
                && cache.stats().flights == 1,
                "a dropped flight whose task was never assigned still makes room: " + cache.stats());
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
        check(byEntries.peek("a") == null && byEntries.peek("b") != null && byEntries.peek("c") != null
                && env.evictions.equals(listOf("a:CAPACITY")),
                "least recently used goes first and the listener hears about it: " + env.evictions);

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
        check("v-x".equals(cache.computeNow("x", env.producer())) && env.produced.get() == 1
                && "v-x".equals(cache.peek("x")) && "v-x".equals(cache.computeNow("x", env.producer())),
                "computeNow produces on the calling thread and caches the result");
        env.workers.refuse = false;

        Probe waiting = new Probe("waiting");
        cache.request("y", env.producer(), waiting);
        check(env.workers.pending() == 1 && "v-y".equals(cache.computeNow("y", env.producer())),
                "computeNow does not wait for the queued flight");
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
        Ticket<String> afterClose = cache.request("again", env.producer(), new Probe("x"));
        check(pending.events.isEmpty() && env.produced.get() == 1
                && afterClose.status() == Status.REJECTED_CLOSED,
                "queued work never runs after close and requests are refused");
        expect(IllegalStateException.class, new Runnable() { public void run() {
            try { cache.computeNow("z", env.producer()); } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new AssertionError(e); } }});
    }

    private static void executorRefusal() {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().build();
        env.workers.refuse = true;
        Probe refused = new Probe("refused");
        check(cache.request("k", env.producer(), refused).status() == Status.REJECTED_CAPACITY
                && cache.stats().flights == 0 && refused.events.isEmpty(),
                "a refusing executor is a rejection that leaves no flight behind");
        env.workers.refuse = false;
        check(cache.request("k", env.producer(), new Probe("again")).status() == Status.STARTED, "the key is usable afterwards");
    }

    /**
     * A custom executor may fail for a reason that is not a capacity refusal (an
     * {@link IllegalStateException}, a {@link NullPointerException}, ...). The flight must be rolled
     * back so the key is usable again, the original exception must propagate because the host
     * cannot treat it as a rejection, and a subscriber that joined in the meantime must be told.
     */
    private static void executorFailureIsNotARefusal() {
        final Env env = new Env();
        final FlightCache.Producer<String, String> producer = env.producer();
        final Probe joined = new Probe("joined");
        final FlightCache<String, String>[] holder = new FlightCache[1];
        final Ticket<String>[] joinedTicket = new Ticket[1];
        // The executor joins one subscriber into the in-flight flight and only then fails with a
        // non-refusal exception, so the rollback has an "others" list to notify.
        Executor broken = new Executor() {
            @Override public void execute(Runnable task) {
                joinedTicket[0] = holder[0].request("k", producer, joined);
                throw new IllegalStateException("executor broken");
            }
        };
        FlightCache<String, String> cache = FlightCache.<String, String>builder(broken, env.owner).build();
        holder[0] = cache;
        Probe first = new Probe("first");
        expect(IllegalStateException.class, new Runnable() { public void run() {
            cache.request("k", producer, first);
        }});
        check(cache.stats().flights == 0 && cache.peek("k") == null,
                "a non-refusal executor failure rolls the flight back: " + cache.stats());
        env.owner.drain();
        check(joinedTicket[0] != null && joinedTicket[0].status() == Status.JOINED
                && joined.events.equals(listOf("failed:executor broken")) && first.events.isEmpty(),
                "the joined subscriber gets the failure and the caller got the exception: " + joined.events);
    }

    /**
     * An {@link Error} (not only a {@link RuntimeException}) from the owner poster must be treated
     * like a refusal: the subscriber is counted stale and released, the remaining subscribers are
     * still posted, and nothing escapes the delivery loop.
     */
    private static void posterErrorIsCountedAndDoesNotLeak() throws Exception {
        final Env env = new Env();
        final AtomicInteger posts = new AtomicInteger();
        final FlightCache.OwnerPoster poster = new FlightCache.OwnerPoster() {
            @Override public boolean post(Runnable task) {
                if (posts.getAndIncrement() == 0) throw new Error("poster broken");
                return env.owner.post(task);
            }
        };
        FlightCache<String, String> cache = FlightCache.<String, String>builder(env.workers, poster).build();
        Probe first = new Probe("first"), second = new Probe("second");
        Ticket<String> firstTicket = cache.request("k", env.producer(), first);
        Ticket<String> secondTicket = cache.request("k", env.producer(), second);
        check(firstTicket.status() == Status.STARTED && secondTicket.status() == Status.JOINED,
                "one flight collects both subscribers");
        env.workers.runAll();
        check(posts.get() == 2, "both subscribers were offered to the owner");
        env.owner.drain();
        check(first.events.isEmpty() && second.events.equals(listOf("ready:v-k")),
                "the subscriber after the failed post is still delivered");
        FlightCache.Stats stats = cache.stats();
        check(stats.droppedStale == 1 && stats.delivered == 1 && stats.flights == 0,
                "the failed post is stale and the other delivery counts: " + stats);
        Object firstSub = subscriptionOf(firstTicket);
        Object secondSub = subscriptionOf(secondTicket);
        check(firstSub != null && secondSub != null, "both tickets carry a subscription");
        check(field(firstSub.getClass(), "subscriber").get(firstSub) == null
                && field(firstSub.getClass(), "flight").get(firstSub) == null
                && field(secondSub.getClass(), "subscriber").get(secondSub) == null
                && field(secondSub.getClass(), "flight").get(secondSub) == null,
                "neither terminal subscription keeps the subscriber or the flight alive");
    }

    /**
     * An {@link Error} from the eviction listener is counted like any other listener failure and
     * never propagates into the cache's caller.
     */
    private static void evictionListenerErrorIsCountedNotThrown() {
        Env env = new Env();
        final AtomicInteger calls = new AtomicInteger();
        FlightCache<String, String> cache = FlightCache.<String, String>builder(env.workers, env.owner)
                .maxEntries(1)
                .evictionListener(new FlightCache.EvictionListener<String, String>() {
                    @Override public void onEvicted(String key, String value, EvictionReason reason) {
                        calls.incrementAndGet();
                        throw new Error("listener broken");
                    }
                }).build();
        cache.request("a", env.producer(), new Probe("a"));
        env.workers.runAll();
        env.owner.drain();
        cache.request("b", env.producer(), new Probe("b"));
        env.workers.runAll();
        env.owner.drain();
        check(calls.get() == 1 && cache.stats().listenerFailures == 1,
                "one eviction reached the listener and its Error was counted: " + cache.stats());
        check(cache.peek("b") != null && cache.peek("a") == null,
                "the cache still evicts and keeps working after a listener Error");
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
                check(pool.awaitTermination(5, TimeUnit.SECONDS) && sawCancel[0] == abandon
                        && "late".equals(cache.peek("k")),
                        "abandonRunning=" + abandon + " controls cancellation and the started flight is cached");
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
            a.cancel();
            cache.request("c", env.producer(), new Probe("c"));
            check(pool.getQueue().size() == 2,
                    "both flights waited in the pool queue and the abandoned task left it when its slot was reused");
            cache.close();
            check(pool.getQueue().isEmpty(), "closing empties the pool queue of its tasks");
            release.countDown();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void stampede() throws Exception {
        // Default keeps one deterministic round of the real eight-way stampede; the full twenty
        // rounds are opt-in stress so the default suite stays a representative sample.
        int rounds = stress() ? 20 : 1;
        for (int round = 0; round < rounds; round++) {
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
                        // Default is a small deterministic sample; the 4x2000 production-call load
                        // is opt-in stress.
                        for (int i = 0; i < (stress() ? 2000 : 16); i++) {
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
            check(stats.flights == 0 && stats.entries <= 8 && stats.weight <= 40
                    && stats.produced + stats.failures + stats.abandonedDropped == stats.flightsStarted,
                    "at rest nothing is in flight and every started flight ended counted: " + stats);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A ticket that reached a terminal state must not keep the producer, the flight (and through its
     * task the whole cache) or the subscriber alive. Deterministic reflection only: the same-thread
     * drain()/cancel() program order and the cache lock make the cleared fields visible, so no GC
     * or production counter is involved.
     */
    private static void retentionFastPath() throws Exception {
        Env env = new Env();
        FlightCache<String, String> cache = env.builder().build();
        FlightCache.Producer<String, String> producer = env.producer();
        Probe probe = new Probe("done");
        Ticket<String> ticket = cache.request("k", producer, probe);
        Field subscription = Ticket.class.getDeclaredField("subscription");
        subscription.setAccessible(true);
        Object sub = subscription.get(ticket);
        Field subSubscriber = field(sub.getClass(), "subscriber");
        Field subFlight = field(sub.getClass(), "flight");
        Object flight = subFlight.get(sub);
        Field flightProducer = field(flight.getClass(), "producer");
        Field flightTask = field(flight.getClass(), "task");
        check(flight != null && subSubscriber.get(sub) == probe && flightProducer.get(flight) == producer,
                "a started ticket references its flight, subscriber and producer while in flight");
        env.workers.runAll();
        env.owner.drain();
        check(subSubscriber.get(sub) == null && subFlight.get(sub) == null
                && flightProducer.get(flight) == null && flightTask.get(flight) == null
                && !ticket.cancel(),
                "a completed ticket and its flight drop every reference and have nothing to cancel");

        Ticket<String> cancelled = cache.request("c", env.producer(), new Probe("c"));
        Object cancelledSub = subscription.get(cancelled);
        Object cancelledFlight = subFlight.get(cancelledSub);
        check(cancelled.cancel() && subSubscriber.get(cancelledSub) == null && subFlight.get(cancelledSub) == null,
                "a cancelled ticket drops the subscriber and the flight at once");
        env.workers.runAll();
        env.owner.drain();
        check(flightProducer.get(cancelledFlight) == null && flightTask.get(cancelledFlight) == null
                && cache.stats().flights == 0,
                "the abandoned flight drops the producer and the task when it is dropped");
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field value = type.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static Object subscriptionOf(Ticket<?> ticket) throws Exception {
        return field(Ticket.class, "subscription").get(ticket);
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

    /** Opt-in heavy-load switch shared by the contract suites; default runs stay small. */
    private static boolean stress() {
        return Boolean.getBoolean("meguviz.test.stress");
    }
}
