package io.github.jichuo1.meguviz.drawable.core;

import io.github.jichuo1.meguviz.drawable.cache.FlightCache;
import io.github.jichuo1.meguviz.drawable.cache.FlightCache.Status;
import io.github.jichuo1.meguviz.drawable.cache.FlightCache.Ticket;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Retention contracts of {@link FlightCache} tickets. Every scenario drives a ticket to a terminal
 * state and then asserts, by deterministic reflection, that the ticket no longer reaches the
 * subscriber, the flight, the producer or the flight task (which captures the whole cache). This is
 * a <b>partial</b> retention reduction: a terminal STARTED/JOINED ticket still holds
 * {@code owner == cache} because {@code cancel()} needs it while the ticket is active, so the owner
 * chain can still keep the shared cache alive; READY and REJECTED tickets own nothing and must not
 * hold it. No GC and no production counters are involved: the same-thread drain()/cancel() program
 * order and the cache lock make the cleared fields visible to the assertions.
 *
 * <p>This file is wired into the host suite by the caller; it only exposes {@link #run()}.</p>
 */
final class TicketRetentionContractTest {
    private static int checks;

    static int run() throws Exception {
        startedTicketReleasesOnCompletion();
        cancelledTicketReleasesImmediately();
        rejectedAndReadyTicketsDoNotHoldTheCache();
        if (stress()) {
            joinedTicketReleasesOnCompletion();
            closedCacheReleasesTicketReferences();
            posterRefusalReleasesSubscriber();
            computeNowSupersedeReleasesQueuedFlight();
            executorRefusalReleasesFlightAndJoinedSubscribers();
            cancelSemanticsSurviveRetention();
            closeDuringExecuteDoesNotNullTheTask();
            computeNowDuringExecuteDoesNotNullTheTask();
            cancelAfterRefusedDeliveryStillTrue();
            deliverCallbackReentrantCancelAndCloseDoesNotNpe();
            closeBeforeDrainDropsPostedDelivery();
        }
        return checks;
    }

    /** Opt-in race-window coverage; the default run keeps the deterministic retention contracts. */
    private static boolean stress() {
        return Boolean.getBoolean("meguviz.test.stress");
    }

    /** Runs tasks only when the test says so. */
    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
        boolean refuse;
        @Override public synchronized void execute(Runnable task) {
            if (refuse) throw new RejectedExecutionException("refused");
            queue.add(task);
        }
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
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        volatile boolean current = true;
        @Override public boolean isCurrent() { return current; }
        @Override public void onReady(String value) { events.add("ready:" + value); }
        @Override public void onFailed(Throwable failure) { events.add("failed:" + failure.getMessage()); }
    }

    private static final class Env {
        final ManualExecutor workers = new ManualExecutor();
        final ManualPoster owner = new ManualPoster();
        FlightCache<String, String> cache() {
            return FlightCache.<String, String>builder(workers, owner).build();
        }
        FlightCache.Producer<String, String> producer() {
            return new FlightCache.Producer<String, String>() {
                @Override public String produce(String key, FlightCache.Cancellation cancellation) {
                    return "v-" + key;
                }
            };
        }
    }

    private static void startedTicketReleasesOnCompletion() throws Exception {
        Env env = new Env();
        FlightCache<String, String> cache = env.cache();
        Probe probe = new Probe();
        Ticket<String> ticket = cache.request("k", env.producer(), probe);
        Object sub = subscription(ticket);
        Object flight = flight(sub);
        Object producer = get(flight, "producer");
        check(producer != null && get(sub, "subscriber") == probe && flight != null,
                "the in-flight ticket still references the flight, subscriber and producer");
        env.workers.runAll();
        env.owner.drain();
        check(probe.events.equals(listOf("ready:v-k"))
                && get(sub, "subscriber") == null && get(sub, "flight") == null
                && get(flight, "producer") == null && get(flight, "task") == null
                && !ticket.cancel(),
                "a completed ticket and its flight drop every reference and have nothing to cancel");
    }

    private static void joinedTicketReleasesOnCompletion() throws Exception {
        Env env = new Env();
        FlightCache<String, String> cache = env.cache();
        Probe first = new Probe(), second = new Probe();
        Ticket<String> started = cache.request("k", env.producer(), first);
        Ticket<String> joined = cache.request("k", env.producer(), second);
        Object startedSub = subscription(started);
        Object joinedSub = subscription(joined);
        Object flight = flight(joinedSub);
        check(joined.status() == Status.JOINED && flight == flight(startedSub),
                "the second request joins and both tickets reference the same flight");
        env.workers.runAll();
        env.owner.drain();
        check(first.events.equals(listOf("ready:v-k")) && second.events.equals(listOf("ready:v-k"))
                && get(startedSub, "subscriber") == null && get(startedSub, "flight") == null
                && get(joinedSub, "subscriber") == null && get(joinedSub, "flight") == null,
                "both subscribers were served and both completed tickets drop their references");
        check(get(flight, "producer") == null && get(flight, "task") == null,
                "the shared flight drops the producer and the task once");
    }

    private static void cancelledTicketReleasesImmediately() throws Exception {
        Env env = new Env();
        FlightCache<String, String> cache = env.cache();
        Ticket<String> ticket = cache.request("k", env.producer(), new Probe());
        Object sub = subscription(ticket);
        Object flight = flight(sub);
        check(ticket.cancel() && !ticket.cancel()
                && get(sub, "subscriber") == null && get(sub, "flight") == null,
                "cancel succeeds once and drops the subscriber and the flight immediately");
        env.workers.runAll();
        env.owner.drain();
        check(get(flight, "producer") == null && get(flight, "task") == null
                && cache.stats().flights == 0,
                "the abandoned flight drops the producer and the task when it is dropped");
    }

    private static void closedCacheReleasesTicketReferences() throws Exception {
        Env env = new Env();
        FlightCache<String, String> cache = env.cache();
        Ticket<String> queued = cache.request("k", env.producer(), new Probe());
        Object sub = subscription(queued);
        Object flight = flight(sub);
        cache.close();
        check(get(sub, "subscriber") == null && get(sub, "flight") == null
                && get(flight, "producer") == null && !queued.cancel(),
                "close drops the ticket references and the producer, and the ticket has nothing to cancel");
        env.workers.runAll();
        env.owner.drain();
        check(cache.stats().flights == 0, "close leaves no flight");
    }

    private static void posterRefusalReleasesSubscriber() throws Exception {
        Env env = new Env();
        FlightCache<String, String> cache = env.cache();
        Ticket<String> ticket = cache.request("k", env.producer(), new Probe());
        Object sub = subscription(ticket);
        Object flight = flight(sub);
        env.owner.accepting = false;
        env.workers.runAll();
        check(get(sub, "subscriber") == null && get(sub, "flight") == null
                && get(flight, "producer") == null && get(flight, "task") == null
                && cache.stats().droppedStale == 1,
                "a refused delivery drops every reference and is still counted as stale");
        env.owner.accepting = true;
    }

    private static void rejectedAndReadyTicketsDoNotHoldTheCache() throws Exception {
        Env env = new Env();
        FlightCache<String, String> cache = FlightCache.<String, String>builder(env.workers, env.owner)
                .maxFlights(1).build();
        cache.request("taken", env.producer(), new Probe());
        Ticket<String> rejected = cache.request("other", env.producer(), new Probe());
        check(rejected.status() == Status.REJECTED_CAPACITY && owner(rejected) == null && !rejected.cancel(),
                "a rejected ticket holds nothing and has nothing to cancel");
        env.workers.runAll();
        env.owner.drain();
        Ticket<String> ready = cache.request("taken", env.producer(), new Probe());
        check(ready.status() == Status.READY && owner(ready) == null && !ready.cancel(),
                "a ready ticket holds nothing and has nothing to cancel");
        cache.close();
        Ticket<String> closed = cache.request("taken", env.producer(), new Probe());
        check(closed.status() == Status.REJECTED_CLOSED && owner(closed) == null,
                "a closed cache rejects and the ticket holds nothing");
    }

    private static void computeNowSupersedeReleasesQueuedFlight() throws Exception {
        Env env = new Env();
        FlightCache<String, String> cache = env.cache();
        Probe waiting = new Probe();
        Ticket<String> queued = cache.request("k", env.producer(), waiting);
        Object sub = subscription(queued);
        Object flight = flight(sub);
        check("v-k".equals(cache.computeNow("k", env.producer())), "computeNow produces synchronously");
        env.owner.drain();
        check(waiting.events.equals(listOf("ready:v-k"))
                && get(sub, "subscriber") == null && get(sub, "flight") == null
                && get(flight, "producer") == null,
                "the queued subscriber is completed and the superseded ticket and flight drop their references");
        env.workers.runAll();
        env.owner.drain();
        check(cache.stats().produced == 1, "the superseded task never runs");
    }

    @SuppressWarnings("unchecked")
    private static void executorRefusalReleasesFlightAndJoinedSubscribers() throws Exception {
        final Env env = new Env();
        final FlightCache.Producer<String, String> producer = env.producer();
        final Probe joined = new Probe();
        final FlightCache<String, String>[] holder = new FlightCache[1];
        final Ticket<String>[] joinedTicket = new Ticket[1];
        // The executor joins one subscriber into the in-flight flight and only then refuses, so the
        // rollback has an "others" list to notify; that is the window a custom executor can produce.
        Executor refusing = new Executor() {
            @Override public void execute(Runnable task) {
                joinedTicket[0] = holder[0].request("k", producer, joined);
                throw new RejectedExecutionException("refused");
            }
        };
        FlightCache<String, String> cache = FlightCache.<String, String>builder(refusing, env.owner).build();
        holder[0] = cache;
        Ticket<String> ticket = cache.request("k", producer, new Probe());
        check(ticket.status() == Status.REJECTED_CAPACITY && !ticket.cancel()
                && joinedTicket[0] != null && joinedTicket[0].status() == Status.JOINED,
                "a refusing executor is a rejection that still joined a subscriber first");
        Object sub = subscription(joinedTicket[0]);
        Object flight = flight(sub);
        env.owner.drain();
        check(joined.events.equals(listOf("failed:refused"))
                && get(sub, "subscriber") == null && get(sub, "flight") == null
                && get(flight, "producer") == null && get(flight, "task") == null
                && cache.stats().flights == 0,
                "the joined subscriber gets the refusal and every reference is dropped");
    }

    private static void cancelSemanticsSurviveRetention() throws Exception {
        Env env = new Env();
        FlightCache<String, String> cache = env.cache();
        Ticket<String> active = cache.request("a", env.producer(), new Probe());
        check(active.cancel() && !active.cancel(), "an active ticket cancels exactly once");
        Ticket<String> completed = cache.request("b", env.producer(), new Probe());
        env.workers.runAll();
        env.owner.drain();
        check(!completed.cancel(), "a completed ticket cannot cancel");
        Ticket<String> rejected = cache.request("c", env.producer(), new Probe());
        cache.close();
        check(rejected.status() == Status.STARTED && !rejected.cancel(), "a closed ticket cannot cancel");
    }

    /**
     * The task is assigned outside the lock, so an executor can run close() between the assignment
     * and the execute call. The request must still hand a non-null task to the executor and the
     * closed flight must not survive.
     */
    @SuppressWarnings("unchecked")
    private static void closeDuringExecuteDoesNotNullTheTask() throws Exception {
        final FlightCache<String, String>[] holder = new FlightCache[1];
        final ManualPoster owner = new ManualPoster();
        final List<Runnable> accepted = new ArrayList<Runnable>();
        Executor closing = new Executor() {
            @Override public void execute(Runnable task) {
                holder[0].close();
                accepted.add(task);
            }
        };
        FlightCache<String, String> cache = FlightCache.<String, String>builder(closing, owner).build();
        holder[0] = cache;
        Ticket<String> ticket = cache.request("k", new FlightCache.Producer<String, String>() {
            @Override public String produce(String key, FlightCache.Cancellation cancellation) {
                return "v-" + key;
            }
        }, new Probe());
        check(ticket.status() == Status.STARTED && accepted.size() == 1 && accepted.get(0) != null
                && cache.isClosed(),
                "the request returns a ticket, the executor gets a non-null task, and it closed the cache");
        for (Runnable task : accepted) task.run();
        check(cache.stats().flights == 0, "the closed flight is gone");
    }

    /** The same assignment race, but computeNow() supersedes the queued flight instead of close(). */
    @SuppressWarnings("unchecked")
    private static void computeNowDuringExecuteDoesNotNullTheTask() throws Exception {
        final FlightCache<String, String>[] holder = new FlightCache[1];
        final ManualPoster owner = new ManualPoster();
        final List<Runnable> accepted = new ArrayList<Runnable>();
        final FlightCache.Producer<String, String> producer = new FlightCache.Producer<String, String>() {
            @Override public String produce(String key, FlightCache.Cancellation cancellation) {
                return "v-" + key;
            }
        };
        Executor computing = new Executor() {
            @Override public void execute(Runnable task) {
                try {
                    holder[0].computeNow("k", producer);
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
                accepted.add(task);
            }
        };
        FlightCache<String, String> cache = FlightCache.<String, String>builder(computing, owner).build();
        holder[0] = cache;
        Ticket<String> ticket = cache.request("k", producer, new Probe());
        check(ticket.status() == Status.STARTED && accepted.size() == 1 && accepted.get(0) != null,
                "the request returns a ticket and the executor receives a non-null task");
        owner.drain();
        check("v-k".equals(cache.peek("k")), "computeNow published the value");
        for (Runnable task : accepted) task.run();
        check(cache.stats().flights == 0, "the superseded flight is gone");
    }

    /**
     * A subscriber that re-enters {@code Ticket.cancel()} and {@code FlightCache.close()} from inside
     * {@code onReady} must not observe a half-cleared subscription: cancel() sees delivered==true and
     * returns false, close() no longer finds the flight, and the terminal cleanup still runs.
     */
    @SuppressWarnings("unchecked")
    private static void deliverCallbackReentrantCancelAndCloseDoesNotNpe() throws Exception {
        Env env = new Env();
        final FlightCache<String, String> cache = env.cache();
        final Ticket<String>[] ticketHolder = new Ticket[1];
        FlightCache.Subscriber<String> reentrant = new FlightCache.Subscriber<String>() {
            @Override public boolean isCurrent() { return true; }
            @Override public void onReady(String value) {
                ticketHolder[0].cancel();
                cache.close();
            }
            @Override public void onFailed(Throwable failure) { }
        };
        Ticket<String> ticket = cache.request("k", env.producer(), reentrant);
        ticketHolder[0] = ticket;
        Object sub = subscription(ticket);
        env.workers.runAll();
        env.owner.drain();
        check(get(sub, "subscriber") == null && get(sub, "flight") == null
                && !ticket.cancel() && cache.isClosed(),
                "reentrant cancel/close in the callback kept the terminal cleanup and closed the cache");
    }

    /**
     * A delivery drained and posted before close() is no longer in a flight, so close() cannot
     * cancel it. deliver() still checks closed and drops it, which keeps the documented "no callback
     * after close" promise for this interleaving.
     */
    private static void closeBeforeDrainDropsPostedDelivery() throws Exception {
        Env env = new Env();
        FlightCache<String, String> cache = env.cache();
        Probe probe = new Probe();
        cache.request("k", env.producer(), probe);
        env.workers.runAll();
        check(env.owner.pending() == 1, "the delivery is posted before close");
        cache.close();
        env.owner.drain();
        check(probe.events.isEmpty() && cache.stats().droppedStale == 1,
                "a delivery posted before close is not called after close and is counted as stale");
    }

    private static void cancelAfterRefusedDeliveryStillTrue() throws Exception {
        Env env = new Env();
        FlightCache<String, String> cache = env.cache();
        Ticket<String> ticket = cache.request("k", env.producer(), new Probe());
        Object sub = subscription(ticket);
        env.owner.accepting = false;
        env.workers.runAll();
        check(get(sub, "subscriber") == null && get(sub, "flight") == null && ticket.cancel(),
                "the refused delivery dropped its references and cancel still reports true");
        env.owner.accepting = true;
    }

    private static Object subscription(Ticket<?> ticket) throws Exception {
        return field(ticket, "subscription").get(ticket);
    }

    private static Object owner(Ticket<?> ticket) throws Exception {
        return field(ticket, "owner").get(ticket);
    }

    private static Object flight(Object sub) throws Exception {
        return get(sub, "flight");
    }

    private static Object get(Object target, String name) throws Exception {
        return field(target, name).get(target);
    }

    private static Field field(Object target, String name) throws Exception {
        Field value = target.getClass().getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static List<String> listOf(String... values) {
        List<String> list = new ArrayList<String>();
        Collections.addAll(list, values);
        return list;
    }

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
}
