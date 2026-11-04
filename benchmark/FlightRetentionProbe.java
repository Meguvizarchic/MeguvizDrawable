package io.github.jichuo1.meguviz.drawable.benchmark;

import io.github.jichuo1.meguviz.drawable.cache.FlightCache;
import io.github.jichuo1.meguviz.drawable.cache.FlightCache.Status;
import io.github.jichuo1.meguviz.drawable.cache.FlightCache.Ticket;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Host retention probe for {@link FlightCache} tickets, not an Android/device test. Drives tickets
 * through every terminal state and reports, by deterministic reflection, whether the ticket still
 * reaches the subscriber, the flight, the producer, the flight task (which captures the whole
 * cache) or the cache itself. A bounded GC pass then reports whether weak references to the flight
 * and to payloads captured by the producer/subscriber are collectable once the ticket and cache are
 * dropped.
 *
 * <p>The probe adds no production counters and reuses the ticket lifecycle: the payloads are
 * captured by the producer/subscriber instances themselves, so a retained flight keeps them alive.
 * The reflection columns are the deterministic evidence; the GC columns are supporting evidence and
 * are best-effort (a bounded loop, never an assertion on a single GC). Usage:
 * {@code java -cp <classes> io.github.jichuo1.meguviz.drawable.benchmark.FlightRetentionProbe}.
 */
public final class FlightRetentionProbe {
    private static final int PAYLOAD_BYTES = 1 << 20;
    private static final int GC_ROUNDS = 200;
    private static final long GC_SLEEP_MILLIS = 10L;

    private static final List<String> weakNames = new ArrayList<String>();
    private static final List<WeakReference<Object>> weakRefs = new ArrayList<WeakReference<Object>>();
    /** The host pattern under test: tickets are kept, so a retained producer would stay alive. */
    private static final List<Ticket<?>> retainedTickets = new ArrayList<Ticket<?>>();
    private static int violations;

    public static void main(String[] args) throws Exception {
        System.out.println("columns,scenario,owner_null,sub_null,sub_flight_null,sub_subscriber_null,"
                + "flight_producer_null,flight_task_null,weak_flight_gone,weak_producer_gone,weak_subscriber_gone");
        startedComplete();
        joinedComplete();
        cancelled();
        closed();
        posterRefused();
        ready();
        rejectedCapacity();
        gcPass();
        System.out.println("summary,violations," + violations);
        if (violations != 0) {
            System.out.println("RETENTION_PROBE_FAILED");
            System.exit(1);
        }
        System.out.println("RETENTION_PROBE_OK");
    }

    private static void startedComplete() throws Exception {
        ManualExecutor workers = new ManualExecutor();
        ManualPoster owner = new ManualPoster();
        FlightCache<String, String> cache = FlightCache.<String, String>builder(workers, owner).build();
        final byte[] producerPayload = new byte[PAYLOAD_BYTES];
        final byte[] subscriberPayload = new byte[PAYLOAD_BYTES];
        FlightCache.Producer<String, String> producer = (key, cancellation) -> {
            producerPayload.hashCode();
            return "v-" + key;
        };
        FlightCache.Subscriber<String> subscriber = subscriber(subscriberPayload);
        Ticket<String> ticket = cache.request("k", producer, subscriber);
        Object sub = get(ticket, "subscription");
        Object flight = get(sub, "flight");
        addWeak("flight", flight);
        addWeak("producerPayload", producerPayload);
        addWeak("subscriberPayload", subscriberPayload);
        workers.runAll();
        owner.drain();
        report("started_complete", ticket, sub, flight, true);
    }

    private static void joinedComplete() throws Exception {
        ManualExecutor workers = new ManualExecutor();
        ManualPoster owner = new ManualPoster();
        FlightCache<String, String> cache = FlightCache.<String, String>builder(workers, owner).build();
        final byte[] producerPayload = new byte[PAYLOAD_BYTES];
        final byte[] subscriberPayload = new byte[PAYLOAD_BYTES];
        FlightCache.Producer<String, String> producer = (key, cancellation) -> {
            producerPayload.hashCode();
            return "v-" + key;
        };
        cache.request("k", producer, subscriber(subscriberPayload));
        Ticket<String> joined = cache.request("k", producer, subscriber(subscriberPayload));
        Object sub = get(joined, "subscription");
        Object flight = get(sub, "flight");
        addWeak("flight", flight);
        addWeak("producerPayload", producerPayload);
        addWeak("subscriberPayload", subscriberPayload);
        workers.runAll();
        owner.drain();
        report("joined_complete", joined, sub, flight, true);
    }

