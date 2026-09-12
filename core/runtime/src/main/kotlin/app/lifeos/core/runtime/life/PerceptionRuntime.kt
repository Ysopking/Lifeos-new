package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
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
) {
    init {
        require(sourceId.isNotBlank()) { "Perception source id must not be blank" }
        require(mimeType.isNotBlank()) { "Perception MIME type must not be blank" }
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(salience.isFinite() && salience in 0.0..1.0)
        require(tags.none { it.isBlank() })
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
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
}

data class PerceptionBatch(
    val photons: List<Photon>,
    val batchFingerprint: String,
)

/**
 * Block B perception seam. It normalizes heterogeneous inputs into canonical Photons without
 * performing domain interpretation. Equal signal sets always yield equal ids and ordering.
 */
class PerceptionFusionEngine {
    fun fuse(signals: Collection<PerceptionSignal>): PerceptionBatch {
        val canonical = signals
            .distinctBy { it.fingerprint }
            .sortedWith(compareBy<PerceptionSignal> { it.observedAt }.thenBy { it.fingerprint })

        val photons = canonical.mapIndexed { ordinal, signal ->
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
                ),
                tags = signal.tags + setOf(
                    "perception",
                    "perception-source:${signal.source.name.lowercase()}",
                    "perception-fingerprint:${signal.fingerprint}",
                ),
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
