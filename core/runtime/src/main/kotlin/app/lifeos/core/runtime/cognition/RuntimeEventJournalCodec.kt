package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.PhotonId
import java.time.Instant

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

    fun decode(content: String): RuntimeEventEnvelope {
        val f = CognitionJournalCodecSupport.decode(content)
        require(f.size == 14 && f[0] == SCHEMA) { "Invalid runtime event schema" }
        fun req(i: Int) = f[i] ?: error("Missing runtime event field $i")
        val delta = PhotonDelta(
            deltaId = req(3),
            source = req(4),
            photonId = f[5]?.let(::PhotonId),
            revisionBefore = f[6]?.toLong(),
            revisionAfter = f[7]?.toLong(),
            type = PhotonDeltaType.valueOf(req(8)),
            importanceHint = f[9]?.toDouble(),
            timestamp = Instant.parse(req(10)),
            causationId = f[11],
            correlationId = f[12],
        )
        return RuntimeEventEnvelope(
            offset = req(1).toLong().also { require(it > 0L) },
            event = CognitiveEvent(req(2), delta, Instant.parse(req(13))),
        )
    }
}
