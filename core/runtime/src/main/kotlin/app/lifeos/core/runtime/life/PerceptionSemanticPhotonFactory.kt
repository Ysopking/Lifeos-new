package app.lifeos.core.runtime.life

import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds

/**
 * Converts a typed observation Photon into a separate semantic result and then, only when resolved,
 * into a normal user utterance. Raw/source evidence is never rewritten or masqueraded as semantics.
 */
class PerceptionSemanticPhotonFactory {
    fun recognition(
        observation: Photon,
        modality: PerceptionModality,
        recognizedText: String?,
        confidence: Double,
        traceFingerprint: String,
        candidates: List<PerceptionCandidate> = emptyList(),
    ): Photon {
        require(modality == PerceptionModality.SPEECH || modality == PerceptionModality.WRITING) {
            "Only speech/writing observations can become language recognition Photons"
        }
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(traceFingerprint.isNotBlank())
        val resolvedText = recognizedText?.trim()?.takeIf { it.isNotBlank() }
        val candidateFingerprint = StableCognitiveIds.fingerprint(
            "perception-semantic-candidates/v1",
            *candidates.sortedWith(
                compareByDescending<PerceptionCandidate> { it.confidence }
                    .thenBy { it.value }
                    .thenBy { it.semanticTag.orEmpty() }
            ).flatMap { candidate ->
                listOf(
                    candidate.value,
                    candidate.semanticTag.orEmpty(),
                    java.lang.Double.toHexString(candidate.confidence),
                )
            }.toTypedArray(),
        )
        val stateHash = CanonicalPhotonState.inputHash(observation).value
        val idFingerprint = StableCognitiveIds.fingerprint(
            "perception-semantic-photon/v2",
            observation.id.value,
            observation.revision.toString(),
            stateHash,
            modality.name,
            traceFingerprint,
            resolvedText.orEmpty(),
            java.lang.Double.toHexString(confidence),
            candidateFingerprint,
        )
        return Photon(
            id = PhotonId("perception-semantic-${idFingerprint.take(48)}"),
            content = resolvedText ?: "[unresolved:$traceFingerprint]",
            mimeType = "application/vnd.lifeos.${modality.name.lowercase()}-semantic+text",
            phase = PhotonPhase.CONVERGED,
            semanticMass = observation.semanticMass,
            energy = maxOf(observation.energy, confidence.coerceAtLeast(0.05)),
            confidence = confidence,
            provenance = Provenance(
                source = "perception-semantic:${modality.name.lowercase()}",
                actor = "lifeos-field",
                createdAt = observation.provenance.createdAt,
                parentIds = setOf(observation.id),
            ),
            relations = setOf(
                PhotonRelation(observation.id, RelationType.DERIVED_FROM),
                PhotonRelation(observation.id, RelationType.TRANSFORMS),
            ),
            tags = buildSet {
                add("perception")
                add("perception-semantic")
                add("perception-modality:${modality.name.lowercase()}")
                add("source-state:$stateHash")
                add("trace:$traceFingerprint")
                add("candidate-distribution:$candidateFingerprint")
                add(if (resolvedText == null) "recognition:unresolved" else "recognition:resolved")
                observation.tags.filterTo(this) {
                    it.startsWith("conversation:") || it.startsWith("turn:") || it.startsWith("source-asset:")
                }
            },
        )
    }

    fun asUserUtterance(
        recognition: Photon,
        modality: PerceptionModality,
        conversationId: String = "default",
    ): Photon {
        require(modality == PerceptionModality.SPEECH || modality == PerceptionModality.WRITING)
        require("recognition:resolved" in recognition.tags) {
            "Unresolved multimodal recognition cannot become a user utterance"
        }
        require(conversationId.isNotBlank())
        val fingerprint = StableCognitiveIds.fingerprint(
            "perception-user-utterance/v2",
            recognition.id.value,
            CanonicalPhotonState.inputHash(recognition).value,
            modality.name,
            conversationId,
            recognition.content,
        )
        return Photon(
            id = PhotonId("perception-chat-${fingerprint.take(48)}"),
            content = recognition.content,
            mimeType = "text/plain",
            phase = PhotonPhase.CREATED,
            semanticMass = recognition.semanticMass,
            energy = recognition.energy,
            confidence = recognition.confidence,
            provenance = Provenance(
                source = "multimodal-perception",
                actor = "user",
                createdAt = recognition.provenance.createdAt,
                parentIds = setOf(recognition.id),
            ),
            relations = setOf(
                PhotonRelation(recognition.id, RelationType.DERIVED_FROM),
                PhotonRelation(recognition.id, RelationType.TRANSFORMS),
            ),
            tags = setOf(
                "chat",
                "chat:user",
                "conversation:$conversationId",
                "turn:perception-${fingerprint.take(20)}",
                "input:${modality.name.lowercase()}",
                "perception-derived-utterance",
            ),
        )
    }
}
