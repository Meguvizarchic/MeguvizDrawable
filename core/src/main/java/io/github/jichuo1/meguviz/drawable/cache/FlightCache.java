package io.github.jichuo1.meguviz.drawable.cache;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded cache of expensive, pure results with single-flight production, meant to be shared by
 * many owners (for example every scene that needs the same background tile).
 *
 * <p><b>Lookup is never a computation.</b> {@link #peek} and the hit path of {@link #request} only
 * read memory and allocate nothing beyond the returned {@link Ticket}, so a draw path can use them
 * on the owner thread; a miss is turned into work for the supplied {@link Executor} instead of being
 * done inline. {@link #computeNow} exists for the one case
 * that must be exact and synchronous (an export), and it deliberately never waits for the executor.</p>
 *
 * <p><b>One flight per key.</b> Concurrent requests for a missing key join the same flight: the
 * producer runs once, off any lock, and the result is cached and delivered to every subscriber that
 * is still interested. The first requester's producer runs, so equal keys must mean equal inputs.</p>
 *
 * <p><b>Subscribers are owner-confined and generation-checked.</b> A {@link Subscriber} is called on
 * the owner thread through the {@link OwnerPoster}, and only if it was not cancelled and still says
 * {@link Subscriber#isCurrent()} when the delivery runs; a result nobody wants any more is still
 * cached but not delivered. {@link Ticket#cancel} and every callback run on the owner thread, so a
 * cancelled subscriber is never called after {@code cancel} returned.</p>
 *
 * <p><b>Abandoned work does not pile up.</b> A flight that lost all its subscribers before it started
 * is dropped when its turn comes (and removed from a {@link ThreadPoolExecutor} queue at once), and
 * it is the first thing to give way when the flight limit is reached; a new request for the same key
 * revives it. A flight that already started finishes: its result is shared pure work and is cached,
 * unless {@link Builder#abandonRunning} asks the producer to stop at its next
 * {@link Cancellation} check.</p>
 *
 * <p><b>Bounds.</b> Entries and total weight are capped (least recently used goes first), and so is
 * the number of live flights; past the flight limit {@link #request} answers
 * {@link Status#REJECTED_CAPACITY} instead of queuing without limit. The cache never recycles a value
 * on eviction, because the evicted value may still be on screen; use the
 * {@link EvictionListener} to release resources the owner knows are free.</p>
 *
 * <p>Instances are thread-safe. Values must be immutable once produced.</p>
 */
public final class FlightCache<K, V> implements AutoCloseable {
    /** Computes the value for a key, on an executor thread. Must be pure and thread-safe. */
    public interface Producer<K, V> {
        /** Returns a non-null value; throwing fails every waiting subscriber and caches nothing. */
        V produce(K key, Cancellation cancellation) throws Exception;
    }

    /** Polled by long producers; true once the cache was closed or the work was abandoned (if enabled). */
    public interface Cancellation {
        boolean isCancelled();
    }

    /** Receives a result on the owner thread. */
    public interface Subscriber<V> {
        /** Checked on the owner thread just before delivery; false drops the delivery (stale generation). */
        boolean isCurrent();

        void onReady(V value);

        void onFailed(Throwable failure);
    }

    /** Runs a task on the owner thread (for example a main-looper post). May be called from any thread. */
    public interface OwnerPoster {
        /** False if the owner is gone; the delivery is then dropped. A thrown Throwable is treated the same way and counted. */
        boolean post(Runnable task);
    }

    /** Cost of one value against {@link Builder#maxWeight}, for example bytes. Must be stable. */
    public interface Weigher<V> {
        long weigh(V value);
    }

    public enum EvictionReason { CAPACITY, REPLACED, CLOSED }

    /** Called outside the cache lock, on the thread that caused the eviction. Throwables are counted, not thrown. */
    public interface EvictionListener<K, V> {
        void onEvicted(K key, V value, EvictionReason reason);
    }

    public enum Status {
        /** The value was cached; {@link Ticket#value} has it and no callback will follow. */
        READY,
        /** Another flight for the key is in progress; the subscriber was added to it. */
        JOINED,
        /** A new flight was queued. */
        STARTED,
        /** Too many flights; nothing was queued and no callback will follow. */
        REJECTED_CAPACITY,
        REJECTED_CLOSED
    }

    /** What {@link #request} returned. */
    public static final class Ticket<V> {
        private final Status status;
        private final V value;
        private final Sub<?, V> subscription;
        private final FlightCache<?, V> owner;

        private Ticket(Status status, V value, Sub<?, V> subscription, FlightCache<?, V> owner) {
            this.status = status;
            this.value = value;
            this.subscription = subscription;
            this.owner = owner;
        }

        public Status status() { return status; }

        /** The cached value for {@link Status#READY}, otherwise null. */
        public V value() { return value; }

        /**
         * Withdraws the subscriber. Returns true if it will never be called; false if it already was
         * (or there was nothing to cancel). Owner thread only.
         */
        public boolean cancel() {
            return subscription != null && owner.cancel(subscription);
        }
    }

    /** A consistent snapshot of the counters. */
    public static final class Stats {
        public final long hits, misses, joins, flightsStarted, produced, failures, evictions;
        public final long rejectedCapacity, abandonedDropped, cancelledSubscribers, delivered, droppedStale;
        public final long listenerFailures, version;
        public final int entries, flights;
        public final long weight;

        private Stats(long[] c, long version, int entries, long weight, int flights) {
            hits = c[0]; misses = c[1]; joins = c[2]; flightsStarted = c[3]; produced = c[4]; failures = c[5];
            evictions = c[6]; rejectedCapacity = c[7]; abandonedDropped = c[8]; cancelledSubscribers = c[9];
            delivered = c[10]; droppedStale = c[11]; listenerFailures = c[12];
            this.version = version;
            this.entries = entries;
            this.weight = weight;
            this.flights = flights;
        }

        @Override public String toString() {
            return "Stats{hits=" + hits + ", misses=" + misses + ", joins=" + joins + ", flightsStarted=" + flightsStarted
                    + ", produced=" + produced + ", failures=" + failures + ", evictions=" + evictions
                    + ", rejectedCapacity=" + rejectedCapacity + ", abandonedDropped=" + abandonedDropped
                    + ", cancelledSubscribers=" + cancelledSubscribers + ", delivered=" + delivered
                    + ", droppedStale=" + droppedStale + ", version=" + version + ", entries=" + entries
                    + ", weight=" + weight + ", flights=" + flights + '}';
        }
    }

    public static <K, V> Builder<K, V> builder(Executor workers, OwnerPoster owner) {
        return new Builder<K, V>(workers, owner);
    }

    public static final class Builder<K, V> {
        private final Executor workers;
        private final OwnerPoster owner;
        private int maxEntries = 64;
        private long maxWeight = Long.MAX_VALUE;
        private int maxFlights = 16;
        private Weigher<V> weigher;
        private EvictionListener<K, V> listener;
        private boolean abandonRunning;

        private Builder(Executor workers, OwnerPoster owner) {
            this.workers = Objects.requireNonNull(workers, "workers");
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        /** Default 64. */
        public Builder<K, V> maxEntries(int value) {
            if (value < 1) throw new IllegalArgumentException("maxEntries must be positive");
            maxEntries = value;
            return this;
        }

        /** Total weight cap; needs a {@link #weigher}. Default unbounded. */
        public Builder<K, V> maxWeight(long value) {
            if (value < 1) throw new IllegalArgumentException("maxWeight must be positive");
            maxWeight = value;
            return this;
        }

        /** Queued plus running flights allowed at once. Default 16. */
        public Builder<K, V> maxFlights(int value) {
            if (value < 1) throw new IllegalArgumentException("maxFlights must be positive");
            maxFlights = value;
            return this;
        }

        public Builder<K, V> weigher(Weigher<V> value) {
            weigher = Objects.requireNonNull(value, "weigher");
            return this;
        }

        public Builder<K, V> evictionListener(EvictionListener<K, V> value) {
            listener = Objects.requireNonNull(value, "listener");
            return this;
        }

        /** Let a running producer see {@link Cancellation#isCancelled()} once its last subscriber left. Default off. */
        public Builder<K, V> abandonRunning(boolean value) {
            abandonRunning = value;
            return this;
        }

        public FlightCache<K, V> build() {
            if (maxWeight != Long.MAX_VALUE && weigher == null) {
                throw new IllegalStateException("maxWeight requires a weigher");
            }
            return new FlightCache<K, V>(this);
        }
    }

    private enum FlightState { QUEUED, RUNNING, DONE }

    private static final class Cached<V> {
        final V value;
        final long weight;
        Cached(V value, long weight) {
            this.value = value;
            this.weight = weight;
        }
    }

    private static final class Flight<K, V> {
        final K key;
        // Cleared under the lock once the flight reaches a terminal state, so a ticket that outlives
        // the flight cannot keep the producer (and whatever it captures) alive. Read under the lock
        // into a local before the producer runs, because close() may clear it while produce() runs.
        Producer<K, V> producer;
        final List<Sub<K, V>> subscribers = new ArrayList<Sub<K, V>>(1);
        FlightState state = FlightState.QUEUED;
        volatile boolean cancelled;
        volatile Runnable task;
        Flight(K key, Producer<K, V> producer) {
            this.key = key;
            this.producer = producer;
        }
    }

    private static final class Sub<K, V> {
        // Cleared under the lock at the terminal state. The subscriber and the flight are the two
        // outbound references a retained Ticket would otherwise keep alive; all reads/writes hold
        // the cache lock (deliver reads it under the lock, cancel/close/release write it under it).
        Subscriber<V> subscriber;
        Flight<K, V> flight;
        volatile boolean cancelled;
        boolean delivered;
        Sub(Subscriber<V> subscriber) { this.subscriber = subscriber; }
    }

    private static final Cancellation NEVER = new Cancellation() {
        @Override public boolean isCancelled() { return false; }
    };

    private static final int HITS = 0, MISSES = 1, JOINS = 2, STARTED = 3, PRODUCED = 4, FAILURES = 5, EVICTIONS = 6,
            REJECTED = 7, ABANDONED = 8, CANCELLED = 9, DELIVERED = 10, STALE = 11, LISTENER_FAILURES = 12, COUNTERS = 13;

    private final Object lock = new Object();
    private final Executor workers;
    private final OwnerPoster owner;
    private final int maxEntries;
    private final long maxWeight;
    private final int maxFlights;
    private final Weigher<V> weigher;
    private final EvictionListener<K, V> listener;
    private final boolean abandonRunning;
    private final LinkedHashMap<K, Cached<V>> cache = new LinkedHashMap<K, Cached<V>>(16, 0.75f, true);
    private final Map<K, Flight<K, V>> flights = new LinkedHashMap<K, Flight<K, V>>();
    // Every counter except the three below is only ever written while holding {@code lock}, so a
    // plain long under the lock is enough; an atomic read-modify-write there was a second, redundant
    // synchronization on every hit. stats() reads them under the same lock, as before.
    private final long[] counts = new long[COUNTERS];
    // Written outside the lock (delivery on the owner thread, eviction listeners on any thread).
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();
    private final AtomicLong listenerFailures = new AtomicLong();
    private long weight;
    private long version;
    private volatile boolean closed;

    private FlightCache(Builder<K, V> builder) {
        workers = builder.workers;
        owner = builder.owner;
        maxEntries = builder.maxEntries;
        maxWeight = builder.maxWeight;
        maxFlights = builder.maxFlights;
        weigher = builder.weigher;
        listener = builder.listener;
        abandonRunning = builder.abandonRunning;
    }

    /** The cached value or null. Never computes and never blocks on a producer. */
    public V peek(K key) {
        Objects.requireNonNull(key, "key");
        synchronized (lock) {
            Cached<V> hit = cache.get(key);
            if (hit == null) return null;
            counts[HITS]++;
            return hit.value;
        }
    }

    /**
     * Returns the cached value, joins the flight for {@code key}, or starts one. For
     * {@link Status#STARTED} and {@link Status#JOINED}, exactly one of the subscriber's callbacks
     * follows on the owner thread unless it is cancelled, stale or the cache is closed.
     * <p>
     * If the executor refuses the task with a {@link RejectedExecutionException}, the flight is
     * rolled back and {@link Status#REJECTED_CAPACITY} is returned. Any other
     * {@link RuntimeException} (or an {@link Error}) from the executor rolls the flight back,
     * delivers the failure to the subscribers that joined in the meantime, and is then rethrown
     * to the caller.
     */
    public Ticket<V> request(K key, Producer<K, V> producer, Subscriber<V> subscriber) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(subscriber, "subscriber");
        // The hit and rejection paths own nothing. A join does allocate its subscriber record,
        // because it is registered on the flight; the unqueue list exists only when a flight is
        // actually started or an abandoned one is dropped to make room for it.
        List<Runnable> unqueue = null;
        Flight<K, V> start = null;
        Sub<K, V> sub = null;
        // The closed-rejection and hit paths only read state under the lock, so their Ticket is
        // built after it: the hit path allocates nothing but that Ticket. They leave {@code ticket}
        // null, which is why it is initialized rather than left to definite assignment.
        Ticket<V> ticket = null;
        Status early = null;
        V readyValue = null;
        synchronized (lock) {
            if (closed) {
                early = Status.REJECTED_CLOSED;
            } else {
                Cached<V> hit = cache.get(key);
                if (hit != null) {
                    counts[HITS]++;
                    // No subscription, so nothing to cancel and no reason to keep the cache
                    // reachable from a ticket whose whole lifetime is this return value.
                    readyValue = hit.value;
                    early = Status.READY;
                } else {
                    counts[MISSES]++;
                    Flight<K, V> joined = flights.get(key);
                    if (joined != null && !joined.cancelled) {
                        sub = new Sub<K, V>(subscriber);
                        joined.subscribers.add(sub);
                        sub.flight = joined;
                        counts[JOINS]++;
                        return new Ticket<V>(Status.JOINED, null, sub, this);
                    }
                    // A flight told to stop (every subscriber left, abandonRunning) is replaced rather than joined;
                    // it ends on its own and only removes the map entry if the entry is still itself.
                    boolean rejected = false;
                    Flight<K, V> dropped = null;
                    if (joined == null && flights.size() >= maxFlights) {
                        dropped = dropOneAbandoned();
                        rejected = dropped == null;
                    }
                    if (rejected) {
                        counts[REJECTED]++;
                        ticket = new Ticket<V>(Status.REJECTED_CAPACITY, null, null, null);
                    } else {
                        if (dropped != null && dropped.task != null) {
                            unqueue = new ArrayList<Runnable>(1);
                            unqueue.add(dropped.task);
                        }
                        sub = new Sub<K, V>(subscriber);
                        start = new Flight<K, V>(key, producer);
                        start.subscribers.add(sub);
                        sub.flight = start;
                        flights.put(key, start);
                        counts[STARTED]++;
                        ticket = new Ticket<V>(Status.STARTED, null, sub, this);
                    }
                }
            }
        }
        if (early != null) return new Ticket<V>(early, readyValue, null, null);
        removeFromQueue(unqueue);
        if (start == null) return ticket;
        final Flight<K, V> flight = start;
        // The task is assigned outside the lock, so close()/computeNow() can run between the
        // assignment and the execute call. Keep the reference in a local: the executor must never
        // see a null task just because another thread cleared flight.task.
        final Runnable task = new Runnable() {
            @Override public void run() { FlightCache.this.run(flight); }
        };
        flight.task = task;
        try {
            workers.execute(task);
        } catch (Throwable thrown) {
            // A custom executor may fail for a reason that is not a capacity refusal (an
            // IllegalStateException, a NullPointerException, ...). The host cannot treat that as a
            // rejection, so the flight is rolled back exactly like a refusal and the original
            // exception is rethrown: a flight left behind would never run, and every later request
            // for the key would join it and never be called back. Errors are rolled back and
            // rethrown for the same reason.
            List<Sub<K, V>> others;
            synchronized (lock) {
                // An executor may run the task synchronously and only then throw; the flight is
                // then already finished and must not be accounted as a capacity refusal.
                boolean ownsQueuedFlight = flights.get(key) == flight && flight.state == FlightState.QUEUED;
                if (flights.get(key) == flight) flights.remove(key);
                flight.state = FlightState.DONE;
                flight.producer = null;
                flight.task = null;
                if (ownsQueuedFlight) {
                    counts[STARTED]--;
                    counts[REJECTED]++;
                }
                flight.subscribers.remove(sub);
                others = ownsQueuedFlight ? drain(flight) : java.util.Collections.<Sub<K, V>>emptyList();
            }
            // Requests that joined in the meantime were promised a callback; they get the failure.
            deliverAll(others, null, thrown);
            if (thrown instanceof Error) throw (Error) thrown;
            if (thrown instanceof RejectedExecutionException) {
                return new Ticket<V>(Status.REJECTED_CAPACITY, null, null, null);
            }
            throw (RuntimeException) thrown;
        }
        return ticket;
    }

    /**
     * Produces the value on the calling thread and caches it, or returns the cached one. It never
     * waits for the executor, so it cannot deadlock behind queued work; the price is that a flight
     * already running for the key may produce the same value again. A flight still queued for the key
     * is completed with this value instead of running.
     */
    public V computeNow(K key, Producer<K, V> producer) throws Exception {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(producer, "producer");
        synchronized (lock) {
            if (closed) throw new IllegalStateException("cache closed");
            Cached<V> hit = cache.get(key);
            if (hit != null) {
                counts[HITS]++;
                return hit.value;
            }
        }
        V value = Objects.requireNonNull(producer.produce(key, NEVER), "producer returned null");
        long cost = cost(value);
        List<Eviction<K, V>> evicted = new ArrayList<Eviction<K, V>>();
        List<Runnable> unqueue = new ArrayList<Runnable>(1);
        List<Sub<K, V>> waiting = null;
        V result = value;
        synchronized (lock) {
            if (closed) throw new IllegalStateException("cache closed");
            Cached<V> existing = cache.get(key);
            if (existing != null) {
                result = existing.value;
            } else {
                publish(key, value, cost, evicted);
                Flight<K, V> queued = flights.get(key);
                if (queued != null && queued.state == FlightState.QUEUED) {
                    flights.remove(key);
                    queued.state = FlightState.DONE;
                    if (queued.task != null) unqueue.add(queued.task);
                    queued.producer = null;
                    waiting = drain(queued);
                }
            }
        }
        removeFromQueue(unqueue);
        notifyEvicted(evicted);
        if (waiting != null) deliverAll(waiting, result, null);
        return result;
    }

    /** Increments on every published value, so a host can tell that something new became available. */
    public long version() {
        synchronized (lock) {
            return version;
        }
    }

    public Stats stats() {
        synchronized (lock) {
            long[] values = new long[COUNTERS];
            System.arraycopy(counts, 0, values, 0, COUNTERS);
            values[DELIVERED] = delivered.get();
            values[STALE] = stale.get();
            values[LISTENER_FAILURES] = listenerFailures.get();
            return new Stats(values, version, cache.size(), weight, flights.size());
        }
    }

    public boolean isClosed() { return closed; }

    /** Drops everything: no subscriber is called again, queued work never runs, running producers see cancellation. */
    @Override public void close() {
        List<Eviction<K, V>> evicted = new ArrayList<Eviction<K, V>>();
        List<Runnable> unqueue = new ArrayList<Runnable>();
        synchronized (lock) {
            if (closed) return;
            closed = true;
            for (Map.Entry<K, Cached<V>> entry : cache.entrySet()) {
                evicted.add(new Eviction<K, V>(entry.getKey(), entry.getValue().value, EvictionReason.CLOSED));
            }
            cache.clear();
            weight = 0;
            for (Flight<K, V> flight : flights.values()) {
                flight.cancelled = true;
                // Collect the task so a queued task can still be removed from a ThreadPoolExecutor
                // queue below. The field itself is left alone: the task is assigned outside the
                // lock, and clearing it here could race with request()'s execute call.
                if (flight.state == FlightState.QUEUED && flight.task != null) unqueue.add(flight.task);
                flight.producer = null;
                for (Sub<K, V> sub : flight.subscribers) {
                    sub.cancelled = true;
                    // close() is terminal for every subscriber, including ones a caller still holds
                    // a Ticket for; break the Ticket -> Sub -> Flight -> producer/cache chain.
                    sub.subscriber = null;
                    sub.flight = null;
                }
                flight.subscribers.clear();
            }
            flights.clear();
        }
        removeFromQueue(unqueue);
        notifyEvicted(evicted);
    }

    private void run(Flight<K, V> flight) {
        Producer<K, V> producer;
        synchronized (lock) {
            if (flight.state != FlightState.QUEUED) {
                // Superseded (computeNow) or re-entered: nothing is going to consume it.
                flight.producer = null;
                flight.task = null;
                return;
            }
            if (closed || flight.subscribers.isEmpty()) {
                if (flights.get(flight.key) == flight) flights.remove(flight.key);
                flight.state = FlightState.DONE;
                flight.producer = null;
                flight.task = null;
                if (!closed) counts[ABANDONED]++;
                return;
            }
            flight.state = FlightState.RUNNING;
            // Read under the lock: close() may clear producer while produce() runs, but this local
            // keeps the producer alive for the duration and the produce call itself is unsynchronized.
            producer = flight.producer;
        }
        V value = null;
        long cost = 0;
        Throwable failure = null;
        try {
            value = Objects.requireNonNull(producer.produce(flight.key, new Cancellation() {
                @Override public boolean isCancelled() { return closed || flight.cancelled; }
            }), "producer returned null");
            cost = cost(value);
        } catch (Throwable thrown) {
            failure = thrown;
        }
        List<Eviction<K, V>> evicted = new ArrayList<Eviction<K, V>>();
        List<Sub<K, V>> targets;
        synchronized (lock) {
            flight.state = FlightState.DONE;
            flight.producer = null;
            flight.task = null;
            if (flights.get(flight.key) == flight) flights.remove(flight.key);
            if (failure != null) {
                counts[FAILURES]++;
            } else if (!closed) {
                publish(flight.key, value, cost, evicted);
            }
            targets = drain(flight);
        }
        notifyEvicted(evicted);
        deliverAll(targets, value, failure);
        if (failure instanceof Error) throw (Error) failure;
    }

    /** Caller holds the lock. */
    private void publish(K key, V value, long cost, List<Eviction<K, V>> evicted) {
        Cached<V> previous = cache.put(key, new Cached<V>(value, cost));
        if (previous != null) {
            weight -= previous.weight;
            evicted.add(new Eviction<K, V>(key, previous.value, EvictionReason.REPLACED));
        }
        weight += cost;
        version++;
        counts[PRODUCED]++;
        Iterator<Map.Entry<K, Cached<V>>> eldest = cache.entrySet().iterator();
        while (eldest.hasNext() && (cache.size() > maxEntries || weight > maxWeight)) {
            Map.Entry<K, Cached<V>> entry = eldest.next();
            eldest.remove();
            weight -= entry.getValue().weight;
            counts[EVICTIONS]++;
            evicted.add(new Eviction<K, V>(entry.getKey(), entry.getValue().value, EvictionReason.CAPACITY));
        }
    }

    /**
     * Caller holds the lock. Removes the oldest queued flight that nobody waits for and returns it so
     * the caller can take its task out of a thread pool queue, or null when there is none.
     */
    private Flight<K, V> dropOneAbandoned() {
        for (Iterator<Flight<K, V>> it = flights.values().iterator(); it.hasNext(); ) {
            Flight<K, V> flight = it.next();
            if (flight.state == FlightState.QUEUED && flight.subscribers.isEmpty()) {
                it.remove();
                flight.state = FlightState.DONE;
                flight.producer = null;
                counts[ABANDONED]++;
                return flight;
            }
        }
        return null;
    }

    /** Caller holds the lock. Hands out the subscribers that still want the result. */
    private List<Sub<K, V>> drain(Flight<K, V> flight) {
        List<Sub<K, V>> targets = new ArrayList<Sub<K, V>>(flight.subscribers.size());
        for (Sub<K, V> sub : flight.subscribers) if (!sub.cancelled) targets.add(sub);
        flight.subscribers.clear();
        return targets;
    }

    private boolean cancel(Sub<?, V> untyped) {
        @SuppressWarnings("unchecked")
        Sub<K, V> sub = (Sub<K, V>) untyped;
        synchronized (lock) {
            if (sub.cancelled || sub.delivered) return false;
            sub.cancelled = true;
            counts[CANCELLED]++;
            Flight<K, V> flight = sub.flight;
            if (flight != null && flight.subscribers.remove(sub) && flight.subscribers.isEmpty()
                    && flight.state == FlightState.RUNNING && abandonRunning) {
                flight.cancelled = true;
            }
            // Cancelled means terminal: a retained Ticket must not keep the callback or the flight
            // (and through it the producer and the cache) alive.
            sub.subscriber = null;
            sub.flight = null;
            return true;
        }
    }

    private void deliverAll(List<Sub<K, V>> targets, final V value, final Throwable failure) {
        for (final Sub<K, V> sub : targets) {
            boolean accepted;
            try {
                accepted = owner.post(new Runnable() {
                    @Override public void run() { deliver(sub, value, failure); }
                });
            } catch (Throwable rejected) {
                accepted = false;
            }
            if (!accepted) {
                stale.incrementAndGet();
                // Nobody will deliver this subscriber; drop its outbound references now.
                release(sub);
            }
        }
    }

    /** Clears a terminal subscriber's outbound references. Caller may or may not hold the lock. */
    private void release(Sub<K, V> sub) {
        synchronized (lock) {
            sub.subscriber = null;
            sub.flight = null;
        }
    }

    private void deliver(Sub<K, V> sub, V value, Throwable failure) {
        Subscriber<V> target;
        synchronized (lock) {
            // closed is checked here as well as in close(): a delivery that was drained and posted
            // before close() is not in a flight any more, so close() cannot cancel it. Dropping it
            // keeps the documented "no subscriber is called after close" promise.
            if (sub.cancelled || closed) {
                stale.incrementAndGet();
                release(sub);
                return;
            }
            sub.delivered = true;
            target = sub.subscriber;
        }
        try {
            if (target == null || !target.isCurrent()) {
                stale.incrementAndGet();
                return;
            }
            delivered.incrementAndGet();
            if (failure == null) target.onReady(value);
            else target.onFailed(failure);
        } finally {
            // Terminal in every path above, including a subscriber that throws: the ticket must not
            // keep the callback or the flight alive after the callback returned.
            release(sub);
        }
    }

    private long cost(V value) {
        if (weigher == null) return 1;
        long cost = weigher.weigh(value);
        if (cost < 0) throw new IllegalStateException("weigher returned a negative weight");
        return cost;
    }

    private void removeFromQueue(List<Runnable> tasks) {
        if (tasks == null || tasks.isEmpty() || !(workers instanceof ThreadPoolExecutor)) return;
        ThreadPoolExecutor pool = (ThreadPoolExecutor) workers;
        for (Runnable task : tasks) pool.remove(task);
    }

    private void notifyEvicted(List<Eviction<K, V>> evicted) {
        if (listener == null) return;
        for (Eviction<K, V> eviction : evicted) {
            try {
                listener.onEvicted(eviction.key, eviction.value, eviction.reason);
            } catch (Throwable failure) {
                listenerFailures.incrementAndGet();
            }
        }
    }

    private static final class Eviction<K, V> {
        final K key;
        final V value;
        final EvictionReason reason;
        Eviction(K key, V value, EvictionReason reason) {
            this.key = key;
            this.value = value;
            this.reason = reason;
        }
    }
}
