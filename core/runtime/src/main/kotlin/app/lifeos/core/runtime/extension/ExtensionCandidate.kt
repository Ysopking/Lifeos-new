package app.lifeos.core.runtime.extension

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.deepsearch.DeepSearchEvidenceId
import app.lifeos.core.runtime.deepsearch.DeepSearchHypothesisId

data class ExtensionCandidateRequest(
    val gap: ExtensionGap,
    val claimGraph: DeepSearchClaimGraph,
    val selectedClaimIds: Set<DeepSearchHypothesisId>,
    val selectedEvidenceIds: Set<DeepSearchEvidenceId>,
    val rationale: String,
) {
    init {
        require(selectedClaimIds.isNotEmpty()) {
            "Extension candidate requires at least one explicit claim"
        }
        require(selectedEvidenceIds.isNotEmpty()) {
            "Extension candidate requires at least one explicit evidence item"
        }
        require(rationale.isNotBlank()) {
            "Extension candidate rationale must not be blank"
        }

        val graphClaimIds = claimGraph.claimNodes.mapTo(linkedSetOf()) { it.hypothesis.id }
        val graphEvidenceIds = claimGraph.evidenceNodes.mapTo(linkedSetOf()) { it.evidence.id }
        require(selectedClaimIds.all(graphClaimIds::contains)) {
            "Extension candidate references a claim outside its DeepSearch claim graph"
        }
        require(selectedEvidenceIds.all(graphEvidenceIds::contains)) {
            "Extension candidate references evidence outside its DeepSearch claim graph"
        }

        val referencedByClaims = claimGraph.claimNodes
            .filter { it.hypothesis.id in selectedClaimIds }
            .flatMapTo(linkedSetOf()) { it.hypothesis.evidenceIds }
        require(selectedEvidenceIds.all(referencedByClaims::contains)) {
            "Selected candidate evidence must be referenced by selected claims"
        }
    }
}

data class ExtensionCandidate private constructor(
    val id: String,
    val gapId: String,
    val claimGraphId: String,
    val requestedKinds: Set<ExtensionKind>,
    val claimIds: Set<DeepSearchHypothesisId>,
    val evidenceIds: Set<DeepSearchEvidenceId>,
    val rationale: String,
    val sourceGapFingerprint: String,
    val sourceGraphFingerprint: String,
) {
    init {
        require(id.isNotBlank())
        require(gapId.isNotBlank())
        require(claimGraphId.isNotBlank())
        require(requestedKinds.isNotEmpty())
        require(claimIds.isNotEmpty())
        require(evidenceIds.isNotEmpty())
        require(rationale.isNotBlank())
        require(sourceGapFingerprint.isNotBlank())
        require(sourceGraphFingerprint.isNotBlank())
        require(id == expectedId()) {
            "Extension candidate id does not match content"
        }
    }

    val activationAllowed: Boolean
        get() = false

    val directRegistryMutationAllowed: Boolean
        get() = false

    val requiresEvolutionPromotion: Boolean
        get() = ExtensionKind.WORLD_EQUATION_PACK in requestedKinds

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "extension-candidate/v1",
        gapId,
        claimGraphId,
        rationale,
        sourceGapFingerprint,
        sourceGraphFingerprint,
        *requestedKinds.map { it.name }.sorted().toTypedArray(),
        *claimIds.map { it.value }.sorted().toTypedArray(),
        *evidenceIds.map { it.value }.sorted().toTypedArray(),
    )

    private fun expectedId(): String = "extension-candidate:${fingerprint()}"

    companion object {
        fun create(request: ExtensionCandidateRequest): ExtensionCandidate {
            val gapFingerprint = StableFieldIds.fingerprint(
                "extension-candidate-gap-source/v1",
                request.gap.id,
                request.gap.kind.name,
                request.gap.semanticKey,
                request.gap.reason,
                request.gap.sourceFingerprint,
                *request.gap.requiredExtensionKinds.map { it.name }.sorted().toTypedArray(),
            )
            val graphFingerprint = request.claimGraph.fingerprint()
            val provisionalFingerprint = StableFieldIds.fingerprint(
                "extension-candidate/v1",
                request.gap.id,
                request.claimGraph.id,
                request.rationale,
                gapFingerprint,
                graphFingerprint,
                *request.gap.requiredExtensionKinds.map { it.name }.sorted().toTypedArray(),
                *request.selectedClaimIds.map { it.value }.sorted().toTypedArray(),
                *request.selectedEvidenceIds.map { it.value }.sorted().toTypedArray(),
            )
            return ExtensionCandidate(
                id = "extension-candidate:$provisionalFingerprint",
                gapId = request.gap.id,
                claimGraphId = request.claimGraph.id,
                requestedKinds = request.gap.requiredExtensionKinds,
                claimIds = request.selectedClaimIds.toSortedSet(compareBy { it.value }),
                evidenceIds = request.selectedEvidenceIds.toSortedSet(compareBy { it.value }),
                rationale = request.rationale,
                sourceGapFingerprint = gapFingerprint,
                sourceGraphFingerprint = graphFingerprint,
            )
        }
    }
}

/**
 * B154 materializes only evidence-bound proposals. It does not compile, validate, register,
 * activate or promote an extension.
 */
class ExtensionCandidateFactory {
    fun propose(request: ExtensionCandidateRequest): ExtensionCandidate =
        ExtensionCandidate.create(request)
}
