package app.lifeos.core.runtime.informationasset.project

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.runtime.informationasset.InformationAssetAssembler
import app.lifeos.core.runtime.informationasset.InformationAssetAssemblyRequest
import app.lifeos.core.runtime.informationasset.InformationAssetPhotonContract
import app.lifeos.core.runtime.informationasset.InformationAssetPhotonFactory
import app.lifeos.core.runtime.informationasset.InformationAssetRepository
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.InformationAssetResolutionState
import app.lifeos.core.runtime.informationasset.InformationAssetRevision
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionRef
import app.lifeos.core.runtime.informationasset.InformationAssetSaveResult
import app.lifeos.core.runtime.informationasset.InformationClaim
import app.lifeos.core.runtime.informationasset.InformationClaimId
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.InformationConflict
import app.lifeos.core.runtime.informationasset.InformationConflictId
import app.lifeos.core.runtime.informationasset.InformationConflictResolutionState
import app.lifeos.core.runtime.informationasset.InformationEvidenceBinding
import app.lifeos.core.runtime.informationasset.PhotonRevisionReference
import java.time.Instant

enum class InformationAssetReingressResult {
    CREATED,
    ALREADY_PRESENT,
}

data class CrossSourceConsolidationRequest(
    val assetRequest: InformationAssetRequest,
    val sourcePhotons: List<Photon>,
    val evidenceBindings: List<InformationEvidenceBinding>,
    val claims: List<InformationClaim>,
    val conflicts: List<InformationConflict> = emptyList(),
    val participatingModules: Set<String>,
    val supersedeSemanticKeys: Set<String> = emptySet(),
    val conflictSemanticKeys: Set<String> = emptySet(),
) {
    init {
        require(participatingModules.isNotEmpty()) {
            "Cross-source consolidation requires participating modules"
        }
        require(participatingModules.none { it.isBlank() })
        require(supersedeSemanticKeys.none { it.isBlank() })
        require(conflictSemanticKeys.none { it.isBlank() })
    }
}

data class CrossSourceConsolidationResult(
    val revision: InformationAssetRevision,
    val photon: Photon,
    val saveResult: InformationAssetSaveResult,
    val reingressResult: InformationAssetReingressResult,
    val supersededClaimIds: Set<InformationClaimId>,
    val synthesizedConflictIds: Set<InformationConflictId>,
    val noOp: Boolean,
)

/**
 * M208 canonical cross-source consolidation authority.
 *
 * Existing asset history is the parent authority. Claims are deduplicated by canonical id; old
 * claims remain unless their semantic key is explicitly superseded by an incoming claim. Retained
 * contradictory statements are surfaced as OPEN conflicts instead of selecting a winner.
 *
 * Every new revision is assembled by [InformationAssetAssembler], persisted append-only, then
 * re-enters the Photon graph as its immutable InformationAsset revision Photon.
 */
