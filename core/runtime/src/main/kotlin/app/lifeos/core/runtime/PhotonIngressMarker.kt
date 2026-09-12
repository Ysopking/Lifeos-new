package app.lifeos.core.runtime

import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.StableCognitiveIds

/**
 * Productive ingress semantics for one Photon revision.
 *
 * ORIGIN is intentionally represented by the absence of a marker. DERIVED and REPLAY are persisted
 * as separate management Photons so deterministic domain/future outputs remain byte-for-byte equal
 * to their reconstructed value while task observers can still suppress a second causal root pass.
 */
enum class PhotonIngressMode {
    ORIGIN,
    DERIVED,
    REPLAY,
}

/** Durable, fail-closed classification of Photon revisions entering productive runtime sinks. */
object PhotonIngressMarkerStore {
    const val MIME_TYPE = "application/vnd.lifeos.photon-ingress-marker+text"
    const val MARKER_TAG = "photon-ingress-marker"
    private const val SCHEMA = "1"

    suspend fun mark(
        photons: PhotonRepository,
        photon: Photon,
        mode: PhotonIngressMode,
    ): Photon {
        require(mode != PhotonIngressMode.ORIGIN) {
            "ORIGIN is represented by the absence of a Photon ingress marker"
        }
        val marker = marker(photon, mode)
        val existing = photons.load(marker.id)
        if (existing == null) {
            photons.save(marker)
        } else {
            check(existing == marker) {
                "Conflicting Photon ingress classification for ${photon.id.value}@${photon.revision}"
            }
        }
        return marker
    }

    /**
     * Returns ORIGIN only when no marker exists. Corrupt or mismatched markers throw so callers can
     * fail closed instead of accidentally re-running derived/replayed Photons as causal roots.
     */
    suspend fun mode(
        photons: PhotonRepository,
        photon: Photon,
    ): PhotonIngressMode {
        val stored = photons.load(markerId(photon)) ?: return PhotonIngressMode.ORIGIN
        require(stored.mimeType == MIME_TYPE && MARKER_TAG in stored.tags) {
            "Photon ingress marker identity resolved to another record"
        }
        require(stored.provenance.parentIds == setOf(photon.id)) {
            "Photon ingress marker target lineage mismatch"
        }
        val fields = stored.content.lineSequence()
            .filter { it.isNotBlank() }
            .associate { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "Malformed Photon ingress marker" }
                line.substring(0, separator) to line.substring(separator + 1)
            }
        require(fields["schema"] == SCHEMA) { "Unsupported Photon ingress marker schema" }
        require(fields["revision"] == photon.revision.toString()) {
            "Photon ingress marker revision mismatch"
        }
        require(fields["state"] == CanonicalPhotonState.inputHash(photon).value) {
            "Photon ingress marker state mismatch"
        }
        val mode = fields["mode"]?.let(PhotonIngressMode::valueOf)
            ?: error("Photon ingress marker mode missing")
        require(mode != PhotonIngressMode.ORIGIN) {
            "Persisted ORIGIN Photon ingress marker is invalid"
        }
        require("photon-ingress-mode:${mode.name.lowercase()}" in stored.tags) {
            "Photon ingress marker mode tag mismatch"
        }
        return mode
    }

    fun markerId(photon: Photon): PhotonId = PhotonId(
        "photon-ingress-" + StableCognitiveIds.fingerprint(
            "photon-ingress-marker/v1",
            photon.id.value,
            photon.revision.toString(),
        )
    )

    private fun marker(photon: Photon, mode: PhotonIngressMode): Photon = Photon(
        id = markerId(photon),
        revision = 1,
        content = buildString {
            appendLine("schema=$SCHEMA")
            appendLine("revision=${photon.revision}")
            appendLine("state=${CanonicalPhotonState.inputHash(photon).value}")
            append("mode=${mode.name}")
        },
        mimeType = MIME_TYPE,
        semanticMass = 0.0,
        energy = 0.0,
        confidence = 1.0,
        provenance = Provenance(
            source = "photon-ingress",
            actor = "lifeos",
            createdAt = photon.provenance.createdAt,
            parentIds = setOf(photon.id),
        ),
        tags = setOf(
            "life-memory-management",
            MARKER_TAG,
            "photon-ingress-mode:${mode.name.lowercase()}",
        ),
    )
}
