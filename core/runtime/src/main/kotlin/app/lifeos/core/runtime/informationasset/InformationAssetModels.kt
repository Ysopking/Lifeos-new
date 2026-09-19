package app.lifeos.core.runtime.informationasset

import app.lifeos.core.field.EvidenceId
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.CognitiveStateHash
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import java.time.Instant

enum class InformationAssetKind {
    KNOWLEDGE,
    LEGAL,
    ORGANIZATION,
    FINANCIAL,
    SCIENTIFIC,
    CODE_AUDIT,
    CODE_CHANGE_PROPOSAL,
    PROJECT,
    DOCUMENT,
    CONVERSATION,
    EVENT,
    OTHER,
}

enum class InformationClaimState {
    SUPPORTED,
    ASSUMPTION,
    UNRESOLVED,
    REJECTED,
}

enum class InformationConflictResolutionState {
    OPEN,
    RESOLVED,
}

enum class InformationAssetResolutionState {
    CONVERGED,
    UNRESOLVED,
}

data class PhotonRevisionReference(
    val photonId: PhotonId,
    val revision: Long,
    val inputStateHash: CognitiveStateHash,
    val semanticStateHash: CognitiveStateHash,
) {
    init {
        require(photonId.value.isNotBlank()) { "Photon revision reference id must not be blank" }
        require(revision > 0) { "Photon revision reference must be positive" }
    }

    fun fingerprint(): String = InformationAssetFingerprints.fingerprint(
        "photon-revision-reference/v1",
        photonId.value,
        revision.toString(),
        inputStateHash.value,
        semanticStateHash.value,
    )

    companion object {
        fun from(photon: Photon): PhotonRevisionReference = PhotonRevisionReference(
            photonId = photon.id,
            revision = photon.revision,
            inputStateHash = CanonicalPhotonState.inputHash(photon),
            semanticStateHash = CanonicalPhotonState.semanticHash(photon),
        )
    }
}

data class InformationEvidenceBinding(
    val id: InformationEvidenceBindingId,
    val source: PhotonRevisionReference,
    val fieldEvidenceId: EvidenceId?,
    val domainId: FieldDomainId,
    val authority: SourceAuthority,
    val confidence: Double,
    val reliability: EvidenceReliability,
    val validity: TemporalValidity,
    val observedAt: Instant,
    val payloadFingerprint: String,
) {
    init {
        require(confidence in 0.0..1.0) { "Information evidence confidence must be in 0..1" }
        require(payloadFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Information evidence payload fingerprint must be lowercase SHA-256"
        }
        require(id == expectedId()) { "Information evidence binding id does not match its content" }
    }

    fun fingerprint(): String = id.value

    private fun expectedId(): InformationEvidenceBindingId = InformationAssetFingerprints.evidenceBinding(
        source.fingerprint(),
        fieldEvidenceId?.value.orEmpty(),
        domainId.value,
        authority.name,
        confidence.toString(),
        reliability.score.toString(),
        reliability.reason,
        validity.validFrom?.toString().orEmpty(),
        validity.validUntilExclusive?.toString().orEmpty(),
        observedAt.toString(),
        payloadFingerprint,
    )

    companion object {
        fun create(
            source: PhotonRevisionReference,
            fieldEvidenceId: EvidenceId?,
            domainId: FieldDomainId,
            authority: SourceAuthority,
            confidence: Double,
            reliability: EvidenceReliability,
            validity: TemporalValidity,
            observedAt: Instant,
            payloadFingerprint: String,
        ): InformationEvidenceBinding {
            val id = InformationAssetFingerprints.evidenceBinding(
                source.fingerprint(),
                fieldEvidenceId?.value.orEmpty(),
                domainId.value,
                authority.name,
                confidence.toString(),
                reliability.score.toString(),
                reliability.reason,
                validity.validFrom?.toString().orEmpty(),
                validity.validUntilExclusive?.toString().orEmpty(),
                observedAt.toString(),
                payloadFingerprint,
            )
            return InformationEvidenceBinding(
                id = id,
                source = source,
                fieldEvidenceId = fieldEvidenceId,
                domainId = domainId,
                authority = authority,
                confidence = confidence,
                reliability = reliability,
                validity = validity,
                observedAt = observedAt,
                payloadFingerprint = payloadFingerprint,
            )
        }

        fun fromFieldEvidence(
            sourcePhoton: Photon,
            evidence: FieldEvidence,
        ): InformationEvidenceBinding {
            require(evidence.sourcePhotonId == sourcePhoton.id) {
                "Field evidence source Photon id does not match supplied Photon"
            }
            require(evidence.sourceRevision == sourcePhoton.revision) {
                "Field evidence source revision does not match supplied Photon"
            }
            return create(
                source = PhotonRevisionReference.from(sourcePhoton),
                fieldEvidenceId = evidence.id,
                domainId = evidence.domainId,
                authority = evidence.authority,
                confidence = evidence.confidence,
                reliability = evidence.reliability,
                validity = evidence.validity,
                observedAt = evidence.observedAt,
                payloadFingerprint = evidence.payload.stableFingerprint(),
            )
        }
    }
}