    private static void cancelled() throws Exception {
        ManualExecutor workers = new ManualExecutor();
        ManualPoster owner = new ManualPoster();
        FlightCache<String, String> cache = FlightCache.<String, String>builder(workers, owner).build();
        final byte[] producerPayload = new byte[PAYLOAD_BYTES];
        final byte[] subscriberPayload = new byte[PAYLOAD_BYTES];
        FlightCache.Producer<String, String> producer = (key, cancellation) -> {
            producerPayload.hashCode();
            return "v-" + key;
        };
        Ticket<String> ticket = cache.request("k", producer, subscriber(subscriberPayload));
        Object sub = get(ticket, "subscription");
        Object flight = get(sub, "flight");
        addWeak("flight", flight);
        addWeak("producerPayload", producerPayload);
        addWeak("subscriberPayload", subscriberPayload);
        ticket.cancel();
        workers.runAll();
        owner.drain();
        report("cancelled", ticket, sub, flight, true);
    }

    private static void closed() throws Exception {
        // A custom executor is used here, so close() cannot take the queued task out of its queue
        // (removeFromQueue only handles ThreadPoolExecutor). The flight object therefore stays
        // reachable through the executor queue for as long as the cache is; that is a known custom
        // executor limitation, not a ticket-chain retention, so no flight weak reference is added.
        // The producer and subscriber payloads are still released, which is what the probe checks.
        ManualExecutor workers = new ManualExecutor();
        ManualPoster owner = new ManualPoster();
        FlightCache<String, String> cache = FlightCache.<String, String>builder(workers, owner).build();
        final byte[] producerPayload = new byte[PAYLOAD_BYTES];
        final byte[] subscriberPayload = new byte[PAYLOAD_BYTES];
        FlightCache.Producer<String, String> producer = (key, cancellation) -> {
            producerPayload.hashCode();
            return "v-" + key;
        };
        Ticket<String> ticket = cache.request("k", producer, subscriber(subscriberPayload));
        Object sub = get(ticket, "subscription");
        Object flight = get(sub, "flight");
        addWeak("producerPayload", producerPayload);
        addWeak("subscriberPayload", subscriberPayload);
        cache.close();
        report("closed", ticket, sub, flight, false);
    }

    private static void posterRefused() throws Exception {
        ManualExecutor workers = new ManualExecutor();
        ManualPoster owner = new ManualPoster();
        owner.accepting = false;
        FlightCache<String, String> cache = FlightCache.<String, String>builder(workers, owner).build();
        final byte[] producerPayload = new byte[PAYLOAD_BYTES];
        final byte[] subscriberPayload = new byte[PAYLOAD_BYTES];
        FlightCache.Producer<String, String> producer = (key, cancellation) -> {
            producerPayload.hashCode();
            return "v-" + key;
        };
        Ticket<String> ticket = cache.request("k", producer, subscriber(subscriberPayload));
        Object sub = get(ticket, "subscription");
        Object flight = get(sub, "flight");
        addWeak("flight", flight);
        addWeak("producerPayload", producerPayload);
        addWeak("subscriberPayload", subscriberPayload);
        workers.runAll();
        report("poster_refused", ticket, sub, flight, true);
    }

    private static void ready() throws Exception {
        ManualExecutor workers = new ManualExecutor();
        ManualPoster owner = new ManualPoster();
        FlightCache<String, String> cache = FlightCache.<String, String>builder(workers, owner).build();
        cache.computeNow("k", (key, cancellation) -> "v-" + key);
        Ticket<String> ticket = cache.request("k", (key, cancellation) -> "v-" + key,
                subscriber(new byte[PAYLOAD_BYTES]));
        report("ready", ticket, get(ticket, "subscription"), null, true);
    }

    private static void rejectedCapacity() throws Exception {
        ManualExecutor workers = new ManualExecutor();
        ManualPoster owner = new ManualPoster();
        FlightCache<String, String> cache = FlightCache.<String, String>builder(workers, owner)
                .maxFlights(1).build();
        cache.request("taken", (key, cancellation) -> "v-" + key, subscriber(new byte[PAYLOAD_BYTES]));
        Ticket<String> ticket = cache.request("other", (key, cancellation) -> "v-" + key,
                subscriber(new byte[PAYLOAD_BYTES]));
        report("rejected_capacity", ticket, get(ticket, "subscription"), null, true);
    }

