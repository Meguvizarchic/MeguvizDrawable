package io.github.jichuo1.meguviz.drawable.core;

import io.github.jichuo1.meguviz.drawable.binding.BindingEnvironment;
import io.github.jichuo1.meguviz.drawable.binding.BindingScope;
import io.github.jichuo1.meguviz.drawable.binding.BindingScope.CommitOutcome;
import io.github.jichuo1.meguviz.drawable.binding.RequestStamp;
import io.github.jichuo1.meguviz.drawable.binding.SlotBinding;
import io.github.jichuo1.meguviz.drawable.binding.SlotBinding.Ensure;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Ownership, idempotence, lifecycle and stale-result contracts of the binding layer. */
final class BindingContractTest {
    private static int checks;

    static int run() {
        slotEnsure();
        scopeLifecycle();
        scopeCommit();
        if (stress()) {
            slotLoss();
            slotRelease();
            slotGuards();
            scopeStaleness();
            scopeClose();
            scopeThreading();
            propertyClaims();
        }
        return checks;
    }

    /** Opt-in extended coverage; the default run keeps a representative subset. */
    private static boolean stress() {
        return Boolean.getBoolean("meguviz.test.stress");
    }

    /** Test double for a host property. */
    private static final class Host implements SlotBinding.Slot<Object> {
        Object value;
        int writes;
        @Override public Object get() { return value; }
        @Override public void set(Object value) { this.value = value; writes++; }
    }

    /** Test double for a prepared resource that counts releases. */
    private static final class Res {
        final String name;
        int released;
        Res(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }

    private static final SlotBinding.Releaser<Object> RELEASE = value -> ((Res) value).released++;

    private static void slotEnsure() {
        Host host = new Host();
        SlotBinding<Object> binding = new SlotBinding<Object>(host, "token", RELEASE);
        int[] created = {0};
        SlotBinding.Factory<Object> factory = () -> new Res("r" + (++created[0]));
        check("token".equals(binding.ownerToken())
                && binding.ensure(null, "spec-a", 1, factory) == Ensure.INSTALLED && created[0] == 1,
                "owner token and first ensure install");
        Res first = (Res) host.value;
        check(binding.current() == first && "spec-a".equals(binding.appliedSpec())
                && binding.environmentRevision() == 1
                && binding.ensure(null, new String("spec-a"), 1, factory) == Ensure.UNCHANGED
                && created[0] == 1 && host.writes == 1,
                "slot records instance/spec/revision; equal spec and revision write nothing");
        check(binding.ensure(null, "spec-b", 1, factory) == Ensure.REPLACED && created[0] == 2
                && host.value != first && first.released == 1,
                "new spec replaces and releases the previous value");
        Res second = (Res) host.value;
        check(binding.ensure(null, "spec-b", 2, factory) == Ensure.REPLACED && created[0] == 3
                && second.released == 1,
                "new environment revision replaces");
        Res third = (Res) host.value;
        check(binding.replace(third, "spec-c", 7) && binding.appliedSpec().equals("spec-c")
                && binding.environmentRevision() == 7 && host.writes == 3 && third.released == 0
                && binding.ensure(null, "spec-c", 7, factory) == Ensure.UNCHANGED,
                "replacing with the same instance only updates the record");

        Host busy = new Host();
        SlotBinding<Object> reentrant = new SlotBinding<Object>(busy);
        fails(IllegalStateException.class, () -> reentrant.ensure(null, "s", 0, () -> {
            reentrant.isOwned();
            return new Res("x");
        }));
        check(busy.value == null && reentrant.ensure(null, "s", 0, () -> new Res("ok")) == Ensure.INSTALLED,
                "factory reentry is rejected and does not poison the binding");

        Host mismatch = new Host();
        Object external = new Res("external");
        mismatch.value = external;
        SlotBinding<Object> declined = new SlotBinding<Object>(mismatch, "t", RELEASE);
        int[] calls = {0};
        check(declined.ensure(null, "s", 0, () -> { calls[0]++; return new Res("n"); }) == Ensure.NOT_OWNED
                && calls[0] == 0 && mismatch.value == external, "first ensure respects expected value without creating");

        Host nullable = new Host();
        SlotBinding<Object> nulls = new SlotBinding<Object>(nullable);
        failsAll(NullPointerException.class,
                () -> nulls.ensure(null, "s", 0, () -> null),
                () -> nulls.ensure(null, null, 0, () -> new Res("x")));
        check(nulls.ensure(null, "s", 0, () -> new Res("y")) == Ensure.INSTALLED, "failed ensure leaves binding usable");

        Host failing = new Host();
        SlotBinding<Object> partial = new SlotBinding<Object>(failing, "t", RELEASE);
        partial.ensure(null, "a", 0, () -> new Res("a"));
        Res kept = (Res) failing.value;
        fails(IllegalStateException.class, () -> partial.ensure(null, "b", 0, () -> { throw new IllegalStateException("boom"); }));
        check(partial.current() == kept && kept.released == 0 && partial.appliedSpec().equals("a"),
                "factory failure leaves the owned value untouched");
    }

