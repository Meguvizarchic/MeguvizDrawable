package io.github.jichuo1.meguviz.drawable.compose

import androidx.compose.runtime.Immutable

/**
 * The Android drawable state flags a recipe's stateful fills can react to, expressed as
 * values so that Compose can compare them. Only flags that are set appear in
 * [stateSet], exactly as in a View's drawable state; a recipe rule that requires a flag to be
 * absent matches when it is false here.
 */
@Immutable
public class DrawableState(
    public val enabled: Boolean = true,
    public val focused: Boolean = false,
    public val pressed: Boolean = false,
    public val hovered: Boolean = false,
    public val selected: Boolean = false,
    public val checked: Boolean = false,
    public val activated: Boolean = false,
) {
    /** A fresh array on every call; callers must not share it. */
    public val stateSet: IntArray
        get() {
            // Both passes must list the same flags in the same order: count sizes the array,
            // index fills it. Keep the two lists in sync.
            var count = 0
            if (enabled) count++
            if (focused) count++
            if (pressed) count++
            if (hovered) count++
            if (selected) count++
            if (checked) count++
            if (activated) count++
            val buffer = IntArray(count)
            var index = 0
            if (enabled) buffer[index++] = android.R.attr.state_enabled
            if (focused) buffer[index++] = android.R.attr.state_focused
            if (pressed) buffer[index++] = android.R.attr.state_pressed
            if (hovered) buffer[index++] = android.R.attr.state_hovered
            if (selected) buffer[index++] = android.R.attr.state_selected
            if (checked) buffer[index++] = android.R.attr.state_checked
            if (activated) buffer[index++] = android.R.attr.state_activated
            return buffer
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is DrawableState && enabled == other.enabled && focused == other.focused &&
            pressed == other.pressed && hovered == other.hovered && selected == other.selected &&
            checked == other.checked && activated == other.activated
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + focused.hashCode()
        result = 31 * result + pressed.hashCode()
        result = 31 * result + hovered.hashCode()
        result = 31 * result + selected.hashCode()
        result = 31 * result + checked.hashCode()
        return 31 * result + activated.hashCode()
    }

    override fun toString(): String =
        "DrawableState(enabled=$enabled, focused=$focused, pressed=$pressed, hovered=$hovered, " +
            "selected=$selected, checked=$checked, activated=$activated)"

    public companion object {
        /** Enabled and nothing else, like a plain View. */
        public val Default: DrawableState = DrawableState()
    }
}