data class InformationClaim(
    val id: InformationClaimId,
    val domainId: FieldDomainId,
    val semanticKey: String,
    val statement: String,
    val state: InformationClaimState,
    val confidence: Double,
    val evidenceBindingIds: Set<InformationEvidenceBindingId>,
    val derivedFromClaimIds: Set<InformationClaimId> = emptySet(),
    val explanation: String,
) {
    init {
        require(semanticKey.isNotBlank()) { "Information claim semantic key must not be blank" }
        require(statement.isNotBlank()) { "Information claim statement must not be blank" }
        require(confidence in 0.0..1.0) { "Information claim confidence must be in 0..1" }
        require(explanation.isNotBlank()) { "Information claim explanation must not be blank" }
        require(id !in derivedFromClaimIds) { "Information claim cannot derive from itself" }
        require(id == expectedId()) { "Information claim id does not match its content" }
    }

    fun fingerprint(): String = id.value

    private fun expectedId(): InformationClaimId = InformationAssetFingerprints.claim(
        domainId.value,
        semanticKey,
        statement,
        state.name,
        confidence.toString(),
        explanation,
        *evidenceBindingIds.map { it.value }.sorted().toTypedArray(),
        *derivedFromClaimIds.map { it.value }.sorted().toTypedArray(),
    )

    companion object {
        fun create(
            domainId: FieldDomainId,
            semanticKey: String,
            statement: String,
            state: InformationClaimState,
            confidence: Double,
            evidenceBindingIds: Set<InformationEvidenceBindingId>,
            derivedFromClaimIds: Set<InformationClaimId> = emptySet(),
            explanation: String,
        ): InformationClaim {
            val id = InformationAssetFingerprints.claim(
                domainId.value,
                semanticKey,
                statement,
                state.name,
                confidence.toString(),
                explanation,
                *evidenceBindingIds.map { it.value }.sorted().toTypedArray(),
                *derivedFromClaimIds.map { it.value }.sorted().toTypedArray(),
            )
            return InformationClaim(
                id = id,
                domainId = domainId,
                semanticKey = semanticKey,
                statement = statement,
                state = state,
                confidence = confidence,
                evidenceBindingIds = evidenceBindingIds,
                derivedFromClaimIds = derivedFromClaimIds,
                explanation = explanation,
            )
        }
    }
}

data class InformationConflict(
    val id: InformationConflictId,
    val domainId: FieldDomainId,
    val claimIds: Set<InformationClaimId>,
    val severity: Double,
    val state: InformationConflictResolutionState,
    val explanation: String,
) {
    init {
        require(claimIds.size >= 2) { "Information conflict requires at least two claims" }
        require(severity in 0.0..1.0) { "Information conflict severity must be in 0..1" }
        require(explanation.isNotBlank()) { "Information conflict explanation must not be blank" }
        require(id == expectedId()) { "Information conflict id does not match its content" }
    }

    fun fingerprint(): String = id.value

    private fun expectedId(): InformationConflictId = InformationAssetFingerprints.conflict(
        domainId.value,
        severity.toString(),
        state.name,
        explanation,
        *claimIds.map { it.value }.sorted().toTypedArray(),
    )

    companion object {
        fun create(
            domainId: FieldDomainId,
            claimIds: Set<InformationClaimId>,
            severity: Double,
            state: InformationConflictResolutionState,
            explanation: String,
        ): InformationConflict {
            val id = InformationAssetFingerprints.conflict(
                domainId.value,
                severity.toString(),
                state.name,
                explanation,
                *claimIds.map { it.value }.sorted().toTypedArray(),
            )
            return InformationConflict(
                id = id,
                domainId = domainId,
                claimIds = claimIds,
                severity = severity,
                state = state,
                explanation = explanation,
            )
        }
    }
}

