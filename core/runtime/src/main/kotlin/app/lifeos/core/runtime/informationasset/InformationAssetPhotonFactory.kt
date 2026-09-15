package app.lifeos.core.runtime.informationasset

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant

object InformationAssetPhotonContract {
    const val MIME_TYPE = "application/vnd.lifeos.information-asset+json"
    const val SCHEMA = "lifeos.information-asset.v1"
    const val PROVENANCE_SOURCE = "lifeos.information-asset"
    const val PROVENANCE_ACTOR = "lifeos-runtime"
}

/**
 * Creates one immutable Photon per semantic asset revision. The Photon id is revision-derived;
 * source Photon states are referenced but never mutated.
 */
class InformationAssetPhotonFactory {
    fun create(
        revision: InformationAssetRevision,
        createdAt: Instant,
    ): Photon {
        val manifest = revision.manifest
        val photonId = PhotonId(
            "infoasset_" + InformationAssetFingerprints.fingerprint(
                "information-asset-photon/v1",
                revision.request.id.value,
                manifest.id.value,
                manifest.stateHash.value,
            )
        )
        val parentIds = buildSet {
            manifest.sourcePhotons.forEach { add(it.photonId) }
            manifest.parent?.let { add(it.photonId) }
        }
        val relations = linkedSetOf<PhotonRelation>()
        manifest.sourcePhotons
            .sortedWith(compareBy<PhotonRevisionReference> { it.photonId.value }.thenBy { it.revision })
            .forEach { source ->
                relations += PhotonRelation(
                    target = source.photonId,
                    type = RelationType.DERIVED_FROM,
                )
            }
        manifest.parent?.let { parent ->
            relations += PhotonRelation(
                target = parent.photonId,
                type = RelationType.TRANSFORMS,
            )
        }
        val confidence = revision.evidenceBindings.minOfOrNull { it.confidence }
            ?: revision.claims.minOf { it.confidence }
        return Photon(
            id = photonId,
            revision = 1L,
            content = envelope(revision),
            mimeType = InformationAssetPhotonContract.MIME_TYPE,
            phase = when (manifest.resolution) {
                InformationAssetResolutionState.CONVERGED -> PhotonPhase.CONVERGED
                InformationAssetResolutionState.UNRESOLVED -> PhotonPhase.REFLECTING
            },
            semanticMass = revision.claims.size.toDouble().coerceAtLeast(1.0),
            energy = 1.0,
            confidence = confidence,
            provenance = Provenance(
                source = InformationAssetPhotonContract.PROVENANCE_SOURCE,
                actor = InformationAssetPhotonContract.PROVENANCE_ACTOR,
                createdAt = createdAt,
                parentIds = parentIds,
            ),
            relations = relations,
            tags = buildSet {
                add("information-asset")
                add("information-asset-id:${revision.request.id.value}")
                add("information-asset-revision:${manifest.id.value}")
                add("information-asset-state-hash:${manifest.stateHash.value}")
                add("information-asset-kind:${revision.request.kind.name.lowercase()}")
                add("information-asset-resolution:${manifest.resolution.name.lowercase()}")
                manifest.domainIds.map { it.value }.sorted().forEach { add("information-domain:$it") }
                manifest.participatingModules.sorted().forEach { add("information-module:$it") }
                manifest.parent?.let { add("information-parent-revision:${it.revisionId.value}") }
            },
        )
    }

    private fun envelope(revision: InformationAssetRevision): String = buildString {
        val manifest = revision.manifest
        append('{')
        append("\"schema\":"); appendJson(InformationAssetPhotonContract.SCHEMA); append(',')
        append("\"assetId\":"); appendJson(revision.request.id.value); append(',')
        append("\"revisionId\":"); appendJson(manifest.id.value); append(',')
        append("\"stateHash\":"); appendJson(manifest.stateHash.value); append(',')
        append("\"kind\":"); appendJson(revision.request.kind.name); append(',')
        append("\"title\":"); appendJson(revision.request.title); append(',')
        append("\"primaryDomainId\":"); appendJson(revision.request.primaryDomainId.value); append(',')
        append("\"resolution\":"); appendJson(manifest.resolution.name); append(',')
        append("\"parentRevision\":")
        manifest.parent?.let { parent ->
            append('{')
            append("\"assetId\":"); appendJson(parent.assetId.value); append(',')
            append("\"revisionId\":"); appendJson(parent.revisionId.value); append(',')
            append("\"photonId\":"); appendJson(parent.photonId.value)
            append('}')
        } ?: append("null")
        append(",\"sourcePhotons\":[")
        manifest.sourcePhotons
            .sortedWith(compareBy<PhotonRevisionReference> { it.photonId.value }.thenBy { it.revision })
            .forEachIndexed { index, source ->
                if (index > 0) append(',')
                append('{')
                append("\"id\":"); appendJson(source.photonId.value); append(',')
                append("\"revision\":"); append(source.revision); append(',')
                append("\"inputStateHash\":"); appendJson(source.inputStateHash.value); append(',')
                append("\"semanticStateHash\":"); appendJson(source.semanticStateHash.value)
                append('}')
            }
        append("],\"domains\":[")
        manifest.domainIds.map { it.value }.sorted().forEachIndexed { index, domain ->
            if (index > 0) append(',')
            appendJson(domain)
        }
        append("],\"modules\":[")
        manifest.participatingModules.sorted().forEachIndexed { index, module ->
            if (index > 0) append(',')
            appendJson(module)
        }
        append("],\"evidence\":[")
        revision.evidenceBindings.sortedBy { it.id.value }.forEachIndexed { index, binding ->
            if (index > 0) append(',')
            append('{')
            append("\"id\":"); appendJson(binding.id.value); append(',')
            append("\"sourcePhotonId\":"); appendJson(binding.source.photonId.value); append(',')
            append("\"sourceRevision\":"); append(binding.source.revision); append(',')
            append("\"domainId\":"); appendJson(binding.domainId.value); append(',')
            append("\"authority\":"); appendJson(binding.authority.name); append(',')
            append("\"confidence\":"); appendJson(binding.confidence.toString()); append(',')
            append("\"payloadFingerprint\":"); appendJson(binding.payloadFingerprint)
            append('}')
        }
        append("],\"claims\":[")
        revision.claims.sortedBy { it.id.value }.forEachIndexed { index, claim ->
            if (index > 0) append(',')
            append('{')
            append("\"id\":"); appendJson(claim.id.value); append(',')
            append("\"domainId\":"); appendJson(claim.domainId.value); append(',')
            append("\"semanticKey\":"); appendJson(claim.semanticKey); append(',')
            append("\"statement\":"); appendJson(claim.statement); append(',')
            append("\"state\":"); appendJson(claim.state.name); append(',')
            append("\"confidence\":"); appendJson(claim.confidence.toString())
            append('}')
        }
        append("],\"conflicts\":[")
        revision.conflicts.sortedBy { it.id.value }.forEachIndexed { index, conflict ->
            if (index > 0) append(',')
            append('{')
            append("\"id\":"); appendJson(conflict.id.value); append(',')
            append("\"domainId\":"); appendJson(conflict.domainId.value); append(',')
            append("\"state\":"); appendJson(conflict.state.name); append(',')
            append("\"severity\":"); appendJson(conflict.severity.toString())
            append('}')
        }
        append("]}")
    }

    private fun StringBuilder.appendJson(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u%04x".format(char.code))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }
}
