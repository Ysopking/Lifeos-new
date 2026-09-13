package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetId
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.runtime.cognition.CognitivePriority
import app.lifeos.core.runtime.cognition.CognitiveWorkBudget
import app.lifeos.core.runtime.cognition.ContinuousCognitionEngine
import app.lifeos.core.runtime.cognition.PhotonDelta
import app.lifeos.core.runtime.cognition.PhotonDeltaType
import app.lifeos.core.runtime.cognition.SalienceVector
import java.time.Instant

object ArtifactCoordinatorContract {
    const val ENVELOPE_MIME_TYPE = "application/vnd.lifeos.collaborative-artifact+json"
    const val PROVENANCE_SOURCE = "lifeos.collaborative-artifact"
    const val PROVENANCE_ACTOR = "lifeos-runtime"
    const val SCHEMA = "lifeos.collaborative-artifact.v1"
    const val LIFECYCLE_SCHEMA = "lifeos.collaborative-artifact.v2"
    const val DEFINITION_FINGERPRINT_TAG_PREFIX = "artifact-definition-fingerprint:"
    const val ASSET_ID_TAG_PREFIX = "artifact-asset-id:"
}

/**
 * Complete productive ingress for one finalized artifact Photon. Implementations own persistence
 * and durable cognition submission as one canonical boundary.
 */
fun interface ArtifactPhotonIngress {
    suspend fun ingest(photon: Photon): ArtifactReentryReceipt
}

/** Legacy continuous-cognition adapter retained for compatibility; productive composition uses [ArtifactPhotonIngress]. */
fun interface ArtifactPhotonReentry {
    suspend fun submit(photon: Photon): ArtifactReentryReceipt
}

/**
 * Legacy re-entry adapter. It does not persist the Photon and therefore must not be used as the
 * productive artifact ingress. Android production composition binds [ArtifactPhotonIngress] to the
 * canonical Photon ingress instead.
 */
class ContinuousCognitionArtifactReentry(
    private val cognition: ContinuousCognitionEngine,
    private val targetModules: Set<String> = emptySet(),
    private val budget: CognitiveWorkBudget = CognitiveWorkBudget(
        maxDurationMs = 5_000,
        maxModuleInvocations = 16,
        maxNewPhotons = 16,
        maxNetworkCalls = 0,
    ),
) : ArtifactPhotonReentry {
    init {
        require(targetModules.none { it.isBlank() }) { "Artifact target modules must not be blank" }
    }

    override suspend fun submit(photon: Photon): ArtifactReentryReceipt {
        val delta = PhotonDelta(
            deltaId = "artifact:${photon.id.value}:revision:${photon.revision}",
            source = ArtifactCoordinatorContract.PROVENANCE_SOURCE,
            photonId = photon.id,
            revisionAfter = photon.revision,
            type = PhotonDeltaType.CREATED,
            importanceHint = photon.semanticMass,
            timestamp = photon.provenance.createdAt,
            causationId = photon.id.value,
            correlationId = photon.id.value,
        )
        val result = cognition.submit(
            delta = delta,
            priority = CognitivePriority.NORMAL,
            salience = SalienceVector(
                novelty = 1.0,
                relevance = 1.0,
                semanticMass = photon.semanticMass,
                confidenceImpact = photon.confidence,
            ),
            targetModules = targetModules,
            budget = budget,
        )
        return ArtifactReentryReceipt(
            accepted = result.accepted,
            durableTaskId = result.durableTaskId,
        )
    }
}

