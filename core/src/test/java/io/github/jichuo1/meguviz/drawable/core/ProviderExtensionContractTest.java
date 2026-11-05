package io.github.jichuo1.meguviz.drawable.core;

import io.github.jichuo1.meguviz.drawable.binding.BindingEnvironment;
import io.github.jichuo1.meguviz.drawable.binding.BindingScope;
import io.github.jichuo1.meguviz.drawable.binding.RequestStamp;
import io.github.jichuo1.meguviz.drawable.spi.ShapeCapabilities;
import io.github.jichuo1.meguviz.drawable.spi.ShapePlanView;
import io.github.jichuo1.meguviz.drawable.spi.ShapePreparation;
import io.github.jichuo1.meguviz.drawable.spi.ShapeProvider;
import io.github.jichuo1.meguviz.drawable.spi.ShapeProviderConformance;
import io.github.jichuo1.meguviz.drawable.spi.ShapeProviderDescriptor;
import io.github.jichuo1.meguviz.drawable.spi.ShapeProviderFactory;
import io.github.jichuo1.meguviz.drawable.spi.ShapeProviderRegistry;
import io.github.jichuo1.meguviz.drawable.spi.ShapeRequest;
import io.github.jichuo1.meguviz.drawable.spi.ShapeUnsupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Capability, fallback, derivation and conformance contracts of the experimental shape SPI. */
final class ProviderExtensionContractTest {
    private static int checks;

    private static final String A = "test.shape.a";
    private static final String B = "test.shape.b";
    private static final String C = "test.shape.c";
    private static final String HW = "test.cap.hardware-only";

    static int run() {
        descriptorExtensions();
        capabilityGating();
        registryRevisionInvalidatesPreparedResults();
        if (stress()) {
            fallbackSemantics();
            conformanceVectors();
            derivationAndSelection();
        }
        return checks;
    }

    /** Opt-in derivation coverage; the default run keeps the descriptor and fallback contracts. */
    private static boolean stress() {
        return Boolean.getBoolean("meguviz.test.stress");
    }

    private static ShapeProviderDescriptor descriptor(String id, List<String> dependencies, Set<String> required,
            List<String> fallbacks) {
        return new ShapeProviderDescriptor(id, 1, 1, "1.0", 8, dependencies, required, fallbacks);
    }

    private static ShapeProviderDescriptor plain(String id) {
        return new ShapeProviderDescriptor(id, 1, 1, "1.0", 8, Collections.<String>emptyList());
    }

    private static ShapeProviderDescriptor withFallbacks(String id, String... fallbacks) {
        return descriptor(id, Collections.<String>emptyList(), Collections.<String>emptySet(), Arrays.asList(fallbacks));
    }

    private static ShapeProviderFactory triangle() {
        return () -> (request, output) -> {
            output.vertex(0, 0);
            output.vertex(1, 0);
            output.vertex(0, 1);
        };
    }

    private static ShapeProviderFactory unsupported(String reason, AtomicInteger calls) {
        return () -> (request, output) -> {
            calls.incrementAndGet();
            throw new ShapeUnsupportedException(reason);
        };
    }

    private static ShapeProviderFactory counting(AtomicInteger calls) {
        return () -> (request, output) -> {
            calls.incrementAndGet();
            output.vertex(0, 0);
            output.vertex(1, 0);
            output.vertex(0, 1);
        };
    }

