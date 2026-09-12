package app.lifeos.core.runtime.cognition

internal enum class CognitionJournalKind(val tag: String) {
    EVENT("event"),
    TRANSACTION("transaction"),
    OUTCOME("outcome"),
    TRIGGER("trigger"),
}
