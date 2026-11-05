package io.github.jichuo1.meguviz.drawable.compose.test

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Choreographer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.painter.Painter
import io.github.jichuo1.meguviz.drawable.compose.DrawableState
import io.github.jichuo1.meguviz.drawable.compose.RecipePainter
import io.github.jichuo1.meguviz.drawable.compose.rememberRecipePainter
import io.github.jichuo1.meguviz.drawable.model.DrawingRecipe
import io.github.jichuo1.meguviz.drawable.model.FillSpec
import io.github.jichuo1.meguviz.drawable.model.LayoutMetrics
import io.github.jichuo1.meguviz.drawable.model.Length
import io.github.jichuo1.meguviz.drawable.model.ShapeSpec
import io.github.jichuo1.meguviz.drawable.model.StateColorList
import io.github.jichuo1.meguviz.drawable.model.StateSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Cache-invalidation, direction and release contracts, observed through real composition and drawing. */
internal class ComposeContract(private val instrumentation: Instrumentation) {
    private var checks = 0
    private lateinit var activity: ComposeHostActivity
    private lateinit var composeView: ComposeView

    // Observations written from composition, read after the UI settles.
    private val painters = mutableListOf<Painter>()
    private var originX = 0
    private var originY = 0
    private var positioned = false

    // Inputs the contracts change from the test thread.
    private var state by mutableStateOf(DrawableState.Default)
    private var recipe by mutableStateOf(solid(Color.RED))
    private var densityValue by mutableFloatStateOf(1f)
    private var direction by mutableStateOf(LayoutDirection.Ltr)
    private var mirror by mutableStateOf(false)
    private var visible by mutableStateOf(true)
    private var alpha by mutableFloatStateOf(1f)

    fun run(): Int {
        val intent = Intent(instrumentation.targetContext, ComposeHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instrumentation.startActivitySync(intent) as ComposeHostActivity
        try {
            setContent { Subject() }
            rendersThroughTheSharedBackend()
            stateUpdatesWithoutNewPainter()
            recipeChangeReplacesAndReleases()
            densityChangeReplacesAndReleases()
            directionMirrorsOnlyWhenAsked()
            alphaIsForwarded()
            hardwareWindowMatchesSoftwareDrawing()
            leavingCompositionReleases()
            closedPainterIsInert()
        } finally {
            onMain { activity.finish() }
        }
        return checks
    }

    @Composable
    private fun Subject() {
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(densityValue, base.fontScale),
            LocalLayoutDirection provides direction,
        ) {
            Box {
                if (visible) {
                    val painter = rememberRecipePainter(recipe, state, mirror)
                    SideEffect { if (painters.lastOrNull() !== painter) painters.add(painter) }
                    // Always 100 pixels, whatever the density, so probes are comparable.
                    val edge = with(LocalDensity.current) { 100.toDp() }
                    Image(
                        painter = painter,
                        contentDescription = null,
                        alpha = alpha,
                        modifier = Modifier.size(edge).onGloballyPositioned {
                            val position = it.positionInRoot()
                            originX = position.x.toInt()
                            originY = position.y.toInt()
                            positioned = true
                        },
                    )
                }
            }
        }
    }

    private fun rendersThroughTheSharedBackend() {
        val image = capture()
        check(pixel(image, 50, 50) == Color.RED, "solid fill drawn through Compose")
        check(Color.alpha(pixel(image, 150, 150)) == 0, "nothing drawn outside the image bounds")
        check(painterCount() == 1 && !(lastPainter() as RecipePainter).isClosed, "one live painter after first composition")
    }

    private fun stateUpdatesWithoutNewPainter() {
        update { recipe = pressedRed(); state = DrawableState.Default }
        val first = lastPainter()
        check(pixel(capture(), 50, 50) == Color.BLUE, "unpressed state uses the fallback color")
        update { state = DrawableState(pressed = true) }
        check(pixel(capture(), 50, 50) == Color.RED, "pressed state selects the pressed color")
        check(lastPainter() === first, "a state change keeps the painter")
        update { state = DrawableState.Default }
        check(pixel(capture(), 50, 50) == Color.BLUE, "state change back")
        val unpressed = DrawableState.Default
        check(unpressed == DrawableState() && unpressed.hashCode() == DrawableState().hashCode()
            && unpressed != DrawableState(pressed = true), "state values compare structurally")
        val pressedChecked = DrawableState(pressed = true, checked = true).stateSet
        check(pressedChecked.contentEquals(intArrayOf(
            android.R.attr.state_enabled, android.R.attr.state_pressed, android.R.attr.state_checked)),
            "state flags map to platform attributes in order")
        check(DrawableState.Default.stateSet.contentEquals(intArrayOf(android.R.attr.state_enabled)),
            "default state is enabled only")
        check(DrawableState(enabled = false).stateSet.isEmpty(), "no flags yields an empty array")
    }

