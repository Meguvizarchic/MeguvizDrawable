package io.github.jichuo1.meguviz.drawable.meguviz.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.jichuo1.meguviz.android.runtime.core.BoundedMailbox;
import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver;
import io.github.jichuo1.meguviz.android.runtime.core.FrameScheduler;
import io.github.jichuo1.meguviz.android.runtime.core.LifecycleScope;
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceSnapshot;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceState;
import io.github.jichuo1.meguviz.drawable.binding.BindingEnvironment;
import io.github.jichuo1.meguviz.drawable.binding.BindingScope;
import io.github.jichuo1.meguviz.drawable.binding.SlotBinding;
import io.github.jichuo1.meguviz.drawable.meguviz.android.MeguvizBinding.UpdateResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The bridge against the real MeguvizAndroid runtime, with a hand-driven owner thread and frame source. */
public final class MeguvizBindingTest {
    /** Owner thread is the test thread; work posted from other threads runs when the test drains. */
    private static final class Dispatcher implements OwnerDispatcher {
        private final Thread owner = Thread.currentThread();
        private final ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
        @Override public boolean isOwnerThread() { return Thread.currentThread() == owner; }
        @Override public synchronized boolean post(Runnable callback) { queue.add(callback); return true; }
        @Override public synchronized void remove(Runnable callback) { queue.remove(callback); }
        void drain() {
            while (true) {
                Runnable next;
                synchronized (this) { next = queue.poll(); }
                if (next == null) return;
                next.run();
            }
        }
    }

    private static final class Driver implements FrameDriver {
        private Callback pending;
        @Override public boolean request(Callback callback) { pending = callback; return true; }
        @Override public void remove(Callback callback) { if (pending == callback) pending = null; }
        void frame() { Callback callback = pending; pending = null; if (callback != null) callback.onFrame(16_000_000L); }
    }

    private static final class Res {
        final String name;
        final List<String> log;
        int released;
        Res(String name, List<String> log) { this.name = name; this.log = log; }
    }

    private static final SlotBinding.Releaser<Res> RELEASE = new SlotBinding.Releaser<Res>() {
        @Override public synchronized void release(Res value) { value.released++; value.log.add("release:" + value.name); }
    };

    private static ResourceSnapshot snapshot(int dpi) {
        return new ResourceSnapshot(dpi, 1f, "en", 0, 0, 360, 640, 0, "base");
    }

    private Dispatcher dispatcher;
    private Driver driver;
    private FrameScheduler scheduler;
    private LifecycleScope lifecycle;
    private ResourceState resources;
    private BoundedMailbox mailbox;
    private final AtomicLong registryRevision = new AtomicLong(1);
    private final AtomicReference<Throwable> sinkError = new AtomicReference<Throwable>();
    private final List<String> log = new ArrayList<String>();
    private MeguvizBinding binding;

    @Before public void setUp() {
        dispatcher = new Dispatcher();
        driver = new Driver();
        scheduler = new FrameScheduler(dispatcher, driver, error -> sinkError.set(error), 8);
        lifecycle = new LifecycleScope(dispatcher, error -> sinkError.set(error));
        resources = new ResourceState(dispatcher, "test", snapshot(320), 4);
        mailbox = new BoundedMailbox(lifecycle, scheduler, error -> sinkError.set(error), 4, 4, 4);
        binding = MeguvizBinding.create(lifecycle, resources, mailbox, () -> registryRevision.get(), 7);
    }

    @After public void tearDown() {
        lifecycle.close();
        resources.close();
        scheduler.close();
        assertNull("no failure reached the error sink", sinkError.get());
    }

    private void activate() {
        lifecycle.setAttached(true);
        lifecycle.setStarted(true);
        assertTrue(binding.sync());
    }

    private Res res(String name) { return new Res(name, log); }

    private static final BindingScope.Installer<Res> ACCEPT = new BindingScope.Installer<Res>() {
        @Override public boolean install(Res result) { return true; }
    };

    @Test public void inactiveLifecycleSuspendsTheScopeUntilItBecomesActive() {
        assertEquals(BindingScope.State.SUSPENDED, binding.scope().state());
        assertFalse(binding.isActive());
        try {
            binding.begin("background");
            fail("requests need an active lifecycle");
        } catch (IllegalStateException expected) {
            // the drawable scope refuses work while suspended
        }
        activate();
        assertEquals(BindingScope.State.BOUND, binding.scope().state());
        assertTrue(binding.isActive());
        assertEquals(7, binding.scope().ownerEpoch());
    }

