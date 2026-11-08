package io.github.jichuo1.meguviz.drawable.samples;

import io.github.jichuo1.meguviz.drawable.cache.FlightCache;
import io.github.jichuo1.meguviz.drawable.core.StateMatcher;
import io.github.jichuo1.meguviz.drawable.model.DrawingRecipe;
import io.github.jichuo1.meguviz.drawable.model.FillSpec;
import io.github.jichuo1.meguviz.drawable.model.Length;
import io.github.jichuo1.meguviz.drawable.model.ShapeSpec;
import io.github.jichuo1.meguviz.drawable.model.StateColorList;
import io.github.jichuo1.meguviz.drawable.model.StateSpec;
import io.github.jichuo1.meguviz.drawable.model.StrokeSpec;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.Executor;

/**
 * The code of docs/engineering/QUICKSTART.md. It is compiled and run by {@code :core:contractTest}
 * (part of {@code gradlew check}), so the guide cannot drift from the API. Keep the two in sync.
 */
public final class QuickStartSample {
    private QuickStartSample() {}

    // --- 1. A recipe: immutable, comparable by value, safe to share between threads. ---
    static final int PRESSED = 0x010100a7; // android.R.attr.state_pressed

    public static DrawingRecipe button() {
        StateColorList colors = new StateColorList(
                Arrays.asList(new StateSpec(PRESSED), new StateSpec()),   // first match wins
                new int[] {0xFF1E4FD8, 0xFF3366FF});
        return DrawingRecipe.of(ShapeSpec.roundRect(Length.dp(12)), FillSpec.statefulSolid(colors, 0xFF3366FF))
                .withStroke(new StrokeSpec(Length.dp(1), 0x33000000));
    }

    // --- 2. State matching without Android: what RecipeDrawable does on each state change. ---
    public static int colorFor(DrawingRecipe recipe, int[] state) {
        StateColorList rules = recipe.fill().stateColors();
        int index = StateMatcher.matchIndex(rules, state);
        return index < 0 ? recipe.fill().fallbackColor() : rules.colorAt(index);
    }

    // --- 3. Expensive shared results: one production per key, delivered on the owner thread. ---
    public static String tileOnce() {
        final ArrayDeque<Runnable> ownerQueue = new ArrayDeque<Runnable>();   // stands in for the main looper
        Executor worker = new Executor() {                                   // stands in for a thread pool
            @Override public void execute(Runnable task) { task.run(); }
        };
        FlightCache<String, String> tiles = FlightCache.<String, String>builder(worker,
                new FlightCache.OwnerPoster() {
                    @Override public boolean post(Runnable task) { return ownerQueue.add(task); }
                }).maxEntries(32).maxFlights(4).build();
        final String[] installed = new String[1];
        FlightCache.Ticket<String> ticket = tiles.request("tile-0",
                new FlightCache.Producer<String, String>() {
                    @Override public String produce(String key, FlightCache.Cancellation c) { return key + ":rendered"; }
                },
                new FlightCache.Subscriber<String>() {
                    @Override public boolean isCurrent() { return true; }               // generation check
                    @Override public void onReady(String value) { installed[0] = value; }
                    @Override public void onFailed(Throwable failure) { throw new AssertionError(failure); }
                });
        if (ticket.status() == FlightCache.Status.READY) installed[0] = ticket.value(); // hits never call back
        while (!ownerQueue.isEmpty()) ownerQueue.poll().run();                           // the owner thread's turn
        String hit = tiles.peek("tile-0");                                               // draw path: read only
        tiles.close();
        return installed[0] != null && installed[0].equals(hit) ? hit : null;
    }
}
