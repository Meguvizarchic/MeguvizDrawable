package io.github.jichuo1.meguviz.drawable.core;

import io.github.jichuo1.meguviz.drawable.binding.SlotBinding;
import io.github.jichuo1.meguviz.drawable.model.DrawingRecipe;
import io.github.jichuo1.meguviz.drawable.model.FillSpec;
import io.github.jichuo1.meguviz.drawable.model.LayoutMetrics;
import io.github.jichuo1.meguviz.drawable.model.Length;
import io.github.jichuo1.meguviz.drawable.model.ShapeSpec;
import io.github.jichuo1.meguviz.drawable.model.StateColorList;
import io.github.jichuo1.meguviz.drawable.model.StateSpec;
import io.github.jichuo1.meguviz.drawable.model.StrokeSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Behavioral contracts supplementing the deterministic state and geometry corpus. */
final class CoreAdditionalContractTest {
    private static int checks;

    static int run() {
        recipeSnapshots();
        conveniencesAndCachedHashes();
        quickStartSampleRuns();
        if (stress()) {
            invalidInputs();
            parameterSlots();
            bindingOwnership();
            bindingFailures();
        }
        return checks;
    }

    /** Opt-in binding coverage; the default run keeps the model and slot contracts. */
    private static boolean stress() {
        return Boolean.getBoolean("meguviz.test.stress");
    }

    /**
     * The convenience factories are spelled-out constructor calls, and the cached hashes are the
     * values a fresh computation gives: equal recipes built either way stay equal, hash alike in
     * every order of first use, and never alias a mutated input.
     */
    private static void conveniencesAndCachedHashes() {
        ShapeSpec shape = ShapeSpec.roundRect(Length.dp(12));
        StateColorList rules = new StateColorList(
                Arrays.asList(new StateSpec(0x010100a7), new StateSpec()), new int[] {0xff112233, 0xff445566});
        FillSpec fill = FillSpec.statefulSolid(rules, 0xff000000);
        StrokeSpec stroke = new StrokeSpec(Length.dp(1), 0x33000000);
        DrawingRecipe spelled = new DrawingRecipe(shape, fill, stroke, LayoutMetrics.unspecified());
        DrawingRecipe sugared = DrawingRecipe.of(shape, fill).withStroke(stroke);
        int coldHash = new DrawingRecipe(ShapeSpec.roundRect(Length.dp(12)), FillSpec.statefulSolid(
                new StateColorList(Arrays.asList(new StateSpec(0x010100a7), new StateSpec()),
                        new int[] {0xff112233, 0xff445566}), 0xff000000),
                new StrokeSpec(Length.dp(1), 0x33000000), LayoutMetrics.unspecified()).hashCode();
        int first = spelled.hashCode();
        check(sugared.equals(spelled) && spelled.equals(sugared) && first == coldHash
                && spelled.hashCode() == first && sugared.hashCode() == first
                && DrawingRecipe.of(shape, fill).stroke() == null
                && !sugared.withStroke(null).equals(sugared) && sugared.withStroke(null).equals(DrawingRecipe.of(shape, fill))
                && sugared.withMetrics(LayoutMetrics.unspecified()).equals(sugared)
                && !sugared.withMetrics(new LayoutMetrics(4, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)).equals(sugared)
                && LayoutMetrics.unspecified() == LayoutMetrics.unspecified()
                && FillSpec.solid(1).equals(FillSpec.solid(1)) && FillSpec.solid(1).hashCode() == FillSpec.solid(1).hashCode()
                && FillSpec.solid(1).colors().length == 0 && FillSpec.solid(1).colors() != FillSpec.solid(1).colors()
                && !FillSpec.solid(1).equals(FillSpec.solid(2))
                && new StateSpec(5, -6).hashCode() == Arrays.hashCode(new int[] {5, -6}),
                "convenience factories equal spelled-out recipes and cached hashes equal fresh ones");
    }

