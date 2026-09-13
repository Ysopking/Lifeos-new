package app.lifeos.next.ui

enum class LifeOsDestination(
    val key: String,
    val label: String,
    val glyph: String,
    val isDefault: Boolean = false,
) {
    CHAT(key = "chat", label = "Chat", glyph = "◉", isDefault = true),
    MEMORY(key = "memory", label = "Gedächtnis", glyph = "◌"),
    ASSETS(key = "assets", label = "Assets", glyph = "▣"),
    GOALS(key = "goals", label = "Ziele", glyph = "◎"),
    WHY(key = "why", label = "Warum", glyph = "?"),
    TOOLS(key = "tools", label = "Tools", glyph = "⚙"),
    SYSTEM(key = "system", label = "System", glyph = "◇"),
    ;

    companion object {
        val ordered: List<LifeOsDestination> = entries.toList()
        val default: LifeOsDestination = entries.single { it.isDefault }

        fun fromKey(key: String?): LifeOsDestination =
            entries.firstOrNull { it.key == key } ?: default
    }
}