    @Test public void projectionFollowsResourcesThemeAndRegistry() {
        BindingEnvironment first = binding.project();
        assertEquals(1, first.registryRevision());
        resources.invalidateTheme();
        BindingEnvironment themed = binding.project();
        assertTrue(themed.resourceRevision() > first.resourceRevision());
        resources.update(snapshot(480));
        BindingEnvironment resized = binding.project();
        assertTrue(resized.resourceRevision() > themed.resourceRevision());
        registryRevision.set(5);
        assertEquals(5, binding.project().registryRevision());
        assertEquals(3f, binding.density(), 0f);
        resources.update(snapshot(320));
        assertEquals(2f, binding.density(), 0f);
    }

    @Test public void unchangedProjectionReusesTheImmutableEnvironment() {
        BindingEnvironment first = binding.project();
        assertSame("an unchanged projection returns the same immutable instance", first, binding.project());
        resources.invalidateTheme();
        BindingEnvironment themed = binding.project();
        assertTrue("a theme change produces a new environment", themed != first);
        assertTrue(themed.resourceRevision() > first.resourceRevision());
        assertSame("the new projection is reused until something changes", themed, binding.project());
        registryRevision.incrementAndGet();
        BindingEnvironment registry = binding.project();
        assertTrue("a registry change produces a new environment", registry != themed);
        assertEquals(registryRevision.get(), registry.registryRevision());
        assertSame(registry, binding.project());
    }

    @Test public void commitInstallsThroughBothGates() {
        activate();
        MeguvizBinding.Request request = binding.begin("background");
        Res result = res("a");
        assertEquals(UpdateResult.COMMITTED, binding.commit(request, result, ACCEPT, RELEASE));
        assertEquals(0, result.released);
        try {
            binding.commit(request, res("again"), ACCEPT, RELEASE);
            fail("a request is single use");
        } catch (IllegalStateException expected) {
            // consumed
        }
    }

    @Test public void stoppedLifecycleIsInactiveAndDetachedWorkIsStale() {
        activate();
        MeguvizBinding.Request early = binding.begin("background");
        lifecycle.setStarted(false);
        Res whileStopped = res("stopped");
        // Stopping changes no epoch: the token is still current, the lifecycle just is not active.
        assertEquals(UpdateResult.INACTIVE, binding.commit(early, whileStopped, ACCEPT, RELEASE));
        assertEquals(1, whileStopped.released);
        assertEquals(BindingScope.State.SUSPENDED, binding.scope().state());

        lifecycle.setStarted(true);
        MeguvizBinding.Request fresh = binding.begin("background");
        assertEquals(UpdateResult.COMMITTED, binding.commit(fresh, res("fresh"), ACCEPT, RELEASE));

        MeguvizBinding.Request beforeDetach = binding.begin("background");
        lifecycle.setAttached(false);
        Res detached = res("detached");
        // Detaching bumps the attach epoch, which the lease token notices before activity is checked.
        assertEquals(UpdateResult.STALE, binding.commit(beforeDetach, detached, ACCEPT, RELEASE));
        assertEquals(1, detached.released);
        assertEquals(BindingScope.State.SUSPENDED, binding.scope().state());

        lifecycle.setAttached(true);
        MeguvizBinding.Request afterReattach = binding.begin("background");
        assertEquals(UpdateResult.COMMITTED, binding.commit(afterReattach, res("reattached"), ACCEPT, RELEASE));

        MeguvizBinding.Request beforeFlap = binding.begin("background");
        lifecycle.setAttached(false);
        lifecycle.setAttached(true);
        Res flapped = res("flapped");
        // Never observed inactive: only the lease token can tell that an attach epoch passed.
        assertEquals(UpdateResult.STALE, binding.commit(beforeFlap, flapped, ACCEPT, RELEASE));
        assertEquals(1, flapped.released);
    }

    @Test public void rebindAndResourceChangesInvalidateOutstandingRequests() {
        activate();
        MeguvizBinding.Request rebound = binding.begin("a");
        lifecycle.rebind();
        Res first = res("rebound");
        assertEquals(UpdateResult.STALE, binding.commit(rebound, first, ACCEPT, RELEASE));
        assertEquals(1, first.released);

        MeguvizBinding.Request themed = binding.begin("a");
        resources.invalidateTheme();
        Res second = res("themed");
        assertEquals(UpdateResult.STALE, binding.commit(themed, second, ACCEPT, RELEASE));
        assertEquals(1, second.released);

        MeguvizBinding.Request registry = binding.begin("a");
        registryRevision.incrementAndGet();
        Res third = res("registry");
        assertEquals(UpdateResult.STALE, binding.commit(registry, third, ACCEPT, RELEASE));
        assertEquals(1, third.released);

        MeguvizBinding.Request current = binding.begin("a");
        assertEquals(UpdateResult.COMMITTED, binding.commit(current, res("current"), ACCEPT, RELEASE));
    }

