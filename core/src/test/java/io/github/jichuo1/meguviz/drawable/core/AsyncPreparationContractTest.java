package io.github.jichuo1.meguviz.drawable.core;

import io.github.jichuo1.meguviz.drawable.binding.BindingEnvironment;
import io.github.jichuo1.meguviz.drawable.binding.BindingScope;
import io.github.jichuo1.meguviz.drawable.binding.RequestStamp;
import io.github.jichuo1.meguviz.drawable.binding.SlotBinding;
import io.github.jichuo1.meguviz.drawable.cache.FlightCache;
import io.github.jichuo1.meguviz.drawable.cache.FlightCache.Status;
import io.github.jichuo1.meguviz.drawable.cache.FlightCache.Ticket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The canonical async-preparation composition, kept as a host contract so it cannot rot. It uses
 * only existing API: {@link BindingScope} for latest-wins and owner-thread installation,
 * {@link FlightCache} for single-flight production and shared results, and the cache's own
 * {@link FlightCache.Subscriber#isCurrent()} for the generation check. There is no new production
 * helper, no new public API, no new thread and no new pool; a host copies the pattern below.
 *
 * <p><b>Ownership.</b> The value {@code V} is produced once, shared by reference with every
 * subscriber, and cached. It must be immutable once produced. Each committed result gets its own
 * owner-side instance (for example its own Drawable); the owner never shares a mutable instance
 * through the cache. The {@link SlotBinding.Releaser} runs when a result is not installed (stale,
 * closed), so it receives a value the cache still holds and other subscribers may still get: a
 * releaser must never destroy {@code V}. Cache-side resources are released through the cache's
 * {@link FlightCache.EvictionListener}, not through the scope.
 *
 * <p><b>Bounded bookkeeping.</b> At most one current submission per slot: a new submission for the
 * same key supersedes the previous one (its ticket is cancelled only after the new request is in,
 * so an in-flight single flight is never killed by its own replacement), and a finished, failed or
 * rejected submission removes its own entry. A rejected submission still supersedes the slot's
 * stamp—`begin` ran first—so the slot retires and the host retries later. The number of distinct
 * keys is the host's explicit scale; this example adds no per-submission state that outlives it.
 *
 * <p>Contracts pinned here: the older of two submissions is never installed while the newer is;
 * several submissions for one key produce once; a cached key installs immediately without a
 * callback; failures and rejections are observable, not swallowed; installation happens on the
 * owner thread and only there; cancelling or closing releases subscriptions but never destroys a
 * shared value.
 */
final class AsyncPreparationContractTest {
    private static int checks;

    static int run() throws Exception {
        latestSubmissionWinsAndTheOlderReadyIsNotInstalled();
        severalSubmissionsForOneKeyProduceOnceAndStayBounded();
        aCachedKeyInstallsImmediatelyWithoutACallback();
        aFailedProducerIsObservableAndCachesNothing();
        aRejectedSubmissionIsObservableAndLeavesNoPhantomSlot();
        aSubmissionRejectedAfterTheCacheClosedRetiresTheSlot();
        installationHappensOnTheOwnerThreadAndOnlyThere();
        cancelAndCloseReleaseSubscriptionsButKeepSharedValues();
        return checks;
    }

    /** Runs tasks only when the test says so. */
    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
        @Override public synchronized void execute(Runnable task) { queue.add(task); }
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
        @Override public synchronized boolean post(Runnable task) { queue.add(task); return true; }
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

    /** Shared immutable payload: produced once, delivered by reference, cached. */
    private static final class Prepared {
        final String key;
        final int generation;
        Prepared(String key, int generation) { this.key = key; this.generation = generation; }
    }

    /** The owner-side instance built from a shared payload; never shared back through the cache. */
    private static final class OwnerInstance {
        final Prepared payload;
        OwnerInstance(Prepared payload) { this.payload = payload; }
    }

    private static final class Env {
        final ManualExecutor workers = new ManualExecutor();
        final ManualPoster owner = new ManualPoster();
        final AtomicInteger produced = new AtomicInteger();
        final FlightCache<String, Prepared> cache;
        final List<OwnerInstance> installed = new ArrayList<OwnerInstance>();
        final List<Prepared> released = new ArrayList<Prepared>();
        final List<String> failures = new ArrayList<String>();
        final BindingScope scope = new BindingScope(0);

        Env() {
            scope.bind(new BindingEnvironment(0, 0));
            cache = FlightCache.<String, Prepared>builder(workers, owner).build();
        }

        FlightCache.Producer<String, Prepared> producer() {
            return new FlightCache.Producer<String, Prepared>() {
                @Override public Prepared produce(String key, FlightCache.Cancellation cancellation) {
                    produced.incrementAndGet();
                    return new Prepared(key, produced.get());
                }
            };
        }

        BindingScope.Installer<Prepared> installer() {
            return new BindingScope.Installer<Prepared>() {
                @Override public boolean install(Prepared result) {
                    installed.add(new OwnerInstance(result));
                    return true;
                }
            };
        }

        SlotBinding.Releaser<Prepared> releaser() {
            return new SlotBinding.Releaser<Prepared>() {
                @Override public void release(Prepared result) { released.add(result); }
            };
        }
    }

    /** One submission: its scope stamp and, once known, the cache ticket. */
    private static final class Submission {
        final RequestStamp stamp;
        Ticket<Prepared> ticket;
        Submission(RequestStamp stamp) { this.stamp = stamp; }
    }

    /**
     * The example. One instance per binding scope; every method is owner-thread only. The scope key
     * is the property a result installs into, so this example uses one property per cache key, which
     * is what a host with one slot per result does; a host with one property for many keys keeps its
     * own latest-wins map keyed by cache key and still checks it from {@code isCurrent}.
     */
    private static final class AsyncPreparation {
        private final BindingScope scope;
        private final FlightCache<String, Prepared> cache;
        private final BindingScope.Installer<Prepared> installer;
        private final SlotBinding.Releaser<Prepared> releaser;
        private final List<String> failures;
        private final Map<String, Submission> current = new HashMap<String, Submission>();

        AsyncPreparation(BindingScope scope, FlightCache<String, Prepared> cache,
                BindingScope.Installer<Prepared> installer, SlotBinding.Releaser<Prepared> releaser,
                List<String> failures) {
            this.scope = scope;
            this.cache = cache;
            this.installer = installer;
            this.releaser = releaser;
            this.failures = failures;
        }

        BindingScope scope() { return scope; }

        /** Current submissions, one per slot; a host bounds the distinct keys it uses. */
        int pendingSubmissions() { return current.size(); }

        /**
         * Owner thread only. Latest submission per key wins; the cache still produces once per key.
         * A superseded submission's ticket is cancelled only after the new request is registered, so
         * replacing a submission never kills the single flight both would have shared. A rejected
         * submission (cache closed or out of flight capacity) still supersedes the slot's stamp, so
         * the slot retires and the host must retry; the rejection is recorded, never swallowed.
         */
        Ticket<Prepared> submit(String key, FlightCache.Producer<String, Prepared> producer) {
            final Submission submission = new Submission(scope.begin(key));
            final Submission previous = current.put(key, submission);
            Ticket<Prepared> ticket = cache.request(key, producer, new FlightCache.Subscriber<Prepared>() {
                @Override public boolean isCurrent() {
                    // Identity against the slot's current submission: a superseded submission is
                    // dropped here, on the owner thread, before anything is installed.
                    return current.get(key) == submission;
                }
                @Override public void onReady(Prepared value) {
                    finish(key, submission);
                    // Owner thread. commit re-validates the stamp and installs in one section; a
                    // stale or closed outcome hands the value to the releaser, which must not
                    // destroy it because the cache still shares it.
                    scope.commit(submission.stamp, value, installer, releaser);
                }
                @Override public void onFailed(Throwable failure) {
                    finish(key, submission);
                    // Never swallowed: the host decides what a failed preparation means.
                    failures.add(key + ": " + failure);
                }
            });
            submission.ticket = ticket;
            Status status = ticket.status();
            if (status == Status.STARTED || status == Status.JOINED) {
                // The new submission owns the slot now; retire the previous one only after the new
                // request is registered, so an in-flight single flight is never killed by its own
                // replacement.
                if (previous != null && previous.ticket != null) previous.ticket.cancel();
            } else if (status == Status.READY) {
                // The value was already cached, so no callback will follow: install it now, on this
                // owner thread, instead of waiting for a delivery that never comes.
                if (previous != null && previous.ticket != null) previous.ticket.cancel();
                finish(key, submission);
                scope.commit(submission.stamp, ticket.value(), installer, releaser);
            } else {
                // Rejected. begin already advanced this slot's latest stamp, so the previous
                // submission can never install again either: retire the slot entirely, release the
                // previous subscription, and make the rejection observable. The host retries when
                // the cache has room; there is no "restore" of a stamp that is already superseded.
                if (previous != null && previous.ticket != null) previous.ticket.cancel();
                current.remove(key);
                failures.add(key + ": " + status);
            }
            return ticket;
        }

        /** Releases this slot's subscription; the cache and its values are untouched. */
        void cancel(String key) {
            Submission submission = current.remove(key);
            if (submission != null && submission.ticket != null) submission.ticket.cancel();
        }

        /** Releases every subscription; the cache and its values are untouched. */
        void cancelSubscriptions() {
            for (Submission submission : current.values()) {
                if (submission.ticket != null) submission.ticket.cancel();
            }
            current.clear();
        }

        /** Releases the subscriptions and closes the scope. The cache and its values are untouched. */
        void close() {
            cancelSubscriptions();
            scope.close();
        }

        private void finish(String key, Submission submission) {
            if (current.get(key) == submission) current.remove(key);
        }
    }

    private static AsyncPreparation preparation(Env env) {
        return new AsyncPreparation(env.scope, env.cache, env.installer(), env.releaser(), env.failures);
    }

    private static void latestSubmissionWinsAndTheOlderReadyIsNotInstalled() {
        Env env = new Env();
        AsyncPreparation prep = preparation(env);
        prep.submit("k", env.producer());
        prep.submit("k", env.producer());
        check(env.workers.pending() == 1, "both submissions share one flight");
        check(prep.pendingSubmissions() == 1, "only the latest submission is current for the slot");
        env.workers.runAll();
        check(env.produced.get() == 1, "the shared key produced once");
        check(env.owner.pending() == 1, "the superseded subscription was cancelled, so one delivery remains");
        env.owner.drain();
        check(env.installed.size() == 1 && env.released.isEmpty(),
                "the older ready is dropped and never installed: installed=" + env.installed.size()
                        + " released=" + env.released.size());
        check(prep.pendingSubmissions() == 0, "a finished delivery removes its own bookkeeping");
        prep.close();
    }

    private static void severalSubmissionsForOneKeyProduceOnceAndStayBounded() {
        Env env = new Env();
        AsyncPreparation prep = preparation(env);
        for (int i = 0; i < 50; i++) prep.submit("tile", env.producer());
        check(prep.pendingSubmissions() == 1, "fifty submissions keep one current entry per slot");
        env.workers.runAll();
        env.owner.drain();
        check(env.produced.get() == 1 && env.installed.size() == 1,
                "fifty submissions produce once and install once: produced=" + env.produced.get()
                        + " installed=" + env.installed.size());
        check(env.failures.isEmpty() && prep.pendingSubmissions() == 0, "and leave nothing behind");
        prep.close();
    }

    private static void aCachedKeyInstallsImmediatelyWithoutACallback() {
        Env env = new Env();
        AsyncPreparation prep = preparation(env);
        prep.submit("k", env.producer());
        env.workers.runAll();
        env.owner.drain();
        check(env.installed.size() == 1, "the first submission installed");
        prep.submit("k", env.producer());
        check(env.installed.size() == 2 && env.produced.get() == 1 && env.owner.pending() == 0,
                "a cached key installs immediately, without producing again or posting a delivery");
        check(prep.pendingSubmissions() == 0, "and leaves no pending submission");
        prep.close();
    }

    private static void aFailedProducerIsObservableAndCachesNothing() {
        Env env = new Env();
        AsyncPreparation prep = preparation(env);
        FlightCache.Producer<String, Prepared> broken = new FlightCache.Producer<String, Prepared>() {
            @Override public Prepared produce(String key, FlightCache.Cancellation cancellation) throws Exception {
                throw new IllegalStateException("render failed");
            }
        };
        prep.submit("bad", broken);
        env.workers.runAll();
        env.owner.drain();
        check(env.failures.size() == 1 && env.failures.get(0).startsWith("bad: "),
                "the failure reaches the host instead of being swallowed: " + env.failures);
        check(env.installed.isEmpty() && env.cache.peek("bad") == null,
                "a failed preparation installs nothing and caches nothing");
        check(prep.pendingSubmissions() == 0, "and cleans up after itself");
        prep.close();
    }

    private static void aRejectedSubmissionIsObservableAndLeavesNoPhantomSlot() {
        Env env = new Env();
        FlightCache<String, Prepared> tight = FlightCache.<String, Prepared>builder(env.workers, env.owner)
                .maxFlights(1).build();
        AsyncPreparation prep = new AsyncPreparation(env.scope, tight,
                env.installer(), env.releaser(), env.failures);
        prep.submit("a", env.producer());
        prep.submit("b", env.producer());
        check(env.failures.size() == 1 && env.failures.get(0).startsWith("b: REJECTED_"),
                "a rejected submission is observable: " + env.failures);
        check(prep.pendingSubmissions() == 1, "and leaves no phantom slot behind");
        env.workers.runAll();
        env.owner.drain();
        check(env.installed.size() == 1 && tight.peek("a") != null && tight.peek("b") == null,
                "the occupying flight still installs; the rejected key was never produced");
        prep.close();
    }

    /**
     * A submission rejected because the cache closed retires the slot: the previous submission's
     * stamp was already superseded by the rejected begin, so it must not install, the slot keeps no
     * reference, and the rejection is observable for the host to retry after reopening.
     */
    private static void aSubmissionRejectedAfterTheCacheClosedRetiresTheSlot() {
        Env env = new Env();
        AsyncPreparation prep = preparation(env);
        prep.submit("k", env.producer());
        check(prep.pendingSubmissions() == 1, "the slot has a pending submission");
        env.cache.close();
        prep.submit("k", env.producer());
        check(prep.pendingSubmissions() == 0, "a rejected submission retires the slot entirely");
        check(env.failures.size() == 1 && env.failures.get(0).startsWith("k: REJECTED_CLOSED"),
                "and the rejection is observable: " + env.failures);
        env.workers.runAll();
        env.owner.drain();
        check(env.installed.isEmpty() && env.released.isEmpty() && env.produced.get() == 0,
                "the old submission installs nothing and releases nothing after the cache closed");
        prep.scope().close();
    }

    private static void installationHappensOnTheOwnerThreadAndOnlyThere() throws Exception {
        Env env = new Env();
        final List<Thread> installing = new ArrayList<Thread>();
        BindingScope.Installer<Prepared> installer = new BindingScope.Installer<Prepared>() {
            @Override public boolean install(Prepared result) {
                installing.add(Thread.currentThread());
                env.installed.add(new OwnerInstance(result));
                return true;
            }
        };
        AsyncPreparation prep = new AsyncPreparation(env.scope, env.cache, installer, env.releaser(), env.failures);
        prep.submit("k", env.producer());
        env.workers.runAll();
        env.owner.drain();
        check(installing.size() == 1 && installing.get(0) == Thread.currentThread(),
                "the result installs on the owner thread");

        prep.submit("k2", env.producer());
        env.workers.runAll();
        final Throwable[] problem = new Throwable[1];
        Thread foreign = new Thread(new Runnable() { public void run() {
            try { env.owner.drain(); } catch (Throwable failure) { problem[0] = failure; }
        }});
        foreign.start();
        foreign.join(5_000);
        check(problem[0] instanceof IllegalStateException,
                "a delivery drained off the owner thread is refused, not installed: " + problem[0]);
        check(env.installed.size() == 1, "the foreign delivery installed nothing");
        prep.close();
    }

    private static void cancelAndCloseReleaseSubscriptionsButKeepSharedValues() {
        Env env = new Env();
        AsyncPreparation prep = preparation(env);

        prep.submit("gone", env.producer());
        prep.cancel("gone");
        check(prep.pendingSubmissions() == 0, "cancelling a slot releases its subscription");
        env.workers.runAll();
        env.owner.drain();
        check(env.released.isEmpty() && env.produced.get() == 0,
                "a flight nobody waits for is dropped without producing or releasing anything");

        prep.submit("kept", env.producer());
        env.workers.runAll();
        env.owner.drain();
        Prepared shared = env.cache.peek("kept");
        check(shared != null && env.installed.size() == 1 && env.installed.get(0).payload == shared,
                "the completed value is cached, installed once, and shared by reference");

        prep.submit("late", env.producer());
        prep.scope().close();   // the scope closes while the flight is in progress
        env.workers.runAll();
        env.owner.drain();
        check(env.released.size() == 1 && env.cache.peek("late") == env.released.get(0),
                "a closed scope releases the result without destroying the shared value");
        check(env.cache.peek("kept") == shared, "earlier shared values survive the scope closing");
        expect(IllegalStateException.class, new Runnable() { public void run() {
            prep.submit("after", env.producer()); }});
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