    private static void slotLoss() {
        Host host = new Host();
        SlotBinding<Object> binding = new SlotBinding<Object>(host, "t", RELEASE);
        binding.ensure(null, "a", 1, () -> new Res("a"));
        Res ours = (Res) host.value;
        Res business = new Res("business");
        host.value = business;
        int[] calls = {0};
        check(binding.ensure(null, "b", 1, () -> { calls[0]++; return new Res("b"); }) == Ensure.NOT_OWNED
                && calls[0] == 0 && host.value == business
                && !binding.isOwned() && binding.current() == null && binding.appliedSpec() == null
                && binding.environmentRevision() == 0,
                "host replacement is never overwritten and the lost binding reports no claim");
        check(ours.released == 0 && business.released == 0
                && binding.ensure(null, "b", 1, () -> new Res("b")) == Ensure.NOT_OWNED,
                "host replacement releases nothing and the loss is sticky");

        Res rebound = new Res("rebound");
        check(binding.takeOver(rebound, "c", 2) && host.value == rebound && binding.isOwned()
                && "c".equals(binding.appliedSpec()) && business.released == 0
                && binding.ensure(null, "c", 2, () -> new Res("never")) == Ensure.UNCHANGED,
                "explicit takeOver rebinds and restores ownership");
        Res replacement = new Res("replacement");
        check(binding.takeOver(replacement, "d", 3) && rebound.released == 1, "takeOver while owned behaves like replace");

        host.value = business;
        binding.close();
        check(host.value == business && replacement.released == 0, "close leaves host-owned values and releases nothing");
        fails(IllegalStateException.class, () -> binding.takeOver(new Res("late"), "e", 4));

        Host racing = new Host();
        SlotBinding<Object> racer = new SlotBinding<Object>(racing, "t", RELEASE);
        Res[] unaccepted = new Res[1];
        Res meanwhile = new Res("meanwhile");
        check(racer.ensure(null, "a", 0, () -> { racing.value = meanwhile; return unaccepted[0] = new Res("a"); })
                == Ensure.NOT_OWNED && racing.value == meanwhile && unaccepted[0].released == 1 && !racer.isOwned(),
                "a factory that changes the slot is not overwritten and its instance is released");
        Host ownedRace = new Host();
        SlotBinding<Object> ownedRacer = new SlotBinding<Object>(ownedRace, "t", RELEASE);
        ownedRacer.ensure(null, "a", 0, () -> new Res("a"));
        Res kept = (Res) ownedRace.value;
        Res late = new Res("late");
        Res[] rejected = new Res[1];
        check(ownedRacer.ensure(null, "b", 0, () -> { ownedRace.value = late; return rejected[0] = new Res("b"); })
                == Ensure.NOT_OWNED && ownedRace.value == late && rejected[0].released == 1 && kept.released == 0,
                "the same holds when replacing an owned value");

        Host fresh = new Host();
        SlotBinding<Object> unused = new SlotBinding<Object>(fresh);
        fails(IllegalStateException.class, () -> unused.takeOver(new Res("early"), "e", 0));
        unused.close();
    }