    @Test public void newerRequestForTheSameKeySupersedesAndOtherKeysAreIndependent() {
        activate();
        MeguvizBinding.Request older = binding.begin("a");
        MeguvizBinding.Request newer = binding.begin("a");
        MeguvizBinding.Request other = binding.begin("b");
        Res olderResult = res("older");
        assertEquals(UpdateResult.STALE, binding.commit(older, olderResult, ACCEPT, RELEASE));
        assertEquals(1, olderResult.released);
        assertEquals(UpdateResult.COMMITTED, binding.commit(newer, res("newer"), ACCEPT, RELEASE));
        assertEquals(UpdateResult.COMMITTED, binding.commit(other, res("other"), ACCEPT, RELEASE));
    }

    @Test public void decliningAndFailingInstallersReleaseWithoutClosingTheLifecycle() {
        activate();
        Res declined = res("declined");
        assertEquals(UpdateResult.REJECTED, binding.commit(binding.begin("a"), declined,
                new BindingScope.Installer<Res>() { @Override public boolean install(Res r) { return false; } }, RELEASE));
        assertEquals(1, declined.released);

        Res failing = res("failing");
        try {
            binding.commit(binding.begin("a"), failing, new BindingScope.Installer<Res>() {
                @Override public boolean install(Res r) { throw new IllegalArgumentException("installer failed"); }
            }, RELEASE);
            fail("installer failure propagates");
        } catch (IllegalArgumentException expected) {
            assertEquals("installer failed", expected.getMessage());
        }
        assertEquals(1, failing.released);
        assertFalse("the lifecycle survives an installer failure", lifecycle.isClosed());
        assertTrue(binding.isActive());
        assertEquals(UpdateResult.COMMITTED, binding.commit(binding.begin("a"), res("after"), ACCEPT, RELEASE));
    }

    @Test public void aFailingSyncReleasesTheResultBeforePropagating() {
        activate();
        MeguvizBinding.Request request = binding.begin("a");
        registryRevision.set(-1);
        Res result = res("unsyncable");
        try {
            binding.commit(request, result, ACCEPT, RELEASE);
            fail("an invalid registry revision cannot be projected");
        } catch (IllegalArgumentException expected) {
            // negative revision
        }
        assertEquals(1, result.released);
        registryRevision.set(1);
        assertEquals(UpdateResult.COMMITTED, binding.commit(binding.begin("a"), res("recovered"), ACCEPT, RELEASE));
    }

    @Test public void requestsBelongToOneBinding() {
        activate();
        LifecycleScope otherLifecycle = new LifecycleScope(dispatcher, error -> sinkError.set(error));
        ResourceState otherResources = new ResourceState(dispatcher, "other", snapshot(320), 2);
        MeguvizBinding other = MeguvizBinding.create(otherLifecycle, otherResources, null, () -> 1L, 1);
        otherLifecycle.setAttached(true);
        otherLifecycle.setStarted(true);
        MeguvizBinding.Request foreign = other.begin("a");
        try {
            binding.commit(foreign, res("foreign"), ACCEPT, RELEASE);
            fail("foreign request");
        } catch (IllegalArgumentException expected) {
            // rejected before any state changed
        }
        assertEquals(UpdateResult.COMMITTED, other.commit(foreign, res("own"), ACCEPT, RELEASE));
        otherLifecycle.close();
        otherResources.close();
    }

    @Test public void closingTheLifecycleClosesTheScopeAndClearsOwnedSlots() {
        activate();
        final Object[] holder = new Object[1];
        SlotBinding.Slot<Object> slot = new SlotBinding.Slot<Object>() {
            @Override public Object get() { return holder[0]; }
            @Override public void set(Object value) { holder[0] = value; }
        };
        SlotBinding<Object> owned = binding.scope().slot("background", slot, null);
        Object installed = new Object();
        assertTrue(owned.install(null, installed));
        MeguvizBinding.Request pending = binding.begin("background");
        lifecycle.close();
        assertEquals(BindingScope.State.CLOSED, binding.scope().state());
        assertNull("the slot was cleared with the lifecycle", holder[0]);
        try {
            binding.begin("background");
            fail("closed");
        } catch (IllegalStateException expected) {
            // closed
        }
        Res late = res("late");
        assertEquals(UpdateResult.CLOSED, binding.commit(pending, late, ACCEPT, RELEASE));
        assertEquals(1, late.released);
        binding.close();
        assertFalse(binding.sync());
    }

    @Test public void closingTheBindingDetachesFromTheLifecycle() {
        activate();
        binding.close();
        binding.close();
        assertEquals(BindingScope.State.CLOSED, binding.scope().state());
        assertFalse("closing the binding leaves the lifecycle open", lifecycle.isClosed());
        assertEquals(0, lifecycle.registrationCount() - 1 /* the mailbox's own */);
    }

