package app.lifeos.core.runtime.informationasset

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.model.Photon

data class InformationAssetAssemblyRequest(
    val request: InformationAssetRequest,
    val sourcePhotons: List<Photon>,
    val evidenceBindings: List<InformationEvidenceBinding>,
    val claims: List<InformationClaim>,
    val conflicts: List<InformationConflict> = emptyList(),
    val participatingModules: Set<String>,
    val parent: InformationAssetRevisionRef? = null,
) {
    init {
        require(claims.isNotEmpty()) { "Information asset assembly requires claims" }
        require(participatingModules.isNotEmpty()) { "Information asset assembly requires participating modules" }
        require(participatingModules.none { it.isBlank() }) { "Information asset module ids must not be blank" }
    }
}

data class InformationAssetAssemblyResult(
    val asset: InformationAsset,
    val revision: InformationAssetRevision,
)

/**
 * Deterministically assembles a semantic information bundle without mutating any source Photon.
 * Exact Photon identity, revision and canonical state hashes remain part of the revision identity.
 */
class InformationAssetAssembler {
    fun assemble(input: InformationAssetAssemblyRequest): InformationAssetAssemblyResult {
        validate(input)

        val sourceReferences = input.sourcePhotons
            .map { PhotonRevisionReference.from(it) }
            .sortedWith(compareBy<PhotonRevisionReference> { it.photonId.value }.thenBy { it.revision })
        val evidence = input.evidenceBindings.sortedBy { it.id.value }
        val claims = input.claims.sortedBy { it.id.value }
        val conflicts = input.conflicts.sortedBy { it.id.value }
        val domains = buildSet<FieldDomainId> {
            add(input.request.primaryDomainId)
            evidence.forEach { add(it.domainId) }
            claims.forEach { add(it.domainId) }
            conflicts.forEach { add(it.domainId) }
        }
        val resolution = when {
            conflicts.any { it.state == InformationConflictResolutionState.OPEN } ->
                InformationAssetResolutionState.UNRESOLVED
            claims.any { it.state == InformationClaimState.UNRESOLVED } ->
                InformationAssetResolutionState.UNRESOLVED
            else -> InformationAssetResolutionState.CONVERGED
        }
        val stateHash = InformationAssetRevisionIntegrity.stateHash(
            request = input.request,
            sources = sourceReferences,
            evidenceBindings = evidence,
            claims = claims,
            conflicts = conflicts,
            domainIds = domains,
            participatingModules = input.participatingModules,
            resolution = resolution,
        )
        val revisionId = InformationAssetRevisionIntegrity.revisionId(
            request = input.request,
            stateHash = stateHash,
            parent = input.parent,
        )
        val manifest = InformationAssetRevisionManifest(
            id = revisionId,
            parent = input.parent,
            sourcePhotons = sourceReferences,
            domainIds = domains,
            participatingModules = input.participatingModules.toSortedSet(),
            stateHash = stateHash,
            resolution = resolution,
        )
        val revision = InformationAssetRevision(
            request = input.request,
            evidenceBindings = evidence,
            claims = claims,
            conflicts = conflicts,
            manifest = manifest,
        )
        InformationAssetRevisionIntegrity.requireValid(revision)
        return InformationAssetAssemblyResult(
            asset = InformationAsset(input.request.id, revision),
            revision = revision,
        )
    }

    private fun validate(input: InformationAssetAssemblyRequest) {
        val exactSources = input.sourcePhotons.map { PhotonRevisionReference.from(it) }
        require(exactSources.map { it.photonId to it.revision }.distinct().size == exactSources.size) {
            "Information asset cannot contain two states for the same Photon id and revision"
        }
        val exactSourceFingerprints = exactSources.associateBy { it.fingerprint() }
        input.evidenceBindings.forEach { binding ->
            require(binding.source.fingerprint() in exactSourceFingerprints) {
                "Information evidence binding references a Photon state not supplied to assembly"
            }
        }

        require(input.evidenceBindings.map { it.id }.distinct().size == input.evidenceBindings.size) {
            "Information asset evidence binding ids must be unique"
        }
        val bindingIds = input.evidenceBindings.mapTo(mutableSetOf()) { it.id }
        require(input.claims.map { it.id }.distinct().size == input.claims.size) {
            "Information asset claim ids must be unique"
        }
        val claimIds = input.claims.mapTo(mutableSetOf()) { it.id }
        input.claims.forEach { claim ->
            require(claim.evidenceBindingIds.all(bindingIds::contains)) {
                "Information claim references unknown evidence binding"
            }
            require(claim.derivedFromClaimIds.all(claimIds::contains)) {
                "Information claim references unknown parent claim"
            }
            if (claim.state != InformationClaimState.ASSUMPTION) {
                require(claim.evidenceBindingIds.isNotEmpty()) {
                    "Only explicit assumptions may exist without evidence"
                }
            }
        }

        require(input.conflicts.map { it.id }.distinct().size == input.conflicts.size) {
            "Information asset conflict ids must be unique"
        }
        input.conflicts.forEach { conflict ->
            require(conflict.claimIds.all(claimIds::contains)) {
                "Information conflict references unknown claim"
            }
        }

        val presentSemanticKeys = input.claims
            .asSequence()
            .filter { it.state != InformationClaimState.REJECTED }
            .map { it.semanticKey }
            .toSet()
        val missingRequired = input.request.requiredSemanticKeys - presentSemanticKeys
        require(missingRequired.isEmpty()) {
            "Information asset missing required semantic keys: ${missingRequired.sorted().joinToString(",")}" 
        }

        input.parent?.let { parent ->
            require(parent.assetId == input.request.id) {
                "Information asset parent revision belongs to a different logical asset"
            }
        }
    }
}