    private static void descriptorExtensions() {
        List<String> fallbacks = new ArrayList<String>(Arrays.asList(B, C));
        Set<String> required = new HashSet<String>(Arrays.asList("test.cap.z", "test.cap.a"));
        ShapeProviderDescriptor full = descriptor(A, Collections.<String>emptyList(), required, fallbacks);
        fallbacks.clear();
        required.clear();
        check(plain(A).requiredCapabilities().isEmpty() && plain(A).fallbacks().isEmpty()
                && full.fallbacks().equals(Arrays.asList(B, C)) && full.requiredCapabilities().size() == 2
                && new ArrayList<String>(full.requiredCapabilities()).equals(Arrays.asList("test.cap.a", "test.cap.z")),
                "the original constructor declares nothing; extensions copy and sort");
        failsAll(UnsupportedOperationException.class,
                () -> full.fallbacks().add(B),
                () -> full.requiredCapabilities().add("test.cap.q"));
        Set<String> tooManyCapabilities = new HashSet<String>();
        for (int i = 0; i < 33; i++) tooManyCapabilities.add("test.cap.c" + i);
        List<String> tooManyFallbacks = new ArrayList<String>();
        for (int i = 0; i < 9; i++) tooManyFallbacks.add("test.shape.f" + i);
        invalidAll(
                () -> withFallbacks(A, A),
                () -> withFallbacks(A, B, B),
                () -> withFallbacks(A, "Not-Namespaced"),
                () -> descriptor(A, Collections.<String>emptyList(), Collections.singleton("nodots"), Collections.<String>emptyList()),
                () -> descriptor(A, Collections.<String>emptyList(), tooManyCapabilities, Collections.<String>emptyList()),
                () -> descriptor(A, Collections.<String>emptyList(), Collections.<String>emptySet(), tooManyFallbacks),
                () -> new ShapeUnsupportedException(""));
        failsAll(NullPointerException.class,
                () -> descriptor(A, Collections.<String>emptyList(), null, Collections.<String>emptyList()),
                () -> descriptor(A, Collections.<String>emptyList(), Collections.<String>emptySet(), null));
        check(new ShapeUnsupportedException("x").getMessage().equals("x")
                && new ShapeUnsupportedException(new String(new char[1000]).replace('\0', 'r')).getMessage().length() == 256,
                "unsupported reasons are bounded");
    }

    private static void capabilityGating() {
        ShapeProviderDescriptor needsHardware = descriptor(A, Collections.<String>emptyList(),
                Collections.singleton(HW), Arrays.asList(B));
        AtomicInteger hardwareCalls = new AtomicInteger();
        ShapeProviderRegistry software = new ShapeProviderRegistry.Builder(4, 8)
                .offer(ShapeCapabilities.CANVAS_SOFTWARE)
                .register(needsHardware, counting(hardwareCalls)).register(plain(B), triangle()).snapshot(1);
        fails(ShapeUnsupportedException.class, () -> software.prepare(A, new ShapeRequest()));
        ShapePreparation fellBack = software.prepareWithFallback(A, new ShapeRequest());
        check(software.capabilities().equals(Collections.singleton(ShapeCapabilities.CANVAS_SOFTWARE))
                && hardwareCalls.get() == 0
                && fellBack.supported() && fellBack.fellBack() && B.equals(fellBack.providerId())
                && A.equals(fellBack.requestedId()) && fellBack.plan().vertexCount() == 3
                && fellBack.reasons().size() == 1
                && fellBack.reasons().get(0).equals(A + ": missing capability " + HW),
                "capabilities gate execution and the fallback records provider and missing capability");
        fails(UnsupportedOperationException.class, () -> fellBack.reasons().add("x"));

        ShapeProviderRegistry hardware = new ShapeProviderRegistry.Builder(4, 8).offer(HW, ShapeCapabilities.CANVAS_SOFTWARE)
                .register(needsHardware, counting(hardwareCalls)).register(plain(B), triangle()).snapshot(1);
        ShapePreparation direct = hardware.prepareWithFallback(A, new ShapeRequest());
        check(direct.supported() && !direct.fellBack() && A.equals(direct.providerId()) && direct.reasons().isEmpty()
                && hardwareCalls.get() == 1, "a capable host runs the requested provider");

        ShapeProviderRegistry.Builder many = new ShapeProviderRegistry.Builder(1, 8);
        for (int i = 0; i < 64; i++) many.offer("test.cap.m" + i);
        many.offer("test.cap.m0");
        failsAll(IllegalArgumentException.class,
                () -> new ShapeProviderRegistry.Builder(1, 8).offer("bad token"),
                () -> many.offer("test.cap.overflow"));
        fails(NullPointerException.class, () -> new ShapeProviderRegistry.Builder(1, 8).offer((String[]) null));
    }

