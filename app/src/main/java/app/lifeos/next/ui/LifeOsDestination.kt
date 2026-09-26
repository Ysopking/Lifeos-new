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
    PROJECTS(
        key = "projects",
        label = "Projekte",
        iconRes = R.drawable.ic_today,
    ),
    WEEK(
        key = "week",
        label = "Woche",
        iconRes = R.drawable.ic_today,
    ),
    ACTIONS(
        key = "actions",
        label = "Rückfragen & Aktionen",
        iconRes = R.drawable.ic_system,
    ),
    ARTIFACTS(
        key = "artifacts",
        label = "Artefakte",
        iconRes = R.drawable.ic_memory,
    ),
    ;

    companion object {
        val ordered: List<LifeOsDestination> =
            listOf(CHAT, PROJECTS, WEEK, ACTIONS, ARTIFACTS)
        val default: LifeOsDestination = entries.single { it.isDefault }

        fun fromKey(key: String?): LifeOsDestination = when (key) {
            "goals" -> PROJECTS
            "assets" -> ARTIFACTS
            else -> entries.firstOrNull { it.key == key } ?: default
        }
    }
}
