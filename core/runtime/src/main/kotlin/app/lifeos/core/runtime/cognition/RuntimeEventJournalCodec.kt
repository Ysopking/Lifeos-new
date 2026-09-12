package app.lifeos.core.runtime.cognition

internal data class RuntimeEventEnvelope(val offset: Long, val event: CognitiveEvent)

internal object RuntimeEventJournalCodec {
    const val SCHEMA = "runtime-event/v1"
}