    private static void slotRelease() {
        Host host = new Host();
        SlotBinding<Object> binding = new SlotBinding<Object>(host, "t", RELEASE);
        binding.ensure(null, "a", 1, () -> new Res("a"));
        Res installed = (Res) host.value;
        binding.close();
        binding.close();
        check(host.value == null && installed.released == 1, "close clears the slot and releases exactly once");

        Object foreign = new Res("foreign");
        SlotBinding.Slot<Object> redirect = new SlotBinding.Slot<Object>() {
            Object value;
            public Object get() { return value; }
            public void set(Object ignored) { value = foreign; }
        };
        SlotBinding<Object> redirected = new SlotBinding<Object>(redirect, "t", RELEASE);
        Res[] made = new Res[1];
        check(redirected.ensure(null, "a", 0, () -> made[0] = new Res("m")) == Ensure.NOT_OWNED
                && made[0].released == 0 && !redirected.isOwned(), "an instance the host did not accept is not released");

        SlotBinding.Slot<Object> throwing = new SlotBinding.Slot<Object>() {
            public Object get() { return null; }
            public void set(Object ignored) { throw new IllegalStateException("setter failed"); }
        };
        SlotBinding<Object> failed = new SlotBinding<Object>(throwing, "t", RELEASE);
        Res[] attempted = new Res[1];
        fails(IllegalStateException.class, () -> failed.ensure(null, "a", 0, () -> attempted[0] = new Res("a")));
        check(attempted[0].released == 0 && !failed.isOwned(), "a throwing setter neither claims nor releases");

        Host releasing = new Host();
        SlotBinding<Object> throwingRelease = new SlotBinding<Object>(releasing, "t", value -> {
            throw new IllegalArgumentException("release failed");
        });
        throwingRelease.ensure(null, "a", 0, () -> new Res("a"));
        Res replacedValue = (Res) releasing.value;
        Res[] newest = new Res[1];
        fails(IllegalArgumentException.class, () -> throwingRelease.ensure(null, "b", 0, () -> newest[0] = new Res("b")));
        check(releasing.value == newest[0] && throwingRelease.current() == newest[0] && replacedValue.released == 0,
                "a failing releaser does not undo the installation");
    }

    private static void slotGuards() {
        fails(NullPointerException.class, () -> new SlotBinding<Object>(new Host(), null, null));
        Host host = new Host();
        SlotBinding<Object> binding = new SlotBinding<Object>(host);
        check(binding.ownerToken() != null && new SlotBinding<Object>(host).ownerToken() != binding.ownerToken(),
                "default tokens are distinct");
        check(binding.install(null, "plain", "spec", 5) && "spec".equals(binding.appliedSpec())
                && binding.environmentRevision() == 5, "install can record spec and revision");
        check(binding.replace("other") && binding.appliedSpec() == null && binding.environmentRevision() == 0,
                "unrecorded replacement clears the record");
        binding.close();
    }

    private static final BindingEnvironment ENV = new BindingEnvironment(1, 1);

    private static void scopeLifecycle() {
        BindingScope scope = new BindingScope(9);
        check(scope.state() == BindingScope.State.CREATED && scope.ownerEpoch() == 9 && scope.environment() == null
                && scope.bindingEpoch() == 0, "created state");
        fails(IllegalStateException.class, () -> scope.begin("background"));
        scope.bind(ENV);
        check(scope.state() == BindingScope.State.BOUND && scope.bindingEpoch() == 1 && ENV.equals(scope.environment())
                && !scope.resume(ENV), "bind and resume-while-bound no-op");
        fails(IllegalStateException.class, () -> scope.bind(ENV));
        check(scope.suspend() && scope.state() == BindingScope.State.SUSPENDED && scope.bindingEpoch() == 2
                && !scope.suspend(), "suspend bumps the epoch once");
        BindingEnvironment later = new BindingEnvironment(4, 1);
        check(scope.resume(later) && scope.state() == BindingScope.State.BOUND && later.equals(scope.environment())
                && scope.bindingEpoch() == 2 && !scope.updateEnvironment(later)
                && scope.updateEnvironment(new BindingEnvironment(4, 2)),
                "resume refreshes the environment; update only on change");
        failsAll(IllegalArgumentException.class,
                () -> new BindingEnvironment(-1, 0),
                () -> scope.begin(""));
        scope.close();
        check(scope.state() == BindingScope.State.CLOSED && scope.environment() == null
                && !scope.suspend() && !scope.resume(ENV) && !scope.updateEnvironment(ENV),
                "closed state and late callbacks are no-ops");
        fails(IllegalStateException.class, () -> scope.begin("background"));
        check(new BindingEnvironment(3, 4).equals(new BindingEnvironment(3, 4))
                && new BindingEnvironment(3, 4).hashCode() == new BindingEnvironment(3, 4).hashCode()
                && !new BindingEnvironment(3, 4).equals(new BindingEnvironment(4, 3)), "environment value semantics");
    }

