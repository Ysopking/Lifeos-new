package app.lifeos.next.ui.chat

/**
 * Presentation-only cleanup. Authoritative Photon text stays unchanged; this layer only makes the
 * owner-facing surface easier to read and speak.
 */
object HumanReadableOutput {
    fun forDisplay(raw: String): String = raw
        .trim()
        .replace(Regex("(?m)^#{1,6}\\s+"), "")
        .replace(Regex("(?m)^[-*]\\s+"), "• ")
        .replace("**", "")
        .replace("__", "")
        .replace("LIFEOS-Photonen", "lokale Einträge")
        .replace("LIFEOS-Photon", "lokaler Eintrag")
        .replace("produktive Provider", "benötigte Funktionen")
        .replace("produktiver Provider", "benötigte Funktion")
        .replace("Systemstrom", "Systembereich")
        .replace("Genesis", "LIFEOS-Erweiterung")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    fun forSpeech(raw: String): String = forDisplay(raw)
        .replace(Regex("\`\`\`[\\s\\S]*?\`\`\`"), " Code ist in der Textansicht sichtbar. ")
        .replace(Regex("\\[([^]]+)]\\(https?://[^)]+\\)"), "$1")
        .replace(Regex("https?://\\S+"), " Link ")
        .replace("•", "")
        .replace("\`", "")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n+"), ". ")
        .replace(Regex("\\.{2,}"), ".")
        .trim()
}
