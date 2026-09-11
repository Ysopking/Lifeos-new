package app.lifeos.core.runtime.cognition

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.runtime.learning.OutcomeEvidence
import app.lifeos.core.runtime.learning.OutcomePrediction
import app.lifeos.core.runtime.learning.OutcomeScore
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import java.time.Instant

object OutcomePhotonContract {
    const val MIME_TYPE = "application/vnd.lifeos.outcome+json"
    const val PROVENANCE_SOURCE = "lifeos.outcome-learning"
    const val PROVENANCE_ACTOR = "lifeos-runtime"
}

/** Immutable projection of an observed productive action result back into the Photon universe. */
class OutcomePhotonFactory {
    fun create(
        prediction: OutcomePrediction,
        result: CognitiveTaskExecutionResult,
        evidence: List<OutcomeEvidence>,
        score: OutcomeScore,
        recordedAt: Instant,
    ): Photon {
        require(score.predictionId == prediction.id) { "Outcome score/prediction mismatch" }
        require(evidence.isNotEmpty()) { "Outcome Photon requires explicit outcome evidence" }
        require(evidence.all { it.predictionId == prediction.id }) {
            "Outcome Photon evidence/prediction mismatch"
        }
        require(recordedAt >= prediction.createdAt) { "Outcome Photon cannot predate prediction" }
        val canonicalEvidence = evidence.sortedBy { it.id.value }
        val sourcePhotonId = result.photonId
        val content = buildString {
            append('{')
            append("\"schema\":\"lifeos.outcome.v2\",")
            append("\"actionId\":"); appendJson(prediction.actionId); append(',')
            append("\"taskId\":"); appendJson(result.taskId.value); append(',')
            append("\"predictionId\":"); appendJson(prediction.id.value); append(',')
            append("\"decisionId\":"); appendJson(prediction.decisionId.value); append(',')
            append("\"workingSetFingerprint\":"); appendJson(prediction.thoughtGraphWorkingSetFingerprint); append(',')
            append("\"decisionPolicyFingerprint\":"); appendJson(prediction.decisionPolicyFingerprint); append(',')
            append("\"finalState\":"); appendJson(result.finalState.name); append(',')
            append("\"scoreId\":"); appendJson(score.id.value); append(',')
            append("\"scoreState\":"); appendJson(score.state.name); append(',')
            append("\"scorePolicyFingerprint\":"); appendJson(score.policyFingerprint); append(',')
            append("\"quality\":"); append(java.lang.Double.toString(score.quality)); append(',')
            append("\"signedScore\":"); append(java.lang.Double.toString(score.signedScore)); append(',')
            append("\"sourcePhotonId\":")
            if (sourcePhotonId == null) append("null") else appendJson(sourcePhotonId.value)
            append(',')
            append("\"fieldSnapshotId\":")
            val snapshotId = result.fieldShadow?.snapshotId?.value
            if (snapshotId == null) append("null") else appendJson(snapshotId)
            append(',')
            append("\"providers\":[")
            prediction.providerIds.forEachIndexed { index, provider ->
                if (index > 0) append(',')
                appendJson(provider)
            }
            append("],\"fieldSnapshotFingerprints\":[")
            prediction.fieldSnapshotFingerprints.forEachIndexed { index, fingerprint ->
                if (index > 0) append(',')
                appendJson(fingerprint)
            }
            append("],\"evidence\":[")
            canonicalEvidence.forEachIndexed { index, item ->
                if (index > 0) append(',')
                append('{')
                append("\"id\":"); appendJson(item.id.value); append(',')
                append("\"sourceClass\":"); appendJson(item.sourceClass.name); append(',')
                append("\"sourceId\":"); appendJson(item.sourceId); append(',')
                append("\"sourceFingerprint\":"); appendJson(item.sourceFingerprint); append(',')
                append("\"confidence\":"); append(java.lang.Double.toString(item.confidence)); append(',')
                append("\"observedAt\":"); appendJson(item.observedAt.toString()); append(',')
                append("\"reason\":"); appendJson(item.reason); append(',')
                append("\"independentOfProviders\":[")
                item.independentOfProviderIds.sorted().forEachIndexed { providerIndex, provider ->
                    if (providerIndex > 0) append(',')
                    appendJson(provider)
                }
                append("],\"signal\":{")
                append("\"completion\":"); appendNullableDouble(item.signal.completion); append(',')
                append("\"correctness\":"); appendNullableDouble(item.signal.correctness); append(',')
                append("\"usefulness\":"); appendNullableDouble(item.signal.usefulness); append(',')
                append("\"policyCompliance\":"); appendNullableDouble(item.signal.policyCompliance)
                append("}}")
            }
            append("]}")
        }
        val identity = StableFieldIds.fingerprint(
            "outcome-photon/v2",
            prediction.contentFingerprint(),
            result.taskId.value,
            result.finalState.name,
            result.photonId?.value.orEmpty(),
            result.fieldShadow?.snapshotId?.value.orEmpty(),
            score.contentFingerprint(),
            recordedAt.toString(),
            *canonicalEvidence.map { it.contentFingerprint() }.toTypedArray(),
        )
        val parentIds = buildSet {
            add(PhotonId(prediction.id.value))
            sourcePhotonId?.let(::add)
        }
        val relations = buildSet {
            add(PhotonRelation(target = PhotonId(prediction.id.value), type = RelationType.DERIVED_FROM))
            sourcePhotonId?.let {
                add(PhotonRelation(target = it, type = RelationType.DERIVED_FROM))
            }
        }
        return Photon(
            id = PhotonId("outcome:$identity"),
            revision = 1L,
            content = content,
            mimeType = OutcomePhotonContract.MIME_TYPE,
            phase = PhotonPhase.CONVERGED,
            semanticMass = 1.0,
            energy = score.quality,
            confidence = canonicalEvidence.maxOfOrNull { it.confidence } ?: 0.0,
            provenance = Provenance(
                source = OutcomePhotonContract.PROVENANCE_SOURCE,
                actor = OutcomePhotonContract.PROVENANCE_ACTOR,
                createdAt = recordedAt,
                parentIds = parentIds,
            ),
            relations = relations,
            tags = buildSet {
                add("outcome")
                add("task:${result.finalState.name.lowercase()}")
                add("score:${score.state.name.lowercase()}")
                if (score.adaptationAllowed) add("verified-outcome")
            },
        )
    }

    private fun StringBuilder.appendNullableDouble(value: Double?) {
        if (value == null) append("null") else append(java.lang.Double.toString(value))
    }

    private fun StringBuilder.appendJson(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }
}