    /** The guide's code is this sample; running it keeps the documented results true. */
    private static void quickStartSampleRuns() {
        DrawingRecipe button = io.github.jichuo1.meguviz.drawable.samples.QuickStartSample.button();
        check(io.github.jichuo1.meguviz.drawable.samples.QuickStartSample.colorFor(button, new int[] {0x010100a7}) == 0xFF1E4FD8
                && io.github.jichuo1.meguviz.drawable.samples.QuickStartSample.colorFor(button, new int[0]) == 0xFF3366FF
                && "tile-0:rendered".equals(io.github.jichuo1.meguviz.drawable.samples.QuickStartSample.tileOnce()),
                "quick-start sample produces the documented results");
    }

    private static void recipeSnapshots() {
        Length[] radii = new Length[8];
        Arrays.fill(radii, Length.dp(4));
        int[] colors = {0x00000000, 0xff234567};
        float[] stops = {0f, 1f};
        ShapeSpec shape = ShapeSpec.roundRect(radii);
        FillSpec fill = FillSpec.linearGradient(0, 0, 1, 1, colors, stops);
        DrawingRecipe recipe = new DrawingRecipe(shape, fill,
                new StrokeSpec(Length.px(2), 0), LayoutMetrics.unspecified());
        DrawingRecipe expected = new DrawingRecipe(ShapeSpec.roundRect(Length.dp(4)),
                FillSpec.linearGradient(0, 0, 1, 1, new int[] {0, 0xff234567}, new float[] {0, 1}),
                new StrokeSpec(Length.px(2), 0), LayoutMetrics.unspecified());
        int hash = recipe.hashCode();
        radii[0] = Length.dp(100);
        colors[1] = 3;
        stops[1] = 0;
        shape.radii()[0] = Length.px(0);
        fill.colors()[0] = 5;
        fill.stops()[1] = 0;
        check(recipe.equals(expected) && expected.equals(recipe)
                && hash == recipe.hashCode() && hash == expected.hashCode(),
                "recipe nested copies, equality and stable hash");
        check(recipe.stroke().color() == 0 && recipe.stroke().width().value() == 2,
                "transparent stroke retains geometry");

        ArrayList<StateSpec> rules = new ArrayList<StateSpec>(Arrays.asList(new StateSpec(1), new StateSpec(-2)));
        int[] stateColors = {11, 22};
        FillSpec stateFill = FillSpec.statefulSolid(new StateColorList(rules, stateColors), 33);
        rules.clear();
        stateColors[0] = 44;
        check(stateFill.stateColors().colorAt(0) == 11 && stateFill.fallbackColor() == 33
                && StateMatcher.matchIndex(stateFill.stateColors(), new int[] {2}) == -1,
                "state fill snapshot, fallback and independent no-match");
        check(Length.px(-0f).equals(Length.px(0f)) && Length.px(-0f).hashCode() == Length.px(0f).hashCode()
                && !Length.boundsFraction(0.5f, Length.Axis.WIDTH)
                        .equals(Length.boundsFraction(0.5f, Length.Axis.HEIGHT)),
                "signed zero canonicalized and fraction axis matters");
    }

    private static void invalidInputs() {
        invalidAll(
                new Runnable() { public void run() { Length.boundsFraction(1.1f, Length.Axis.WIDTH); }},
                new Runnable() { public void run() { Length.dp(Float.NaN); }},
                new Runnable() { public void run() { ShapeSpec.roundRect(Length.dp(-1)); }},
                new Runnable() { public void run() { new StrokeSpec(Length.px(-1), 0); }},
                new Runnable() { public void run() { new LayoutMetrics(-2, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); }});
        invalidAll(
                new Runnable() { public void run() { FillSpec.linearGradient(0, 0, 0, 0, new int[] {1, 2}, new float[] {0, 1}); }},
                new Runnable() { public void run() { FillSpec.linearGradient(0, 0, 1, 0, new int[] {1, 2}, new float[] {1, 0}); }},
                new Runnable() { public void run() { FillSpec.linearGradient(0, 0, 1, 0, new int[65], new float[65]); }},
                new Runnable() { public void run() { FillSpec.linearGradient(0, 0, 1, 0, new int[] {1, 2}, new float[] {0, Float.NaN}); }});
    }

