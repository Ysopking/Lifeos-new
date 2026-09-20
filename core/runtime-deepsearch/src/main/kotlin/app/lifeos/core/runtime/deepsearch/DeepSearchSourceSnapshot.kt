package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon

/**
 * Stable fingerprint of the exact Photon evidence visible to one DeepSearch mission.
 * Includes every field that can change local search eligibility, ordering or scoring.
 */
object DeepSearchSourceSnapshot {
    fun fingerprint(photons: List<Photon>): String {
        val entries = photons
            .sortedWith(compareBy<Photon> { it.id.value }.thenBy { it.revision })
            .map { photon ->
                StableFieldIds.fingerprint(
                    "deep-search-source-photon/v1",
                    photon.id.value,
                    photon.revision.toString(),
                    photon.content,
                    photon.mimeType,
                    photon.phase.name,
                    java.lang.Double.toHexString(photon.confidence),
                    photon.provenance.createdAt.toString(),
                    *photon.tags.sorted().map { "tag:$it" }.toTypedArray(),
                )
            }
        return StableFieldIds.fingerprint(
            "deep-search-source-snapshot/v1",
            *entries.toTypedArray(),
        )
    }
}