    private static FlightCache.Subscriber<String> subscriber(final byte[] payload) {
        return new FlightCache.Subscriber<String>() {
            @Override public boolean isCurrent() { return payload != null; }
            @Override public void onReady(String value) { payload.hashCode(); }
            @Override public void onFailed(Throwable failure) { payload.hashCode(); }
        };
    }

    private static void report(String scenario, Ticket<?> ticket, Object sub, Object flight,
                               boolean requireTaskNull) throws Exception {
        boolean ownerNull = get(ticket, "owner") == null;
        boolean subNull = sub == null;
        boolean subFlightNull = sub == null || get(sub, "flight") == null;
        boolean subSubscriberNull = sub == null || get(sub, "subscriber") == null;
        boolean flightProducerNull = flight == null || get(flight, "producer") == null;
        boolean flightTaskNull = flight == null || get(flight, "task") == null;
        // READY/REJECTED tickets own nothing and must not hold the cache. A terminal STARTED/JOINED
        // ticket still holds owner=cache (cancel needs it while active), so the owner chain can keep
        // the shared cache alive; the reduction here is that the ticket no longer reaches the
        // subscriber, the flight, the producer or the flight task. close() leaves flight.task alone
        // because the task is assigned outside the lock and the flight is already unreachable.
        boolean ownerOk = sub != null || ownerNull;
        boolean taskOk = !requireTaskNull || flightTaskNull;
        if (!ownerOk || !subFlightNull || !subSubscriberNull || !flightProducerNull || !taskOk) {
            violations++;
        }
        retainedTickets.add(ticket);
        System.out.println("row," + scenario + "," + ownerNull + "," + subNull + "," + subFlightNull + ","
                + subSubscriberNull + "," + flightProducerNull + "," + flightTaskNull + ",pending,pending,pending");
    }

    private static void gcPass() throws InterruptedException {
        // The tickets are still strongly held, which is the host pattern that used to keep the
        // producer and subscriber payloads alive. A bounded GC pass is enough to show the
        // difference; it is supporting evidence, never a single-GC assertion.
        for (int round = 0; round < GC_ROUNDS; round++) {
            System.gc();
            Thread.sleep(GC_SLEEP_MILLIS);
            if (remainingWeak() == 0) break;
        }
        int remainingWhileRetained = remainingWeak();
        System.out.println("weak_total," + weakRefs.size() + ",remaining_while_ticket_retained," + remainingWhileRetained);
        for (int i = 0; i < weakNames.size(); i++) {
            if (weakRefs.get(i).get() != null) {
                System.out.println("weak_retained," + weakNames.get(i));
            }
        }
        if (remainingWhileRetained != 0) violations++;
        retainedTickets.clear();
        for (int round = 0; round < GC_ROUNDS; round++) {
            System.gc();
            Thread.sleep(GC_SLEEP_MILLIS);
            if (remainingWeak() == 0) break;
        }
        System.out.println("weak_total," + weakRefs.size() + ",remaining_after_ticket_dropped," + remainingWeak());
    }

    private static int remainingWeak() {
        int remaining = 0;
        for (WeakReference<Object> reference : weakRefs) {
            if (reference.get() != null) remaining++;
        }
        return remaining;
    }

    private static void addWeak(String name, Object target) {
        weakNames.add(name + "#" + weakRefs.size());
        weakRefs.add(new WeakReference<Object>(target));
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /** Runs tasks only when the test says so. */
    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
        @Override public synchronized void execute(Runnable task) { queue.add(task); }
        void runAll() {
            while (true) {
                Runnable next;
                synchronized (this) { next = queue.poll(); }
                if (next == null) return;
                next.run();
            }
        }
    }

    /** Stands in for the owner thread; deliveries run when the probe drains. */
    private static final class ManualPoster implements FlightCache.OwnerPoster {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
        volatile boolean accepting = true;
        @Override public synchronized boolean post(Runnable task) {
            if (!accepting) return false;
            queue.add(task);
            return true;
        }
        void drain() {
            while (true) {
                Runnable next;
                synchronized (this) { next = queue.poll(); }
                if (next == null) return;
                next.run();
            }
        }
    }
}
