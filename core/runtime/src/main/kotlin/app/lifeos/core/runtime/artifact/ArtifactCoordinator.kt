package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.SemanticArtifactPlan
import app.lifeos.core.runtime.cognition.CognitivePriority
import app.lifeos.core.runtime.cognition.CognitiveWorkBudget
import app.lifeos.core.runtime.cognition.ContinuousCognitionEngine
import app.lifeos.core.runtime.cognition.PhotonDelta
import app.lifeos.core.runtime.cognition.PhotonDeltaType
import app.lifeos.core.runtime.cognition.SalienceVector
import app.lifeos.core.runtime.trace.LifecycleDecisionTraceRecorder
import app.lifeos.core.runtime.trace.LifecycleDecisionTraceRuntimeRegistry
import java.time.Instant
import kotlinx.coroutines.CancellationException

object ArtifactCoordinatorContract {
    const val ENVELOPE_MIME_TYPE = "application/vnd.lifeos.collaborative-artifact+json"
    const val PROVENANCE_SOURCE = "lifeos.collaborative-artifact"
    const val PROVENANCE_ACTOR = "lifeos-runtime"
    const val SCHEMA = "lifeos.collaborative-artifact.v2"
    const val LEGACY_SCHEMA = "lifeos.collaborative-artifact.v1"
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
    private val lifecycleTraceRecorder: LifecycleDecisionTraceRecorder? =
        LifecycleDecisionTraceRuntimeRegistry.currentOrNull(),
) {
    suspend fun finalize(
        request: CollaborativeArtifactRequest,
        contributions: List<ArtifactContribution>,
        finalizedAt: Instant,
        parentRevision: ArtifactRevisionRef? = null,
        materializedAsset: AssetRef? = null,
        semanticPlan: SemanticArtifactPlan? = null,
    ): ArtifactFinalizationResult {
        val validation = validator.requireValid(request, contributions, finalizedAt)
        semanticPlan?.let { requireSemanticPlan(contributions, it) }
        materializedAsset?.let { asset ->
            require(asset.mediaType == request.targetMimeType) {
                "Materialized asset MIME type ${asset.mediaType} does not match requested ${request.targetMimeType}"
            }
        }
        val canonicalContributions = contributions.sortedWith(
            compareBy<ArtifactContribution>(
                { it.field },
                { it.module },
                { it.source },
                { it.id },
            )
        )
        if (parentRevision != null) {
            requireParentRevision(request, parentRevision)
        }
        val revision = revisionManifest(
            request = request,
            contributions = canonicalContributions,
            parentRevision = parentRevision,
            materializedAsset = materializedAsset,
            validation = validation,
            semanticPlan = semanticPlan,
        )
        val photonId = artifactPhotonId(request, revision)
        val existing = photons.load(photonId)
        val photon: Photon
        val effectiveFinalizedAt: Instant
        if (existing == null) {
            photon = createPhoton(
                photonId = photonId,
                request = request,
                contributions = canonicalContributions,
                revision = revision,
                finalizedAt = finalizedAt,
            )
            effectiveFinalizedAt = finalizedAt
        } else {
            requireOwnedArtifact(existing, request.id, revision)
            photon = existing
            effectiveFinalizedAt = existing.provenance.createdAt
        }

        val receipt = ingress.ingest(photon)
        val result = ArtifactFinalizationResult(
            artifact = CollaborativeArtifact(
                request = request,
                contributions = canonicalContributions,
                photon = photon,
                finalizedAt = effectiveFinalizedAt,
                revision = revision,
            ),
            reentry = receipt,
        )
        traceIfDurablyPublished(result)
        return result
    }

    /**
     * Staged owner-review artifacts deliberately remain outside [PhotonRepository]. V15 tracing is
     * therefore emitted only when canonical ingress has made the exact finalized Photon durable.
     */
    private suspend fun traceIfDurablyPublished(result: ArtifactFinalizationResult) {
        val artifactPhoton = result.artifact.photon
        val durable = try {
            photons.load(artifactPhoton.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (durable == artifactPhoton) {
            lifecycleTraceRecorder?.recordArtifact(result)
        }
    }

    private fun requireSemanticPlan(
        contributions: List<ArtifactContribution>,
        plan: SemanticArtifactPlan,
    ) {
        val claims = plan.claims.associateBy { it.claimId }
        contributions.forEach { contribution ->
            require(contribution.claimIds.size == 1) {
                "Semantic artifact contribution ${contribution.id} must map to exactly one claim"
            }
            val claimId = contribution.claimIds.single()
            val claim = requireNotNull(claims[claimId]) {
                "Artifact contribution references unknown semantic claim: $claimId"
            }
            require(claimId !in plan.unresolvedClaimIds) {
                "Unresolved semantic claim cannot be rendered as a secured artifact assertion: $claimId"
            }
            require(claim.evidence.isNotEmpty()) {
                "Semantic artifact claim has no revision evidence: $claimId"
            }
            require(contribution.content == claim.canonicalContent) {
                "Artifact contribution changed canonical semantic claim content: $claimId"
            }
        }
        val renderedClaimIds = contributions.map { it.claimIds.single() }.toSet()
        require(renderedClaimIds.isNotEmpty()) { "Semantic artifact must render at least one claim" }
        require(renderedClaimIds.none { it in plan.unresolvedClaimIds })
    }

    private suspend fun requireParentRevision(
        request: CollaborativeArtifactRequest,
        parentRevision: ArtifactRevisionRef,
    ) {
        require(parentRevision.artifactId == request.id) {
            "Artifact revision parent belongs to ${parentRevision.artifactId.value}, not ${request.id.value}"
        }
        val parentPhoton = requireNotNull(photons.load(parentRevision.photonId)) {
            "Artifact parent Photon ${parentRevision.photonId.value} is missing"
        }
        requireOwnedArtifact(parentPhoton)
        require("artifact-id:${request.id.value}" in parentPhoton.tags) {
            "Artifact parent Photon belongs to a different logical artifact"
        }
        require("artifact-revision:${parentRevision.revisionId.value}" in parentPhoton.tags) {
            "Artifact parent revision id does not match its Photon"
        }
    }

    private fun revisionManifest(
        request: CollaborativeArtifactRequest,
        contributions: List<ArtifactContribution>,
        parentRevision: ArtifactRevisionRef?,
        materializedAsset: AssetRef?,
        validation: ArtifactValidationEvidence,
        semanticPlan: SemanticArtifactPlan?,
    ): ArtifactRevisionManifest {
        val inputPhotonIds = contributions
            .flatMap { it.provenance.parentIds }
            .toSortedSet(compareBy { it.value })
        val participatingModules = contributions
            .map { it.module }
            .toSortedSet()
        val stateParts = buildList {
            add("artifact-revision-state/v1")
            add(request.id.value)
            add(request.kind.name)
            add(request.title)
            add(request.targetMimeType)
            add(request.requestedAt.toString())
            add(validation.profile.minimumDistinctModules.toString())
            request.requiredFields.sorted().forEach(::add)
            contributions.map { it.contentFingerprint() }.forEach(::add)
            semanticPlan?.let { plan ->
                add(plan.fingerprint)
                add(plan.planId)
                add(plan.planRevision.toString())
                add(plan.sourceWorldRevision.toString())
            }
            materializedAsset?.let { asset ->
                add(asset.id.value)
                add(asset.mediaType)
                add(asset.byteCount.toString())
                add(asset.sha256)
            }
        }
        val stateHash = ArtifactFingerprints.fingerprint(*stateParts.toTypedArray())
        val revisionParts = buildList {
            add("artifact-revision-id/v1")
            add(request.id.value)
            add(stateHash)
            parentRevision?.let { parent ->
                add(parent.revisionId.value)
                add(parent.photonId.value)
            }
        }
        return ArtifactRevisionManifest(
            id = ArtifactRevisionId(
                ArtifactFingerprints.fingerprint(*revisionParts.toTypedArray())
            ),
            parent = parentRevision,
            inputPhotonIds = inputPhotonIds,
            participatingModules = participatingModules,
            stateHash = stateHash,
            materializedAsset = materializedAsset,
            validation = validation,
            semanticPlanFingerprint = semanticPlan?.fingerprint,
        )
    }

    private fun artifactPhotonId(
        request: CollaborativeArtifactRequest,
        revision: ArtifactRevisionManifest,
    ): PhotonId {
        val identity = ArtifactFingerprints.fingerprint(
            "collaborative-artifact-photon/v2",
            request.id.value,
            revision.id.value,
        )
        // EncryptedPhotonStore only accepts [A-Za-z0-9_-]{1,128}; keep lifecycle ids vault-safe.
        return PhotonId("artifact_$identity")
    }

    private fun createPhoton(
        photonId: PhotonId,
        request: CollaborativeArtifactRequest,
        contributions: List<ArtifactContribution>,
        revision: ArtifactRevisionManifest,
        finalizedAt: Instant,
    ): Photon {
        val parentIds = buildSet {
            addAll(revision.inputPhotonIds)
            revision.parent?.let { add(it.photonId) }
        }
        val confidence = contributions.minOf { it.confidence }
        val content = envelopeJson(request, contributions, revision, finalizedAt)
        val relations = linkedSetOf<PhotonRelation>()
        revision.inputPhotonIds
            .sortedBy { it.value }
            .forEach { parentId ->
                relations += PhotonRelation(
                    target = parentId,
                    type = RelationType.DERIVED_FROM,
                )
            }
        revision.parent?.let { parent ->
            relations += PhotonRelation(
                target = parent.photonId,
                type = RelationType.TRANSFORMS,
            )
        }
        return Photon(
            id = photonId,
            revision = 1L,
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
                parentIds = parentIds,
            ),
            relations = relations,
            tags = buildSet {
                add("artifact")
                add("artifact-kind:${request.kind.name.lowercase()}")
                add("artifact-id:${request.id.value}")
                add("artifact-revision:${revision.id.value}")
                add("artifact-state-hash:${revision.stateHash}")
                revision.parent?.let { parent ->
                    add("artifact-parent-revision:${parent.revisionId.value}")
                }
                revision.materializedAsset?.let { asset ->
                    add("artifact-output-sha256:${asset.sha256}")
                }
                revision.semanticPlanFingerprint?.let { fingerprint ->
                    add("artifact-semantic-plan:$fingerprint")
                }
                revision.participatingModules.forEach { module ->
                    add("artifact-module:$module")
                }
                contributions.map { it.field }.toSortedSet().forEach { field ->
                    add("artifact-field:$field")
                }
            },
        )
    }

    private fun requireOwnedArtifact(photon: Photon) {
        require(photon.mimeType == ArtifactCoordinatorContract.ENVELOPE_MIME_TYPE) {
            "Artifact Photon id collision for ${photon.id.value}: unexpected MIME type"
        }
        require(photon.provenance.source == ArtifactCoordinatorContract.PROVENANCE_SOURCE) {
            "Artifact Photon id collision for ${photon.id.value}: unexpected provenance source"
        }
        require(photon.revision == 1L) {
            "Artifact revision Photon ${photon.id.value} has unsupported Photon revision ${photon.revision}"
        }
    }

    private fun requireOwnedArtifact(
        photon: Photon,
        artifactId: ArtifactId,
        revision: ArtifactRevisionManifest,
    ) {
        requireOwnedArtifact(photon)
        require("artifact-id:${artifactId.value}" in photon.tags) {
            "Artifact Photon id collision for ${photon.id.value}: logical artifact mismatch"
        }
        require("artifact-revision:${revision.id.value}" in photon.tags) {
            "Artifact Photon id collision for ${photon.id.value}: revision mismatch"
        }
        require("artifact-state-hash:${revision.stateHash}" in photon.tags) {
            "Artifact Photon id collision for ${photon.id.value}: state hash mismatch"
        }
    }

    private fun envelopeJson(
        request: CollaborativeArtifactRequest,
        contributions: List<ArtifactContribution>,
        revision: ArtifactRevisionManifest,
        finalizedAt: Instant,
    ): String = buildString {
        append('{')
        append("\"schema\":"); appendJson(ArtifactCoordinatorContract.SCHEMA); append(',')
        append("\"artifactId\":"); appendJson(request.id.value); append(',')
        append("\"revisionId\":"); appendJson(revision.id.value); append(',')
        append("\"stateHash\":"); appendJson(revision.stateHash); append(',')
        append("\"parentRevision\":")
        revision.parent?.let { parent ->
            append('{')
            append("\"artifactId\":"); appendJson(parent.artifactId.value); append(',')
            append("\"revisionId\":"); appendJson(parent.revisionId.value); append(',')
            append("\"photonId\":"); appendJson(parent.photonId.value)
            append('}')
        } ?: append("null")
        append(',')
        append("\"kind\":"); appendJson(request.kind.name); append(',')
        append("\"title\":"); appendJson(request.title); append(',')
        append("\"targetMimeType\":"); appendJson(request.targetMimeType); append(',')
        append("\"requestedAt\":"); appendJson(request.requestedAt.toString()); append(',')
        append("\"finalizedAt\":"); appendJson(finalizedAt.toString()); append(',')
        append("\"requiredFields\":[")
        request.requiredFields.sorted().forEachIndexed { index, field ->
            if (index > 0) append(',')
            appendJson(field)
        }
        append("],\"inputPhotonIds\":[")
        revision.inputPhotonIds.map { it.value }.sorted().forEachIndexed { index, inputId ->
            if (index > 0) append(',')
            appendJson(inputId)
        }
        append("],\"participatingModules\":[")
        revision.participatingModules.sorted().forEachIndexed { index, module ->
            if (index > 0) append(',')
            appendJson(module)
        }
        append("],\"materializedAsset\":")
        revision.materializedAsset?.let { asset ->
            append('{')
            append("\"id\":"); appendJson(asset.id.value); append(',')
            append("\"mediaType\":"); appendJson(asset.mediaType); append(',')
            append("\"byteCount\":"); append(asset.byteCount); append(',')
            append("\"sha256\":"); appendJson(asset.sha256)
            append('}')
        } ?: append("null")
        append(",\"semanticPlanFingerprint\":")
        revision.semanticPlanFingerprint?.let { fingerprint -> appendJson(fingerprint) } ?: append("null")
        append(",\"validation\":{")
        append("\"minimumDistinctModules\":")
        append(revision.validation.profile.minimumDistinctModules)
        append(",\"valid\":true}")
        append(",\"contributions\":[")
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

enum class LivingArtifactRefreshState {
    STABLE,
    UPDATE_CANDIDATE,
    BLOCKED,
}

data class LivingArtifactRefreshCandidate(
    val parentRevision: ArtifactRevisionRef,
    val currentSemanticPlanFingerprint: String,
    val nextSemanticPlanFingerprint: String,
    val currentWorldRevision: Long,
    val nextWorldRevision: Long,
    val mediaType: String,
    val currentAssetSha256: String,
    val nextAssetSha256: String,
    val addedClaimIds: List<String>,
    val removedClaimIds: List<String>,
    val changedClaimIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(currentSemanticPlanFingerprint.matches(SHA_256_B448))
        require(nextSemanticPlanFingerprint.matches(SHA_256_B448))
        require(currentWorldRevision >= 0L)
        require(nextWorldRevision > currentWorldRevision)
        require(mediaType.isNotBlank())
        require(currentAssetSha256.matches(SHA_256_B448))
        require(nextAssetSha256.matches(SHA_256_B448))
        require(addedClaimIds == addedClaimIds.distinct().sorted())
        require(removedClaimIds == removedClaimIds.distinct().sorted())
        require(changedClaimIds == changedClaimIds.distinct().sorted())
        require(addedClaimIds.isNotEmpty() || removedClaimIds.isNotEmpty() || changedClaimIds.isNotEmpty())
        require(
            fingerprint == livingArtifactCandidateFingerprint(
                parentRevision,
                currentSemanticPlanFingerprint,
                nextSemanticPlanFingerprint,
                currentWorldRevision,
                nextWorldRevision,
                mediaType,
                currentAssetSha256,
                nextAssetSha256,
                addedClaimIds,
                removedClaimIds,
                changedClaimIds,
            )
        )
    }

    val finalizationAuthority: Boolean get() = false
    val publicationAuthority: Boolean get() = false
    val overwriteAuthority: Boolean get() = false
    val factualAuthority: Boolean get() = false
    val ownerPolicyAuthority: Boolean get() = false
}

data class LivingArtifactRefreshReport(
    val state: LivingArtifactRefreshState,
    val reasonCode: String,
    val candidate: LivingArtifactRefreshCandidate?,
    val fingerprint: String,
) {
    init {
        require(reasonCode.isNotBlank())
        require((state == LivingArtifactRefreshState.UPDATE_CANDIDATE) == (candidate != null))
        require(
            fingerprint == livingArtifactReportFingerprint(
                state,
                reasonCode,
                candidate?.fingerprint,
            )
        )
    }

    val automaticFinalizationAllowed: Boolean get() = false
    val automaticPublicationAllowed: Boolean get() = false
}

/**
 * B448 turns exact knowledge changes into bounded refresh candidates for already materialized
 * artifacts.
 *
 * The runtime does not rewrite, overwrite, finalize or publish an artifact. It only proves whether
 * a newer closed semantic plan contains an exact relevant change and, when it does, emits a child
 * revision candidate bound to the current artifact revision. Productive callers must still render
 * the next asset, re-run the existing factual/quality gates where applicable, and finalize through
 * [ArtifactCoordinator] with [LivingArtifactRefreshCandidate.parentRevision].
 */
class LivingArtifactRuntime {
    fun evaluate(
        currentRevision: ArtifactRevisionRef,
        currentManifest: ArtifactRevisionManifest,
        currentPlan: SemanticArtifactPlan,
        nextPlan: SemanticArtifactPlan,
        nextAsset: AssetRef,
    ): LivingArtifactRefreshReport {
        if (currentManifest.id != currentRevision.revisionId) {
            return report(
                LivingArtifactRefreshState.BLOCKED,
                "current-revision-manifest-mismatch",
            )
        }
        if (currentManifest.semanticPlanFingerprint != currentPlan.fingerprint) {
            return report(
                LivingArtifactRefreshState.BLOCKED,
                "current-semantic-plan-lineage-mismatch",
            )
        }
        val currentAsset = currentManifest.materializedAsset
            ?: return report(
                LivingArtifactRefreshState.BLOCKED,
                "current-artifact-not-materialized",
            )
        if (currentAsset.mediaType != nextAsset.mediaType) {
            return report(
                LivingArtifactRefreshState.BLOCKED,
                "living-refresh-media-type-change",
            )
        }
        if (currentPlan.kind != nextPlan.kind) {
            return report(
                LivingArtifactRefreshState.BLOCKED,
                "living-refresh-semantic-kind-change",
            )
        }
        if (currentPlan.unresolvedClaimIds.isNotEmpty()) {
            return report(
                LivingArtifactRefreshState.BLOCKED,
                "current-plan-not-closed",
            )
        }
        if (nextPlan.unresolvedClaimIds.isNotEmpty()) {
            return report(
                LivingArtifactRefreshState.BLOCKED,
                "next-plan-not-closed",
            )
        }
        if (nextPlan.sourceWorldRevision < currentPlan.sourceWorldRevision) {
            return report(
                LivingArtifactRefreshState.BLOCKED,
                "world-revision-regression",
            )
        }

        val currentClaims = currentPlan.resolvedClaims().associateBy { it.claimId }
        val nextClaims = nextPlan.resolvedClaims().associateBy { it.claimId }
        val added = (nextClaims.keys - currentClaims.keys).sorted()
        val removed = (currentClaims.keys - nextClaims.keys).sorted()
        val changed = (currentClaims.keys intersect nextClaims.keys)
            .filter { claimId ->
                currentClaims.getValue(claimId).fingerprint !=
                    nextClaims.getValue(claimId).fingerprint
            }
            .sorted()
        val semanticChanged = added.isNotEmpty() || removed.isNotEmpty() || changed.isNotEmpty()

        if (nextPlan.sourceWorldRevision == currentPlan.sourceWorldRevision) {
            return if (
                !semanticChanged &&
                currentPlan.fingerprint == nextPlan.fingerprint &&
                currentAsset.sha256 == nextAsset.sha256
            ) {
                report(
                    LivingArtifactRefreshState.STABLE,
                    "exact-artifact-unchanged",
                )
            } else {
                report(
                    LivingArtifactRefreshState.BLOCKED,
                    "same-world-revision-mutation",
                )
            }
        }

        if (!semanticChanged) {
            return if (currentAsset.sha256 == nextAsset.sha256) {
                report(
                    LivingArtifactRefreshState.STABLE,
                    "world-advanced-no-relevant-semantic-change",
                )
            } else {
                report(
                    LivingArtifactRefreshState.BLOCKED,
                    "render-drift-without-semantic-change",
                )
            }
        }

        val candidate = LivingArtifactRefreshCandidate(
            parentRevision = currentRevision,
            currentSemanticPlanFingerprint = currentPlan.fingerprint,
            nextSemanticPlanFingerprint = nextPlan.fingerprint,
            currentWorldRevision = currentPlan.sourceWorldRevision,
            nextWorldRevision = nextPlan.sourceWorldRevision,
            mediaType = nextAsset.mediaType,
            currentAssetSha256 = currentAsset.sha256,
            nextAssetSha256 = nextAsset.sha256,
            addedClaimIds = added,
            removedClaimIds = removed,
            changedClaimIds = changed,
            fingerprint = livingArtifactCandidateFingerprint(
                currentRevision,
                currentPlan.fingerprint,
                nextPlan.fingerprint,
                currentPlan.sourceWorldRevision,
                nextPlan.sourceWorldRevision,
                nextAsset.mediaType,
                currentAsset.sha256,
                nextAsset.sha256,
                added,
                removed,
                changed,
            ),
        )
        return report(
            LivingArtifactRefreshState.UPDATE_CANDIDATE,
            "knowledge-change-refresh-candidate",
            candidate,
        )
    }

    private fun report(
        state: LivingArtifactRefreshState,
        reasonCode: String,
        candidate: LivingArtifactRefreshCandidate? = null,
    ): LivingArtifactRefreshReport =
        LivingArtifactRefreshReport(
            state = state,
            reasonCode = reasonCode,
            candidate = candidate,
            fingerprint = livingArtifactReportFingerprint(
                state,
                reasonCode,
                candidate?.fingerprint,
            ),
        )
}

private fun livingArtifactCandidateFingerprint(
    parentRevision: ArtifactRevisionRef,
    currentSemanticPlanFingerprint: String,
    nextSemanticPlanFingerprint: String,
    currentWorldRevision: Long,
    nextWorldRevision: Long,
    mediaType: String,
    currentAssetSha256: String,
    nextAssetSha256: String,
    addedClaimIds: List<String>,
    removedClaimIds: List<String>,
    changedClaimIds: List<String>,
): String = ArtifactFingerprints.fingerprint(
    "living-artifact-refresh-candidate/v1",
    parentRevision.artifactId.value,
    parentRevision.revisionId.value,
    parentRevision.photonId.value,
    currentSemanticPlanFingerprint,
    nextSemanticPlanFingerprint,
    currentWorldRevision.toString(),
    nextWorldRevision.toString(),
    mediaType,
    currentAssetSha256,
    nextAssetSha256,
    addedClaimIds.joinToString("\u001f"),
    removedClaimIds.joinToString("\u001f"),
    changedClaimIds.joinToString("\u001f"),
)

private fun livingArtifactReportFingerprint(
    state: LivingArtifactRefreshState,
    reasonCode: String,
    candidateFingerprint: String?,
): String = ArtifactFingerprints.fingerprint(
    "living-artifact-refresh-report/v1",
    state.name,
    reasonCode,
    candidateFingerprint.orEmpty(),
)

private val SHA_256_B448 = Regex("[0-9a-f]{64}")

