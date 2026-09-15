package app.lifeos.core.runtime.informationasset.convergence

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldConvergenceRequest
import app.lifeos.core.field.FieldConvergenceResult
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.HypothesisId
import app.lifeos.core.field.HypothesisState
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.convergence.AuditedCrossDomainBridge
import app.lifeos.core.runtime.convergence.CrossDomainBridgeStatus
import app.lifeos.core.runtime.convergence.CrossDomainConvergenceRequest
import app.lifeos.core.runtime.convergence.CrossDomainConvergenceResult
import app.lifeos.core.runtime.convergence.CrossDomainConvergenceStatus
import app.lifeos.core.runtime.convergence.ConvergenceCoordinator
import app.lifeos.core.runtime.informationasset.InformationAssetAssemblyRequest
import app.lifeos.core.runtime.informationasset.InformationAssetAssemblyResult
import app.lifeos.core.runtime.informationasset.InformationAssetAssembler
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.InformationAssetResolutionState
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionId
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionRef
import app.lifeos.core.runtime.informationasset.InformationClaim
import app.lifeos.core.runtime.informationasset.InformationClaimId
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.InformationConflict
import app.lifeos.core.runtime.informationasset.InformationConflictResolutionState
import app.lifeos.core.runtime.informationasset.InformationEvidenceBinding
import app.lifeos.core.runtime.informationasset.PhotonRevisionReference

data class CrossDomainInformationAssetRequest(
    val convergence: CrossDomainConvergenceRequest,
    val auditedBridges: List<AuditedCrossDomainBridge>,
    val assetRequest: InformationAssetRequest,
    val sourcePhotons: List<Photon>,
    val participatingModules: Set<String>,
    val parent: InformationAssetRevisionRef? = null,
) {
    init {
        require(sourcePhotons.isNotEmpty()) { "Cross-domain InformationAsset requires source Photons" }
        require(participatingModules.isNotEmpty()) { "Cross-domain InformationAsset requires participating modules" }
        require(participatingModules.none { it.isBlank() }) { "Participating module ids must not be blank" }
        require(auditedBridges.map { it.rule.id }.distinct().size == auditedBridges.size) {
            "Audited cross-domain bridge ids must be unique"
        }
        require(auditedBridges.map { it.rule.id }.toSet() == convergence.bridges.map { it.id }.toSet()) {
            "Every productive cross-domain bridge must have exactly one audit binding"
        }
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "cross-domain-information-asset-request/v1",
        convergence.id,
        assetRequest.id.value,
        parent?.revisionId?.value.orEmpty(),
        *auditedBridges.sortedBy { it.rule.id }.map { it.fingerprint }.toTypedArray(),
        *sourcePhotons
            .map { PhotonRevisionReference.from(it).fingerprint() }
            .sorted()
            .toTypedArray(),
        *participatingModules.sorted().toTypedArray(),
    )
}

data class CrossDomainInformationAssetTrace(
    val requestFingerprint: String,
    val convergenceRequestId: String,
    val convergenceStatus: CrossDomainConvergenceStatus,
    val domainSnapshots: Map<FieldDomainId, String>,
    val bridgeAuditFingerprints: List<String>,
    val assetRevisionId: InformationAssetRevisionId,
    val assetResolution: InformationAssetResolutionState,
) {
    val fingerprint: String = StableFieldIds.fingerprint(
        "cross-domain-information-asset-trace/v1",
        requestFingerprint,
        convergenceRequestId,
        convergenceStatus.name,
        assetRevisionId.value,
        assetResolution.name,
        *domainSnapshots.entries
            .sortedBy { it.key.value }
            .map { "${it.key.value}:${it.value}" }
            .toTypedArray(),
        *bridgeAuditFingerprints.sorted().toTypedArray(),
    )
}

data class CrossDomainInformationAssetResult(
    val convergence: CrossDomainConvergenceResult,
    val assembly: InformationAssetAssemblyResult?,
    val trace: CrossDomainInformationAssetTrace?,
    val failures: List<String> = emptyList(),
) {
    init {
        require(failures.none { it.isBlank() })
        if (assembly == null) require(trace == null)
    }
}

private data class DomainProjection(
    val bindings: List<InformationEvidenceBinding>,
    val claims: List<InformationClaim>,
    val conflicts: List<InformationConflict>,
    val claimByHypothesisId: Map<HypothesisId, InformationClaimId>,
)

