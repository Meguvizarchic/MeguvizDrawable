package io.github.jichuo1.meguviz.drawable.android.samples;

import android.graphics.Bitmap;
import android.view.View;

import io.github.jichuo1.meguviz.drawable.android.BitmapWeigher;
import io.github.jichuo1.meguviz.drawable.android.MainThreadPoster;
import io.github.jichuo1.meguviz.drawable.android.RecipeDrawable;
import io.github.jichuo1.meguviz.drawable.cache.FlightCache;
import io.github.jichuo1.meguviz.drawable.model.DrawingRecipe;
import io.github.jichuo1.meguviz.drawable.model.FillSpec;
import io.github.jichuo1.meguviz.drawable.model.Length;
import io.github.jichuo1.meguviz.drawable.model.ShapeSpec;
import io.github.jichuo1.meguviz.drawable.model.StrokeSpec;

import java.util.concurrent.ExecutorService;

/**
 * The Android half of docs/engineering/QUICKSTART.md. Compiled by
 * {@code :android:assembleDebugAndroidTest} (part of the Android gate); not run as a test. Keep the
 * guide's snippets identical to this file.
 */
public final class AndroidQuickStartSample {
    private AndroidQuickStartSample() {}

    /** A View background. One drawable per owner; the recipe itself may be shared freely. */
    public static void applyBackground(View view) {
        DrawingRecipe card = DrawingRecipe.of(ShapeSpec.roundRect(Length.dp(16)), FillSpec.solid(0xFFFFFFFF))
                .withStroke(new StrokeSpec(Length.dp(1), 0x1F000000));
        view.setBackground(new RecipeDrawable(card, view.getResources().getDisplayMetrics().density));
    }

    /** A process-wide tile cache: bounded by bytes, produced off the main thread, delivered on it. */
    public static FlightCache<String, Bitmap> tileCache(ExecutorService workers) {
        return FlightCache.<String, Bitmap>builder(workers, new MainThreadPoster())
                .weigher(BitmapWeigher.INSTANCE)
                .maxWeight(24L * 1024 * 1024)
                .maxEntries(64)
                .maxFlights(8)
                .build();
    }
}
