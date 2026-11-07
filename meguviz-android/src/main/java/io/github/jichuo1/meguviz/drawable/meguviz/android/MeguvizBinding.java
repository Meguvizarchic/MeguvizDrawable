package io.github.jichuo1.meguviz.drawable.meguviz.android;

import io.github.jichuo1.meguviz.android.runtime.core.BindingLease;
import io.github.jichuo1.meguviz.android.runtime.core.BoundedMailbox;
import io.github.jichuo1.meguviz.android.runtime.core.EpochDomain;
import io.github.jichuo1.meguviz.android.runtime.core.LifecycleScope;
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher;
import io.github.jichuo1.meguviz.android.runtime.core.Registration;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceSnapshot;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceState;
import io.github.jichuo1.meguviz.drawable.binding.BindingEnvironment;
import io.github.jichuo1.meguviz.drawable.binding.BindingScope;
import io.github.jichuo1.meguviz.drawable.binding.RequestStamp;
import io.github.jichuo1.meguviz.drawable.binding.SlotBinding;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connects one drawable {@link BindingScope} to the three MeguvizAndroid authorities that already
 * decide when work may run: the {@link LifecycleScope} (attached, started, visible, rebind), the
 * {@link ResourceState} (configuration and theme epochs) and, optionally, a {@link BoundedMailbox}
 * driven by the shared {@code FrameScheduler}. The bridge adds no thread, handler or frame
 * callback of its own and copies no MeguvizAndroid state: it projects it.
 *
 * <p><b>Projection.</b> {@link #project()} turns the resource and theme epochs plus a caller supplied
 * registry revision into the {@link BindingEnvironment} of the drawable scope. The sum of the two
 * epochs only ever grows, so any invalidation changes it.</p>
 *
 * <p><b>Two gates.</b> A {@link Request} carries the drawable scope's stamp and a MeguvizAndroid
 * {@link BindingLease}. A result installs only if both agree: the lease token must still be current
 * (attach, rebind and resource epochs) and the lifecycle active, and the drawable scope must accept
 * the stamp. Everything else is released, never installed.</p>
 *
 * <p><b>Lifecycle.</b> The bridge cannot observe lifecycle transitions, so {@link #sync()} aligns the
 * drawable scope with the lifecycle on demand: it is called by {@link #begin} and the commit paths
 * and may be called by hosts after they change the lifecycle. An inactive lifecycle suspends the
 * scope (slots stay installed); closing the lifecycle closes the bridge and its scope.</p>
 *
 * <p>All methods except {@link #post} must run on the lifecycle's owner thread.</p>
 */
public final class MeguvizBinding implements AutoCloseable {
    /** Supplies the revision of the host's provider registry; it must change when the registry does. */
    public interface RegistrySource {
        long revision();
    }

    /** Outcome of an update. Every outcome except {@link #COMMITTED} released the result. */
    public enum UpdateResult {
        COMMITTED,
        /** Superseded, or prepared before an attach, rebind, resource or registry change. */
        STALE,
        /** The lifecycle is not active (detached, stopped or hidden). */
        INACTIVE,
        /** The installer declined the result, for example because the host took the slot over. */
        REJECTED,
        CLOSED
    }

    /** Receives the outcome of a posted update on the owner thread. */
    public interface Listener {
        void onResult(UpdateResult result);

        /** The installer or releaser threw; the result is released. */
        void onFailure(Throwable failure);
    }

    /** One preparation request. Single use; hand it to exactly one commit or post. */
    public static final class Request {
        private final MeguvizBinding owner;
        private final RequestStamp stamp;
        private final BindingLease lease;
        private final BoundedMailbox.Slot slot;
        private final AtomicBoolean used = new AtomicBoolean();

        private Request(MeguvizBinding owner, RequestStamp stamp, BindingLease lease, BoundedMailbox.Slot slot) {
            this.owner = owner;
            this.stamp = stamp;
            this.lease = lease;
            this.slot = slot;
        }

        /** The stamp for workers; it holds no Android object. */
        public RequestStamp stamp() { return stamp; }
    }

    /** Result of {@link #post}. */
    public static final class Posting {
        private final BoundedMailbox.Admission admission;
        private final BoundedMailbox.WorkHandle handle;

        private Posting(BoundedMailbox.Admission admission, BoundedMailbox.WorkHandle handle) {
            this.admission = admission;
            this.handle = handle;
        }

        public BoundedMailbox.Admission admission() { return admission; }

        /** Null unless the mailbox accepted the update. */
        public BoundedMailbox.WorkHandle handle() { return handle; }

        public boolean accepted() { return handle != null; }
    }

    private static final int MAX_SLOTS = 64;

    private final LifecycleScope lifecycle;
    private final ResourceState resources;
    private final BoundedMailbox mailbox;
    private final RegistrySource registry;
    private final OwnerDispatcher dispatcher;
    private final BindingScope scope;
    private final Registration registration;
    private final Map<String, BoundedMailbox.Slot> slots = new HashMap<String, BoundedMailbox.Slot>();
    /**
     * The resource and theme domains of {@link #resources}, captured once. {@code ResourceState}
     * holds them in final fields, so the array contents never change; {@link BindingLease} clones
     * the array it is given, so sharing this one is safe.
     */
    private final EpochDomain[] allDependencies;
    /** Last stamp observed for each domain of {@link #allDependencies}; null until first read. */
    private final EpochDomain.Stamp[] dependencyStamps;
    private long projectedResourceRevision;
    private long projectedRegistryRevision;
    private BindingEnvironment projectedEnvironment;
    private boolean closed;

    private MeguvizBinding(LifecycleScope lifecycle, ResourceState resources, BoundedMailbox mailbox,
            RegistrySource registry, long ownerEpoch) {
        this.lifecycle = lifecycle;
        this.resources = resources;
        this.mailbox = mailbox;
        this.registry = registry;
        this.dispatcher = lifecycle.dispatcher();
        dispatcher.checkOwner();
        allDependencies = resources.dependencies(ResourceState.ALL);
        dependencyStamps = new EpochDomain.Stamp[allDependencies.length];
        scope = new BindingScope(ownerEpoch);
        scope.bind(project());
        registration = lifecycle.own(new Registration(dispatcher, new Runnable() {
            @Override public void run() { close(); }
        }));
    }

    /**
     * @param mailbox optional; required only for {@link #post}. It must belong to {@code lifecycle}
     *        and be driven by the application's single frame scheduler.
     * @param ownerEpoch host generation of the owner; stamped into every request
     */
    public static MeguvizBinding create(LifecycleScope lifecycle, ResourceState resources, BoundedMailbox mailbox,
            RegistrySource registry, long ownerEpoch) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(registry, "registry");
        MeguvizBinding binding = new MeguvizBinding(lifecycle, resources, mailbox, registry, ownerEpoch);
        binding.sync();
        return binding;
    }

    /** The drawable scope; create slots with {@link BindingScope#slot} and own resources through it. */
    public BindingScope scope() { return scope; }

    /**
     * The current environment: resource plus theme epochs and the registry revision.
     *
     * <p>The domains are final and their stamps are immutable, so a stamp is re-read only when
     * {@link EpochDomain#matches} reports a change. An unchanged projection returns the same
     * immutable {@link BindingEnvironment} instance instead of allocating a new one; a caller that
     * keeps the old instance still holds a valid snapshot, and {@link BindingScope} compares by
     * value. This keeps the per-request path (begin/commit/sync) free of the array, stamp and
     * environment allocations the uncached projection would make.
     */
    public BindingEnvironment project() {
        dispatcher.checkOwner();
        EpochDomain[] domains = allDependencies;
        EpochDomain.Stamp[] stamps = dependencyStamps;
        long resourceRevision = 0;
        for (int i = 0; i < domains.length; i++) {
            EpochDomain domain = domains[i];
            EpochDomain.Stamp stamp = stamps[i];
            if (!domain.matches(stamp)) {
                stamp = domain.stamp();
                stamps[i] = stamp;
            }
            resourceRevision += stamp.epoch();
        }
        resourceRevision &= Long.MAX_VALUE;
        long registryRevision = registry.revision();
        BindingEnvironment cached = projectedEnvironment;
        if (cached != null && resourceRevision == projectedResourceRevision
                && registryRevision == projectedRegistryRevision) {
            return cached;
        }
        BindingEnvironment environment = new BindingEnvironment(resourceRevision, registryRevision);
        projectedResourceRevision = resourceRevision;
        projectedRegistryRevision = registryRevision;
        projectedEnvironment = environment;
        return environment;
    }

    /** The density the authoritative snapshot describes, in the unit {@code RecipeDrawable} expects. */
    public float density() {
        dispatcher.checkOwner();
        ResourceSnapshot snapshot = resources.snapshot();
        return snapshot.densityDpi / 160f;
    }

    /** True while the lifecycle is active and the drawable scope accepts new requests. */
    public boolean isActive() {
        sync();
        return !closed && scope.state() == BindingScope.State.BOUND;
    }

    /**
     * Aligns the drawable scope with the lifecycle and the projected environment. Returns true if
     * anything changed (suspend, resume, environment update or close).
     */
    public boolean sync() {
        dispatcher.checkOwner();
        if (closed) return false;
        if (lifecycle.admissionClosed() || lifecycle.isClosed()) {
            close();
            return true;
        }
        BindingEnvironment environment;
        try {
            environment = project();
        } catch (IllegalStateException resourcesClosed) {
            close();
            return true;
        }
        if (lifecycle.isActive()) {
            if (scope.state() == BindingScope.State.SUSPENDED) return scope.resume(environment);
            return scope.updateEnvironment(environment);
        }
        return scope.suspend();
    }

    /**
     * Starts a request for the latest update of {@code key}. Requires an active lifecycle.
     * A newer request for the same key makes older ones stale.
     */
    public Request begin(String key) {
        dispatcher.checkOwner();
        sync();
        requireOpen();
        RequestStamp stamp = scope.begin(key);
        BindingLease lease = lifecycle.lease(LifecycleScope.ALL, allDependencies);
        return new Request(this, stamp, lease, mailbox == null ? null : slotFor(key));
    }

    /**
     * Installs {@code result} now, on the owner thread. The result is released unless installed.
     * An installer or releaser exception is rethrown after the result was released; unlike
     * {@link LifecycleScope#commit} it does not close the lifecycle.
     */
    public <R> UpdateResult commit(Request request, R result, BindingScope.Installer<R> installer,
            SlotBinding.Releaser<R> releaser) {
        dispatcher.checkOwner();
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(installer, "installer");
        Objects.requireNonNull(releaser, "releaser");
        claim(request);
        try {
            if (closed || syncReleasingOnFailure(result, releaser)) {
                releaser.release(result);
                return UpdateResult.CLOSED;
            }
            final UpdateResult[] outcome = new UpdateResult[1];
            final Throwable[] failure = new Throwable[1];
            LifecycleScope.CommitResult gate = lifecycle.commit(request.lease.token(), new Runnable() {
                @Override public void run() {
                    try {
                        outcome[0] = installUnderGate(request, result, installer, releaser);
                    } catch (Throwable error) {
                        // Keep the lifecycle open: its commit would close the whole scope on a throw.
                        failure[0] = error;
                    }
                }
            });
            switch (gate) {
                case COMPLETED:
                    if (failure[0] != null) throw rethrow(failure[0]);
                    return outcome[0];
                case STALE:
                    releaser.release(result);
                    return UpdateResult.STALE;
                case INACTIVE:
                    releaser.release(result);
                    return UpdateResult.INACTIVE;
                default:
                    releaser.release(result);
                    throw new IllegalStateException("lifecycle commit failed: " + lifecycle.lastFailure());
            }
        } finally {
            request.lease.close();
        }
    }

    /**
     * Hands a result computed on any thread to the mailbox, which installs it on the owner thread in
     * a frame, or never. At most one update per key waits: a newer post replaces an older one.
     *
     * <p>The releaser also runs when the update is replaced, rejected or dropped, which can happen on
     * the posting thread, so it must be safe to call from any thread. An installer failure goes to
     * {@code listener} if there is one; without a listener it propagates into the mailbox, which
     * treats it as fatal.</p>
     */
    public <R> Posting post(Request request, final R result, final BindingScope.Installer<R> installer,
            final SlotBinding.Releaser<R> releaser, final Listener listener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(installer, "installer");
        Objects.requireNonNull(releaser, "releaser");
        if (mailbox == null) throw new IllegalStateException("created without a mailbox");
        claim(request);
        final MeguvizBinding self = this;
        final Request item = request;
        final AtomicBoolean ran = new AtomicBoolean();
        Runnable action = new Runnable() {
            @Override public void run() {
                ran.set(true);
                try {
                    UpdateResult outcome = self.installFromMailbox(item, result, installer, releaser);
                    if (listener != null) listener.onResult(outcome);
                } catch (Throwable failure) {
                    if (listener == null) throw rethrow(failure);
                    listener.onFailure(failure);
                } finally {
                    item.lease.close();
                }
            }
        };
        Runnable release = new Runnable() {
            @Override public void run() {
                if (!ran.get()) releaser.release(result);
                if (dispatcher.isOwnerThread()) item.lease.close();
            }
        };
        BoundedMailbox.Submission submission = mailbox.offerState(request.slot, request.lease.token(), action, release);
        if (!submission.accepted()) releaser.release(result);
        return new Posting(submission.admission(), submission.handle());
    }

    @Override public void close() {
        dispatcher.checkOwner();
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try {
            scope.close();
        } catch (RuntimeException error) {
            failure = error;
        }
        if (!registration.isClosed()) {
            try {
                lifecycle.release(registration);
            } catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private <R> UpdateResult installFromMailbox(Request request, R result, BindingScope.Installer<R> installer,
            SlotBinding.Releaser<R> releaser) {
        dispatcher.checkOwner();
        if (closed || syncReleasingOnFailure(result, releaser)) {
            releaser.release(result);
            return UpdateResult.CLOSED;
        }
        // The mailbox already checked the lease token and the lifecycle; the drawable scope decides last.
        return installUnderGate(request, result, installer, releaser);
    }

    /** Syncs; if that throws, the result is released first. Returns whether the binding is now closed. */
    private <R> boolean syncReleasingOnFailure(R result, SlotBinding.Releaser<R> releaser) {
        try {
            sync();
        } catch (RuntimeException failure) {
            releaseAfterFailure(result, releaser, failure);
            throw failure;
        } catch (Error failure) {
            releaseAfterFailure(result, releaser, failure);
            throw failure;
        }
        return closed;
    }

    private static <R> void releaseAfterFailure(R result, SlotBinding.Releaser<R> releaser, Throwable failure) {
        try {
            releaser.release(result);
        } catch (RuntimeException secondary) {
            failure.addSuppressed(secondary);
        } catch (Error secondary) {
            failure.addSuppressed(secondary);
        }
    }

    private <R> UpdateResult installUnderGate(Request request, R result, BindingScope.Installer<R> installer,
            SlotBinding.Releaser<R> releaser) {
        switch (scope.commit(request.stamp, result, installer, releaser)) {
            case COMMITTED: return UpdateResult.COMMITTED;
            case STALE: return UpdateResult.STALE;
            case NOT_BOUND: return UpdateResult.INACTIVE;
            case REJECTED: return UpdateResult.REJECTED;
            case CLOSED: return UpdateResult.CLOSED;
            default: throw new IllegalStateException("stamp from a foreign scope");
        }
    }

    private void claim(Request request) {
        Objects.requireNonNull(request, "request");
        if (request.owner != this) throw new IllegalArgumentException("request belongs to another binding");
        if (!request.used.compareAndSet(false, true)) throw new IllegalStateException("request already used");
    }

    private BoundedMailbox.Slot slotFor(String key) {
        BoundedMailbox.Slot slot = slots.get(key);
        if (slot == null) {
            if (slots.size() >= MAX_SLOTS) throw new IllegalStateException("mailbox slot budget exceeded");
            slot = mailbox.newSlot();
            slots.put(key, slot);
        }
        return slot;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("binding closed");
    }

    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException) return (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        return new IllegalStateException(failure);
    }
}
