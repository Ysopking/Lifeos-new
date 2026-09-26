package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.thought.ThoughtMatrixSnapshot

data class MetaInferenceBudget(
    val maxCandidates: Int = 16,
    val structural: StructuralAnalysisBudget = StructuralAnalysisBudget(),
) {
    init {
        require(maxCandidates in 2..128)
    }
}

enum class MetaInferenceStatus {
    DISTINCT,
    INFORMATION_REQUIRED,
    MODEL_INCONSISTENT,
    BUDGET_EXHAUSTED,
    CONFLICT,
}

data class MetaInferenceResult(
    val sourceGroupFingerprint: String,
    val candidateIds: List<String>,
    val status: MetaInferenceStatus,
    val identifiability: LayeredIdentifiabilityAssessment?,
    val structuralSignatures: List<StructuralNeighborhoodSignature>,
    val support: MetaSupportRankAssessment,
    val realizationGap: PathRealizationGap?,
    val deformation: DeformationOnsetAssessment?,
    val fingerprint: String,
) {
    init {
        require(sourceGroupFingerprint.isNotBlank())
        require(candidateIds.size >= 2)
        require(candidateIds == candidateIds.distinct().sorted())
        require(
            structuralSignatures ==
                structuralSignatures.sortedBy { it.sourcePhotonId.value }
        )
    }

    val truthAuthority: Boolean get() = false
    val mergeAuthority: Boolean get() = false
    val causalAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    val informationRequired: Boolean
        get() = status == MetaInferenceStatus.INFORMATION_REQUIRED ||
            status == MetaInferenceStatus.BUDGET_EXHAUSTED ||
            status == MetaInferenceStatus.MODEL_INCONSISTENT
}

class MetaInferenceEngine(
    private val structuralAnalyzer: StructuralNeighborhoodMomentAnalyzer =
        StructuralNeighborhoodMomentAnalyzer(),
    private val identifiabilityAnalyzer: IdentifiabilityDepthAnalyzer =
        IdentifiabilityDepthAnalyzer(),
    private val supportAnalyzer: MetaSupportRankAnalyzer =
        MetaSupportRankAnalyzer(),
    private val deformationAnalyzer: DeformationOnsetAnalyzer =
        DeformationOnsetAnalyzer(),
) {
    fun infer(
        group: MetaCandidateGroup,
        snapshot: ThoughtMatrixSnapshot,
        budget: MetaInferenceBudget = MetaInferenceBudget(),
        explicitSupports: Collection<MetaSupportVector>? = null,
        realizationGap: PathRealizationGap? = null,
        deformationSeries: DeformationSeries? = null,
    ): MetaInferenceResult {
        val candidateIds = group.candidates.map { it.photonId.value }.distinct().sorted()
        require(candidateIds.size >= 2) {
            "Meta inference requires at least two distinct candidate Photons"
        }
        val support = supportAnalyzer.assess(explicitSupports)
        val deformation = deformationSeries?.let(deformationAnalyzer::assess)

        if (snapshot.conflicts.any { it.photonId.value in candidateIds }) {
            return buildResult(
                group, candidateIds, MetaInferenceStatus.CONFLICT, null, emptyList(),
                support, realizationGap, deformation,
            )
        }
        if (candidateIds.size > budget.maxCandidates) {
            return buildResult(
                group, candidateIds, MetaInferenceStatus.BUDGET_EXHAUSTED, null, emptyList(),
                support, realizationGap, deformation,
            )
        }

        val direct = group.candidates.map {
            LayeredCandidateObservation(
                candidateId = it.photonId.value,
                layers = mapOf(0 to it.comparisonFingerprint),
            )
        }
        val directAssessment = identifiabilityAnalyzer.assess(direct, maximumDepth = 0)
        if (directAssessment.fullyIdentifiable) {
            return buildResult(
                group, candidateIds, MetaInferenceStatus.DISTINCT, directAssessment, emptyList(),
                support, realizationGap, deformation,
            )
        }

        val structural = group.candidates.map {
            structuralAnalyzer.analyze(snapshot, it.photonId, budget.structural)
        }.sortedBy { it.sourcePhotonId.value }
        val byId = structural.associateBy { it.sourcePhotonId.value }
        val layered = group.candidates.map { candidate ->
            val signature = byId.getValue(candidate.photonId.value)
            LayeredCandidateObservation(
                candidateId = candidate.photonId.value,
                layers = buildMap {
                    put(0, candidate.comparisonFingerprint)
                    signature.layers.forEach { put(it.depth, it.fingerprint) }
                },
            )
        }
        val assessment = identifiabilityAnalyzer.assess(
            layered,
            maximumDepth = budget.structural.maxDepth,
        )
        val status = when {
            assessment.fullyIdentifiable -> MetaInferenceStatus.DISTINCT
            realizationGap?.status == RealizationGapStatus.EARLIER_THAN_MODEL ->
                MetaInferenceStatus.MODEL_INCONSISTENT
            structural.any { it.budgetExhausted } ->
                MetaInferenceStatus.BUDGET_EXHAUSTED
            else -> MetaInferenceStatus.INFORMATION_REQUIRED
        }
        return buildResult(
            group, candidateIds, status, assessment, structural,
            support, realizationGap, deformation,
        )
    }

    private fun buildResult(
        group: MetaCandidateGroup,
        candidateIds: List<String>,
        status: MetaInferenceStatus,
        identifiability: LayeredIdentifiabilityAssessment?,
        structural: List<StructuralNeighborhoodSignature>,
        support: MetaSupportRankAssessment,
        realizationGap: PathRealizationGap?,
        deformation: DeformationOnsetAssessment?,
    ): MetaInferenceResult {
        val fingerprint = StableFieldIds.fingerprint(
            "meta-inference-result/v1",
            group.fingerprint,
            status.name,
            identifiability?.fingerprint.orEmpty(),
            support.fingerprint,
            realizationGap?.fingerprint.orEmpty(),
            deformation?.fingerprint.orEmpty(),
            *structural.map { it.fingerprint }.toTypedArray(),
        )
        return MetaInferenceResult(
            sourceGroupFingerprint = group.fingerprint,
            candidateIds = candidateIds,
            status = status,
            identifiability = identifiability,
            structuralSignatures = structural,
            support = support,
            realizationGap = realizationGap,
            deformation = deformation,
            fingerprint = fingerprint,
        )
    }
}
