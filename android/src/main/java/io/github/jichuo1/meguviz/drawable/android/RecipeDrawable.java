package io.github.jichuo1.meguviz.drawable.android;

import android.content.res.ColorStateList;
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

import io.github.jichuo1.meguviz.drawable.core.CornerRadii;
import io.github.jichuo1.meguviz.drawable.core.StateMatcher;
import io.github.jichuo1.meguviz.drawable.core.StateMatcherCache;
import io.github.jichuo1.meguviz.drawable.model.DrawingRecipe;
import io.github.jichuo1.meguviz.drawable.model.FillSpec;
import io.github.jichuo1.meguviz.drawable.model.LayoutMetrics;
import io.github.jichuo1.meguviz.drawable.model.Length;
import io.github.jichuo1.meguviz.drawable.model.ShapeSpec;
import io.github.jichuo1.meguviz.drawable.model.StrokeSpec;

import java.util.Arrays;

/** A static, software-capable Drawable for built-in shapes. Instances must not be shared by owners. */
public final class RecipeDrawable extends Drawable {
    private final DrawingRecipe recipe;
    private final float density;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shapeBounds = new RectF();
    private final Path roundedPath = new Path();
    private final float[] radius = new float[8];
    private final float[] resolved = new float[8];
    private boolean hasGeometry;
    private boolean uniformRadius;
    private int alpha = 255;
    private ColorFilter explicitFilter;
    private ColorStateList tint;
    private PorterDuff.Mode tintMode = PorterDuff.Mode.SRC_IN;
    private ColorFilter tintFilter;
    private int tintColor;
    /** Owner-thread memo of the compiled state table; null means "not resolved yet" or "use direct". */
    private StateMatcher.PreparedMatcher stateMatcher;
    private boolean stateMatcherResolved;

    /** Density is fixed for the lifetime of this instance. Construct a new instance on density change. */
    public RecipeDrawable(DrawingRecipe recipe, float density) {
        if (recipe == null) throw new NullPointerException("recipe");
        if (Float.isNaN(density) || Float.isInfinite(density) || density <= 0f) {
            throw new IllegalArgumentException("density must be finite and positive");
        }
        LayoutMetrics metrics = recipe.metrics();
        if (metrics.opticalInsetLeft() != 0 || metrics.opticalInsetTop() != 0
                || metrics.opticalInsetRight() != 0 || metrics.opticalInsetBottom() != 0) {
            throw new IllegalArgumentException("optical insets are not supported by this backend");
        }
        this.recipe = recipe;
        this.density = density;
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        updateAppearance();
    }

    public DrawingRecipe recipe() { return recipe; }

    @Override public void draw(Canvas canvas) {
        if (!hasGeometry || !isVisible()) return;
        drawShape(canvas, fillPaint);
        if (recipe.stroke() != null && strokePaint.getStrokeWidth() > 0f) {
            drawShape(canvas, strokePaint);
        }
    }

    private void drawShape(Canvas canvas, Paint paint) {
        switch (recipe.shape().kind()) {
            case RECTANGLE: canvas.drawRect(shapeBounds, paint); break;
            case ELLIPSE: canvas.drawOval(shapeBounds, paint); break;
            case ROUND_RECT:
                if (uniformRadius) canvas.drawRoundRect(shapeBounds, radius[0], radius[1], paint);
                else canvas.drawPath(roundedPath, paint);
                break;
            default: throw new AssertionError("unsupported shape");
        }
    }

