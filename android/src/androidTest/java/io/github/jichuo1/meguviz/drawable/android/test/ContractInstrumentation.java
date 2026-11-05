package io.github.jichuo1.meguviz.drawable.android.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import io.github.jichuo1.meguviz.drawable.android.RecipeDrawable;
import io.github.jichuo1.meguviz.drawable.model.DrawingRecipe;
import io.github.jichuo1.meguviz.drawable.model.FillSpec;
import io.github.jichuo1.meguviz.drawable.model.LayoutMetrics;
import io.github.jichuo1.meguviz.drawable.model.Length;
import io.github.jichuo1.meguviz.drawable.model.ShapeSpec;
import io.github.jichuo1.meguviz.drawable.model.StateColorList;
import io.github.jichuo1.meguviz.drawable.model.StateSpec;
import io.github.jichuo1.meguviz.drawable.model.StrokeSpec;

/** Standalone device contracts, run with adb shell am instrument -w -r. */
public final class ContractInstrumentation extends Instrumentation {
    private int checks;
    private String mode;
    private static volatile int timingSink;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mode = arguments.getString("mode", "contracts");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if ("benchmark".equals(mode)) {
                result.putString("stream", benchmarkSameState());
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("protocol".equals(mode)) {
                constantStateAndOwnerIsolation();
                result.putString("stream", "\nPROTOCOL PASS checks=" + checks + "\n");
                result.putInt("checks", checks);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("wrapper".equals(mode)) {
                checks = WrapperContract.run();
                result.putString("stream", "\nWRAPPER PASS checks=" + checks + "\n");
                result.putInt("checks", checks);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("cache".equals(mode)) {
                checks = new CacheContract(this).run();
                result.putString("stream", "\nCACHE PASS checks=" + checks + "\n");
                result.putInt("checks", checks);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("binding".equals(mode)) {
                checks = BindingContract.run(this);
                result.putString("stream", "\nBINDING PASS checks=" + checks + "\n");
                result.putInt("checks", checks);
                finish(Activity.RESULT_OK, result);
                return;
            }
            shapesAndGradient();
            statesAndIsolation();
            reusedStateArrayAndDistinctKeys();
            metricsAndCallbacks();
            filtersAndAlpha();
            transparentGradientReference();
            asymmetricGeometryReference();
            statefulTintAndOwnership();
            boundsAndMetricsEdges();
            if ("hardware".equals(mode)) {
                hardwareCanvasReference();
                result.putString("hardware_scenes", "gradient=6084; asymmetric_round_rect=6400; "
                        + "checked_alpha128=6400; selected_alpha255=6400; exact_pixel_matches");
            }
            result.putString("stream", "\nCONTRACT PASS checks=" + checks + "\n");
            result.putInt("checks", checks);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "\nCONTRACT FAIL after " + checks + " checks: " + failure + "\n");
            result.putString("failure", android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private RecipeDrawable drawable(ShapeSpec shape, FillSpec fill, StrokeSpec stroke, LayoutMetrics metrics) {
        RecipeDrawable d = new RecipeDrawable(new DrawingRecipe(shape, fill, stroke, metrics), 1f);
        d.setBounds(4, 5, 24, 25);
        return d;
    }
    private Bitmap bitmap(RecipeDrawable d) {
        Bitmap bitmap = Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888);
        d.draw(new Canvas(bitmap));
        return bitmap;
    }
    private void shapesAndGradient() {
        RecipeDrawable rect = drawable(ShapeSpec.rectangle(), FillSpec.solid(Color.RED), null, LayoutMetrics.unspecified());
        Bitmap b = bitmap(rect);
        check(b.getPixel(10, 10) == Color.RED, "solid rectangle center");
        check(b.getPixel(2, 2) == Color.TRANSPARENT, "nonzero bounds outside");
        RecipeDrawable round = drawable(ShapeSpec.roundRect(Length.px(8)), FillSpec.solid(Color.RED), null, LayoutMetrics.unspecified());
        b = bitmap(round);
        check(Color.alpha(b.getPixel(4, 5)) == 0, "round corner transparent");
        check(b.getPixel(14, 15) == Color.RED, "round center");
        RecipeDrawable ellipse = drawable(ShapeSpec.ellipse(), FillSpec.solid(Color.BLUE), null, LayoutMetrics.unspecified());
        b = bitmap(ellipse);
        check(Color.alpha(b.getPixel(4, 5)) == 0 && b.getPixel(14, 15) == Color.BLUE, "ellipse pixels");
        RecipeDrawable gradient = drawable(ShapeSpec.rectangle(), FillSpec.linearGradient(0, 0, 1, 0,
                new int[] { Color.RED, Color.BLUE }, new float[] { 0, 1 }), null, LayoutMetrics.unspecified());
        b = bitmap(gradient);
        check(Color.red(b.getPixel(6, 15)) > Color.blue(b.getPixel(6, 15)), "gradient start");
        check(Color.blue(b.getPixel(21, 15)) > Color.red(b.getPixel(21, 15)), "gradient end");
        RecipeDrawable transparentStroke = drawable(ShapeSpec.rectangle(), FillSpec.solid(Color.GREEN),
                new StrokeSpec(Length.px(4), Color.TRANSPARENT), LayoutMetrics.unspecified());
        b = bitmap(transparentStroke);
        check(b.getPixel(4, 15) == Color.TRANSPARENT && b.getPixel(8, 15) == Color.GREEN,
                "transparent stroke affects geometry");
    }
    private void statesAndIsolation() {
        int pressed = android.R.attr.state_pressed;
        StateColorList rules = new StateColorList(Arrays.asList(new StateSpec(pressed), new StateSpec(-pressed)),
                new int[] { Color.RED, Color.BLUE });
        DrawingRecipe recipe = new DrawingRecipe(ShapeSpec.rectangle(), FillSpec.statefulSolid(rules, Color.GREEN),
                null, LayoutMetrics.unspecified());
        RecipeDrawable first = new RecipeDrawable(recipe, 1f);
        RecipeDrawable second = new RecipeDrawable(recipe, 1f);
        first.setBounds(4, 5, 24, 25); second.setBounds(4, 5, 24, 25);
        CallbackProbe callback = new CallbackProbe(); first.setCallback(callback);
        check(first.isStateful(), "stateful flag");
        check(bitmap(first).getPixel(10, 10) == Color.BLUE, "negative state default");
        int[] input = { pressed };
        first.setState(input);
        check(callback.invalidations > 0 && bitmap(first).getPixel(10, 10) == Color.RED, "state redraw");
        input[0] = 0;
        check(first.getState()[0] == pressed, "state input copied");
        int[] exported = first.getState();
        exported[0] = 0;
        check(first.getState()[0] == pressed, "state output copied");
        first.setState(new int[0]);
        first.setState(new int[] { pressed });
        check(bitmap(first).getPixel(10, 10) == Color.RED, "state update after exported array mutation");
        check(bitmap(second).getPixel(10, 10) == Color.BLUE, "owner isolation");
        first.setState(new int[0]);
        check(bitmap(first).getPixel(10, 10) == Color.BLUE, "state restored");
        // The compiled table is memoized on first stateful lookup; repeated round trips must keep
        // matching the direct matcher (same rule order, positive and negative conditions).
        for (int round = 0; round < 8; round++) {
            first.setState(new int[] { pressed });
            check(bitmap(first).getPixel(10, 10) == Color.RED, "memoized table pressed round " + round);
            first.setState(new int[0]);
            check(bitmap(first).getPixel(10, 10) == Color.BLUE, "memoized table default round " + round);
        }
        check(first.getConstantState() != null, "static recipe has reconstructible constant state");
    }
    private void reusedStateArrayAndDistinctKeys() {
        int checked = android.R.attr.state_checked;
        int selected = android.R.attr.state_selected;
        StateColorList rules = new StateColorList(Arrays.asList(
                new StateSpec(checked, 0, selected), new StateSpec(selected), new StateSpec()),
                new int[] { Color.RED, Color.BLUE, Color.GREEN });
        RecipeDrawable d = drawable(ShapeSpec.rectangle(), FillSpec.statefulSolid(rules, Color.MAGENTA),
                null, LayoutMetrics.unspecified());
        check(bitmap(d).getPixel(10, 10) == Color.GREEN, "initial empty state uses wildcard");

        int[] state = { checked, 0, selected };
        check(d.setState(state) && bitmap(d).getPixel(10, 10) == Color.RED,
                "checked matches without selected when spec ends at zero");
        state[0] = selected;
        state[2] = checked;
        check(bitmap(d).getPixel(10, 10) == Color.RED && d.getState()[0] == checked,
                "mutating caller array cannot mutate installed state");
        check(d.setState(state) && bitmap(d).getPixel(10, 10) == Color.BLUE,
                "reusing array switches checked to selected and ignores checked after zero");
        state[0] = 0;
        state[1] = checked;
        state[2] = selected;
        check(d.setState(state) && bitmap(d).getPixel(10, 10) == Color.GREEN,
                "zero at array start ignores checked and selected");
        state[0] = checked;
        state[1] = selected;
        state[2] = 0;
        check(d.setState(state) && bitmap(d).getPixel(10, 10) == Color.RED,
                "checked precedes selected when both are present");
        check(!d.setState(state), "unchanged reused array has no state change");
    }

    private void metricsAndCallbacks() {
        LayoutMetrics metrics = new LayoutMetrics(-1, 20, 3, 4, 1, 2, 3, 4, 0, 0, 0, 0);
        RecipeDrawable d = drawable(ShapeSpec.rectangle(), FillSpec.solid(Color.RED), null, metrics);
        Rect padding = new Rect();
        check(d.getIntrinsicWidth() == -1 && d.getIntrinsicHeight() == 20, "intrinsic metrics");
        check(d.getMinimumWidth() == 3 && d.getMinimumHeight() == 4, "minimum metrics");
        check(d.getPadding(padding) && padding.equals(new Rect(1, 2, 3, 4)), "padding");
        CallbackProbe callback = new CallbackProbe(); d.setCallback(callback);
        Runnable task = new Runnable() { public void run() {} };
        d.scheduleSelf(task, 123L); d.unscheduleSelf(task); d.invalidateSelf();
        check(callback.schedules == 1 && callback.unschedules == 1 && callback.invalidations == 1, "callback forwarding");
        d.setVisible(false, false);
        check(bitmap(d).getPixel(10, 10) == Color.TRANSPARENT, "invisible drawable");
        d.setVisible(true, false);
        check(bitmap(d).getPixel(10, 10) == Color.RED, "visible drawable");
        Outline outline = new Outline(); d.getOutline(outline);
        check(outline.isEmpty(), "conservative outline");
    }
    private void filtersAndAlpha() {
        RecipeDrawable d = drawable(ShapeSpec.rectangle(), FillSpec.solid(Color.RED), null, LayoutMetrics.unspecified());
        d.setAlpha(128);
        int pixel = bitmap(d).getPixel(10, 10);
        check(Color.alpha(pixel) == 128 && Color.red(pixel) >= 250, "global alpha");
        d.setAlpha(255);
        d.setTintList(ColorStateList.valueOf(Color.BLUE));
        check(bitmap(d).getPixel(10, 10) == Color.BLUE, "tint applied");
        d.setColorFilter(new PorterDuffColorFilter(Color.GREEN, PorterDuff.Mode.SRC_IN));
        check(bitmap(d).getPixel(10, 10) == Color.GREEN, "explicit filter overrides tint");
        d.setColorFilter(null);
        check(bitmap(d).getPixel(10, 10) == Color.BLUE, "clearing filter restores tint");
        d.setTintList(null);
        check(bitmap(d).getPixel(10, 10) == Color.RED, "clearing tint restores color");
        // Re-setting the same tint instance or mode cannot change anything: no refresh, no redraw.
        CallbackProbe callback = new CallbackProbe(); d.setCallback(callback);
        ColorStateList blue = ColorStateList.valueOf(Color.BLUE);
        d.setTintList(blue);
        check(callback.invalidations == 1 && bitmap(d).getPixel(10, 10) == Color.BLUE, "installing a tint redraws once");
        d.setTintList(blue);
        check(callback.invalidations == 1 && bitmap(d).getPixel(10, 10) == Color.BLUE,
                "re-setting the same tint instance neither refreshes nor redraws");
        d.setTintMode(PorterDuff.Mode.SRC_OVER);
        check(callback.invalidations == 2 && bitmap(d).getPixel(10, 10) == Color.BLUE, "a new tint mode redraws once");
        d.setTintMode(PorterDuff.Mode.SRC_OVER);
        check(callback.invalidations == 2 && bitmap(d).getPixel(10, 10) == Color.BLUE,
                "re-setting the same tint mode neither refreshes nor redraws");
        d.setTintList(null);
        check(callback.invalidations == 3 && bitmap(d).getPixel(10, 10) == Color.RED, "clearing the tint redraws again");
    }

    /** Compare transparent-endpoint interpolation to a separately constructed platform shader. */
    private void transparentGradientReference() {
        int transparentRed = Color.argb(0, 255, 0, 0);
        int[] colors = { transparentRed, Color.BLUE };
        float[] stops = { 0f, 1f };
        RecipeDrawable actual = drawable(ShapeSpec.rectangle(),
                FillSpec.linearGradient(0, 0, 1, 0, colors, stops), null,
                LayoutMetrics.unspecified());
        for (int alpha : new int[] { 0, 1, 128, 255 }) {
            actual.setAlpha(alpha);
            Bitmap result = bitmap(actual);
            Bitmap reference = Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);
            paint.setAlpha(alpha);
            paint.setShader(new LinearGradient(4, 5, 24, 5, colors, stops, Shader.TileMode.CLAMP));
            new Canvas(reference).drawRect(new RectF(4, 5, 24, 25), paint);
            check(equalPixels(result, reference), "transparent gradient matches platform shader alpha=" + alpha);
        }
    }

    /** Reference path uses platform round-rect normalization, independently of the core helper. */
    private void asymmetricGeometryReference() {
        float[] radii = { 13, 4, 8, 9, 6, 5, 2, 3 };
        Length[] lengths = new Length[8];
        for (int i = 0; i < radii.length; i++) lengths[i] = Length.px(radii[i]);
        RecipeDrawable actual = drawable(ShapeSpec.roundRect(lengths), FillSpec.solid(Color.MAGENTA),
                null, LayoutMetrics.unspecified());
        Bitmap result = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        Bitmap reference = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        Canvas actualCanvas = new Canvas(result);
        Canvas referenceCanvas = new Canvas(reference);
        actualCanvas.translate(6, 4); referenceCanvas.translate(6, 4);
        actualCanvas.rotate(17, 14, 15); referenceCanvas.rotate(17, 14, 15);
        actualCanvas.scale(0.8f, 0.9f, 14, 15); referenceCanvas.scale(0.8f, 0.9f, 14, 15);
        actual.draw(actualCanvas);
        Path path = new Path();
        path.addRoundRect(new RectF(4, 5, 24, 25), radii, Path.Direction.CW);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.MAGENTA);
        referenceCanvas.drawPath(path, paint);
        check(equalPixels(result, reference), "asymmetric transformed shape matches platform path");
    }

    private void statefulTintAndOwnership() {
        int pressed = android.R.attr.state_pressed;
        ColorStateList tint = new ColorStateList(new int[][] { { pressed }, {} },
                new int[] { Color.RED, Color.BLUE });
        DrawingRecipe recipe = new DrawingRecipe(ShapeSpec.rectangle(), FillSpec.solid(Color.WHITE),
                null, LayoutMetrics.unspecified());
        RecipeDrawable first = new RecipeDrawable(recipe, 1f);
        RecipeDrawable second = new RecipeDrawable(recipe, 1f);
        first.setBounds(4, 5, 24, 25); second.setBounds(4, 5, 24, 25);
        first.setTintList(tint); second.setTintList(tint);
        first.setColorFilter(new PorterDuffColorFilter(Color.GREEN, PorterDuff.Mode.SRC_IN));
        first.setState(new int[] { pressed });
        check(bitmap(first).getPixel(10, 10) == Color.GREEN, "explicit filter retains precedence across state change");
        first.setColorFilter(null);
        check(bitmap(first).getPixel(10, 10) == Color.RED, "clearing filter restores current state tint");
        check(bitmap(second).getPixel(10, 10) == Color.BLUE, "tint and state isolated across owners");
        CallbackProbe callback = new CallbackProbe(); first.setCallback(callback);
        check(!first.setState(new int[] { pressed }) && callback.invalidations == 0,
                "same state does not invalidate");
        first.setAlpha(0);
        check(Color.alpha(bitmap(first).getPixel(10, 10)) == 0, "zero alpha");
        check(bitmap(second).getPixel(10, 10) == Color.BLUE, "alpha isolated across owners");
    }

    private void boundsAndMetricsEdges() {
        RecipeDrawable d = drawable(ShapeSpec.rectangle(), FillSpec.solid(Color.RED), null,
                LayoutMetrics.unspecified());
        Rect padding = new Rect(9, 9, 9, 9);
        check(!d.getPadding(padding) && padding.equals(new Rect()), "absent padding clears output");
        d.setBounds(0, 0, 0, 0);
        check(bitmap(d).getPixel(10, 10) == Color.TRANSPARENT, "empty bounds do not draw");
        d.setBounds(4, 5, 24, 25);
        check(bitmap(d).getPixel(10, 10) == Color.RED, "nonempty bounds restore drawing");
        RecipeDrawable wide = new RecipeDrawable(new DrawingRecipe(ShapeSpec.rectangle(), FillSpec.solid(Color.RED),
                new StrokeSpec(Length.px(20), Color.TRANSPARENT), LayoutMetrics.unspecified()), 1f);
        boolean rejected = false;
        try { wide.setBounds(4, 5, 24, 25); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "excessive stroke rejected in bounds preparation");
        wide.setBounds(0, 0, 30, 30);
        check(bitmap(wide).getPixel(15, 15) == Color.RED, "valid bounds recover after rejected stroke");
    }

    private void constantStateAndOwnerIsolation() {
        int pressed = android.R.attr.state_pressed;
        StateColorList colors = new StateColorList(Arrays.asList(new StateSpec(pressed), new StateSpec()),
                new int[] { Color.RED, Color.BLUE });
        DrawingRecipe recipe = new DrawingRecipe(ShapeSpec.rectangle(), FillSpec.statefulSolid(colors, Color.GREEN),
                null, new LayoutMetrics(-1, 20, 3, 4, 1, 2, 3, 4, 0, 0, 0, 0));
        RecipeDrawable original = new RecipeDrawable(recipe, 2f);
        original.setBounds(4, 5, 24, 25);
        original.setState(new int[] { pressed });
        original.setLevel(777);
        original.setAlpha(128);
        original.setChangingConfigurations(0x400);
        CallbackProbe originalCallback = new CallbackProbe();
        original.setCallback(originalCallback);

        Drawable.ConstantState snapshot = original.getConstantState();
        check(snapshot != null, "static recipe can be reconstructed");
        check(snapshot.getChangingConfigurations() == 0x400, "constant configurations recorded");
        RecipeDrawable a = (RecipeDrawable) snapshot.newDrawable();
        RecipeDrawable b = (RecipeDrawable) snapshot.newDrawable(getTargetContext().getResources());
        check(a != b && a != original && b != original, "newDrawable returns separate owners");
        check(a.mutate() == a && b.mutate() == b, "mutate returns the owned instance");
        check(a.getBounds().isEmpty() && b.getBounds().isEmpty(), "bounds are not copied from owner");
        check(a.getLevel() == 0 && b.getLevel() == 0, "level is not copied from owner");
        check(a.getState().length == 0 && b.getState().length == 0, "state is not copied from owner");
        check(a.getCallback() == null && b.getCallback() == null, "callback is not copied from owner");
        check(a.getAlpha() == 128 && b.getAlpha() == 128, "appearance alpha is reconstructed");
        check(a.getChangingConfigurations() == 0x400, "instance configurations are reconstructed");
        a.setBounds(4, 5, 24, 25);
        b.setBounds(4, 5, 24, 25);
        check(Color.blue(bitmap(a).getPixel(10, 10)) >= 250
                && Color.alpha(bitmap(a).getPixel(10, 10)) == 128, "default state and alpha restored");
        a.setState(new int[] { pressed });
        a.setAlpha(255);
        check(bitmap(a).getPixel(10, 10) == Color.RED, "first owner changes independently");
        check(Color.blue(bitmap(b).getPixel(10, 10)) >= 250
                && Color.alpha(bitmap(b).getPixel(10, 10)) == 128, "second owner keeps its appearance");
        check(Color.red(bitmap(original).getPixel(10, 10)) >= 250
                && Color.alpha(bitmap(original).getPixel(10, 10)) == 128, "original owner keeps its appearance");
        a.setLevel(-7);
        check(a.getLevel() == -7 && b.getLevel() == 0 && original.getLevel() == 777,
                "level is per owner and is not clamped");
        a.setVisible(false, false);
        check(!a.isVisible() && b.isVisible() && original.isVisible(), "visibility is per owner");
        CallbackProbe aCallback = new CallbackProbe();
        a.setCallback(aCallback);
        a.invalidateSelf();
        check(aCallback.invalidations == 1 && originalCallback.invalidations == 0,
                "callbacks are per owner");
        Rect padding = new Rect();
        check(a.getIntrinsicWidth() == -1 && a.getIntrinsicHeight() == 20
                && a.getMinimumWidth() == 3 && a.getMinimumHeight() == 4
                && a.getPadding(padding) && padding.equals(new Rect(1, 2, 3, 4)), "fixed metrics reconstructed");
        original.setColorFilter(new PorterDuffColorFilter(Color.YELLOW, PorterDuff.Mode.SRC_IN));
        check(original.getConstantState() == null, "mutable external filter cannot be reconstructed");
        original.setColorFilter(null);
        check(original.getConstantState() != null, "clearing filter restores reconstructibility");
        original.setTintList(ColorStateList.valueOf(Color.BLUE));
        check(original.getConstantState() == null, "tint is conservatively not reconstructed");
        RecipeDrawable beforeChange = (RecipeDrawable) snapshot.newDrawable();
        beforeChange.setBounds(4, 5, 24, 25);
        check(Color.blue(bitmap(beforeChange).getPixel(10, 10)) >= 250, "snapshot remains stable after later owner change");
    }

    /** Draw and compare candidate/reference on a real hardware-accelerated window. */
    private void hardwareCanvasReference() throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 26) {
            throw new UnsupportedOperationException("hardware window capture requires API 26+");
        }
        Intent intent = new Intent(getTargetContext(), HardwareProbeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        HardwareProbeActivity activity = (HardwareProbeActivity) startActivitySync(intent);
        try {
            check(activity.drawn.await(10, TimeUnit.SECONDS), "hardware fixture draw completed");
            check(activity.hardwareCanvas, "fixture Canvas is hardware accelerated");
            check(activity.viewWidth >= 220 && activity.viewHeight >= 400, "hardware fixture is visible");
            waitForIdleSync();
            boolean matched = false;
            boolean visible = false;
            HardwareDifference gradient = null, rounded = null, checked = null, selected = null;
            String failureImage = "not captured";
            // onDraw may precede display presentation; bound the wait and inspect only our fixture.
            for (int attempt = 0; attempt < 30 && !matched; attempt++) {
                Bitmap screenshot = HardwareCaptureApi26.capture(activity);
                if (screenshot != null) {
                    // Keep the original gradient comparison region; other scenes include their edges.
                    gradient = compareHardwarePixels(screenshot, 21, 21, 99, 99);
                    rounded = compareHardwarePixels(screenshot, 20, 120, 100, 200);
                    checked = compareHardwarePixels(screenshot, 20, 220, 100, 300);
                    selected = compareHardwarePixels(screenshot, 20, 320, 100, 400);
                    int leftGradient = screenshot.getPixel(22, 50);
                    int rightGradient = screenshot.getPixel(98, 50);
                    int roundCenter = screenshot.getPixel(60, 160);
                    int checkedCenter = screenshot.getPixel(60, 260);
                    int selectedCenter = screenshot.getPixel(60, 360);
                    // Require real drawn colors, not an equally blank or black pair of panels.
                    visible = Color.red(leftGradient) > 100 && Color.blue(rightGradient) > 100
                            && Color.blue(roundCenter) > Color.red(roundCenter)
                            && Color.red(checkedCenter) > Color.green(checkedCenter) + 40
                            && Color.blue(selectedCenter) > Color.red(selectedCenter) + 100;
                    matched = gradient.count == 0 && rounded.count == 0 && checked.count == 0
                            && selected.count == 0 && visible;
                    if (!matched && attempt == 29) failureImage = saveHardwareFailure(activity, screenshot);
                    screenshot.recycle();
                }
                if (!matched) Thread.sleep(100);
            }
            check(gradient != null && gradient.count == 0,
                    "hardware gradient differs: " + gradient + " failure image=" + failureImage);
            check(rounded != null && rounded.count == 0,
                    "hardware asymmetric round rect differs: " + rounded + " failure image=" + failureImage);
            check(checked != null && checked.count == 0,
                    "hardware checked alpha differs: " + checked + " failure image=" + failureImage);
            check(selected != null && selected.count == 0,
                    "hardware selected color differs: " + selected + " failure image=" + failureImage);
            check(visible, "hardware fixture produced no expected color signals; failure image=" + failureImage);
        } finally {
            runOnMainSync(new Runnable() { @Override public void run() { activity.finish(); } });
        }
    }