    private static void fallbackSemantics() {
        AtomicInteger primary = new AtomicInteger(), secondary = new AtomicInteger(), last = new AtomicInteger();
        ShapeProviderRegistry registry = new ShapeProviderRegistry.Builder(8, 8)
                .register(withFallbacks(A, B, C), unsupported("parameters out of range", primary))
                .register(withFallbacks(B, C), unsupported("no support", secondary))
                .register(plain(C), counting(last)).snapshot(3);
        ShapePreparation chained = registry.prepareWithFallback(A, new ShapeRequest(.5f));
        check(chained.supported() && C.equals(chained.providerId()) && chained.fellBack()
                && chained.reasons().equals(Arrays.asList(A + ": parameters out of range", B + ": no support"))
                && primary.get() == 1 && secondary.get() == 1 && last.get() == 1,
                "fallbacks are tried depth first with all reasons recorded and each provider runs once");

        AtomicInteger noHelp = new AtomicInteger();
        ShapeProviderRegistry hopeless = new ShapeProviderRegistry.Builder(4, 8)
                .register(withFallbacks(A, B), unsupported("a declined", noHelp))
                .register(plain(B), unsupported("b declined", noHelp)).snapshot(1);
        ShapePreparation none = hopeless.prepareWithFallback(A, new ShapeRequest());
        check(!none.supported() && none.providerId() == null && !none.fellBack() && none.reasons().size() == 2,
                "exhausted fallbacks produce an explicit unsupported result");
        fails(IllegalStateException.class, none::plan);
        fails(ShapeUnsupportedException.class, () -> hopeless.prepare(A, new ShapeRequest()));

        AtomicInteger sentinel = new AtomicInteger();
        ShapeProviderRegistry defective = new ShapeProviderRegistry.Builder(4, 8)
                .register(withFallbacks(A, B), () -> (request, output) -> output.vertex(2f, 0f))
                .register(plain(B), counting(sentinel)).snapshot(1);
        fails(IllegalArgumentException.class, () -> defective.prepareWithFallback(A, new ShapeRequest()));
        check(sentinel.get() == 0, "malformed output is a defect and is never masked by a fallback");

        failsAll(IllegalArgumentException.class,
                () -> registry.prepareWithFallback("test.shape.missing", new ShapeRequest()));
        fails(NullPointerException.class, () -> registry.prepareWithFallback(A, null));

        invalidAll(
                () -> new ShapeProviderRegistry.Builder(2, 8).register(withFallbacks(A, B), triangle()).snapshot(1),
                () -> new ShapeProviderRegistry.Builder(2, 8)
                        .register(withFallbacks(A, B), triangle()).register(withFallbacks(B, A), triangle()).snapshot(1));
        check(new ShapeProviderRegistry.Builder(3, 8).register(
                descriptor(A, Arrays.asList(B), Collections.<String>emptySet(), Arrays.asList(B)), triangle())
                .register(plain(B), triangle()).snapshot(1).contains(A),
                "depending on and falling back to the same provider is not a cycle");
    }

