package app.lifeos.next.ui

import androidx.annotation.DrawableRes
import app.lifeos.next.R

enum class LifeOsDestination(
    val key: String,
    val label: String,
    @DrawableRes val iconRes: Int,
    val isDefault: Boolean = false,
) {
    CHAT(
        key = "chat",
        label = "LIFEOS",
        iconRes = R.drawable.ic_lifeos,
        isDefault = true,
    ),
    GOALS(
        key = "goals",
        label = "Heute",
        iconRes = R.drawable.ic_today,
    ),
    MEMORY(
        key = "memory",
        label = "Gedächtnis",
        iconRes = R.drawable.ic_memory,
    ),
    ;

    companion object {
        val ordered: List<LifeOsDestination> = listOf(CHAT, GOALS, MEMORY)
        val default: LifeOsDestination = entries.single { it.isDefault }

        fun fromKey(key: String?): LifeOsDestination =
            entries.firstOrNull { it.key == key } ?: default
    }
}