    private static HardwareDifference compareHardwarePixels(Bitmap image, int left, int top, int right, int bottom) {
        HardwareDifference result = new HardwareDifference();
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int actual = image.getPixel(x, y);
                int expected = image.getPixel(x + 120, y);
                if (actual == expected) continue;
                if (result.count++ == 0) {
                    result.x = x; result.y = y; result.actual = actual; result.expected = expected;
                }
                result.maxDelta = Math.max(result.maxDelta, Math.abs(Color.alpha(actual) - Color.alpha(expected)));
                result.maxDelta = Math.max(result.maxDelta, Math.abs(Color.red(actual) - Color.red(expected)));
                result.maxDelta = Math.max(result.maxDelta, Math.abs(Color.green(actual) - Color.green(expected)));
                result.maxDelta = Math.max(result.maxDelta, Math.abs(Color.blue(actual) - Color.blue(expected)));
            }
        }
        return result;
    }

    private static final class HardwareDifference {
        int count, x = -1, y = -1, actual, expected, maxDelta;
        @Override public String toString() {
            return "differences=" + count + " first=" + Integer.toHexString(actual) + "/"
                    + Integer.toHexString(expected) + " x=" + x + " y=" + y + " max_channel_delta=" + maxDelta;
        }
    }

    /** Store only this fixture's captured window when a pixel comparison fails. */
    private static String saveHardwareFailure(Activity activity, Bitmap image) {
        try {
            File file = File.createTempFile("hardware-failure-", ".png", activity.getFilesDir());
            try (FileOutputStream stream = new FileOutputStream(file)) {
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("PNG encoding failed");
            }
            return file.getAbsolutePath();
        } catch (IOException error) {
            return "capture save failed: " + error.getClass().getSimpleName();
        }
    }

    private static boolean equalPixels(Bitmap a, Bitmap b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) return false;
            }
        }
        return true;
    }
    private void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError(what); }

    /** Same state, no callbacks: paired runs compare the old clone path with the current implementation. */
    private String benchmarkSameState() {
        int[] state = { android.R.attr.state_pressed, android.R.attr.state_enabled };
        Drawable old = new CloneStateDrawable();
        RecipeDrawable current = new RecipeDrawable(new DrawingRecipe(ShapeSpec.rectangle(),
                FillSpec.solid(Color.RED), null, LayoutMetrics.unspecified()), 1f);
        old.setState(state); current.setState(state);
        if (!Arrays.equals(old.getState(), current.getState()) || old.setState(state) || current.setState(state)) {
            throw new AssertionError("benchmark requires identical, unchanged states");
        }
        final int count = 500000;
        timed(old, state, count); timed(current, state, count);
        timed(current, state, count); timed(old, state, count);
        StringBuilder out = new StringBuilder("\nSTATE_TIMING pid=").append(android.os.Process.myPid())
                .append(" api=").append(android.os.Build.VERSION.SDK_INT)
                .append(" ops=").append(count).append(" warmup=2-per-path pairs=12 ns_per_op\n");
        for (int i = 0; i < 12; i++) {
            long oldNanos, newNanos;
            if ((i & 1) == 0) {
                oldNanos = timed(old, state, count); newNanos = timed(current, state, count);
            } else {
                newNanos = timed(current, state, count); oldNanos = timed(old, state, count);
            }
            out.append("pair=").append(i + 1).append(" old=")
                    .append((double) oldNanos / count).append(" candidate=")
                    .append((double) newNanos / count).append('\n');
        }
        return out.toString();
    }
    private static long timed(Drawable d, int[] state, int count) {
        int changes = 0;
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) if (d.setState(state)) changes++;
        long elapsed = System.nanoTime() - start;
        timingSink = changes;
        if (changes != 0) throw new AssertionError("unexpected state change");
        return elapsed;
    }
    private static final class CloneStateDrawable extends Drawable {
        @Override public boolean setState(int[] state) { return super.setState(state.clone()); }
        @Override public void draw(Canvas canvas) {}
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    private static final class CallbackProbe implements Drawable.Callback {
        int invalidations, schedules, unschedules;
        @Override public void invalidateDrawable(Drawable who) { invalidations++; }
        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { schedules++; }
        @Override public void unscheduleDrawable(Drawable who, Runnable what) { unschedules++; }
    }
}
