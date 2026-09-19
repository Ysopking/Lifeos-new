package app.lifeos.next.ui

import androidx.annotation.DrawableRes
import app.lifeos.next.R

enum class LifeOsDestination(
    val key: String,
    val label: String,
    @DrawableRes val iconRes: Int,
    val isDefault: Boolean = false,
) {
    CHAT(key = "chat", label = "LIFEOS", iconRes = R.drawable.ic_lifeos, isDefault = true),
    MEMORY(key = "memory", label = "Gedächtnis", iconRes = R.drawable.ic_memory),
    GOALS(key = "goals", label = "Heute", iconRes = R.drawable.ic_today),

    // Transitional internal destinations: removed from primary navigation now and migrated into
    // contextual/system detail surfaces in UX3. Keeping them addressable preserves restored state
    // compatibility across the exact-head rollout.
    ASSETS(key = "assets", label = "Assets", iconRes = R.drawable.ic_system),
    WHY(key = "why", label = "Warum", iconRes = R.drawable.ic_system),
    TOOLS(key = "tools", label = "Tools", iconRes = R.drawable.ic_system),
    SYSTEM(key = "system", label = "System", iconRes = R.drawable.ic_system),
    ;

    companion object {
        val ordered: List<LifeOsDestination> = listOf(CHAT, GOALS, MEMORY)
        val default: LifeOsDestination = entries.single { it.isDefault }

        fun fromKey(key: String?): LifeOsDestination =
            entries.firstOrNull { it.key == key } ?: default
    }
}