class ArtifactCoordinator(
    private val photons: PhotonRepository,
    private val ingress: ArtifactPhotonIngress,
    private val validator: ArtifactValidator = ArtifactValidator(),
) {
    suspend fun finalize(
        request: CollaborativeArtifactRequest,
        contributions: List<ArtifactContribution>,
        finalizedAt: Instant,
        lifecycle: ArtifactLifecycle = ArtifactLifecycle(),
    ): ArtifactFinalizationResult {
        validator.requireValid(request, contributions, finalizedAt)
        requireLifecycleCompatible(request, lifecycle, finalizedAt)
        requirePersistedLineage(request, lifecycle, finalizedAt)

        val canonicalContributions = contributions.sortedWith(
            compareBy<ArtifactContribution>(
                { it.field },
                { it.module },
                { it.source },
                { it.id },
            )
        )
        val photonId = artifactPhotonId(request, canonicalContributions, lifecycle)
        val existing = photons.load(photonId)
        val photon: Photon
        val effectiveFinalizedAt: Instant
        val effectiveLifecycle: ArtifactLifecycle
        if (existing == null) {
            photon = createPhoton(
                photonId = photonId,
                request = request,
                contributions = canonicalContributions,
                finalizedAt = finalizedAt,
                lifecycle = lifecycle,
            )
            effectiveFinalizedAt = finalizedAt
            effectiveLifecycle = lifecycle
        } else {
            requireOwnedArtifact(
                photon = existing,
                artifactId = request.id,
                expectedRevision = lifecycle.revision.revision,
            )
            effectiveLifecycle = if (lifecycle.isLegacyDefault) {
                lifecycle
            } else {
                val expectedFingerprint = artifactRevisionDefinitionFingerprint(
                    request = request,
                    contributions = canonicalContributions,
                    lifecycle = lifecycle,
                )
                require(
                    "${ArtifactCoordinatorContract.DEFINITION_FINGERPRINT_TAG_PREFIX}$expectedFingerprint" in existing.tags
                ) {
                    "Artifact ${request.id.value} revision ${lifecycle.revision.revision} conflicts with persisted state"
                }
                canonicalizePersistedAssetLocator(existing, lifecycle)
            }
            photon = existing
            effectiveFinalizedAt = existing.provenance.createdAt
        }

        val receipt = ingress.ingest(photon)
        return ArtifactFinalizationResult(
            artifact = CollaborativeArtifact(
                request = request,
                contributions = canonicalContributions,
                photon = photon,
                finalizedAt = effectiveFinalizedAt,
                lifecycle = effectiveLifecycle,
            ),
            reentry = receipt,
        )
    }

    private suspend fun requirePersistedLineage(
        request: CollaborativeArtifactRequest,
        lifecycle: ArtifactLifecycle,
        finalizedAt: Instant,
    ) {
        val revision = lifecycle.revision
        revision.parentPhotonId?.let { parentId ->
            val parent = requireNotNull(photons.load(parentId)) {
                "Artifact revision ${revision.revision} parent Photon ${parentId.value} is not persisted"
            }
            requireOwnedArtifact(
                photon = parent,
                artifactId = request.id,
                expectedRevision = revision.revision - 1L,
            )
            require(parent.provenance.createdAt <= finalizedAt) {
                "Artifact revision parent cannot postdate its child"
            }
        }

        revision.inputPhotonIds.sortedBy { it.value }.forEach { inputId ->
            requireNotNull(photons.load(inputId)) {
                "Artifact input Photon ${inputId.value} is not persisted"
            }
        }
        lifecycle.output
            ?.validationEvidence
            .orEmpty()
            .flatMap { it.evidencePhotonIds }
            .toSet()
            .sortedBy { it.value }
            .forEach { evidenceId ->
                requireNotNull(photons.load(evidenceId)) {
                    "Artifact validation evidence Photon ${evidenceId.value} is not persisted"
                }
            }
    }

    private fun requireLifecycleCompatible(
        request: CollaborativeArtifactRequest,
        lifecycle: ArtifactLifecycle,
        finalizedAt: Instant,
    ) {
        val output = lifecycle.output ?: return
        require(output.asset.mediaType == request.targetMimeType) {
            "Artifact asset MIME type must match request target MIME type"
        }
        when (output.profile) {
            is DocumentArtifactProfile -> require(
                request.kind == ArtifactKind.DOCUMENT || request.kind == ArtifactKind.REPORT
            ) { "Document generation profile requires DOCUMENT or REPORT artifact kind" }
            is CodeArtifactProfile -> require(request.kind == ArtifactKind.CODE) {
                "Code generation profile requires CODE artifact kind"
            }
            is ImageArtifactProfile -> require(request.kind == ArtifactKind.IMAGE) {
                "Image generation profile requires IMAGE artifact kind"
            }
        }
        require(output.validationEvidence.all { it.passed }) {
            "Finalized artifact cannot contain failed validation evidence"
        }
        require(output.validationEvidence.all { it.observedAt <= finalizedAt }) {
            "Artifact validation evidence cannot postdate finalization"
        }
    }

    private fun artifactPhotonId(
        request: CollaborativeArtifactRequest,
        contributions: List<ArtifactContribution>,
        lifecycle: ArtifactLifecycle,
    ): PhotonId {
        if (lifecycle.isLegacyDefault) {
            val identity = ArtifactFingerprints.fingerprint(
                "collaborative-artifact-photon/v1",
                request.id.value,
                request.kind.name,
                request.title,
                request.targetMimeType,
                request.requestedAt.toString(),
                *request.requiredFields.sorted().toTypedArray(),
                *contributions.map { it.contentFingerprint() }.toTypedArray(),
            )
            return PhotonId("artifact:${request.id.value}:$identity")
        }

        val identity = ArtifactFingerprints.fingerprint(
            "collaborative-artifact-revision-photon/v2",
            request.id.value,
            lifecycle.revision.revision.toString(),
        )
        return PhotonId("artifact-revision:$identity")
    }

    private fun artifactRevisionDefinitionFingerprint(
        request: CollaborativeArtifactRequest,
        contributions: List<ArtifactContribution>,
        lifecycle: ArtifactLifecycle,
    ): String {
        val parts = buildList {
            add("collaborative-artifact-definition/v2")
            add(request.id.value)
            add(request.kind.name)
            add(request.title)
            add(request.targetMimeType)
            add(request.requestedAt.toString())
            addAll(request.requiredFields.sorted())
            addAll(contributions.map { it.contentFingerprint() })
            add(lifecycle.contentFingerprint())
        }
        return ArtifactFingerprints.fingerprint(*parts.toTypedArray())
    }

    private fun canonicalizePersistedAssetLocator(
        photon: Photon,
        lifecycle: ArtifactLifecycle,
    ): ArtifactLifecycle {
        val output = lifecycle.output ?: return lifecycle
        val persistedAssetIds = photon.tags
            .asSequence()
            .filter { it.startsWith(ArtifactCoordinatorContract.ASSET_ID_TAG_PREFIX) }
            .map { it.removePrefix(ArtifactCoordinatorContract.ASSET_ID_TAG_PREFIX) }
            .toSet()
        require(persistedAssetIds.size == 1) {
            "Artifact Photon ${photon.id.value} has invalid persisted asset locator metadata"
        }
        val persistedAssetId = AssetId(persistedAssetIds.single())
        return lifecycle.copy(
            output = output.copy(
                asset = output.asset.copy(id = persistedAssetId),
            )
        )
    }

    private fun createPhoton(
        photonId: PhotonId,
        request: CollaborativeArtifactRequest,
        contributions: List<ArtifactContribution>,
        finalizedAt: Instant,
        lifecycle: ArtifactLifecycle,
    ): Photon {
        val contributionParents = contributions
            .flatMap { it.provenance.parentIds }
            .toSet()
        val lineageParents = buildSet {
            addAll(contributionParents)
            addAll(lifecycle.revision.inputPhotonIds)
            lifecycle.revision.parentPhotonId?.let(::add)
        }
        val evidenceParents = lifecycle.output
            ?.validationEvidence
            .orEmpty()
            .flatMap { it.evidencePhotonIds }
            .toSet()
        val allParents = (lineageParents + evidenceParents).toSortedSet(compareBy { it.value })
        val confidence = contributions.minOf { it.confidence }
        val content = envelopeJson(request, contributions, finalizedAt, lifecycle)
        return Photon(
            id = photonId,
            revision = lifecycle.revision.revision,
            content = content,
            mimeType = ArtifactCoordinatorContract.ENVELOPE_MIME_TYPE,
            phase = PhotonPhase.CONVERGED,
            semanticMass = contributions.size.toDouble().coerceAtLeast(1.0),
            energy = 1.0,
            confidence = confidence,
            provenance = Provenance(
                source = ArtifactCoordinatorContract.PROVENANCE_SOURCE,
                actor = ArtifactCoordinatorContract.PROVENANCE_ACTOR,
                createdAt = finalizedAt,
                parentIds = allParents,
            ),
            relations = buildSet {
                lineageParents.sortedBy { it.value }.forEach { parentId ->
                    add(PhotonRelation(target = parentId, type = RelationType.DERIVED_FROM))
                }
                evidenceParents.sortedBy { it.value }.forEach { evidenceId ->
                    add(PhotonRelation(target = evidenceId, type = RelationType.SUPPORTS))
                }
            },
            tags = buildSet {
                add("artifact")
                add("artifact-kind:${request.kind.name.lowercase()}")
                add("artifact-id:${request.id.value}")
                contributions.map { it.field }.toSortedSet().forEach { field ->
                    add("artifact-field:$field")
                }
                if (!lifecycle.isLegacyDefault) {
                    add("artifact-schema:v2")
                    add("artifact-revision:${lifecycle.revision.revision}")
                    add(
                        ArtifactCoordinatorContract.DEFINITION_FINGERPRINT_TAG_PREFIX +
                            artifactRevisionDefinitionFingerprint(request, contributions, lifecycle)
                    )
                    lifecycle.output?.let { output ->
                        add("artifact-asset-sha256:${output.asset.sha256}")
                        add("artifact-asset-media:${output.asset.mediaType}")
                        add("${ArtifactCoordinatorContract.ASSET_ID_TAG_PREFIX}${output.asset.id.value}")
                    }
                }
            },
        )
    }

    private fun requireOwnedArtifact(
        photon: Photon,
        artifactId: ArtifactId,
        expectedRevision: Long,
    ) {
        require(photon.mimeType == ArtifactCoordinatorContract.ENVELOPE_MIME_TYPE) {
            "Artifact Photon id collision for ${photon.id.value}: unexpected MIME type"
        }
        require(photon.provenance.source == ArtifactCoordinatorContract.PROVENANCE_SOURCE) {
            "Artifact Photon id collision for ${photon.id.value}: unexpected provenance source"
        }
        require(photon.revision == expectedRevision) {
            "Artifact Photon ${photon.id.value} has revision ${photon.revision}, expected $expectedRevision"
        }
        require("artifact-id:${artifactId.value}" in photon.tags) {
            "Artifact Photon ${photon.id.value} belongs to another logical artifact"
        }
    }

    private fun envelopeJson(
        request: CollaborativeArtifactRequest,
        contributions: List<ArtifactContribution>,
        finalizedAt: Instant,
        lifecycle: ArtifactLifecycle,
    ): String = buildString {
        append('{')
        append("\"schema\":")
        appendJson(
            if (lifecycle.isLegacyDefault) ArtifactCoordinatorContract.SCHEMA
            else ArtifactCoordinatorContract.LIFECYCLE_SCHEMA
        )
        append(',')
        append("\"artifactId\":"); appendJson(request.id.value); append(',')
        append("\"kind\":"); appendJson(request.kind.name); append(',')
        append("\"title\":"); appendJson(request.title); append(',')
        append("\"targetMimeType\":"); appendJson(request.targetMimeType); append(',')
        append("\"requestedAt\":"); appendJson(request.requestedAt.toString()); append(',')
        append("\"finalizedAt\":"); appendJson(finalizedAt.toString()); append(',')
        if (!lifecycle.isLegacyDefault) {
            append("\"revision\":"); append(lifecycle.revision.revision); append(',')
            append("\"parentPhotonId\":")
            appendNullableJson(lifecycle.revision.parentPhotonId?.value)
            append(',')
            append("\"inputPhotonIds\":[")
            lifecycle.revision.inputPhotonIds.map { it.value }.sorted().forEachIndexed { index, inputId ->
                if (index > 0) append(',')
                appendJson(inputId)
            }
            append("],")
            append("\"output\":")
            appendArtifactOutput(lifecycle.output)
            append(',')
        }
        append("\"requiredFields\":[")
        request.requiredFields.sorted().forEachIndexed { index, field ->
            if (index > 0) append(',')
            appendJson(field)
        }
        append("],\"contributions\":[")
        contributions.forEachIndexed { index, contribution ->
            if (index > 0) append(',')
            append('{')
            append("\"id\":"); appendJson(contribution.id); append(',')
            append("\"module\":"); appendJson(contribution.module); append(',')
            append("\"field\":"); appendJson(contribution.field); append(',')
            append("\"source\":"); appendJson(contribution.source); append(',')
            append("\"confidence\":"); append(java.lang.Double.toString(contribution.confidence)); append(',')
            append("\"contributedAt\":"); appendJson(contribution.contributedAt.toString()); append(',')
            append("\"provenance\":{")
            append("\"source\":"); appendJson(contribution.provenance.source); append(',')
            append("\"actor\":"); appendJson(contribution.provenance.actor); append(',')
            append("\"createdAt\":"); appendJson(contribution.provenance.createdAt.toString()); append(',')
            append("\"parentIds\":[")
            contribution.provenance.parentIds.map { it.value }.sorted().forEachIndexed { parentIndex, parent ->
                if (parentIndex > 0) append(',')
                appendJson(parent)
            }
            append("]},\"content\":")
            appendJson(contribution.content)
            append('}')
        }
        append("]}")
    }

    private fun StringBuilder.appendArtifactOutput(output: ArtifactOutputDescriptor?) {
        if (output == null) {
            append("null")
            return
        }
        append('{')
        append("\"asset\":{")
        append("\"id\":"); appendJson(output.asset.id.value); append(',')
        append("\"mediaType\":"); appendJson(output.asset.mediaType); append(',')
        append("\"byteCount\":"); append(output.asset.byteCount); append(',')
        append("\"sha256\":"); appendJson(output.asset.sha256)
        append("},\"profile\":")
        appendGenerationProfile(output.profile)
        append(",\"validationEvidence\":[")
        output.validationEvidence.sortedBy { it.id }.forEachIndexed { index, evidence ->
            if (index > 0) append(',')
            append('{')
            append("\"id\":"); appendJson(evidence.id); append(',')
            append("\"validator\":"); appendJson(evidence.validator); append(',')
            append("\"check\":"); appendJson(evidence.check); append(',')
            append("\"passed\":"); append(evidence.passed); append(',')
            append("\"detail\":"); appendJson(evidence.detail); append(',')
            append("\"observedAt\":"); appendJson(evidence.observedAt.toString()); append(',')
            append("\"evidencePhotonIds\":[")
            evidence.evidencePhotonIds.map { it.value }.sorted().forEachIndexed { evidenceIndex, photonId ->
                if (evidenceIndex > 0) append(',')
                appendJson(photonId)
            }
            append("]}")
        }
        append("]}")
    }

    private fun StringBuilder.appendGenerationProfile(profile: ArtifactGenerationProfile) {
        append('{')
        when (profile) {
            is DocumentArtifactProfile -> {
                append("\"type\":\"document\",")
                append("\"format\":"); appendJson(profile.format); append(',')
                append("\"style\":"); appendNullableJson(profile.style)
            }
            is CodeArtifactProfile -> {
                append("\"type\":\"code\",")
                append("\"language\":"); appendJson(profile.language); append(',')
                append("\"entrypoint\":"); appendNullableJson(profile.entrypoint); append(',')
                append("\"files\":[")
                profile.files.sorted().forEachIndexed { index, file ->
                    if (index > 0) append(',')
                    appendJson(file)
                }
                append(']')
            }
            is ImageArtifactProfile -> {
                append("\"type\":\"image\",")
                append("\"width\":"); append(profile.width); append(',')
                append("\"height\":"); append(profile.height); append(',')
                append("\"promptFingerprint\":"); appendJson(profile.promptFingerprint); append(',')
                append("\"model\":"); appendNullableJson(profile.model)
            }
        }
        append('}')
    }

    private fun StringBuilder.appendNullableJson(value: String?) {
        if (value == null) append("null") else appendJson(value)
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
