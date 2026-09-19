package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.SemanticArtifactKind
import app.lifeos.core.model.SemanticArtifactPlan
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionRef
import java.time.Instant

object ArtifactGenerationContract {
    const val SCHEMA = "lifeos.artifact-generation.v1"
    const val MIME_TYPE = "application/vnd.lifeos.artifact-generation+json"
    const val PROVENANCE_SOURCE = "lifeos.artifact-generation"
    const val PROVENANCE_ACTOR = "lifeos-runtime"
}

data class ArtifactGenerationRequest(
    val request: CollaborativeArtifactRequest,
    val profile: ArtifactGenerationProfile,
    val contributions: List<ArtifactContribution>,
    val semanticPlan: SemanticArtifactPlan,
    val finalizedAt: Instant,
    val materializedAsset: AssetRef,
    val parentRevision: ArtifactRevisionRef? = null,
    val semanticInputRevisions: List<InformationAssetRevisionRef> = emptyList(),
) {
    init {
        profile.requireCompatible(request.kind)
        require(materializedAsset.mediaType == request.targetMimeType) {
            "Materialized asset MIME type ${materializedAsset.mediaType} does not match requested ${request.targetMimeType}"
        }
        require(semanticPlan.kind == expectedSemanticKind(request)) {
            "Semantic artifact plan kind ${semanticPlan.kind} is incompatible with ${request.kind}"
        }
        require(semanticInputRevisions.map { it.assetId }.distinct().size == semanticInputRevisions.size) {
            "Artifact generation cannot bind multiple revisions of one InformationAsset"
        }
        val contributionParents = contributions.flatMap { it.provenance.parentIds }.toSet()
        val unrepresentedSemanticInputs = semanticInputRevisions
            .map { it.photonId }
            .toSet() - contributionParents
        require(unrepresentedSemanticInputs.isEmpty()) {
            "Artifact generation semantic inputs must already be represented by contribution provenance: " +
                unrepresentedSemanticInputs.map { it.value }.sorted().joinToString(",")
        }
    }

    private fun expectedSemanticKind(request: CollaborativeArtifactRequest): SemanticArtifactKind =
        when (request.kind) {
            ArtifactKind.IMAGE -> SemanticArtifactKind.IMAGE
            ArtifactKind.CODE -> SemanticArtifactKind.TASK
            ArtifactKind.DOCUMENT,
            ArtifactKind.REPORT -> if (request.targetMimeType == "application/pdf") {
                SemanticArtifactKind.PDF
            } else {
                SemanticArtifactKind.TEXT
            }
            ArtifactKind.OTHER -> SemanticArtifactKind.TEXT
        }
}

data class ArtifactGenerationResult(
    val finalization: ArtifactFinalizationResult,
    val generationPhoton: Photon,
    val generationReentry: ArtifactReentryReceipt,
)

/**
 * Adds deterministic generation provenance around the immutable artifact revision lifecycle.
 *
 * Artifact revision identity remains content/lineage based. Generation configuration is emitted as
 * its own DERIVED Photon, so identical bytes produced by different document/code/image profiles can
 * share one artifact revision while retaining distinct, replay-safe execution provenance.
 */