    private fun recipeChangeReplacesAndReleases() {
        val before = lastPainter() as RecipePainter
        update { recipe = solid(Color.GREEN); state = DrawableState.Default }
        val after = lastPainter() as RecipePainter
        check(after !== before && before.isClosed && !after.isClosed, "a different recipe replaces and releases the painter")
        check(pixel(capture(), 50, 50) == Color.GREEN, "the new recipe is drawn")
        val sizeBefore = painterCount()
        update { recipe = solid(Color.GREEN) }
        check(painterCount() == sizeBefore, "an equal recipe reuses the painter")
    }

    private fun densityChangeReplacesAndReleases() {
        // A 10 dp corner radius: 10 px at density 1 keeps (4,4) inside the arc, 30 px at density 3 does not.
        update { recipe = rounded(Color.MAGENTA, Length.dp(10f)); densityValue = 1f }
        val atOne = lastPainter() as RecipePainter
        check(Color.alpha(pixel(capture(), 4, 4)) == 255, "small radius covers the corner probe")
        update { densityValue = 3f }
        val atThree = lastPainter() as RecipePainter
        check(atThree !== atOne && atOne.isClosed, "a density change replaces and releases the painter")
        check(Color.alpha(pixel(capture(), 4, 4)) == 0, "the larger radius at density 3 leaves the corner empty")
        update { densityValue = 1f }
    }

    private fun directionMirrorsOnlyWhenAsked() {
        update { recipe = gradient(); mirror = false; direction = LayoutDirection.Ltr }
        var image = capture()
        check(redDominant(pixel(image, 10, 50)) && blueDominant(pixel(image, 90, 50)), "gradient runs left to right in LTR")
        update { direction = LayoutDirection.Rtl }
        image = capture()
        check(redDominant(pixel(image, 10, 50)) && blueDominant(pixel(image, 90, 50)),
            "RTL alone does not mirror: the recipe is direction-agnostic")
        update { mirror = true }
        image = capture()
        check(blueDominant(pixel(image, 10, 50)) && redDominant(pixel(image, 90, 50)), "RTL with mirroring flips the drawing")
        update { direction = LayoutDirection.Ltr }
        image = capture()
        check(redDominant(pixel(image, 10, 50)) && blueDominant(pixel(image, 90, 50)), "mirroring is inactive in LTR")
        update { mirror = false }
    }

    private fun alphaIsForwarded() {
        update { recipe = solid(Color.RED); alpha = 0.5f }
        val half = Color.alpha(pixel(capture(), 50, 50))
        check(half in 120..135, "Image alpha reaches the drawable (was $half)")
        update { alpha = 1f }
        check(Color.alpha(pixel(capture(), 50, 50)) == 255, "alpha restored")
    }

    /** The same recipe through the window's real hardware pipeline (PixelCopy), not just a software redraw. */
    private fun hardwareWindowMatchesSoftwareDrawing() {
        if (Build.VERSION.SDK_INT < 26) return
        update { recipe = gradient(); mirror = false; direction = LayoutDirection.Ltr; alpha = 1f }
        val location = IntArray(2)
        onMain { composeView.getLocationInWindow(location) }
        val hardware = AtomicReference<Bitmap>()
        val copied = CountDownLatch(1)
        val outcome = AtomicReference<Int>()
        val shot = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
        onMain {
            PixelCopy.request(activity.window, shot, { code -> outcome.set(code); copied.countDown() }, Handler(Looper.getMainLooper()))
        }
        check(copied.await(10, TimeUnit.SECONDS) && outcome.get() == PixelCopy.SUCCESS, "window pixels copied (code ${outcome.get()})")
        hardware.set(shot)
        val x = location[0] + originX
        val y = location[1] + originY
        check(redDominant(shot.getPixel(x + 10, y + 50)) && blueDominant(shot.getPixel(x + 90, y + 50)),
            "the hardware window shows the same gradient")
        val software = capture()
        val sw = pixel(software, 10, 50)
        val hw = shot.getPixel(x + 10, y + 50)
        check(kotlin.math.abs(Color.red(sw) - Color.red(hw)) <= 2 && kotlin.math.abs(Color.blue(sw) - Color.blue(hw)) <= 2,
            "hardware and software draw the same left pixel")
    }