    @Test public void postedUpdatesInstallInAFrameAndNewerOnesReplaceOlder() throws Exception {
        activate();
        final MeguvizBinding.Request first = binding.begin("a");
        final MeguvizBinding.Request second = binding.begin("a");
        final Res firstResult = res("first");
        final Res secondResult = res("second");
        final List<UpdateResult> results = new ArrayList<UpdateResult>();
        MeguvizBinding.Listener listener = new MeguvizBinding.Listener() {
            @Override public void onResult(UpdateResult result) { results.add(result); }
            @Override public void onFailure(Throwable failure) { throw new AssertionError(failure); }
        };
        final List<MeguvizBinding.Posting> postings = new ArrayList<MeguvizBinding.Posting>();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                postings.add(binding.post(first, firstResult, ACCEPT, RELEASE, listener));
                postings.add(binding.post(second, secondResult, ACCEPT, RELEASE, listener));
            }
        });
        worker.start();
        worker.join();
        assertTrue(postings.get(0).accepted() && postings.get(1).accepted());
        assertEquals("the older posting was released on the posting thread", 1, firstResult.released);
        assertEquals(0, secondResult.released);
        dispatcher.drain();
        driver.frame();
        assertEquals(1, results.size());
        assertEquals(UpdateResult.COMMITTED, results.get(0));
        assertEquals(0, secondResult.released);
        assertEquals(BoundedMailbox.Outcome.COMPLETED, postings.get(1).handle().outcome());
    }

    @Test public void postedUpdateInvalidatedBeforeTheFrameIsReleasedNotInstalled() throws Exception {
        activate();
        final MeguvizBinding.Request request = binding.begin("a");
        final Res result = res("late");
        final List<UpdateResult> results = new ArrayList<UpdateResult>();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                binding.post(request, result, ACCEPT, RELEASE, new MeguvizBinding.Listener() {
                    @Override public void onResult(UpdateResult r) { results.add(r); }
                    @Override public void onFailure(Throwable failure) { throw new AssertionError(failure); }
                });
            }
        });
        worker.start();
        worker.join();
        lifecycle.rebind();
        dispatcher.drain();
        driver.frame();
        assertTrue("the update never ran", results.isEmpty());
        assertEquals(1, result.released);
    }

    @Test public void postFailureGoesToTheListenerAndKeepsTheMailboxAlive() throws Exception {
        activate();
        final MeguvizBinding.Request request = binding.begin("a");
        final Res result = res("failing");
        final List<Throwable> failures = new ArrayList<Throwable>();
        binding.post(request, result, new BindingScope.Installer<Res>() {
            @Override public boolean install(Res r) { throw new IllegalStateException("boom"); }
        }, RELEASE, new MeguvizBinding.Listener() {
            @Override public void onResult(UpdateResult r) { throw new AssertionError("no result expected"); }
            @Override public void onFailure(Throwable failure) { failures.add(failure); }
        });
        dispatcher.drain();
        driver.frame();
        assertEquals(1, failures.size());
        assertEquals(1, result.released);
        assertFalse(mailbox.isClosed());
        MeguvizBinding.Request next = binding.begin("a");
        Res ok = res("ok");
        binding.post(next, ok, ACCEPT, RELEASE, null);
        dispatcher.drain();
        driver.frame();
        assertEquals(0, ok.released);
    }

    @Test public void postWithoutAMailboxOrAfterCloseIsRefused() throws Exception {
        activate();
        LifecycleScope bare = new LifecycleScope(dispatcher, error -> sinkError.set(error));
        ResourceState bareResources = new ResourceState(dispatcher, "bare", snapshot(320), 2);
        MeguvizBinding noMailbox = MeguvizBinding.create(bare, bareResources, null, () -> 1L, 1);
        bare.setAttached(true);
        bare.setStarted(true);
        MeguvizBinding.Request request = noMailbox.begin("a");
        try {
            noMailbox.post(request, res("x"), ACCEPT, RELEASE, null);
            fail("no mailbox");
        } catch (IllegalStateException expected) {
            // needs a mailbox
        }
        bare.close();
        bareResources.close();

        MeguvizBinding.Request orphan = binding.begin("a");
        Res refused = res("refused");
        mailbox.close();
        MeguvizBinding.Posting posting = binding.post(orphan, refused, ACCEPT, RELEASE, null);
        assertFalse(posting.accepted());
        assertEquals(BoundedMailbox.Admission.REJECTED_CLOSED, posting.admission());
        assertEquals("a refused posting is released by the bridge", 1, refused.released);
        assertNotNull(posting.admission());
        assertSame(null, posting.handle());
    }
}
