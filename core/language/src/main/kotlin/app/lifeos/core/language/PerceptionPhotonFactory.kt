package app.lifeos.core.language

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant

/**
 * Converts multimodal field outcomes into durable, causally linked LIFEOS Photons.
 * Derived ids are content-addressed so replay of the same source + field trace is idempotent.
 */
class PerceptionPhotonFactory {
    fun speech(
        source: Photon,
        result: SpeechFieldRecognitionResult,
        createdAt: Instant = source.provenance.createdAt,
    ): Photon {
        val text = result.recognizedText ?: "[speech-unresolved:${result.traceFingerprint.take(16)}]"
        return derived(
            source = source,
            kind = "speech",
            content = text,
            mimeType = SPEECH_RECOGNITION_MIME,
            confidence = result.confidence,
            traceFingerprint = result.traceFingerprint,
            createdAt = createdAt,
            extraTags = setOf(
                "perception:speech",
                "speech-field",
                "segments:${result.segmentCount}",
                if (result.recognizedText == null) "recognition:unresolved" else "recognition:resolved",
            ),
        )
    }

    fun writing(
        source: Photon,
        result: WritingFieldRecognitionResult,
        createdAt: Instant = source.provenance.createdAt,
    ): Photon = derived(
        source = source,
        kind = "writing",
        content = result.recognizedText,
        mimeType = WRITING_RECOGNITION_MIME,
        confidence = result.confidence,
        traceFingerprint = result.traceFingerprint,
        createdAt = createdAt,
        extraTags = setOf("perception:writing", "grapheme-field", "recognition:resolved"),
    )

    fun asUserUtterance(
        sourceRecognition: Photon,
        modality: PerceptionModality,
        conversationId: String = "default",
        createdAt: Instant = sourceRecognition.provenance.createdAt,
    ): Photon {
        require(modality == PerceptionModality.SPEECH || modality == PerceptionModality.WRITING)
        require("recognition:unresolved" !in sourceRecognition.tags) {
            "Unresolved perception evidence cannot be promoted to a user utterance"
        }
        val fingerprint = stablePerceptionFingerprint(
            listOf(
                "perception-user-utterance/v1",
                sourceRecognition.id.value,
                modality.name,
                conversationId,
                sourceRecognition.content,
            )
        )
        return Photon(
            id = PhotonId("perception-chat-${fingerprint.take(40)}"),
            content = sourceRecognition.content,
            mimeType = "text/plain",
            phase = PhotonPhase.CREATED,
            semanticMass = sourceRecognition.semanticMass,
            energy = sourceRecognition.energy,
            confidence = sourceRecognition.confidence,
            provenance = Provenance(
                source = "multimodal-perception",
                actor = "user",
                createdAt = createdAt,
                parentIds = setOf(sourceRecognition.id),
            ),
            relations = setOf(
                PhotonRelation(sourceRecognition.id, RelationType.DERIVED_FROM),
                PhotonRelation(sourceRecognition.id, RelationType.TRANSFORMS),
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

    private fun derived(
        source: Photon,
        kind: String,
        content: String,
        mimeType: String,
        confidence: Double,
        traceFingerprint: String,
        createdAt: Instant,
        extraTags: Set<String>,
    ): Photon {
        val id = stablePerceptionFingerprint(
            listOf(
                "perception-photon/v1",
                kind,
                source.id.value,
                source.revision.toString(),
                traceFingerprint,
                content,
            )
        )
        return Photon(
            id = PhotonId("perception-${kind}-${id.take(40)}"),
            content = content,
            mimeType = mimeType,
            phase = PhotonPhase.CONVERGED,
            semanticMass = 1.0,
            energy = confidence.coerceAtLeast(0.05),
            confidence = confidence,
            provenance = Provenance(
                source = "multimodal-perception:$kind-field",
                actor = when (kind) {
                    "speech" -> "DeterministicSpeechRecognitionEngine"
                    "writing" -> "WritingFieldRecognitionEngine"
                    else -> "MultimodalPerception"
                },
                createdAt = createdAt,
                parentIds = setOf(source.id),
            ),
            relations = setOf(
                PhotonRelation(source.id, RelationType.DERIVED_FROM),
                PhotonRelation(source.id, RelationType.TRANSFORMS),
            ),
            tags = buildSet {
                add("perception")
                add("result")
                add("deterministic-field")
                add("trace:$traceFingerprint")
                addAll(extraTags)
                source.tags.filterTo(this) {
                    it.startsWith("conversation:") || it.startsWith("turn:")
                }
            },
        )
    }

    companion object {
        const val SPEECH_RECOGNITION_MIME = "application/vnd.lifeos.speech-field+text"
        const val WRITING_RECOGNITION_MIME = "application/vnd.lifeos.writing-field+text"
    }
}