    private static final BindingScope.Installer<Res> NOOP = result -> true;
    private static final SlotBinding.Releaser<Res> RELEASE_RES = value -> value.released++;
    private static final SlotBinding.Releaser<Res> COUNT = result -> result.released++;

    private static void scopeCommit() {
        BindingScope scope = new BindingScope(1);
        scope.bind(ENV);
        Host host = new Host();
        SlotBinding<Object> slot = scope.slot("background", host, RELEASE);
        fails(IllegalArgumentException.class, () -> scope.slot("background", new Host(), null));

        RequestStamp stamp = scope.begin("background");
        Res result = new Res("first");
        check(slot.ownerToken() == scope
                && stamp.requestId() == 1 && stamp.ownerEpoch() == 1 && stamp.bindingEpoch() == 1
                && stamp.key().equals("background") && stamp.environment().equals(ENV)
                && scope.commit(stamp, result, r -> slot.install(null, r, "spec", ENV.resourceRevision()), COUNT) == CommitOutcome.COMMITTED
                && host.value == result && result.released == 0 && "spec".equals(slot.appliedSpec()),
                "scope slots carry the scope token and a valid result installs");
        Res again = new Res("again");
        check(scope.commit(stamp, again, r -> { throw new AssertionError("installed twice"); }, COUNT) == CommitOutcome.STALE
                && again.released == 1, "a request commits at most once");

        RequestStamp older = scope.begin("background");
        RequestStamp newer = scope.begin("background");
        Res olderResult = new Res("older"), newerResult = new Res("newer");
        RequestStamp thumb = scope.begin("thumb");
        RequestStamp track = scope.begin("track");
        check(scope.commit(older, olderResult, NOOP, COUNT) == CommitOutcome.STALE && olderResult.released == 1
                && scope.commit(newer, newerResult, NOOP, COUNT) == CommitOutcome.COMMITTED && newerResult.released == 0
                && scope.commit(thumb, new Res("t"), NOOP, COUNT) == CommitOutcome.COMMITTED
                && scope.commit(track, new Res("k"), NOOP, COUNT) == CommitOutcome.COMMITTED,
                "latest request commits and keys supersede independently");

        RequestStamp failing = scope.begin("background");
        Res failedResult = new Res("failing");
        fails(IllegalStateException.class, () -> scope.commit(failing, failedResult,
                r -> { throw new IllegalStateException("install failed"); }, COUNT));
        check(failedResult.released == 1 && scope.commit(failing, new Res("retry"), NOOP, COUNT) == CommitOutcome.STALE,
                "a failed installer releases the result and consumes the request");

        RequestStamp reentrant = scope.begin("background");
        Res reentrantResult = new Res("reentrant");
        fails(IllegalStateException.class, () -> scope.commit(reentrant, reentrantResult, r -> { scope.begin("background"); return true; }, COUNT));
        RequestStamp usable = scope.begin("background");
        check(reentrantResult.released == 1 && scope.state() == BindingScope.State.BOUND
                && scope.commit(usable, new Res("after"), NOOP, COUNT) == CommitOutcome.COMMITTED,
                "installer cannot re-enter the scope and the scope stays usable");

        RequestStamp doubleFault = scope.begin("background");
        Res doubleResult = new Res("double");
        IllegalStateException primary = new IllegalStateException("install failed");
        try {
            scope.commit(doubleFault, doubleResult, r -> { throw primary; }, r -> { throw new IllegalArgumentException("release failed"); });
            throw new AssertionError("expected failure");
        } catch (IllegalStateException failure) {
            check(failure == primary && failure.getSuppressed().length == 1, "release failure is suppressed under the install failure");
        }

        BindingScope other = new BindingScope(1);
        other.bind(ENV);
        Res foreign = new Res("foreign");
        check(other.commit(stamp, foreign, NOOP, COUNT) == CommitOutcome.FOREIGN && foreign.released == 1
                && other.state() == BindingScope.State.BOUND, "stamps are scope-bound even with equal epochs");
        fails(NullPointerException.class, () -> scope.commit(stamp, null, NOOP, COUNT));
        scope.close();
        other.close();
    }

