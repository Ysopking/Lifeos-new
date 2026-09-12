package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import java.time.Instant

internal object PhotonTransactionJournalCodec {
    const val SCHEMA = "photon-transaction/v1"

    fun encode(value: PhotonTransactionRecord): String = CognitionJournalCodecSupport.encode(
        listOf(
            SCHEMA,
            value.transactionId,
            value.taskId.value,
            value.photonId?.value,
            value.state.name,
            value.recordedAt.toString(),
            encodeInfluences(value.influences),
            encodeFailures(value.failures),
        )
    )

    fun decode(content: String): PhotonTransactionRecord {
        val f = CognitionJournalCodecSupport.decode(content)
        require(f.size == 8 && f[0] == SCHEMA) { "Invalid photon transaction schema" }
        fun req(i: Int) = f[i] ?: error("Missing photon transaction field $i")
        return PhotonTransactionRecord(
            transactionId = req(1),
            taskId = TaskId(req(2)),
            photonId = f[3]?.let(::PhotonId),
            state = PhotonTransactionState.valueOf(req(4)),
            influences = decodeInfluences(req(6)),
            failures = decodeFailures(req(7)),
            recordedAt = Instant.parse(req(5)),
        )
    }

    private fun encodeInfluences(values: List<FieldInfluence>): String = CognitionJournalCodecSupport.encode(
        values.map { value ->
            CognitionJournalCodecSupport.encode(listOf(value.module, value.photonId.value, value.type, value.deltaEnergy.toString(), value.confidence.toString(), value.explanation, value.occurredAt.toString()))
        }
    )

    private fun decodeInfluences(raw: String): List<FieldInfluence> = CognitionJournalCodecSupport.decode(raw).map { encoded ->
        val f = CognitionJournalCodecSupport.decode(requireNotNull(encoded))
        require(f.size == 7) { "Invalid influence record" }
        FieldInfluence(requireNotNull(f[0]), PhotonId(requireNotNull(f[1])), requireNotNull(f[2]), requireNotNull(f[3]).toDouble(), requireNotNull(f[4]).toDouble(), requireNotNull(f[5]), Instant.parse(requireNotNull(f[6])))
    }

    private fun encodeFailures(values: List<RuntimeFailure>): String = CognitionJournalCodecSupport.encode(
        values.map { value -> CognitionJournalCodecSupport.encode(listOf(value.category.name, value.source, value.message, value.recoverable.toString(), value.photonId?.value)) }
    )

    private fun decodeFailures(raw: String): List<RuntimeFailure> = CognitionJournalCodecSupport.decode(raw).map { encoded ->
        val f = CognitionJournalCodecSupport.decode(requireNotNull(encoded))
        require(f.size == 5) { "Invalid failure record" }
        RuntimeFailure(RuntimeFailureCategory.valueOf(requireNotNull(f[0])), requireNotNull(f[1]), requireNotNull(f[2]), requireNotNull(f[3]).toBooleanStrict(), f[4]?.let(::PhotonId))
    }
}
