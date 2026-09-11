package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.learning.OutcomePrediction

object OutcomePredictionPhotonContract {
    const val MIME_TYPE = "application/vnd.lifeos.outcome-prediction+json"
    const val PROVENANCE_SOURCE = "lifeos.outcome-learning.prediction"
    const val PROVENANCE_ACTOR = "lifeos-runtime"
}

/** Durable pre-action boundary: the prediction exists as an immutable Photon before execution. */
class OutcomePredictionPhotonFactory {
    fun create(prediction: OutcomePrediction): Photon = Photon(
        id = PhotonId(prediction.id.value),
        revision = 1L,
        content = buildString {
            append('{')
            append("\"schema\":\"lifeos.outcome-prediction.v1\",")
            append("\"actionId\":"); appendJson(prediction.actionId); append(',')
            append("\"decisionId\":"); appendJson(prediction.decisionId.value); append(',')
            append("\"workingSetFingerprint\":"); appendJson(prediction.thoughtGraphWorkingSetFingerprint); append(',')
            append("\"decisionPolicyFingerprint\":"); appendJson(prediction.decisionPolicyFingerprint); append(',')
            append("\"createdAt\":"); appendJson(prediction.createdAt.toString()); append(',')
            append("\"providers\":[")
            prediction.providerIds.forEachIndexed { index, provider ->
                if (index > 0) append(',')
                appendJson(provider)
            }
            append("],\"fieldSnapshots\":[")
            prediction.fieldSnapshotFingerprints.forEachIndexed { index, fingerprint ->
                if (index > 0) append(',')
                appendJson(fingerprint)
            }
            append("],\"hypotheses\":[")
            prediction.expectedHypotheses.forEachIndexed { index, expected ->
                if (index > 0) append(',')
                append('{')
                append("\"id\":"); appendJson(expected.hypothesisId.value); append(',')
                append("\"lower\":"); append(expected.confidenceBand.lower); append(',')
                append("\"point\":"); append(expected.confidenceBand.point); append(',')
                append("\"upper\":"); append(expected.confidenceBand.upper)
                append('}')
            }
            append("]}")
        },
        mimeType = OutcomePredictionPhotonContract.MIME_TYPE,
        phase = PhotonPhase.ACTIVE,
        semanticMass = 1.0,
        energy = prediction.expectedHypotheses.map { it.confidenceBand.point }.average(),
        confidence = prediction.expectedHypotheses.map { it.confidenceBand.point }.average(),
        provenance = Provenance(
            source = OutcomePredictionPhotonContract.PROVENANCE_SOURCE,
            actor = OutcomePredictionPhotonContract.PROVENANCE_ACTOR,
            createdAt = prediction.createdAt,
        ),
        tags = setOf("outcome-prediction", "pre-action"),
    )

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
