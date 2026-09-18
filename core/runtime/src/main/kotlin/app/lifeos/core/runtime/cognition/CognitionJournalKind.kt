package app.lifeos.core.runtime.cognition

enum class CognitionJournalKind(val tag: String) {
    EVENT("event"),
    TRANSACTION("transaction"),
    OUTCOME("outcome"),
    TRIGGER("trigger"),
}