    private static void derivationAndSelection() {
        ShapeProviderRegistry base = new ShapeProviderRegistry.Builder(4, 8).offer(ShapeCapabilities.CANVAS_SOFTWARE)
                .register(plain(B), triangle()).register(plain(A), triangle()).snapshot(5);
        ShapeProviderRegistry reversed = new ShapeProviderRegistry.Builder(4, 8).offer(ShapeCapabilities.CANVAS_SOFTWARE)
                .register(plain(A), triangle()).register(plain(B), triangle()).snapshot(5);
        check(base.ids().equals(Arrays.asList(A, B)) && base.descriptor(B).id().equals(B) && base.descriptor(C) == null
                && reversed.ids().equals(base.ids()) && reversed.capabilities().equals(base.capabilities()),
                "ids and descriptors are looked up sorted and registration order does not matter");
        fails(UnsupportedOperationException.class, () -> base.ids().add(C));

        ShapeProviderRegistry.Builder derived = base.toBuilder();
        invalidAll(() -> derived.snapshot(5), () -> derived.snapshot(4));
        ShapeProviderRegistry next = derived.register(plain(C), triangle()).snapshot(6);
        check(next.contains(C) && !base.contains(C) && next.revision() == 6 && base.revision() == 5
                && next.capabilities().equals(base.capabilities()), "derivation leaves the base registry untouched");
        ShapeProviderRegistry smaller = next.toBuilder().unregister(C).snapshot(7);
        check(!smaller.contains(C) && next.contains(C), "unregistering affects only the derived snapshot");
        ShapeProviderRegistry.Builder full = new ShapeProviderRegistry.Builder(1, 8).register(plain(A), triangle()).snapshot(1).toBuilder();
        ShapeProviderRegistry dependent = new ShapeProviderRegistry.Builder(4, 8)
                .register(plain(B), triangle())
                .register(descriptor(A, Arrays.asList(B), Collections.<String>emptySet(), Collections.<String>emptyList()), triangle())
                .snapshot(1);
        ShapeProviderRegistry fallbackUser = new ShapeProviderRegistry.Builder(4, 8)
                .register(plain(B), triangle()).register(withFallbacks(A, B), triangle()).snapshot(1);
        invalidAll(
                () -> next.toBuilder().register(plain(A), triangle()),
                () -> next.toBuilder().unregister("test.shape.nothing"),
                () -> full.register(plain(B), triangle()),
                () -> dependent.toBuilder().unregister(B).snapshot(2),
                () -> fallbackUser.toBuilder().unregister(B).snapshot(2));
        check(fallbackUser.toBuilder().unregister(A).snapshot(2).contains(B), "removing the dependent first succeeds");
    }

    private static List<ShapeRequest> requests() {
        return Arrays.asList(new ShapeRequest(), new ShapeRequest(.25f));
    }

