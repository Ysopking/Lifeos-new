package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.reasoning.ProblemHypothesisSeed
import app.lifeos.core.reasoning.ProblemStateGraph
import app.lifeos.core.reasoning.ReasoningSearchResult
import app.lifeos.core.runtime.level7.EvidenceActionKind

enum class KnowledgeGapKind {
    EXPLICIT_UNKNOWN,
    SEARCH_TRUNCATED,
    NO_COMPLETE_REASONING_STATE,
    OUTCOME_OBSERVATION_MISSING,
    OUTCOME_OBSERVATION_INCOMPLETE,
    OUTCOME_OBSERVATION_UNVERIFIED,
    VERIFIED_FAILURE_PATTERN,
}

data class KnowledgeGapDetectionInput(
    val sourceCycleId: String,
    val problem: ProblemStateGraph,
    val hypothesisSeed: ProblemHypothesisSeed? = null,
    val search: ReasoningSearchResult? = null,
    val expectationModel: OutcomeExpectationModel? = null,
    val predictionErrorReport: PredictionErrorReport? = null,
    val failureLearningResult: FailureLearningResult? = null,
) {
    init {
        require(sourceCycleId.isNotBlank())
        hypothesisSeed?.let { seed ->
            require(seed.problemGraphId == problem.id) {
                "Knowledge-gap hypothesis seed does not belong to the supplied problem graph"
            }
        }
        search?.let { reasoning ->
            val seed = requireNotNull(hypothesisSeed) {
                "Knowledge-gap reasoning search requires its exact hypothesis seed"
            }
            require(reasoning.seedFingerprint == seed.fingerprint) {
                "Knowledge-gap reasoning search does not belong to the supplied hypothesis seed"
            }
        }
        expectationModel?.let { model ->
            require(model.sourceCycleId == sourceCycleId) {
                "Knowledge-gap expectation model belongs to another source cycle"
            }
        }
        predictionErrorReport?.let { report ->
            val model = requireNotNull(expectationModel) {
                "Knowledge-gap prediction error requires its exact expectation model"
            }
            require(report.expectationModelFingerprint == model.fingerprint) {
                "Knowledge-gap prediction error does not belong to the supplied expectation model"
            }
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "knowledge-gap-detection-input/v1",
        sourceCycleId,
        problem.fingerprint,
        hypothesisSeed?.fingerprint.orEmpty(),
        search?.fingerprint.orEmpty(),
        expectationModel?.fingerprint.orEmpty(),
        predictionErrorReport?.fingerprint.orEmpty(),
        failureLearningResult?.fingerprint.orEmpty(),
    )
}

data class KnowledgeGap(
    val id: String,
    val kind: KnowledgeGapKind,
    val sourceCycleId: String,
    val semanticKey: String,
    val rationale: String,
    val sourceFingerprint: String,
    val relatedRefs: List<String>,
    val severity: Double,
    val recommendedEvidenceKinds: List<EvidenceActionKind>,
) {
    init {
        require(id.startsWith(ID_PREFIX))
        require(sourceCycleId.isNotBlank())
        require(semanticKey.isNotBlank())
        require(rationale.isNotBlank())
        require(sourceFingerprint.isNotBlank())
        require(relatedRefs == relatedRefs.distinct().sorted())
        require(severity.isFinite() && severity in 0.0..1.0)
        require(recommendedEvidenceKinds.isNotEmpty())
        require(
            recommendedEvidenceKinds ==
                recommendedEvidenceKinds.distinct().sortedBy { it.name }
        )
        require(id == expectedId())
    }

    val executionAuthority: Boolean
        get() = false

    val currentCycleWorldMutationAllowed: Boolean
        get() = false

    fun fingerprint(): String = knowledgeGapFingerprint(
        kind = kind,
        sourceCycleId = sourceCycleId,
        semanticKey = semanticKey,
        rationale = rationale,
        sourceFingerprint = sourceFingerprint,
        relatedRefs = relatedRefs,
        severity = severity,
        recommendedEvidenceKinds = recommendedEvidenceKinds,
    )

    private fun expectedId(): String = ID_PREFIX + fingerprint()

    companion object {
        const val ID_PREFIX = "knowledge-gap:"

        fun create(
            kind: KnowledgeGapKind,
            sourceCycleId: String,
            semanticKey: String,
            rationale: String,
            sourceFingerprint: String,
            relatedRefs: Collection<String>,
            severity: Double,
            recommendedEvidenceKinds: Collection<EvidenceActionKind>,
        ): KnowledgeGap {
            val refs = relatedRefs.distinct().sorted()
            val kinds = recommendedEvidenceKinds.distinct().sortedBy { it.name }
            val fingerprint = knowledgeGapFingerprint(
                kind = kind,
                sourceCycleId = sourceCycleId,
                semanticKey = semanticKey,
                rationale = rationale,
                sourceFingerprint = sourceFingerprint,
                relatedRefs = refs,
                severity = severity,
                recommendedEvidenceKinds = kinds,
            )
            return KnowledgeGap(
                id = ID_PREFIX + fingerprint,
                kind = kind,
                sourceCycleId = sourceCycleId,
                semanticKey = semanticKey,
                rationale = rationale,
                sourceFingerprint = sourceFingerprint,
                relatedRefs = refs,
                severity = severity,
                recommendedEvidenceKinds = kinds,
            )
        }
    }
}

data class KnowledgeGapDetectionResult(
    val inputFingerprint: String,
    val gaps: List<KnowledgeGap>,
    val fingerprint: String,
) {
    init {
        require(inputFingerprint.isNotBlank())
        require(gaps == gaps.distinctBy { it.id }.sortedWith(gapOrder()))
        require(
            fingerprint == StableFieldIds.fingerprint(
                "knowledge-gap-detection-result/v1",
                inputFingerprint,
                *gaps.map { it.id }.toTypedArray(),
            )
        )
    }

    val hasGaps: Boolean
        get() = gaps.isNotEmpty()

    val executionAuthority: Boolean
        get() = false
}

/**
 * B380 detects epistemic gaps in the problem/reasoning/outcome/learning chain.
 *
 * World-representation/equation extension gaps remain owned by ExtensionGapDetector. B380 emits
 * gap descriptions and recommended evidence kinds only. It does not create EvidenceActionRequest
 * values, execute research/experiments, or mutate the current cycle.
 */
class KnowledgeGapDetector {
    fun detect(input: KnowledgeGapDetectionInput): KnowledgeGapDetectionResult {
        val gaps = buildList {
            input.problem.unknowns
                .sortedBy { it.id.value }
                .forEach { unknown ->
                    add(
                        KnowledgeGap.create(
                            kind = KnowledgeGapKind.EXPLICIT_UNKNOWN,
                            sourceCycleId = input.sourceCycleId,
                            semanticKey = unknown.semanticKey,
                            rationale = "problem-state-explicit-unknown",
                            sourceFingerprint = input.problem.fingerprint,
                            relatedRefs = listOf(unknown.id.value),
                            severity = unknown.confidence.coerceIn(0.0, 1.0),
                            recommendedEvidenceKinds = listOf(
                                EvidenceActionKind.LOCAL_RETRIEVAL,
                                EvidenceActionKind.MEMORY_LOOKUP,
                                EvidenceActionKind.DEEP_SEARCH,
                                EvidenceActionKind.ASK_USER,
                            ),
                        )
                    )
                }

            input.search?.let { search ->
                if (search.truncated) {
                    add(
                        KnowledgeGap.create(
                            kind = KnowledgeGapKind.SEARCH_TRUNCATED,
                            sourceCycleId = input.sourceCycleId,
                            semanticKey = "reasoning-search:truncated",
                            rationale = "bounded-reasoning-search-truncated",
                            sourceFingerprint = search.fingerprint,
                            relatedRefs = listOf(search.seedFingerprint),
                            severity = 0.70,
                            recommendedEvidenceKinds = listOf(
                                EvidenceActionKind.SIMULATION,
                                EvidenceActionKind.ABSTAIN,
                            ),
                        )
                    )
                }
                if (search.completeStates.isEmpty()) {
                    add(
                        KnowledgeGap.create(
                            kind = KnowledgeGapKind.NO_COMPLETE_REASONING_STATE,
                            sourceCycleId = input.sourceCycleId,
                            semanticKey = "reasoning-search:no-complete-state",
                            rationale = "no-compatible-complete-hypothesis-combination",
                            sourceFingerprint = search.fingerprint,
                            relatedRefs = listOf(search.seedFingerprint),
                            severity = 1.0,
                            recommendedEvidenceKinds = listOf(
                                EvidenceActionKind.DEEP_SEARCH,
                                EvidenceActionKind.SAFE_SANDBOX_EXPERIMENT,
                                EvidenceActionKind.ASK_USER,
                                EvidenceActionKind.ABSTAIN,
                            ),
                        )
                    )
                }
            }

            input.predictionErrorReport?.let { report ->
                report.entries.forEach { entry ->
                    when (entry.state) {
                        PredictionErrorState.MISSING_OBSERVATION -> add(
                            outcomeGap(
                                input.sourceCycleId,
                                KnowledgeGapKind.OUTCOME_OBSERVATION_MISSING,
                                "missing-outcome-observation",
                                report.fingerprint,
                                entry,
                                0.90,
                            )
                        )
                        PredictionErrorState.INCOMPLETE_OBSERVATION -> add(
                            outcomeGap(
                                input.sourceCycleId,
                                KnowledgeGapKind.OUTCOME_OBSERVATION_INCOMPLETE,
                                "incomplete-outcome-observation",
                                report.fingerprint,
                                entry,
                                0.80,
                            )
                        )
                        PredictionErrorState.UNVERIFIED_OBSERVATION -> add(
                            outcomeGap(
                                input.sourceCycleId,
                                KnowledgeGapKind.OUTCOME_OBSERVATION_UNVERIFIED,
                                "unverified-outcome-observation",
                                report.fingerprint,
                                entry,
                                0.70,
                            )
                        )
                        PredictionErrorState.WITHIN_EXPECTED_BAND,
                        PredictionErrorState.OUTSIDE_EXPECTED_BAND -> Unit
                    }
                }
            }

            input.failureLearningResult?.let { learning ->
                learning.failures.forEach { failure ->
                    add(
                        KnowledgeGap.create(
                            kind = KnowledgeGapKind.VERIFIED_FAILURE_PATTERN,
                            sourceCycleId = input.sourceCycleId,
                            semanticKey = "failure:" + failure.failureClass,
                            rationale = "verified-failure-requires-new-discriminating-evidence",
                            sourceFingerprint = learning.fingerprint,
                            relatedRefs = listOf(
                                failure.predictionId,
                                failure.evidenceFingerprint,
                            ),
                            severity = 0.85,
                            recommendedEvidenceKinds = listOf(
                                EvidenceActionKind.SIMULATION,
                                EvidenceActionKind.SAFE_SANDBOX_EXPERIMENT,
                            ),
                        )
                    )
                }
            }
        }
            .distinctBy { it.id }
            .sortedWith(gapOrder())

        val inputFingerprint = input.fingerprint()
        val fingerprint = StableFieldIds.fingerprint(
            "knowledge-gap-detection-result/v1",
            inputFingerprint,
            *gaps.map { it.id }.toTypedArray(),
        )
        return KnowledgeGapDetectionResult(
            inputFingerprint = inputFingerprint,
            gaps = gaps,
            fingerprint = fingerprint,
        )
    }

    private fun outcomeGap(
        sourceCycleId: String,
        kind: KnowledgeGapKind,
        rationale: String,
        reportFingerprint: String,
        entry: PredictionErrorEntry,
        severity: Double,
    ): KnowledgeGap = KnowledgeGap.create(
        kind = kind,
        sourceCycleId = sourceCycleId,
        semanticKey = "outcome:" + entry.evidenceActionId,
        rationale = rationale,
        sourceFingerprint = reportFingerprint,
        relatedRefs = listOf(
            entry.evidenceActionId,
            entry.expectationFingerprint,
        ),
        severity = severity,
        recommendedEvidenceKinds = listOf(
            EvidenceActionKind.SOURCE_REFRESH,
            EvidenceActionKind.ASK_USER,
        ),
    )
}

private fun gapOrder(): Comparator<KnowledgeGap> =
    compareByDescending<KnowledgeGap> { it.severity }
        .thenBy { it.kind.name }
        .thenBy { it.semanticKey }
        .thenBy { it.id }

private fun knowledgeGapFingerprint(
    kind: KnowledgeGapKind,
    sourceCycleId: String,
    semanticKey: String,
    rationale: String,
    sourceFingerprint: String,
    relatedRefs: List<String>,
    severity: Double,
    recommendedEvidenceKinds: List<EvidenceActionKind>,
): String = StableFieldIds.fingerprint(
    "knowledge-gap/v1",
    kind.name,
    sourceCycleId,
    semanticKey,
    rationale,
    sourceFingerprint,
    java.lang.Double.toHexString(severity),
    *relatedRefs.sorted().map { "ref:" + it }.toTypedArray(),
    *recommendedEvidenceKinds.sortedBy { it.name }
        .map { "evidence-kind:" + it.name }
        .toTypedArray(),
)
