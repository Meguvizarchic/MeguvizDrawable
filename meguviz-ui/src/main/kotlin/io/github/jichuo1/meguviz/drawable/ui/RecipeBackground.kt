package io.github.jichuo1.meguviz.drawable.ui

import android.graphics.drawable.Drawable
import android.view.View
import io.github.jichuo1.meguviz.drawable.android.RecipeDrawable
import io.github.jichuo1.meguviz.drawable.android.ViewSlots
import io.github.jichuo1.meguviz.drawable.binding.BindingEnvironment
import io.github.jichuo1.meguviz.drawable.binding.BindingScope
import io.github.jichuo1.meguviz.drawable.binding.SlotBinding
import io.github.jichuo1.meguviz.drawable.model.DrawingRecipe
import io.github.jichuo1.meguviz.ui.EffectMask
import io.github.jichuo1.meguviz.ui.PropertyBinding
import io.github.jichuo1.meguviz.ui.PropertyOwnership
import io.github.jichuo1.meguviz.ui.PropertySpec
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewRef
import io.github.jichuo1.meguviz.ui.scheduler.SubmitResult

/** The property id [bindRecipeBackground] registers by default; the Lumen bridge reserves the same one. */
public const val BACKGROUND_PROPERTY: String = "background"

/**
 * A [DrawingRecipe] kept on a View background through MeguvizUI's property system.
 *
 * MeguvizUI decides *when* a write happens (frame scheduling, coalescing, and the attach, rebind,
 * theme and resource epochs that make a pending write stale). This class decides *what* a write
 * does, using the drawable ownership rules:
 * - an equal recipe is skipped by the property binding, and a repeated write of the same recipe at
 *   the same density keeps the installed drawable instead of building another; once the View's
 *   density differs from the last write, an equal recipe is no longer skipped, so the next submit
 *   rebuilds the drawable for the new density;
 * - if business code replaces the background, the next write neither overwrites it nor pretends it
 *   still owns it: [isOwned] turns false until [reclaim] is called;
 * - releasing the View, rebinding it, or closing the session closes this object, which clears the
 *   background only if it is still the one installed here.
 *
 * Binding is the act of claiming the property: the first write replaces whatever background the View
 * has, the way a MeguvizUI property write always does. Owner (main) thread only, like the rest of
 * MeguvizUI.
 */
public class RecipeBackground internal constructor(
    private val binding: PropertyBinding<*, DrawingRecipe>,
    private val scope: BindingScope,
    private val writer: Writer,
) : AutoCloseable {
    /** The state shared with the property's write function. */
    internal class Writer(private val slot: SlotBinding<Drawable>, private val view: View) {
        var written: Boolean = false
        var lost: Boolean = false
        var reclaim: Boolean = false
        /** densityDpi of the last write; 0 before the first one. */
        private var writtenDensityDpi = 0

        /**
         * Whether the property binding may skip an equal recipe. It must not once the density has
         * changed: the skip happens before [write], so the revision check there would never run.
         */
        fun densityUnchanged(): Boolean = writtenDensityDpi == view.resources.displayMetrics.densityDpi

        fun write(recipe: DrawingRecipe) {
            val metrics = view.resources.displayMetrics
            val density = metrics.density
            // A density change is a new revision, so the next write builds a drawable for it.
            val revision = metrics.densityDpi.toLong()
            writtenDensityDpi = metrics.densityDpi
            if (!written) {
                val result = slot.ensure(view.background, recipe, revision) { RecipeDrawable(recipe, density) }
                if (result == SlotBinding.Ensure.NOT_OWNED) lost = true else written = true
            } else if (reclaim) {
                reclaim = false
                lost = !slot.takeOver(RecipeDrawable(recipe, density), recipe, revision)
            } else if (slot.ensure(null, recipe, revision) { RecipeDrawable(recipe, density) } == SlotBinding.Ensure.NOT_OWNED) {
                lost = true
            }
        }

        fun owned(): Boolean = !lost && (!written || slot.isOwned)
    }

    /** Hands the recipe to MeguvizUI's scheduler; the write happens in a frame if the binding is still valid. */
    public fun submit(recipe: DrawingRecipe): SubmitResult = binding.submit(recipe)

    /** Forgets the last applied recipe so that the next [submit] writes even if equal. */
    public fun invalidate(): Unit = binding.invalidate()

    /** Writes that reached the View, and submissions skipped as equal. */
    public val writes: Long get() = binding.writes
    public val skips: Long get() = binding.skips

    /**
     * False once business code replaced the background after this object installed one. Further
     * writes then leave the host's drawable alone.
     */
    public val isOwned: Boolean get() = writer.owned()

    /**
     * Takes the property back: the next write installs the submitted recipe over whatever is there,
     * including the recipe that was last submitted while the host owned the background.
     */
    public fun reclaim() {
        check(writer.written) { "nothing was installed yet" }
        writer.reclaim = true
        // Writes made while the host owned the background still updated the binding's cache;
        // without this, resubmitting that same recipe would be skipped and never take the slot back.
        binding.invalidate()
    }

    override fun close() {
        try {
            binding.close()
        } finally {
            scope.close()
        }
    }
}

/**
 * Binds a [RecipeBackground] to the View behind [ref].
 *
 * Registers [propertyId] with the session, so a second owner of the same property (another binding,
 * the Lumen bridge's reservation) is refused with MeguvizUI's own conflict error before anything is
 * written. The binding closes with the View's release, a rebind of the node, or the session.
 */
public fun <V : View> UiSession.bindRecipeBackground(
    ref: ViewRef<V>,
    padding: ViewSlots.PaddingPolicy = ViewSlots.PaddingPolicy.PRESERVE_HOST,
    propertyId: String = BACKGROUND_PROPERTY,
): RecipeBackground {
    assertOwner()
    val view = ref.get()
    val scope = BindingScope(0)
    scope.bind(BindingEnvironment(0, 0))
    val writer: RecipeBackground.Writer
    val binding: PropertyBinding<V, DrawingRecipe>
    try {
        val slot = scope.slot(propertyId, ViewSlots.background(view, padding), null)
        writer = RecipeBackground.Writer(slot, view)
        val spec = PropertySpec<V, DrawingRecipe>(
            id = propertyId,
            effect = EffectMask.PAINT or EffectMask.LAYOUT,
            ownership = PropertyOwnership.EXCLUSIVE,
            coalescible = true,
            equivalent = { previous, next -> previous == next },
            comparisonAllowed = { writer.densityUnchanged() },
            resourceSensitive = true,
            write = { _, recipe -> writer.write(recipe) },
        )
        binding = bind(ref, spec)
    } catch (failure: Throwable) {
        scope.close()
        throw failure
    }
    val recipeBackground = RecipeBackground(binding, scope, writer)
    onRelease(ref) { recipeBackground.close() }
    return recipeBackground
}
