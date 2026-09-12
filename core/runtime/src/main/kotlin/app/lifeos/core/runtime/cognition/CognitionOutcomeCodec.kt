package app.lifeos.core.runtime.cognition

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldRunId
import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import app.lifeos.core.runtime.field.FieldShadowExecution
import app.lifeos.core.runtime.field.FieldShadowState
import java.time.Instant

internal object CognitionOutcomeCodec {
    fun encode(value: CognitiveOutcome): String = CognitionJournalCodecSupport.encode(buildList {
        add("cognitive-outcome/v1"); add(value.taskId.value); add(value.photonId?.value); add(value.finalState.name); add(value.recordedAt.toString())
        add(value.influences.size.toString())
        value.influences.forEach { x -> add(x.module); add(x.photonId.value); add(x.type); add(java.lang.Double.toHexString(x.deltaEnergy)); add(java.lang.Double.toHexString(x.confidence)); add(x.explanation); add(x.occurredAt.toString()) }
        add(value.failures.size.toString())
        value.failures.forEach { x -> add(x.category.name); add(x.source); add(x.message); add(x.recoverable.toString()); add(x.photonId?.value) }
        val s = value.fieldShadow
        add(s?.state?.name); add(s?.domainId?.value); add(s?.runId?.value); add(s?.snapshotId?.value); add(s?.convergenceStatus?.name)
        add(s?.message); add(s?.sourcePhotonId?.value); add(s?.sourceRevision?.toString()); add(s?.sourceFingerprint)
    })

    fun decode(content: String): CognitiveOutcome {
        val f = CognitionJournalCodecSupport.decode(content); var i = 0
        fun req() = f[i++] ?: error("Missing outcome field")
        fun opt() = f[i++]
        require(req() == "cognitive-outcome/v1")
        val task = TaskId(req()); val photon = opt()?.let(::PhotonId); val state = TaskState.valueOf(req()); val at = Instant.parse(req())
        val influences = List(req().toInt().also { require(it in 0..1024) }) { FieldInfluence(req(), PhotonId(req()), req(), req().toDouble(), req().toDouble(), req(), Instant.parse(req())) }
        val failures = List(req().toInt().also { require(it in 0..1024) }) { RuntimeFailure(RuntimeFailureCategory.valueOf(req()), req(), req(), req().toBooleanStrict(), opt()?.let(::PhotonId)) }
        val shadowState = opt()
        val domain = opt(); val run = opt(); val snapshot = opt(); val convergence = opt(); val message = opt(); val sourceId = opt(); val sourceRevision = opt(); val sourceFingerprint = opt()
        require(i == f.size)
        val shadow = shadowState?.let {
            FieldShadowExecution(
                state = FieldShadowState.valueOf(it),
                domainId = domain?.let(::FieldDomainId),
                runId = run?.let(::FieldRunId),
                snapshotId = snapshot?.let(::FieldSnapshotId),
                convergenceStatus = convergence?.let(ConvergenceStatus::valueOf),
                message = message,
                sourcePhotonId = sourceId?.let(::PhotonId),
                sourceRevision = sourceRevision?.toLong(),
                sourceFingerprint = sourceFingerprint,
            )
        }
        return CognitiveOutcome(task, photon, state, influences, failures, at, shadow)
    }
}