data class InformationAssetRequest(
    val id: InformationAssetId,
    val kind: InformationAssetKind,
    val title: String,
    val primaryDomainId: FieldDomainId,
    val requiredSemanticKeys: Set<String> = emptySet(),
) {
    init {
        require(title.isNotBlank()) { "Information asset title must not be blank" }
        require(requiredSemanticKeys.none { it.isBlank() }) { "Required semantic keys must not be blank" }
    }

    companion object {
        fun create(
            namespace: String,
            stableKey: String,
            kind: InformationAssetKind,
            title: String,
            primaryDomainId: FieldDomainId,
            requiredSemanticKeys: Set<String> = emptySet(),
        ): InformationAssetRequest = InformationAssetRequest(
            id = InformationAssetFingerprints.asset(namespace, stableKey),
            kind = kind,
            title = title,
            primaryDomainId = primaryDomainId,
            requiredSemanticKeys = requiredSemanticKeys,
        )
    }
}

data class InformationAssetRevisionRef(
    val assetId: InformationAssetId,
    val revisionId: InformationAssetRevisionId,
    val photonId: PhotonId,
) {
    init { require(photonId.value.isNotBlank()) { "Information asset revision Photon id must not be blank" } }
}

data class InformationAssetRevisionManifest(
    val id: InformationAssetRevisionId,
    val parent: InformationAssetRevisionRef?,
    val sourcePhotons: List<PhotonRevisionReference>,
    val domainIds: Set<FieldDomainId>,
    val participatingModules: Set<String>,
    val stateHash: CognitiveStateHash,
    val resolution: InformationAssetResolutionState,
) {
    init {
        require(sourcePhotons.map { it.photonId to it.revision }.distinct().size == sourcePhotons.size) {
            "Information asset cannot contain two states for the same Photon id and revision"
        }
        require(domainIds.isNotEmpty()) { "Information asset requires at least one domain" }
        require(participatingModules.isNotEmpty()) { "Information asset requires participating modules" }
        require(participatingModules.none { it.isBlank() }) { "Information asset module ids must not be blank" }
    }
}

data class InformationAssetRevision(
    val request: InformationAssetRequest,
    val evidenceBindings: List<InformationEvidenceBinding>,
    val claims: List<InformationClaim>,
    val conflicts: List<InformationConflict>,
    val manifest: InformationAssetRevisionManifest,
) {
    init {
        require(claims.isNotEmpty()) { "Information asset revision requires at least one claim" }
        require(evidenceBindings.map { it.id }.distinct().size == evidenceBindings.size) {
            "Information asset evidence binding ids must be unique"
        }
        require(claims.map { it.id }.distinct().size == claims.size) {
            "Information asset claim ids must be unique"
        }
        require(conflicts.map { it.id }.distinct().size == conflicts.size) {
            "Information asset conflict ids must be unique"
        }
        require(request.primaryDomainId in manifest.domainIds) {
            "Information asset manifest must retain the primary domain"
        }
        require(parentMatchesRequest()) { "Information asset parent belongs to a different logical asset" }
        val bindingIds = evidenceBindings.mapTo(mutableSetOf()) { it.id }
        require(claims.all { claim -> claim.evidenceBindingIds.all(bindingIds::contains) }) {
            "Information asset revision contains a claim with unknown evidence"
        }
        val claimIds = claims.mapTo(mutableSetOf()) { it.id }
        require(claims.all { claim -> claim.derivedFromClaimIds.all(claimIds::contains) }) {
            "Information asset revision contains a claim with unknown parent claim"
        }
        require(conflicts.all { conflict -> conflict.claimIds.all(claimIds::contains) }) {
            "Information asset revision contains a conflict with unknown claim"
        }
        val representedDomains = buildSet {
            add(request.primaryDomainId)
            evidenceBindings.forEach { add(it.domainId) }
            claims.forEach { add(it.domainId) }
            conflicts.forEach { add(it.domainId) }
        }
        require(manifest.domainIds.containsAll(representedDomains)) {
            "Information asset manifest dropped a represented domain"
        }
    }

    private fun parentMatchesRequest(): Boolean =
        manifest.parent == null || manifest.parent.assetId == request.id
}

data class InformationAsset(
    val id: InformationAssetId,
    val latestRevision: InformationAssetRevision,
) {
    init { require(id == latestRevision.request.id) { "Information asset id must match its latest revision" } }
}
