package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import java.time.Instant

internal object CognitionTriggerCodec {
    private const val SCHEMA = "cognitive-trigger/v1"

    fun encode(value: CognitiveTrigger): String = CognitionJournalCodecSupport.encode(
        listOf(
            SCHEMA,
            value.id,
            value.type.name,
            value.sourceTaskId.value,
            value.photonId?.value,
            value.reason,
            value.createdAt.toString(),
        )
    )

    fun decode(content: String): CognitiveTrigger {
        val f = CognitionJournalCodecSupport.decode(content)
        require(f.size == 7 && f[0] == SCHEMA) { "Invalid cognitive trigger schema" }
        fun req(i: Int) = f[i] ?: error("Missing cognitive trigger field $i")
        return CognitiveTrigger(
            id = req(1),
            type = CognitiveTriggerType.valueOf(req(2)),
            sourceTaskId = TaskId(req(3)),
            photonId = f[4]?.let(::PhotonId),
            reason = req(5),
            createdAt = Instant.parse(req(6)),
        )
    }
}