    private static void conformanceVectors() {
        ShapeProviderDescriptor descriptor = plain(A);
        // The provider is checked alone even if its descriptor names others.
        ShapeProviderDescriptor linked = descriptor(A, Arrays.asList(B), Collections.singleton(HW), Arrays.asList(B));
        check(ShapeProviderConformance.check(descriptor, triangle(), requests()).isEmpty()
                && ShapeProviderConformance.check(linked, triangle(), requests()).isEmpty(),
                "a pure provider conforms even when its descriptor names others");

        AtomicInteger counter = new AtomicInteger();
        ShapeProviderFactory drifting = () -> (request, output) -> {
            float x = (counter.incrementAndGet() % 2) * 0.5f;
            output.vertex(x, 0);
            output.vertex(1, 0);
            output.vertex(0, 1);
        };
        List<String> drift = ShapeProviderConformance.check(descriptor, drifting, requests());
        check(drift.size() == 2 && drift.get(0).startsWith("request 0: ") && drift.get(0).contains("differs between runs"),
                "state carried between runs is detected per request");

        ShapeProvider stateless = (request, output) -> {
            output.vertex(0, 0);
            output.vertex(1, 0);
            output.vertex(0, 1);
        };
        AtomicInteger sharedCalls = new AtomicInteger();
        ShapeProvider stateful = (request, output) -> {
            output.vertex(sharedCalls.incrementAndGet() % 2, 0);
            output.vertex(1, 0);
            output.vertex(0, 1);
        };
        check(ShapeProviderConformance.check(descriptor, () -> stateless, requests()).isEmpty()
                && ShapeProviderConformance.check(descriptor, () -> stateful, requests()).size() == 2,
                "a stateless instance may be shared and a shared stateful one is detected");

        ShapeProviderFactory threadBound = () -> (request, output) -> {
            output.vertex(Thread.currentThread().getName().equals("main") ? 0f : 0.5f, 0);
            output.vertex(1, 0);
            output.vertex(0, 1);
        };
        List<String> threaded = ShapeProviderConformance.check(descriptor, threadBound, Arrays.asList(new ShapeRequest()));
        ShapeProviderFactory errorOnOtherThread = () -> (request, output) -> {
            if (!Thread.currentThread().getName().equals("main")) throw new AssertionError("elsewhere");
            output.vertex(0, 0);
            output.vertex(1, 0);
            output.vertex(0, 1);
        };
        List<String> elsewhere = ShapeProviderConformance.check(descriptor, errorOnOtherThread, Arrays.asList(new ShapeRequest()));
        check(threaded.size() == 1 && threaded.get(0).contains("calling thread")
                && elsewhere.size() == 1 && elsewhere.get(0).contains("calling thread"),
                "thread dependence and off-thread Errors are reported, not propagated");

        ShapeProviderFactory outOfRange = () -> (request, output) -> output.vertex(3f, 0f);
        List<String> malformed = ShapeProviderConformance.check(descriptor, outOfRange, Arrays.asList(new ShapeRequest()));
        check(malformed.size() == 1 && malformed.get(0).startsWith("request 0: IllegalArgumentException"),
                "malformed output is reported with its exception type");

        ShapeProviderFactory consistent = unsupported("only .5 supported", new AtomicInteger());
        AtomicInteger flips = new AtomicInteger();
        ShapeProviderFactory flaky = () -> (request, output) -> {
            if (flips.incrementAndGet() % 2 == 0) throw new ShapeUnsupportedException("sometimes");
            output.vertex(0, 0);
            output.vertex(1, 0);
            output.vertex(0, 1);
        };
        check(ShapeProviderConformance.check(descriptor, consistent, requests()).isEmpty()
                && !ShapeProviderConformance.check(descriptor, flaky, Arrays.asList(new ShapeRequest())).isEmpty(),
                "consistent unsupported conforms and varying support is detected");

        check(ShapeProviderConformance.check(descriptor, () -> null, requests()).get(0).contains("null"),
                "null provider detected");
        invalidAll(() -> ShapeProviderConformance.check(descriptor, triangle(), Collections.<ShapeRequest>emptyList()));
        fails(NullPointerException.class, () -> ShapeProviderConformance.check(descriptor, triangle(), Arrays.asList((ShapeRequest) null)));
    }

    private static void registryRevisionInvalidatesPreparedResults() {
        ShapeProviderRegistry first = new ShapeProviderRegistry.Builder(2, 8).register(plain(A), triangle()).snapshot(1);
        BindingScope scope = new BindingScope(1);
        scope.bind(new BindingEnvironment(0, first.revision()));
        RequestStamp stamp = scope.begin("shape");
        ShapePlanView prepared = first.prepare(A, new ShapeRequest());
        ShapeProviderRegistry second = first.toBuilder().unregister(A).register(plain(A), triangle()).snapshot(2);
        scope.updateEnvironment(new BindingEnvironment(0, second.revision()));
        List<ShapePlanView> installed = new ArrayList<ShapePlanView>();
        List<ShapePlanView> released = new ArrayList<ShapePlanView>();
        check(scope.commit(stamp, prepared, plan -> installed.add(plan), released::add) == BindingScope.CommitOutcome.STALE
                && installed.isEmpty() && released.size() == 1, "a plan prepared under an older registry is not installed");
        RequestStamp renewed = scope.begin("shape");
        check(scope.commit(renewed, second.prepare(A, new ShapeRequest()), plan -> installed.add(plan), released::add)
                == BindingScope.CommitOutcome.COMMITTED && installed.size() == 1, "a plan from the current registry installs");
        scope.close();
    }

    private static void invalid(Runnable action) { fails(IllegalArgumentException.class, action); }
    /** Counts one check for a group of actions that must all be rejected. */
    private static void invalidAll(Runnable... actions) {
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (IllegalArgumentException expected) {
                continue;
            }
            throw new AssertionError("expected IllegalArgumentException");
        }
        checks++;
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