    private fun leavingCompositionReleases() {
        val live = lastPainter() as RecipePainter
        check(!live.isClosed, "painter is live while composed")
        update { visible = false }
        check(live.isClosed, "composition forgetting the painter releases it")
        update { visible = true }
        val reborn = lastPainter() as RecipePainter
        check(reborn !== live && !reborn.isClosed, "re-entering composition creates a fresh painter")
    }

    private fun closedPainterIsInert() {
        val painter = RecipePainter(solid(Color.RED), 1f)
        painter.close()
        painter.close()
        check(painter.isClosed, "close is idempotent")
        painter.state = DrawableState(pressed = true)
        check(painter.state == DrawableState.Default, "a closed painter ignores state assignments")
        check(painter.intrinsicSize == androidx.compose.ui.geometry.Size.Unspecified, "unspecified metrics give an unspecified size")
    }

    private fun solid(color: Int) =
        DrawingRecipe(ShapeSpec.rectangle(), FillSpec.solid(color), null, LayoutMetrics.unspecified())

    private fun rounded(color: Int, radius: Length) =
        DrawingRecipe(ShapeSpec.roundRect(radius), FillSpec.solid(color), null, LayoutMetrics.unspecified())

    private fun pressedRed() = DrawingRecipe(
        ShapeSpec.rectangle(),
        FillSpec.statefulSolid(
            StateColorList(listOf(StateSpec(android.R.attr.state_pressed)), intArrayOf(Color.RED)),
            Color.BLUE,
        ),
        null,
        LayoutMetrics.unspecified(),
    )

    private fun gradient() = DrawingRecipe(
        ShapeSpec.rectangle(),
        FillSpec.linearGradient(0f, 0f, 1f, 0f, intArrayOf(Color.RED, Color.BLUE), floatArrayOf(0f, 1f)),
        null,
        LayoutMetrics.unspecified(),
    )

    private fun lastPainter(): Painter {
        val result = AtomicReference<Painter>()
        onMain { result.set(painters.last()) }
        return result.get()
    }

    private fun painterCount(): Int {
        val result = AtomicReference<Int>()
        onMain { result.set(painters.size) }
        return result.get()
    }

    private fun redDominant(color: Int) = Color.red(color) > Color.blue(color) + 80
    private fun blueDominant(color: Int) = Color.blue(color) > Color.red(color) + 80

    /** Applies a change on the main thread and waits for recomposition and a following draw. */
    private fun update(change: () -> Unit) {
        onMain(change)
        awaitFrames(3)
    }

    private fun setContent(content: @Composable () -> Unit) {
        onMain {
            composeView = ComposeView(activity).apply { setContent(content) }
            activity.setContentView(composeView)
        }
        awaitFrames(4)
        check(positioned, "the subject was laid out")
    }

    private fun capture(): Bitmap {
        val result = AtomicReference<Bitmap>()
        onMain {
            val bitmap = Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
            composeView.draw(Canvas(bitmap))
            result.set(bitmap)
        }
        return result.get()
    }

    private fun pixel(bitmap: Bitmap, x: Int, y: Int): Int = bitmap.getPixel(originX + x, originY + y)

    private fun awaitFrames(count: Int) {
        repeat(count) {
            val latch = CountDownLatch(1)
            onMain { Choreographer.getInstance().postFrameCallback { latch.countDown() } }
            check(latch.await(5, TimeUnit.SECONDS), "frame callback delivered")
        }
        instrumentation.waitForIdleSync()
    }

    private fun onMain(action: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                action()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw it }
    }

    private fun check(condition: Boolean, label: String) {
        checks++
        if (!condition) throw AssertionError(label)
    }
}
