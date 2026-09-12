package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import java.time.Instant

internal object CognitionTransactionCodec {
    fun encode(value: PhotonTransactionRecord): String = CognitionJournalCodecSupport.encode(buildList {
        add("photon-transaction/v1"); add(value.transactionId); add(value.taskId.value)
        add(value.photonId?.value); add(value.state.name); add(value.recordedAt.toString())
        add(value.influences.size.toString())
        value.influences.forEach { x ->
            add(x.module); add(x.photonId.value); add(x.type); add(java.lang.Double.toHexString(x.deltaEnergy))
            add(java.lang.Double.toHexString(x.confidence)); add(x.explanation); add(x.occurredAt.toString())
        }
        add(value.failures.size.toString())
        value.failures.forEach { x ->
            add(x.category.name); add(x.source); add(x.message); add(x.recoverable.toString()); add(x.photonId?.value)
        }
    })

    fun decode(content: String): PhotonTransactionRecord {
        val f = CognitionJournalCodecSupport.decode(content); var i = 0
        fun req() = f[i++] ?: error("Missing transaction field")
        fun opt() = f[i++]
        require(req() == "photon-transaction/v1")
        val id = req(); val task = TaskId(req()); val photon = opt()?.let(::PhotonId)
        val state = PhotonTransactionState.valueOf(req()); val at = Instant.parse(req())
        val influences = List(req().toInt().also { require(it in 0..1024) }) {
            FieldInfluence(req(), PhotonId(req()), req(), req().toDouble(), req().toDouble(), req(), Instant.parse(req()))
        }
        val failures = List(req().toInt().also { require(it in 0..1024) }) {
            RuntimeFailure(RuntimeFailureCategory.valueOf(req()), req(), req(), req().toBooleanStrict(), opt()?.let(::PhotonId))
        }
        require(i == f.size)
        return PhotonTransactionRecord(id, task, photon, state, influences, failures, at)
    }
}