class ArtifactGenerationCoordinator(
    private val artifacts: ArtifactCoordinator,
    private val ingress: ArtifactPhotonIngress,
) {
    suspend fun finalize(generation: ArtifactGenerationRequest): ArtifactGenerationResult {
        val finalization = artifacts.finalize(
            request = generation.request,
            contributions = generation.contributions,
            finalizedAt = generation.finalizedAt,
            parentRevision = generation.parentRevision,
            materializedAsset = generation.materializedAsset,
            semanticPlan = generation.semanticPlan,
        )
        val artifact = finalization.artifact
        val revision = requireNotNull(artifact.revision) {
            "Generated artifacts require an immutable revision manifest"
        }
        val missingSemanticInputs = generation.semanticInputRevisions
            .map { it.photonId }
            .toSet() - revision.inputPhotonIds
        require(missingSemanticInputs.isEmpty()) {
            "Artifact generation semantic inputs are not retained by Artifact revision: " +
                missingSemanticInputs.map { it.value }.sorted().joinToString(",")
        }
        val generationPhoton = createGenerationPhoton(
            generation = generation,
            artifactPhoton = artifact.photon,
            revision = revision,
            effectiveFinalizedAt = artifact.finalizedAt,
        )
        val receipt = ingress.ingest(generationPhoton)
        return ArtifactGenerationResult(
            finalization = finalization,
            generationPhoton = generationPhoton,
            generationReentry = receipt,
        )
    }

    private fun createGenerationPhoton(
        generation: ArtifactGenerationRequest,
        artifactPhoton: Photon,
        revision: ArtifactRevisionManifest,
        effectiveFinalizedAt: Instant,
    ): Photon {
        val semanticParts = generation.semanticInputRevisions
            .sortedWith(
                compareBy<InformationAssetRevisionRef> { it.assetId.value }
                    .thenBy { it.revisionId.value }
                    .thenBy { it.photonId.value }
            )
            .flatMap { ref -> listOf(ref.assetId.value, ref.revisionId.value, ref.photonId.value) }
        val fingerprint = ArtifactFingerprints.fingerprint(
            "artifact-generation-photon/v2",
            generation.request.id.value,
            revision.id.value,
            artifactPhoton.id.value,
            generation.materializedAsset.sha256,
            generation.semanticPlan.fingerprint,
            *generation.profile.fingerprintParts().toTypedArray(),
            *semanticParts.toTypedArray(),
        )
        val photonId = PhotonId("artifact_generation_$fingerprint")
        val inputPhotonIds = revision.inputPhotonIds.toSortedSet(compareBy { it.value })
        val parents = linkedSetOf<PhotonId>().apply {
            add(artifactPhoton.id)
            addAll(inputPhotonIds)
        }
        val relations = parents.mapTo(linkedSetOf()) { parentId ->
            PhotonRelation(
                target = parentId,
                type = RelationType.DERIVED_FROM,
            )
        }
        return Photon(
            id = photonId,
            revision = 1L,
            content = envelopeJson(generation, artifactPhoton, revision, effectiveFinalizedAt),
            mimeType = ArtifactGenerationContract.MIME_TYPE,
            phase = PhotonPhase.CONVERGED,
            semanticMass = 1.0,
            energy = 1.0,
            confidence = artifactPhoton.confidence,
            provenance = Provenance(
                source = ArtifactGenerationContract.PROVENANCE_SOURCE,
                actor = ArtifactGenerationContract.PROVENANCE_ACTOR,
                createdAt = effectiveFinalizedAt,
                parentIds = parents,
            ),
            relations = relations,
            tags = buildSet {
                add("artifact-generation")
                add("artifact-id:${generation.request.id.value}")
                add("artifact-revision:${revision.id.value}")
                add("artifact-generation-profile:${generation.profile.type}")
                add("artifact-output-sha256:${generation.materializedAsset.sha256}")
                add("artifact-semantic-plan:${generation.semanticPlan.fingerprint}")
                generation.semanticInputRevisions
                    .sortedWith(
                        compareBy<InformationAssetRevisionRef> { it.assetId.value }
                            .thenBy { it.revisionId.value }
                            .thenBy { it.photonId.value }
                    )
                    .forEach { ref ->
                        add("information-asset-input:${ref.assetId.value}:${ref.revisionId.value}:${ref.photonId.value}")
                    }
                when (val profile = generation.profile) {
                    is DocumentArtifactProfile -> add("artifact-document-format:${profile.format}")
                    is CodeArtifactProfile -> add("artifact-code-language:${profile.language}")
                    is ImageArtifactProfile -> {
                        add("artifact-image-size:${profile.width}x${profile.height}")
                        add("artifact-image-prompt:${profile.promptFingerprint}")
                    }
                }
            },
        )
    }

    private fun envelopeJson(
        generation: ArtifactGenerationRequest,
        artifactPhoton: Photon,
        revision: ArtifactRevisionManifest,
        effectiveFinalizedAt: Instant,
    ): String = buildString {
        append('{')
        append("\"schema\":"); appendJson(ArtifactGenerationContract.SCHEMA); append(',')
        append("\"artifactId\":"); appendJson(generation.request.id.value); append(',')
        append("\"artifactPhotonId\":"); appendJson(artifactPhoton.id.value); append(',')
        append("\"revisionId\":"); appendJson(revision.id.value); append(',')
        append("\"kind\":"); appendJson(generation.request.kind.name); append(',')
        append("\"targetMimeType\":"); appendJson(generation.request.targetMimeType); append(',')
        append("\"finalizedAt\":"); appendJson(effectiveFinalizedAt.toString()); append(',')
        append("\"semanticPlanFingerprint\":"); appendJson(generation.semanticPlan.fingerprint); append(',')
        append("\"asset\":{")
        append("\"id\":"); appendJson(generation.materializedAsset.id.value); append(',')
        append("\"mediaType\":"); appendJson(generation.materializedAsset.mediaType); append(',')
        append("\"byteCount\":"); append(generation.materializedAsset.byteCount); append(',')
        append("\"sha256\":"); appendJson(generation.materializedAsset.sha256)
        append("},\"inputPhotonIds\":[")
        revision.inputPhotonIds.map { it.value }.sorted().forEachIndexed { index, inputId ->
            if (index > 0) append(',')
            appendJson(inputId)
        }
        append("],\"participatingModules\":[")
        revision.participatingModules.sorted().forEachIndexed { index, module ->
            if (index > 0) append(',')
            appendJson(module)
        }
        append("],\"profile\":")
        appendProfile(generation.profile)
        if (generation.semanticInputRevisions.isNotEmpty()) {
            append(",\"informationAssetInputs\":[")
            generation.semanticInputRevisions
                .sortedWith(
                    compareBy<InformationAssetRevisionRef> { it.assetId.value }
                        .thenBy { it.revisionId.value }
                        .thenBy { it.photonId.value }
                )
                .forEachIndexed { index, ref ->
                    if (index > 0) append(',')
                    append('{')
                    append("\"assetId\":"); appendJson(ref.assetId.value); append(',')
                    append("\"revisionId\":"); appendJson(ref.revisionId.value); append(',')
                    append("\"photonId\":"); appendJson(ref.photonId.value)
                    append('}')
                }
            append(']')
        }
        append('}')
    }

    private fun StringBuilder.appendProfile(profile: ArtifactGenerationProfile) {
        append('{')
        append("\"type\":"); appendJson(profile.type)
        when (profile) {
            is DocumentArtifactProfile -> {
                append(",\"format\":"); appendJson(profile.format)
                append(",\"style\":")
                if (profile.style == null) append("null") else appendJson(profile.style)
            }
            is CodeArtifactProfile -> {
                append(",\"language\":"); appendJson(profile.language)
                append(",\"entrypoint\":")
                if (profile.entrypoint == null) append("null") else appendJson(profile.entrypoint)
                append(",\"files\":[")
                profile.files.sorted().forEachIndexed { index, file ->
                    if (index > 0) append(',')
                    appendJson(file)
                }
                append(']')
            }
            is ImageArtifactProfile -> {
                append(",\"width\":"); append(profile.width)
                append(",\"height\":"); append(profile.height)
                append(",\"promptFingerprint\":"); appendJson(profile.promptFingerprint)
                append(",\"model\":")
                if (profile.model == null) append("null") else appendJson(profile.model)
            }
        }
        append('}')
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