    private static void scopeStaleness() {
        BindingScope scope = new BindingScope(1);
        scope.bind(ENV);
        RequestStamp beforeRoundTrip = scope.begin("background");
        scope.updateEnvironment(new BindingEnvironment(2, 1));
        scope.updateEnvironment(ENV);
        Res roundTrip = new Res("round-trip");
        RequestStamp declined = scope.begin("background");
        Res declinedResult = new Res("declined");
        check(scope.commit(beforeRoundTrip, roundTrip, NOOP, COUNT) == CommitOutcome.STALE && roundTrip.released == 1
                && scope.commit(declined, declinedResult, r -> false, COUNT) == CommitOutcome.REJECTED
                && declinedResult.released == 1,
                "an environment round trip and a declining installer both release the result");

        RequestStamp usesScope = scope.begin("background");
        scope.begin("background");
        int[] reentered = {0};
        check(scope.commit(usesScope, new Res("stale"), NOOP, r -> { scope.begin("other"); reentered[0]++; })
                == CommitOutcome.STALE && reentered[0] == 1, "a releaser may use the scope");

        Host business = new Host();
        SlotBinding<Object> taken = scope.slot("taken", business, RELEASE);
        Res initial = new Res("initial");
        taken.install(null, initial);
        Res external = new Res("external");
        business.value = external;
        RequestStamp lostSlot = scope.begin("taken");
        Res rejectedByLostSlot = new Res("rejected");
        RequestStamp ownedSlot = scope.begin("fresh");
        Host free = new Host();
        SlotBinding<Object> freshSlot = scope.slot("fresh", free, RELEASE);
        Res installedByCommit = new Res("committed");
        check(scope.commit(lostSlot, rejectedByLostSlot, taken::replace, RELEASE_RES) == CommitOutcome.REJECTED
                && business.value == external && rejectedByLostSlot.released == 1 && external.released == 0
                && scope.commit(ownedSlot, installedByCommit, r -> freshSlot.install(null, r, "s", 1), RELEASE_RES)
                        == CommitOutcome.COMMITTED && free.value == installedByCommit && installedByCommit.released == 0,
                "a taken-over slot rejects without touching the host; a valid commit installs through the slot");

        RequestStamp beforeSuspend = scope.begin("background");
        scope.suspend();
        Res late = new Res("late");
        check(scope.commit(beforeSuspend, late, r -> { throw new AssertionError("installed while suspended"); }, COUNT)
                == CommitOutcome.NOT_BOUND && late.released == 1,
                "a late result while suspended is released");
        scope.resume(ENV);
        Res afterResume = new Res("after-resume");
        RequestStamp fresh = scope.begin("background");
        check(scope.commit(beforeSuspend, afterResume, NOOP, COUNT) == CommitOutcome.STALE && afterResume.released == 1
                && fresh.bindingEpoch() == 2 && scope.commit(fresh, new Res("fresh"), NOOP, COUNT) == CommitOutcome.COMMITTED,
                "pre-suspension results stay stale after reconnect and a fresh request commits");

        RequestStamp beforeResource = scope.begin("background");
        scope.updateEnvironment(new BindingEnvironment(2, 1));
        Res resource = new Res("resource");
        boolean resourceStale = scope.commit(beforeResource, resource, NOOP, COUNT) == CommitOutcome.STALE;
        RequestStamp beforeRegistry = scope.begin("background");
        scope.updateEnvironment(new BindingEnvironment(2, 2));
        Res registry = new Res("registry");
        boolean registryStale = scope.commit(beforeRegistry, registry, NOOP, COUNT) == CommitOutcome.STALE;
        check(resourceStale && resource.released == 1 && registryStale && registry.released == 1,
                "resource and registry revision changes invalidate");

        RequestStamp beforeClose = scope.begin("background");
        scope.close();
        Res closed = new Res("closed");
        Res closedAgain = new Res("closed-again");
        check(scope.commit(beforeClose, closed, NOOP, COUNT) == CommitOutcome.CLOSED && closed.released == 1
                && scope.commit(beforeClose, closedAgain, NOOP, COUNT) == CommitOutcome.CLOSED && closedAgain.released == 1,
                "late results after close are released and never installed");
    }

