package app.lifeos.core.runtime.cognition

internal data class RuntimeEventEnvelope(val offset: Long, val event: CognitiveEvent)

internal object RuntimeEventJournalCodec {
    const val SCHEMA = "runtime-event/v1"

    fun encode(value: RuntimeEventEnvelope): String = CognitionJournalCodecSupport.encode(
        listOf(
            SCHEMA,
            value.offset.toString(),
            value.event.eventId,
            value.event.delta.deltaId,
            value.event.delta.source,
            value.event.delta.photonId?.value,
            value.event.delta.revisionBefore?.toString(),
            value.event.delta.revisionAfter?.toString(),
            value.event.delta.type.name,
            value.event.delta.importanceHint?.let(java.lang.Double::toHexString),
            value.event.delta.timestamp.toString(),
            value.event.delta.causationId,
            value.event.delta.correlationId,
            value.event.recordedAt.toString(),
        )
    )
}