/**
 * B9 projects the existing field-convergence authority into semantic InformationAssets.
 * It never re-runs field physics, never inherits source-domain authority across a bridge and never
 * hides an unresolved cross-domain decision behind a CONVERGED asset revision.
 */
class InformationAssetConvergenceCoordinator(
    private val convergenceCoordinator: ConvergenceCoordinator = ConvergenceCoordinator(),
    private val assembler: InformationAssetAssembler = InformationAssetAssembler(),
) {
    fun coordinate(request: CrossDomainInformationAssetRequest): CrossDomainInformationAssetResult {
        val validationFailures = validateSources(request)
        if (validationFailures.isNotEmpty()) {
            val empty = CrossDomainConvergenceResult(
                requestId = request.convergence.id,
                status = CrossDomainConvergenceStatus.INVALID_REQUEST_GRAPH,
                domainResults = emptyList(),
                bridgeTrace = emptyList(),
                conflicts = emptyList(),
                failures = validationFailures,
            )
            return CrossDomainInformationAssetResult(empty, null, null, validationFailures)
        }

        val convergence = convergenceCoordinator.coordinate(request.convergence)
        if (convergence.status == CrossDomainConvergenceStatus.INVALID_REQUEST_GRAPH ||
            convergence.status == CrossDomainConvergenceStatus.DOMAIN_FAILURE
        ) {
            return CrossDomainInformationAssetResult(
                convergence = convergence,
                assembly = null,
                trace = null,
                failures = convergence.failures.ifEmpty { listOf("cross-domain-convergence-failed") },
            )
        }

        val photonByState = request.sourcePhotons.associateBy { it.id.value to it.revision }
        val originalEvidence = request.convergence.domains
            .flatMap { it.request.evidence }
            .associateBy { it.id }
        val directBindingByEvidence = originalEvidence.values
            .sortedBy { it.id.value }
            .associate { evidence ->
                val photon = photonByState.getValue(evidence.sourcePhotonId.value to evidence.sourceRevision)
                evidence.id to InformationEvidenceBinding.fromFieldEvidence(photon, evidence)
            }

        val auditedByRule = request.auditedBridges.associateBy { it.rule.id }
        val sourceClaimIds = linkedMapOf<HypothesisId, InformationClaimId>()
        val allBindings = linkedMapOf<String, InformationEvidenceBinding>()
        val allClaims = linkedMapOf<String, InformationClaim>()
        val allConflicts = linkedMapOf<String, InformationConflict>()

        val originalRequestByDomain = request.convergence.domains.associate { it.request.domainId to it.request }
        convergence.domainResults.forEach { domainResult ->
            val domainRequest = originalRequestByDomain.getValue(domainResult.state.domainId)
            val bridgeBindings = buildBridgeBindings(
                targetDomainId = domainResult.state.domainId,
                request = request,
                convergence = convergence,
                auditedByRule = auditedByRule,
                originalRequestByDomain = originalRequestByDomain,
                photonByState = photonByState,
            )
            val projection = projectDomain(
                request = domainRequest,
                result = domainResult,
                directBindingByEvidence = directBindingByEvidence,
                bridgeBindings = bridgeBindings,
                sourceClaimIds = sourceClaimIds,
                incomingBridges = request.auditedBridges.filter { it.rule.targetDomainId == domainResult.state.domainId },
            )
            projection.bindings.forEach { allBindings[it.id.value] = it }
            projection.claims.forEach { claim ->
                allClaims[claim.id.value] = claim
                val hypothesisId = projection.claimByHypothesisId.entries.firstOrNull { it.value == claim.id }?.key
                if (hypothesisId != null) sourceClaimIds[hypothesisId] = claim.id
            }
            projection.conflicts.forEach { allConflicts[it.id.value] = it }
        }

        if (convergence.status != CrossDomainConvergenceStatus.CONVERGED) {
            val firstBinding = allBindings.values.firstOrNull()
                ?: return CrossDomainInformationAssetResult(
                    convergence = convergence,
                    assembly = null,
                    trace = null,
                    failures = listOf("cross-domain-unresolved-without-projectable-evidence"),
                )
            val statusClaim = InformationClaim.create(
                domainId = request.assetRequest.primaryDomainId,
                semanticKey = "cross-domain.convergence.status",
                statement = "Cross-domain convergence remains unresolved",
                state = InformationClaimState.UNRESOLVED,
                confidence = 0.0,
                evidenceBindingIds = setOf(firstBinding.id),
                explanation = unresolvedExplanation(convergence),
            )
            allClaims[statusClaim.id.value] = statusClaim
        }

        val assembly = assembler.assemble(
            InformationAssetAssemblyRequest(
                request = request.assetRequest,
                sourcePhotons = request.sourcePhotons,
                evidenceBindings = allBindings.values.toList(),
                claims = allClaims.values.toList(),
                conflicts = allConflicts.values.toList(),
                participatingModules = request.participatingModules,
                parent = request.parent,
            )
        )
        val trace = CrossDomainInformationAssetTrace(
            requestFingerprint = request.fingerprint,
            convergenceRequestId = convergence.requestId,
            convergenceStatus = convergence.status,
            domainSnapshots = convergence.domainResults.associate { it.state.domainId to it.snapshot.id.value },
            bridgeAuditFingerprints = request.auditedBridges.map { it.fingerprint },
            assetRevisionId = assembly.revision.manifest.id,
            assetResolution = assembly.revision.manifest.resolution,
        )
        return CrossDomainInformationAssetResult(convergence, assembly, trace)
    }

    private fun validateSources(request: CrossDomainInformationAssetRequest): List<String> {
        val sourceStates = request.sourcePhotons.map { it.id.value to it.revision }.toSet()
        val duplicateStates = request.sourcePhotons
            .groupBy { it.id.value to it.revision }
            .filterValues { it.size > 1 }
            .keys
        return buildList {
            duplicateStates.sortedBy { "${it.first}@${it.second}" }.forEach {
                add("duplicate-source-photon:${it.first}@${it.second}")
            }
            request.convergence.domains
                .flatMap { it.request.evidence }
                .sortedBy { it.id.value }
                .forEach { evidence ->
                    if ((evidence.sourcePhotonId.value to evidence.sourceRevision) !in sourceStates) {
                        add("missing-source-photon:${evidence.sourcePhotonId.value}@${evidence.sourceRevision}")
                    }
                }
        }.distinct().sorted()
    }

    private fun buildBridgeBindings(
        targetDomainId: FieldDomainId,
        request: CrossDomainInformationAssetRequest,
        convergence: CrossDomainConvergenceResult,
        auditedByRule: Map<String, AuditedCrossDomainBridge>,
        originalRequestByDomain: Map<FieldDomainId, FieldConvergenceRequest>,
        photonByState: Map<Pair<String, Long>, Photon>,
    ): Map<HypothesisId, List<InformationEvidenceBinding>> {
        val traceById = convergence.bridgeTrace.associateBy { it.bridgeId }
        return request.convergence.bridges
            .asSequence()
            .filter { it.targetDomainId == targetDomainId }
            .filter { traceById[it.id]?.status == CrossDomainBridgeStatus.APPLIED }
            .associate { rule ->
                val audited = auditedByRule.getValue(rule.id)
                val sourceRequest = originalRequestByDomain.getValue(rule.sourceDomainId)
                val sourceHypothesis = sourceRequest.hypotheses.first { it.id == rule.sourceHypothesisId }
                val evidenceById = sourceRequest.evidence.associateBy { it.id }
                val bindings = sourceHypothesis.evidenceLinks
                    .mapNotNull { evidenceById[it.evidenceId] }
                    .distinctBy { it.id }
                    .sortedBy { it.id.value }
                    .map { evidence ->
                        val photon = photonByState.getValue(evidence.sourcePhotonId.value to evidence.sourceRevision)
                        InformationEvidenceBinding.create(
                            source = PhotonRevisionReference.from(photon),
                            fieldEvidenceId = evidence.id,
                            domainId = rule.targetDomainId,
                            authority = SourceAuthority.UNVERIFIED,
                            confidence = (evidence.confidence * rule.confidenceMultiplier).coerceIn(0.0, 1.0),
                            reliability = EvidenceReliability(
                                score = (evidence.reliability.score * rule.confidenceMultiplier).coerceIn(0.0, 1.0),
                                reason = "Cross-domain bridge ${rule.id}; authority re-evaluated",
                            ),
                            validity = evidence.validity,
                            observedAt = evidence.observedAt,
                            payloadFingerprint = StableFieldIds.fingerprint(
                                "cross-domain-information-binding/v1",
                                audited.fingerprint,
                                evidence.id.value,
                                rule.targetHypothesisId.value,
                            ),
                        )
                    }
                rule.targetHypothesisId to bindings
            }
    }

    private fun projectDomain(
        request: FieldConvergenceRequest,
        result: FieldConvergenceResult,
        directBindingByEvidence: Map<app.lifeos.core.field.EvidenceId, InformationEvidenceBinding>,
        bridgeBindings: Map<HypothesisId, List<InformationEvidenceBinding>>,
        sourceClaimIds: Map<HypothesisId, InformationClaimId>,
        incomingBridges: List<AuditedCrossDomainBridge>,
    ): DomainProjection {
        val conflictNodeIds = result.conflicts.flatMapTo(mutableSetOf()) { it.nodeIds }
        val localClaimsByHypothesis = linkedMapOf<HypothesisId, InformationClaim>()
        val bindings = linkedMapOf<String, InformationEvidenceBinding>()

        result.hypotheses.sortedBy { it.id.value }.forEach { hypothesis ->
            val direct = hypothesis.evidenceLinks.mapNotNull { directBindingByEvidence[it.evidenceId] }
            val bridged = bridgeBindings[hypothesis.id].orEmpty()
            (direct + bridged).forEach { bindings[it.id.value] = it }
            val evidenceIds = (direct + bridged).mapTo(linkedSetOf()) { it.id }
            val parents = incomingBridges
                .filter { it.rule.targetHypothesisId == hypothesis.id }
                .mapNotNull { sourceClaimIds[it.rule.sourceHypothesisId] }
                .toSet()
            val impactedByConflict = hypothesis.nodeIds.any(conflictNodeIds::contains)
            val claimState = when {
                impactedByConflict -> InformationClaimState.UNRESOLVED
                hypothesis.state == HypothesisState.CONVERGED || hypothesis.state == HypothesisState.SUPPORTED ->
                    InformationClaimState.SUPPORTED
                hypothesis.state == HypothesisState.REJECTED -> InformationClaimState.REJECTED
                evidenceIds.isEmpty() -> InformationClaimState.ASSUMPTION
                else -> InformationClaimState.UNRESOLVED
            }
            val claim = InformationClaim.create(
                domainId = request.domainId,
                semanticKey = hypothesis.semanticKey,
                statement = hypothesis.explanation,
                state = claimState,
                confidence = hypothesis.score.total.coerceIn(0.0, 1.0),
                evidenceBindingIds = evidenceIds,
                derivedFromClaimIds = parents,
                explanation = "Projected from FieldHypothesis ${hypothesis.id.value} in state ${hypothesis.state.name}",
            )
            localClaimsByHypothesis[hypothesis.id] = claim
        }

        val conflicts = result.conflicts.sortedBy { it.key }.mapNotNull { fieldConflict ->
            val claimIds = localClaimsByHypothesis
                .filter { (hypothesisId, _) ->
                    result.hypotheses.first { it.id == hypothesisId }.nodeIds.any(fieldConflict.nodeIds::contains)
                }
                .values
                .mapTo(linkedSetOf()) { it.id }
            if (claimIds.size < 2) null else InformationConflict.create(
                domainId = request.domainId,
                claimIds = claimIds,
                severity = fieldConflict.severity,
                state = InformationConflictResolutionState.OPEN,
                explanation = fieldConflict.explanation,
            )
        }

        return DomainProjection(
            bindings = bindings.values.toList(),
            claims = localClaimsByHypothesis.values.toList(),
            conflicts = conflicts,
            claimByHypothesisId = localClaimsByHypothesis.mapValues { it.value.id },
        )
    }

    private fun unresolvedExplanation(result: CrossDomainConvergenceResult): String {
        val bridgeReasons = result.bridgeTrace
            .filter { it.status != CrossDomainBridgeStatus.APPLIED }
            .joinToString(";") { "${it.bridgeId}:${it.status.name}:${it.reason}" }
        val conflicts = result.conflicts.joinToString(";") { "${it.domainId.value}:${it.conflictKey}" }
        return listOf(
            "status=${result.status.name}",
            bridgeReasons.takeIf { it.isNotBlank() },
            conflicts.takeIf { it.isNotBlank() },
        ).filterNotNull().joinToString("|")
    }
}