    private static void scopeClose() {
        BindingScope scope = new BindingScope(1);
        scope.bind(ENV);
        List<String> order = new ArrayList<String>();
        Host mine = new Host(), theirs = new Host();
        SlotBinding<Object> owned = scope.slot("owned", mine, RELEASE);
        SlotBinding<Object> lost = scope.slot("lost", theirs, RELEASE);
        Res ownedValue = new Res("owned"), lostValue = new Res("lost"), business = new Res("business");
        owned.install(null, ownedValue);
        lost.install(null, lostValue);
        theirs.value = business;
        scope.own(() -> order.add("first"));
        scope.own(() -> { order.add("second"); throw new IllegalStateException("second failed"); });
        scope.own(() -> order.add("third"));
        scope.own(() -> scope.close());
        try {
            scope.close();
            throw new AssertionError("expected close failure");
        } catch (IllegalStateException failure) {
            check("second failed".equals(failure.getMessage())
                    && order.size() == 3 && order.get(0).equals("third") && order.get(2).equals("first"),
                    "first failure is rethrown and resources release in reverse order without skipping");
        }
        check(mine.value == null && ownedValue.released == 1
                && theirs.value == business && lostValue.released == 0 && business.released == 0,
                "owned slot cleared and released; host-owned slot left alone");
        scope.close();
        check(order.size() == 3 && ownedValue.released == 1, "close is idempotent");
    }

    private static void scopeThreading() {
        BindingScope scope = new BindingScope(5);
        scope.bind(ENV);
        RequestStamp stamp = scope.begin("background");
        AtomicReference<Throwable> problem = new AtomicReference<Throwable>();
        long[] seen = new long[3];
        Res workerResult = new Res("worker");
        Thread worker = new Thread(() -> {
            seen[0] = stamp.requestId();
            seen[1] = stamp.ownerEpoch();
            seen[2] = stamp.environment().registryRevision();
            try {
                scope.commit(stamp, workerResult, NOOP, COUNT);
            } catch (Throwable error) {
                problem.set(error);
            }
        });
        worker.start();
        try { worker.join(); } catch (InterruptedException error) { throw new AssertionError(error); }
        check(seen[0] == 1 && seen[1] == 5 && seen[2] == 1
                && problem.get() instanceof IllegalStateException && workerResult.released == 0
                && scope.commit(stamp, workerResult, NOOP, COUNT) == CommitOutcome.COMMITTED,
                "stamps are worker-readable, a worker commit is rejected, and the owner can still commit");
        scope.close();
    }

    private static void propertyClaims() {
        BindingScope scope = new BindingScope(1);
        scope.bind(ENV);
        BindingScope.Claim background = scope.claim("background");
        fails(IllegalArgumentException.class, () -> scope.claim("background"));
        fails(IllegalArgumentException.class, () -> scope.slot("background", new Host(), null));
        scope.slot("foreground", new Host(), null);
        fails(IllegalArgumentException.class, () -> scope.claim("foreground"));
        background.close();
        background.close();
        BindingScope.Claim again = scope.claim("background");
        check("background".equals(background.key()) && !again.isReleased(),
                "claim reserves the key and a released key can be claimed again");
        scope.slot("late", new Host(), null);
        fails(IllegalArgumentException.class, () -> scope.claim("late"));
        for (int i = 0; i < 29; i++) scope.claim("filler" + i);
        fails(IllegalStateException.class, () -> scope.claim("one-too-many"));
        again.close();
        check(scope.claim("one-too-many").key().equals("one-too-many"), "closing a claim frees budget");
        fails(IllegalArgumentException.class, () -> scope.claim(""));
        fails(NullPointerException.class, () -> scope.claim(null));
        scope.close();
        check(again.isReleased(), "closing the scope releases claims");
        again.close();
        fails(IllegalStateException.class, () -> scope.claim("after-close"));

        BindingScope other = new BindingScope(2);
        other.bind(ENV);
        BindingScope.Claim foreign = other.claim("p");
        AtomicReference<Throwable> problem = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try { foreign.close(); } catch (Throwable error) { problem.set(error); }
        });
        worker.start();
        try { worker.join(); } catch (InterruptedException error) { throw new AssertionError(error); }
        check(problem.get() instanceof IllegalStateException && !foreign.isReleased(), "claims are owner-thread only");
        other.close();
    }

    /** Counts one check for a group of actions that must all fail with the same exception. */
    private static void failsAll(Class<? extends Throwable> expected, Runnable... actions) {
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (Throwable error) {
                if (expected.isInstance(error)) continue;
                throw new AssertionError("unexpected exception: " + error, error);
            }
            throw new AssertionError("expected " + expected.getName());
        }
        checks++;
    }

    private static void fails(Class<? extends Throwable> expected, Runnable action) {
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