    @Override protected void onBoundsChange(Rect bounds) {
        float width = (float) ((double) bounds.right - bounds.left);
        float height = (float) ((double) bounds.bottom - bounds.top);
        hasGeometry = false;
        fillPaint.setShader(null);
        roundedPath.reset();
        if (width <= 0f || height <= 0f) return;
        if ((float) bounds.right <= (float) bounds.left || (float) bounds.bottom <= (float) bounds.top) {
            throw new IllegalArgumentException("bounds collapse at Canvas float precision");
        }

        StrokeSpec stroke = recipe.stroke();
        float strokeWidth = stroke == null ? 0f : resolve(stroke.width(), width, height);
        strokePaint.setStrokeWidth(strokeWidth);
        if (strokeWidth >= width || strokeWidth >= height) {
            throw new IllegalArgumentException("stroke is too wide for these bounds");
        }
        float inset = strokeWidth / 2f;
        shapeBounds.set(bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset);
        if (shapeBounds.width() <= 0f || shapeBounds.height() <= 0f) {
            throw new IllegalArgumentException("shape bounds collapse at Canvas float precision");
        }

        ShapeSpec shape = recipe.shape();
        if (shape.kind() == ShapeSpec.Kind.ROUND_RECT) {
            Length[] source = shape.radii();
            // Reused scratch: CornerRadii.normalize copies its input and never keeps it, and a
            // drawable instance is confined to its owner thread, so this array never escapes.
            float[] resolved = this.resolved;
            for (int i = 0; i < 8; i++) resolved[i] = resolve(source[i], width, height);
            float[] normalized = CornerRadii.normalize(shapeBounds.width(), shapeBounds.height(), resolved);
            System.arraycopy(normalized, 0, radius, 0, radius.length);
            uniformRadius = true;
            for (int i = 2; i < 8; i += 2) {
                uniformRadius &= radius[i] == radius[0] && radius[i + 1] == radius[1];
            }
            if (!uniformRadius) roundedPath.addRoundRect(shapeBounds, radius, Path.Direction.CW);
        }
        FillSpec fill = recipe.fill();
        if (fill.kind() == FillSpec.Kind.LINEAR_GRADIENT) {
            float startX = gradientCoordinate(bounds.left, fill.startX(), width);
            float startY = gradientCoordinate(bounds.top, fill.startY(), height);
            float endX = gradientCoordinate(bounds.left, fill.endX(), width);
            float endY = gradientCoordinate(bounds.top, fill.endY(), height);
            if (startX == endX && startY == endY) {
                throw new IllegalArgumentException("gradient endpoints collapse at these bounds");
            }
            fillPaint.setShader(new LinearGradient(startX, startY, endX, endY,
                    fill.colors(), fill.stops(), Shader.TileMode.CLAMP));
        }
        hasGeometry = true;
    }

    private static float gradientCoordinate(int origin, float fraction, float extent) {
        double coordinate = (double) origin + (double) fraction * extent;
        float result = (float) coordinate;
        if (Float.isNaN(result) || Float.isInfinite(result)) {
            throw new IllegalArgumentException("gradient coordinate is not finite at these bounds");
        }
        return result;
    }

    private float resolve(Length length, float width, float height) {
        float value;
        switch (length.unit()) {
            case PX: value = length.value(); break;
            case DP: value = length.value() * density; break;
            case BOUNDS_FRACTION:
                value = length.value() * (length.axis() == Length.Axis.WIDTH ? width : height);
                break;
            default: throw new AssertionError("unsupported unit");
        }
        if (Float.isInfinite(value) || Float.isNaN(value)) {
            throw new IllegalArgumentException("resolved geometry is not finite");
        }
        return value;
    }

    @Override public void setAlpha(int alpha) {
        if (alpha < 0 || alpha > 255) throw new IllegalArgumentException("alpha must be in [0,255]");
        if (this.alpha != alpha) { this.alpha = alpha; updateAppearance(); invalidateSelf(); }
    }
    @Override public int getAlpha() { return alpha; }

    @Override public void setColorFilter(ColorFilter filter) {
        if (explicitFilter != filter) { explicitFilter = filter; updateAppearance(); invalidateSelf(); }
    }
    @Override public ColorFilter getColorFilter() { return explicitFilter; }
    @Override public void setTintList(ColorStateList tint) {
        // Re-setting the same instance cannot change anything; skip the refresh and the redraw.
        if (this.tint != tint) {
            this.tint = tint;
            updateTint();
            invalidateSelf();
        }
    }
    @Override public void setTint(int color) { setTintList(ColorStateList.valueOf(color)); }
    @Override public void setTintMode(PorterDuff.Mode mode) {
        if (tintMode != mode) {
            tintMode = mode;
            updateTint();
            invalidateSelf();
        }
    }
    @Override public boolean isStateful() {
        return recipe.fill().kind() == FillSpec.Kind.STATEFUL_SOLID || (tint != null && tint.isStateful());
    }
    /** Copies caller input before the framework stores or compares it. */
    @Override public boolean setState(int[] stateSet) {
        if (stateSet == null) throw new NullPointerException("stateSet");
        if (Arrays.equals(super.getState(), stateSet)) return false;
        return super.setState(stateSet.clone());
    }
    /** The framework retains its state array, so never expose that array to callers. */
    @Override public int[] getState() { return super.getState().clone(); }
    @Override protected boolean onStateChange(int[] state) {
        int previousFill = fillPaint.getColor();
        boolean tintChanged = false;
        if (tint != null && tint.isStateful()) {
            int nextTintColor = tint.getColorForState(state, tint.getDefaultColor());
            if (tintMode != null && tintColor != nextTintColor) {
                tintColor = nextTintColor;
                tintFilter = new PorterDuffColorFilter(tintColor, tintMode);
                tintChanged = explicitFilter == null;
            }
        }
        if (recipe.fill().kind() == FillSpec.Kind.STATEFUL_SOLID || tintChanged) updateAppearance();
        boolean changed = previousFill != fillPaint.getColor() || tintChanged;
        if (changed) invalidateSelf();
        return changed;
    }
    private void updateTint() {
        tintColor = tint == null ? 0 : tint.getColorForState(super.getState(), tint.getDefaultColor());
        tintFilter = tint == null || tintMode == null ? null : new PorterDuffColorFilter(tintColor, tintMode);
        updateAppearance();
    }
    private void updateAppearance() {
        FillSpec fill = recipe.fill();
        int fillColor;
        if (fill.kind() == FillSpec.Kind.SOLID) {
            fillColor = fill.color();
        } else if (fill.kind() == FillSpec.Kind.STATEFUL_SOLID) {
            StateMatcher.PreparedMatcher prepared = stateMatcher();
            int index = prepared == null
                    ? StateMatcher.matchIndex(fill.stateColors(), super.getState())
                    : prepared.matchIndex(super.getState());
            fillColor = index < 0 ? fill.fallbackColor() : fill.stateColors().colorAt(index);
        } else {
            fillColor = Color.WHITE;
        }
        fillPaint.setColor(fillColor);
        fillPaint.setAlpha(multipliedAlpha(Color.alpha(fillColor), alpha));
        StrokeSpec stroke = recipe.stroke();
        if (stroke != null) {
            strokePaint.setColor(stroke.color());
            strokePaint.setAlpha(multipliedAlpha(Color.alpha(stroke.color()), alpha));
        }
        ColorFilter filter = explicitFilter != null ? explicitFilter : tintFilter;
        fillPaint.setColorFilter(filter);
        strokePaint.setColorFilter(filter);
    }
    private static int multipliedAlpha(int first, int second) { return (first * second + 127) / 255; }

