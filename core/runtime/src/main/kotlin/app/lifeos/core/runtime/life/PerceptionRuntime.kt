package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant

enum class PerceptionSource {
    CHAT,
    SENSOR,
    FILE,
    APP_EVENT,
    TOOL_RESULT,
    HUMAN_FEEDBACK,
}

data class PerceptionSignal(
    val source: PerceptionSource,
    val sourceId: String,
    val observedAt: Instant,
    val payload: String,
    val mimeType: String = "text/plain",
    val confidence: Double = 1.0,
    val salience: Double = 0.5,
    val tags: Set<String> = emptySet(),
    val modality: PerceptionModality? = null,
    val observedUntil: Instant = observedAt,
    val candidates: List<PerceptionCandidate> = emptyList(),
    val sourceAssetPhotonId: PhotonId? = null,
) {
    init {
        require(sourceId.isNotBlank()) { "Perception source id must not be blank" }
        require(mimeType.isNotBlank()) { "Perception MIME type must not be blank" }
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(salience.isFinite() && salience in 0.0..1.0)
        require(tags.none { it.isBlank() })
        require(!observedUntil.isBefore(observedAt))
        if (modality == null) {
            require(observedUntil == observedAt && candidates.isEmpty() && sourceAssetPhotonId == null) {
                "Typed perception metadata requires an explicit modality"
            }
        }
    }

    val canonicalCandidates: List<PerceptionCandidate> = candidates.sortedWith(
        compareByDescending<PerceptionCandidate> { it.confidence }
            .thenBy { it.value }
            .thenBy { it.semanticTag.orEmpty() }
    )

    val candidateDistributionFingerprint: String? = modality?.let {
        StableCognitiveIds.fingerprint(
            "perception-candidate-distribution/v1",
            *canonicalCandidates.flatMap { candidate ->
                listOf(
                    candidate.value,
                    candidate.semanticTag.orEmpty(),
                    java.lang.Double.toHexString(candidate.confidence),
                )
            }.toTypedArray(),
        )
    }

    /** Generic Block-B callers retain their exact v1 replay identity. Typed M callers use v2. */
    val fingerprint: String = if (modality == null) {
        StableCognitiveIds.fingerprint(
            "perception-signal/v1",
            source.name,
            sourceId,
            observedAt.toString(),
            mimeType,
            payload,
            java.lang.Double.toHexString(confidence),
            java.lang.Double.toHexString(salience),
            *tags.sorted().toTypedArray(),
        )
    } else {
        StableCognitiveIds.fingerprint(
            "perception-signal/v2",
            source.name,
            sourceId,
            modality.name,
            observedAt.toString(),
            observedUntil.toString(),
            mimeType,
            payload,
            java.lang.Double.toHexString(confidence),
            java.lang.Double.toHexString(salience),
            sourceAssetPhotonId?.value.orEmpty(),
            candidateDistributionFingerprint.orEmpty(),
            *tags.sorted().toTypedArray(),
        )
    }
}

data class PerceptionBatch(
    val photons: List<Photon>,
    val batchFingerprint: String,
)

/**
 * Normalizes generic and typed observations into canonical Photons without performing domain
 * interpretation. Equal signal sets yield equal ids/order. Typed candidate distributions and source
 * asset lineage are part of replay identity, so materially different observations cannot collapse.
 */
class PerceptionFusionEngine {
    fun fuse(signals: Collection<PerceptionSignal>): PerceptionBatch {
        val canonical = signals
            .distinctBy { it.fingerprint }
            .sortedWith(compareBy<PerceptionSignal> { it.observedAt }.thenBy { it.fingerprint })

        val photons = canonical.mapIndexed { ordinal, signal ->
            val parentIds = signal.sourceAssetPhotonId?.let(::setOf).orEmpty()
            val relations = signal.sourceAssetPhotonId?.let { assetId ->
                setOf(PhotonRelation(assetId, RelationType.REFERENCES))
            }.orEmpty()
            Photon(
                id = PhotonId(
                    "perception-" + StableCognitiveIds.fingerprint(
                        signal.fingerprint,
                        ordinal.toString(),
                    )
                ),
                content = signal.payload,
                mimeType = signal.mimeType,
                semanticMass = signal.salience,
                energy = signal.salience,
                confidence = signal.confidence,
                provenance = Provenance(
                    source = "perception:${signal.source.name.lowercase()}",
                    actor = signal.sourceId,
                    createdAt = signal.observedAt,
                    parentIds = parentIds,
                ),
                relations = relations,
                tags = buildSet {
                    addAll(signal.tags)
                    add("perception")
                    add("perception-source:${signal.source.name.lowercase()}")
                    add("perception-fingerprint:${signal.fingerprint}")
                    signal.modality?.let { add("perception-modality:${it.name.lowercase()}") }
                    signal.candidateDistributionFingerprint?.let { add("candidate-distribution:$it") }
                    if (signal.observedUntil != signal.observedAt) {
                        add("observation-until:${signal.observedUntil}")
                    }
                    signal.sourceAssetPhotonId?.let { add("source-asset:${it.value}") }
                },
            )
        }
        return PerceptionBatch(
            photons = photons,
            batchFingerprint = StableCognitiveIds.fingerprint(
                "perception-batch/v1",
                *photons.map { it.id.value }.toTypedArray(),
            ),
        )
    }
}