class CrossSourceConsolidationCoordinator(
    private val assets: InformationAssetRepository,
    private val photons: RevisionedPhotonRepository,
    private val assembler: InformationAssetAssembler = InformationAssetAssembler(),
    private val photonFactory: InformationAssetPhotonFactory = InformationAssetPhotonFactory(),
) {
    suspend fun consolidate(
        request: CrossSourceConsolidationRequest,
    ): CrossSourceConsolidationResult {
        val latest = assets.loadLatest(request.assetRequest.id)
        require(latest.unreadableEntries.isEmpty()) {
            "Cannot consolidate over unreadable or ambiguous InformationAsset history: " +
                latest.unreadableEntries.joinToString(",")
        }

        val parent = latest.revision
        val parentSources = parent?.manifest?.sourcePhotons
            .orEmpty()
            .map { loadExactSource(it) }

        val sourceByRef = linkedMapOf<Pair<String, Long>, Photon>()
        (parentSources + request.sourcePhotons).forEach { photon ->
            val key = photon.id.value to photon.revision
            val existing = sourceByRef[key]
            if (existing != null) {
                require(PhotonRevisionReference.from(existing) == PhotonRevisionReference.from(photon)) {
                    "Cross-source consolidation received conflicting states for the same exact Photon revision"
                }
            } else {
                sourceByRef[key] = photon
            }
        }

        val incomingKeys = request.claims.mapTo(mutableSetOf()) { it.semanticKey }
        val effectiveSupersede = request.supersedeSemanticKeys intersect incomingKeys
        val parentClaims = parent?.claims.orEmpty()
        val superseded = parentClaims
            .filter { it.semanticKey in effectiveSupersede }
            .mapTo(linkedSetOf()) { it.id }

        val claimById = linkedMapOf<String, InformationClaim>()
        parentClaims
            .filterNot { it.id in superseded }
            .forEach { claimById[it.id.value] = it }
        request.claims.forEach { claimById[it.id.value] = it }
        val finalClaims = claimById.values.sortedBy { it.id.value }
        require(finalClaims.isNotEmpty()) {
            "Cross-source consolidation cannot produce an empty InformationAsset"
        }

        val finalClaimIds = finalClaims.mapTo(mutableSetOf()) { it.id }
        finalClaims.forEach { claim ->
            require(claim.derivedFromClaimIds.all(finalClaimIds::contains)) {
                "Claim supersession would orphan derived claim lineage"
            }
        }

        val evidenceById = linkedMapOf<String, InformationEvidenceBinding>()
        parent?.evidenceBindings.orEmpty().forEach { evidenceById[it.id.value] = it }
        request.evidenceBindings.forEach { evidenceById[it.id.value] = it }
        val referencedEvidenceIds = finalClaims.flatMapTo(linkedSetOf()) { it.evidenceBindingIds }
        val finalEvidence = referencedEvidenceIds
            .map { id ->
                requireNotNull(evidenceById[id.value]) {
                    "Consolidated claim references unavailable evidence binding: " + id.value
                }
            }
            .sortedBy { it.id.value }

        val requiredSourceRefs = finalEvidence.mapTo(linkedSetOf()) { it.source }
        val finalSources = requiredSourceRefs
            .map { required ->
                val photon = requireNotNull(sourceByRef[required.photonId.value to required.revision]) {
                    "Consolidated evidence references unavailable exact source Photon"
                }
                require(PhotonRevisionReference.from(photon) == required) {
                    "Consolidated evidence/source state fingerprint mismatch"
                }
                photon
            }
            .sortedWith(compareBy<Photon> { it.id.value }.thenBy { it.revision })
        finalSources.forEach { requireDurableExactSource(it) }

        val retainedConflicts = linkedMapOf<String, InformationConflict>()
        (parent?.conflicts.orEmpty() + request.conflicts)
            .filter { conflict -> conflict.claimIds.all(finalClaimIds::contains) }
            .forEach { conflict -> retainedConflicts[conflict.id.value] = conflict }

        val synthesized = synthesizeConflicts(
            claims = finalClaims,
            semanticKeys = request.conflictSemanticKeys,
        )
        synthesized.forEach { conflict -> retainedConflicts[conflict.id.value] = conflict }
        val finalConflicts = retainedConflicts.values.sortedBy { it.id.value }

        val modules = buildSet {
            addAll(parent?.manifest?.participatingModules.orEmpty())
            addAll(request.participatingModules)
        }

        val parentPhoton = parent?.let { ensureAssetPhoton(it).first }
        val parentRef = parent?.let { revision ->
            InformationAssetRevisionRef(
                assetId = revision.request.id,
                revisionId = revision.manifest.id,
                photonId = requireNotNull(parentPhoton).id,
            )
        }

        if (
            parent != null &&
            sameCanonicalState(
                parent = parent,
                request = request.assetRequest,
                sources = finalSources,
                evidence = finalEvidence,
                claims = finalClaims,
                conflicts = finalConflicts,
                modules = modules,
            )
        ) {
            return CrossSourceConsolidationResult(
                revision = parent,
                photon = requireNotNull(parentPhoton),
                saveResult = InformationAssetSaveResult.ALREADY_PRESENT,
                reingressResult = InformationAssetReingressResult.ALREADY_PRESENT,
                supersededClaimIds = superseded,
                synthesizedConflictIds = synthesized.mapTo(linkedSetOf()) { it.id },
                noOp = true,
            )
        }

        val revision = assembler.assemble(
            InformationAssetAssemblyRequest(
                request = request.assetRequest,
                sourcePhotons = finalSources,
                evidenceBindings = finalEvidence,
                claims = finalClaims,
                conflicts = finalConflicts,
                participatingModules = modules,
                parent = parentRef,
            )
        ).revision

        val saveResult = assets.save(revision)
        val reingress = ensureAssetPhoton(revision)

        return CrossSourceConsolidationResult(
            revision = revision,
            photon = reingress.first,
            saveResult = saveResult,
            reingressResult = reingress.second,
            supersededClaimIds = superseded,
            synthesizedConflictIds = synthesized.mapTo(linkedSetOf()) { it.id },
            noOp = false,
        )
    }

    private suspend fun loadExactSource(reference: PhotonRevisionReference): Photon {
        val photon = requireNotNull(
            photons.load(PhotonRevisionRef(reference.photonId, reference.revision))
        ) {
            "Parent InformationAsset references a missing exact source Photon"
        }
        require(PhotonRevisionReference.from(photon) == reference) {
            "Parent InformationAsset exact source state no longer matches its manifest"
        }
        return photon
    }

    private suspend fun requireDurableExactSource(photon: Photon) {
        val ref = PhotonRevisionRef(photon.id, photon.revision)
        val durable = requireNotNull(photons.load(ref)) {
            "Cross-source consolidation requires every exact source revision to be durable"
        }
        require(PhotonRevisionReference.from(durable) == PhotonRevisionReference.from(photon)) {
            "Durable source revision differs from the consolidation input state"
        }
    }

    private fun synthesizeConflicts(
        claims: List<InformationClaim>,
        semanticKeys: Set<String>,
    ): List<InformationConflict> = semanticKeys
        .sorted()
        .flatMap { key ->
            claims
                .filter {
                    it.semanticKey == key &&
                        it.state != InformationClaimState.REJECTED
                }
                .groupBy { it.domainId }
                .entries
                .sortedBy { it.key.value }
                .mapNotNull { (domainId, domainClaims) ->
                    val statements = domainClaims
                        .map { it.statement.trim() }
                        .distinct()
                    if (statements.size < 2) return@mapNotNull null
                    InformationConflict.create(
                        domainId = domainId,
                        claimIds = domainClaims.mapTo(linkedSetOf()) { it.id },
                        severity = 1.0,
                        state = InformationConflictResolutionState.OPEN,
                        explanation = "Cross-source claims disagree for semantic key $key",
                    )
                }
        }

    private fun sameCanonicalState(
        parent: InformationAssetRevision,
        request: InformationAssetRequest,
        sources: List<Photon>,
        evidence: List<InformationEvidenceBinding>,
        claims: List<InformationClaim>,
        conflicts: List<InformationConflict>,
        modules: Set<String>,
    ): Boolean {
        val resolution = when {
            conflicts.any { it.state == InformationConflictResolutionState.OPEN } ->
                InformationAssetResolutionState.UNRESOLVED
            claims.any { it.state == InformationClaimState.UNRESOLVED } ->
                InformationAssetResolutionState.UNRESOLVED
            else -> InformationAssetResolutionState.CONVERGED
        }
        val sourceRefs = sources
            .map { PhotonRevisionReference.from(it) }
            .sortedWith(
                compareBy<PhotonRevisionReference> { it.photonId.value }
                    .thenBy { it.revision }
            )
        return parent.request == request &&
            parent.manifest.sourcePhotons == sourceRefs &&
            parent.evidenceBindings == evidence.sortedBy { it.id.value } &&
            parent.claims == claims.sortedBy { it.id.value } &&
            parent.conflicts == conflicts.sortedBy { it.id.value } &&
            parent.manifest.participatingModules == modules.toSortedSet() &&
            parent.manifest.resolution == resolution
    }

    private suspend fun ensureAssetPhoton(
        revision: InformationAssetRevision,
    ): Pair<Photon, InformationAssetReingressResult> {
        val generated = photonFactory.create(
            revision = revision,
            createdAt = deterministicCreatedAt(revision),
        )
        val existing = photons.load(generated.id)
        if (existing != null) {
            requireCompatibleAssetPhoton(existing, generated)
            return existing to InformationAssetReingressResult.ALREADY_PRESENT
        }

        return when (val result = photons.saveRevision(generated, expectedPreviousRevision = null)) {
            is PhotonRevisionWriteResult.Created ->
                result.photon to InformationAssetReingressResult.CREATED
            is PhotonRevisionWriteResult.Idempotent -> {
                requireCompatibleAssetPhoton(result.photon, generated)
                result.photon to InformationAssetReingressResult.ALREADY_PRESENT
            }
            is PhotonRevisionWriteResult.Advanced ->
                error("Immutable InformationAsset Photon unexpectedly advanced")
            is PhotonRevisionWriteResult.Conflict ->
                error("InformationAsset Photon reingress conflict: " + result.reason)
        }
    }

    private fun requireCompatibleAssetPhoton(existing: Photon, generated: Photon) {
        require(existing.id == generated.id)
        require(existing.revision == 1L)
        require(existing.mimeType == InformationAssetPhotonContract.MIME_TYPE)
        require(existing.content == generated.content) {
            "InformationAsset Photon id collides with different revision content"
        }
        require(existing.relations == generated.relations)
        require(existing.tags == generated.tags)
    }

    private fun deterministicCreatedAt(revision: InformationAssetRevision): Instant =
        revision.evidenceBindings.maxOfOrNull { it.observedAt }
            ?: Instant.EPOCH
}