    /**
     * Compiles the state table once per instance, on the first stateful lookup.
     *
     * <p>Deliberately lazy: {@code ConstantState.newDrawable()} and XML inflation create many
     * instances that may never change state, and preparing at construction would pay the table
     * cost (up to ~2 ms at K=16) for each of them. The table depends only on rule order and
     * conditions, so a cached instance stays valid for the immutable recipe.
     */
    private StateMatcher.PreparedMatcher stateMatcher() {
        if (!stateMatcherResolved) {
            stateMatcherResolved = true;
            // Shared, bounded cache: instances with the same rule conditions reuse one table, so
            // the one-off preparation cost is amortized across them. Null means "use direct".
            stateMatcher = StateMatcherCache.shared().acquire(recipe.fill().stateColors());
        }
        return stateMatcher;
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    /**
     * Captures the immutable recipe and reconstructible appearance, never the
     * owner's bounds, state, level, callback or mutable drawing objects.
     * Arbitrary filters and tint cannot be safely reconstructed here.
     */
    @Override public ConstantState getConstantState() {
        if (explicitFilter != null || tint != null) return null;
        return new RecipeState(recipe, density, alpha, tintMode, getChangingConfigurations());
    }

    /** This drawable already owns all mutable runtime objects independently. */
    @Override public Drawable mutate() { return this; }

    private static final class RecipeState extends ConstantState {
        private final DrawingRecipe recipe;
        private final float density;
        private final int alpha;
        private final PorterDuff.Mode tintMode;
        private final int configurations;

        RecipeState(DrawingRecipe recipe, float density, int alpha, PorterDuff.Mode tintMode, int configurations) {
            this.recipe = recipe;
            this.density = density;
            this.alpha = alpha;
            this.tintMode = tintMode;
            this.configurations = configurations;
        }

        @Override public Drawable newDrawable() {
            RecipeDrawable result = new RecipeDrawable(recipe, density);
            result.alpha = alpha;
            result.tintMode = tintMode;
            result.setChangingConfigurations(configurations);
            result.updateTint();
            return result;
        }

        @Override public int getChangingConfigurations() { return configurations; }
    }

    @Override public int getIntrinsicWidth() { return recipe.metrics().intrinsicWidth(); }
    @Override public int getIntrinsicHeight() { return recipe.metrics().intrinsicHeight(); }
    @Override public int getMinimumWidth() { return recipe.metrics().minimumWidth(); }
    @Override public int getMinimumHeight() { return recipe.metrics().minimumHeight(); }
    @Override public boolean getPadding(Rect padding) {
        LayoutMetrics m = recipe.metrics();
        padding.set(m.paddingLeft(), m.paddingTop(), m.paddingRight(), m.paddingBottom());
        return (m.paddingLeft() | m.paddingTop() | m.paddingRight() | m.paddingBottom()) != 0;
    }
    @Override public void getOutline(Outline outline) {
        outline.setEmpty();
        // Alpha and color filters can change actual coverage; do not claim opacity.
        outline.setAlpha(0f);
    }
}