    private static void parameterSlots() {
        ParameterSlots a = new ParameterSlots();
        ParameterSlots b = new ParameterSlots();
        check(!a.setAlpha(255) && !a.setProgress(-0f) && !a.setOffset(0f, -0f)
                && a.setAlpha(100) && a.alphaRevision() == 1 && b.alpha() == 255
                && a.setProgress(0.5f) && a.progressRevision() == 1
                && a.setOffset(3, 4) && a.offsetRevision() == 1 && !a.setOffset(3, 4),
                "slot change detection, owner isolation and independent revisions");
        invalidAll(
                new Runnable() { public void run() { a.setAlpha(256); }},
                new Runnable() { public void run() { a.setProgress(-0.01f); }},
                new Runnable() { public void run() { a.setProgress(Float.POSITIVE_INFINITY); }},
                new Runnable() { public void run() { a.setOffset(Float.NaN, 8); }});
        check(a.offsetX() == 3 && a.offsetY() == 4 && a.offsetRevision() == 1,
                "invalid inputs do not partially update slots");
    }

    private static final class Host implements SlotBinding.Slot<Object> {
        Object value;
        int writes;
        @Override public Object get() { return value; }
        @Override public void set(Object value) { this.value = value; writes++; }
    }

    private static void bindingOwnership() {
        Host host = new Host();
        Object initial = new Object(), first = new Object(), replacement = new Object(), external = new Object();
        host.value = initial;
        SlotBinding<Object> binding = new SlotBinding<Object>(host);
        check(binding.install(initial, first) && host.value == first && binding.isOwned()
                && binding.replace(first) && host.writes == 1
                && binding.replace(replacement) && host.value == replacement,
                "install owns the slot and same-instance replace does not rewrite");
        host.value = external;
        check(!binding.replace(first) && !binding.isOwned(), "external replacement relinquishes");
        binding.close();
        binding.close();
        SlotBinding<Object> second = new SlotBinding<Object>(host);
        check(host.value == external && second.install(external, first),
                "close does not overwrite the external value and an explicit rebind works");
        second.close();
        check(host.value == null && !second.isOwned(), "close clears only the owned slot");
        Host mismatchHost = new Host();
        mismatchHost.value = external;
        SlotBinding<Object> mismatch = new SlotBinding<Object>(mismatchHost);
        check(!mismatch.install(null, first) && mismatchHost.value == external && !mismatch.replace(replacement),
                "a mismatched expected value leaves the slot unchanged");
        mismatch.close();
    }

    private static void bindingFailures() {
        final Object installed = new Object();
        final Host host = new Host();
        final SlotBinding<Object> binding = new SlotBinding<Object>(host);
        final AtomicReference<Throwable> problem = new AtomicReference<Throwable>();
        Thread other = new Thread(new Runnable() { public void run() {
            try { binding.install(null, installed); } catch (Throwable error) { problem.set(error); }
        }});
        other.start();
        try { other.join(); } catch (InterruptedException error) { throw new AssertionError(error); }
        check(problem.get() instanceof IllegalStateException && host.value == null && binding.install(null, installed),
                "owner thread enforced and a wrong-thread failure does not poison the owner");
        binding.close();

        final Object foreign = new Object();
        SlotBinding.Slot<Object> redirect = new SlotBinding.Slot<Object>() {
            Object value;
            public Object get() { return value; }
            public void set(Object ignored) { value = foreign; }
        };
        SlotBinding<Object> redirected = new SlotBinding<Object>(redirect);
        check(!redirected.install(null, installed) && !redirected.isOwned() && redirect.get() == foreign,
                "setter interception relinquishes and close preserves the redirected value");
        redirected.close();

        SlotBinding.Slot<Object> throwing = new SlotBinding.Slot<Object>() {
            public Object get() { return null; }
            public void set(Object ignored) { throw new IllegalStateException("setter failed"); }
        };
        final SlotBinding<Object> failed = new SlotBinding<Object>(throwing);
        fails(IllegalStateException.class, new Runnable() { public void run() { failed.install(null, installed); }});
        check(!failed.isOwned(), "failed setter relinquishes claim");
        failed.close();
    }

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
    private static void fails(Class<? extends Throwable> expected, Runnable action) {
        try { action.run(); } catch (Throwable error) {
            if (expected.isInstance(error)) { checks++; return; }
            throw new AssertionError("unexpected exception", error);
        }
        throw new AssertionError("expected " + expected.getName());
    }
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
}
