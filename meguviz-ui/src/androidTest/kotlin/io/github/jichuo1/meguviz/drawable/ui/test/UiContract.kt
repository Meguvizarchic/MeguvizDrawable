package io.github.jichuo1.meguviz.drawable.ui.test

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.jichuo1.meguviz.drawable.android.RecipeDrawable
import io.github.jichuo1.meguviz.drawable.model.DrawingRecipe
import io.github.jichuo1.meguviz.drawable.model.FillSpec
import io.github.jichuo1.meguviz.drawable.model.LayoutMetrics
import io.github.jichuo1.meguviz.drawable.model.ShapeSpec
import io.github.jichuo1.meguviz.drawable.ui.RecipeBackground
import io.github.jichuo1.meguviz.drawable.ui.bindRecipeBackground
import io.github.jichuo1.meguviz.ui.ComponentType
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.ViewRef
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The adapter on a real MeguvizUI session: real scheduler, real property registry, a live View.
 * Session operations run on the main thread; the test thread waits for frames between them.
 */
internal class UiContract(private val instrumentation: Instrumentation) {
    private var checks = 0
    private lateinit var activity: UiHostActivity

    fun run(): Int {
        val intent = Intent(instrumentation.targetContext, UiHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instrumentation.startActivitySync(intent) as UiHostActivity
        try {
            writesThroughTheScheduler()
            skipsEqualRecipesAndKeepsDrawables()
            hostReplacementIsNotOverwritten()
            reclaimWithTheLastSubmittedRecipe()
            densityChangeRebuildsAnEqualRecipe()
            propertyConflictsAreRefused()
            releaseAndRebindClose()
            sessionCloseClearsTheBackground()
        } finally {
            main { activity.finish() }
        }
        return checks
    }

    private fun recipe(color: Int) =
        DrawingRecipe(ShapeSpec.rectangle(), FillSpec.solid(color), null, LayoutMetrics.unspecified())

    private class Fixture(val session: UiSession, val view: View, val ref: ViewRef<View>)

    private fun fixture(): Fixture = main {
        val session = UiSession(CreationEnvironment(activity))
        val parent = FrameLayout(activity)
        activity.addContentView(parent, ViewGroup.LayoutParams(100, 100))
        val view = View(activity)
        parent.addView(view, FrameLayout.LayoutParams(80, 80))
        Fixture(session, view, session.borrow(view, ViewKey("subject"), ComponentType("view", View::class.java)))
    }

    private fun drawnRecipe(view: View): DrawingRecipe? = main { (view.background as? RecipeDrawable)?.recipe() }

    private fun background(view: View): Drawable? = main { view.background }

    private fun submitAndSettle(background: RecipeBackground, value: DrawingRecipe) {
        main { background.submit(value) }
        awaitFrames(3)
    }

    private fun writesThroughTheScheduler() {
        val f = fixture()
        val background = main { f.session.bindRecipeBackground(f.ref) }
        val first = recipe(Color.RED)
        main { background.submit(first) }
        check(background(f.view) == null, "a submission is scheduled, not written synchronously")
        awaitFrames(3)
        check(drawnRecipe(f.view) == first, "the recipe is on the View after a frame")
        check(main { background.writes == 1L && background.isOwned }, "one write, still owned")
        main { f.session.close() }
    }

    private fun skipsEqualRecipesAndKeepsDrawables() {
        val f = fixture()
        val background = main { f.session.bindRecipeBackground(f.ref) }
        submitAndSettle(background, recipe(Color.RED))
        val installed = background(f.view)
        submitAndSettle(background, recipe(Color.RED))
        check(main { background.skips == 1L && background.writes == 1L } && background(f.view) === installed,
            "an equal recipe is skipped by the property binding")
        main { background.invalidate() }
        submitAndSettle(background, recipe(Color.RED))
        check(main { background.writes } == 2L && background(f.view) === installed,
            "after invalidate the write runs but the drawable for the same recipe and density is kept")
        submitAndSettle(background, recipe(Color.BLUE))
        check(background(f.view) !== installed && drawnRecipe(f.view) == recipe(Color.BLUE), "a different recipe installs a new drawable")
        check(main { installed!!.callback } == null, "the old drawable was detached from the View")
        main { f.session.close() }
    }

    private fun hostReplacementIsNotOverwritten() {
        val f = fixture()
        val background = main { f.session.bindRecipeBackground(f.ref) }
        submitAndSettle(background, recipe(Color.RED))
        val business: Drawable = ColorDrawable(Color.GREEN)
        main { f.view.background = business }
        check(!main { background.isOwned }, "isOwned reports a host replacement as soon as it is asked")
        submitAndSettle(background, recipe(Color.BLUE))
        check(!main { background.isOwned } && background(f.view) === business, "a write after a host replacement leaves the host's drawable")
        submitAndSettle(background, recipe(Color.MAGENTA))
        check(background(f.view) === business, "and keeps leaving it")
        main { background.reclaim() }
        submitAndSettle(background, recipe(Color.CYAN))
        check(main { background.isOwned } && drawnRecipe(f.view) == recipe(Color.CYAN), "reclaim takes the property back on the next write")
        main { f.session.close() }
    }

    /** The common case: the caller reclaims and resubmits the recipe it already had. */
    private fun reclaimWithTheLastSubmittedRecipe() {
        val f = fixture()
        val background = main { f.session.bindRecipeBackground(f.ref) }
        submitAndSettle(background, recipe(Color.RED))
        val business: Drawable = ColorDrawable(Color.GREEN)
        main { f.view.background = business }
        submitAndSettle(background, recipe(Color.MAGENTA))
        check(background(f.view) === business, "the write while the host owns the background leaves it alone")
        main { background.reclaim() }
        submitAndSettle(background, recipe(Color.MAGENTA))
        check(main { background.isOwned } && drawnRecipe(f.view) == recipe(Color.MAGENTA),
            "reclaim followed by the same recipe takes the property back instead of being skipped")
        main { f.session.close() }
    }

    private fun densityChangeRebuildsAnEqualRecipe() {
        val f = fixture()
        val background = main { f.session.bindRecipeBackground(f.ref) }
        submitAndSettle(background, recipe(Color.RED))
        val installed = background(f.view)
        val metrics = main { f.view.resources.displayMetrics }
        val originalDpi = main { metrics.densityDpi }
        val originalDensity = main { metrics.density }
        try {
            // Simulates a density change the host handles without recreating the View.
            main { metrics.densityDpi = originalDpi * 2; metrics.density = originalDensity * 2 }
            submitAndSettle(background, recipe(Color.RED))
            check(main { background.writes } == 2L && background(f.view) !== installed && drawnRecipe(f.view) == recipe(Color.RED),
                "an equal recipe at a new density is written and rebuilds the drawable")
            val rebuilt = background(f.view)
            submitAndSettle(background, recipe(Color.RED))
            check(main { background.skips } == 1L && background(f.view) === rebuilt,
                "at the unchanged new density an equal recipe is skipped again")
        } finally {
            main { metrics.densityDpi = originalDpi; metrics.density = originalDensity }
            main { f.session.close() }
        }
    }

    private fun propertyConflictsAreRefused() {
        val f = fixture()
        val reservation = main { f.session.reserveProperties(f.ref, setOf("background")) }
        expectFailure<IllegalStateException> { f.session.bindRecipeBackground(f.ref) }
        check(background(f.view) == null, "a refused bind writes nothing")
        main { reservation.close() }
        val background = main { f.session.bindRecipeBackground(f.ref) }
        expectFailure<IllegalStateException> { f.session.reserveProperties(f.ref, setOf("background")) }
        expectFailure<IllegalStateException> { f.session.bindRecipeBackground(f.ref) }
        main { background.close() }
        val again = main { f.session.bindRecipeBackground(f.ref) }
        check(main { again.isOwned }, "closing frees the property for a new binding")
        main { f.session.close() }
    }

    private fun releaseAndRebindClose() {
        val f = fixture()
        val background = main { f.session.bindRecipeBackground(f.ref) }
        submitAndSettle(background, recipe(Color.RED))
        check(background(f.view) != null, "installed before the rebind")
        main { f.session.rebind(f.ref) }
        check(background(f.view) == null, "rebinding the node closes the binding and clears its background")
        expectFailure<IllegalStateException> { background.submit(recipe(Color.BLUE)) }
        val fresh = main { f.session.bindRecipeBackground(f.ref) }
        submitAndSettle(fresh, recipe(Color.BLUE))
        check(drawnRecipe(f.view) == recipe(Color.BLUE), "a new binding works after the rebind")
        main { f.session.release(f.ref) }
        check(background(f.view) == null, "releasing the node clears the background")
        main { f.session.close() }
    }

    private fun sessionCloseClearsTheBackground() {
        val f = fixture()
        val background = main { f.session.bindRecipeBackground(f.ref) }
        submitAndSettle(background, recipe(Color.RED))
        val installed = background(f.view)
        main { f.session.close() }
        check(background(f.view) == null && main { installed!!.callback } == null, "closing the session clears and detaches")
        main { background.close() }
    }

    private inline fun <reified T : Throwable> expectFailure(crossinline action: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                action()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        val error = failure.get()
        if (error is T) { checks++; return }
        throw AssertionError(if (error == null) "expected ${T::class.java.simpleName}" else "unexpected $error", error)
    }

    private fun awaitFrames(count: Int) {
        repeat(count) {
            val latch = CountDownLatch(1)
            main { Choreographer.getInstance().postFrameCallback { latch.countDown() } }
            check(latch.await(5, TimeUnit.SECONDS), "frame callback delivered")
        }
        instrumentation.waitForIdleSync()
    }

    private fun <R> main(action: () -> R): R {
        val result = AtomicReference<R>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                result.set(action())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    private fun check(condition: Boolean, label: String) {
        checks++
        if (!condition) throw AssertionError(label)
    }
}
