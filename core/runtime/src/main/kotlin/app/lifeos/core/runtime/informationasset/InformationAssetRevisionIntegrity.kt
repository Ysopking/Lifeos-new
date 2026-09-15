package app.lifeos.core.runtime.informationasset

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.model.CognitiveStateHash

/** Single deterministic authority for InformationAsset revision state and identity. */
object InformationAssetRevisionIntegrity {
    fun stateHash(
        request: InformationAssetRequest,
        sources: Collection<PhotonRevisionReference>,
        evidenceBindings: Collection<InformationEvidenceBinding>,
        claims: Collection<InformationClaim>,
        conflicts: Collection<InformationConflict>,
        domainIds: Collection<FieldDomainId>,
        participatingModules: Collection<String>,
        resolution: InformationAssetResolutionState,
    ): CognitiveStateHash = InformationAssetFingerprints.stateHash(
        *stateParts(
            request = request,
            sources = sources,
            evidenceBindings = evidenceBindings,
            claims = claims,
            conflicts = conflicts,
            domainIds = domainIds,
            participatingModules = participatingModules,
            resolution = resolution,
        ).toTypedArray(),
    )

    fun stateHash(revision: InformationAssetRevision): CognitiveStateHash = stateHash(
        request = revision.request,
        sources = revision.manifest.sourcePhotons,
        evidenceBindings = revision.evidenceBindings,
        claims = revision.claims,
        conflicts = revision.conflicts,
        domainIds = revision.manifest.domainIds,
        participatingModules = revision.manifest.participatingModules,
        resolution = revision.manifest.resolution,
    )

    fun revisionId(
        request: InformationAssetRequest,
        stateHash: CognitiveStateHash,
        parent: InformationAssetRevisionRef?,
    ): InformationAssetRevisionId = InformationAssetFingerprints.revision(
        request.id.value,
        stateHash.value,
        parent?.assetId?.value.orEmpty(),
        parent?.revisionId?.value.orEmpty(),
        parent?.photonId?.value.orEmpty(),
    )

    fun revisionId(revision: InformationAssetRevision): InformationAssetRevisionId =
        revisionId(revision.request, stateHash(revision), revision.manifest.parent)

    fun requireValid(revision: InformationAssetRevision) {
        val expectedStateHash = stateHash(revision)
        require(revision.manifest.stateHash == expectedStateHash) {
            "InformationAsset manifest state hash mismatch"
        }
        val expectedRevisionId = revisionId(
            request = revision.request,
            stateHash = expectedStateHash,
            parent = revision.manifest.parent,
        )
        require(revision.manifest.id == expectedRevisionId) {
            "InformationAsset revision id mismatch"
        }
    }

    private fun stateParts(
        request: InformationAssetRequest,
        sources: Collection<PhotonRevisionReference>,
        evidenceBindings: Collection<InformationEvidenceBinding>,
        claims: Collection<InformationClaim>,
        conflicts: Collection<InformationConflict>,
        domainIds: Collection<FieldDomainId>,
        participatingModules: Collection<String>,
        resolution: InformationAssetResolutionState,
    ): List<String> = buildList {
        add("information-asset-state/v1")
        add(request.id.value)
        add(request.kind.name)
        add(request.title)
        add(request.primaryDomainId.value)
        request.requiredSemanticKeys.sorted().forEach { add("required:$it") }
        sources
            .sortedWith(compareBy<PhotonRevisionReference> { it.photonId.value }.thenBy { it.revision })
            .forEach { add("source:${it.fingerprint()}") }
        evidenceBindings.sortedBy { it.id.value }.forEach { add("evidence:${it.fingerprint()}") }
        claims.sortedBy { it.id.value }.forEach { add("claim:${it.fingerprint()}") }
        conflicts.sortedBy { it.id.value }.forEach { add("conflict:${it.fingerprint()}") }
        domainIds.map { it.value }.distinct().sorted().forEach { add("domain:$it") }
        participatingModules.distinct().sorted().forEach { add("module:$it") }
        add("resolution:${resolution.name}")
    }
}
