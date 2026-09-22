package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.level7.EvidenceActionKind
import app.lifeos.core.runtime.reasoning.KnowledgeGap
import app.lifeos.core.runtime.reasoning.KnowledgeGapKind
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class LearningGoalCriterionKind {
    RESOLVE_UNKNOWN,
    REDUCE_SEARCH_UNCERTAINTY,
    COMPLETE_REASONING_STATE,
    OBSERVE_OUTCOME,
    VERIFY_OUTCOME,
    DISCRIMINATE_FAILURE,
}

data class LearningGoalSuccessCriterion(
    val kind: LearningGoalCriterionKind,
    val semanticKey: String,
    val description: String,
) {
    init {
        require(semanticKey.isNotBlank())
        require(description.isNotBlank())
    }

    fun fingerprint(): String = learningGoalFingerprint(
        "learning-goal-success-criterion/v1",
        kind.name,
        semanticKey,
        description,
    )
}

data class AutonomousLearningGoalCandidate(
    val id: String,
    val sourceGapId: String,
    val sourceCycleId: String,
    val semanticKey: String,
    val objective: String,
    val severity: Double,
    val allowedEvidenceKinds: List<EvidenceActionKind>,
    val successCriteria: List<LearningGoalSuccessCriterion>,
    val maxEvidenceAttempts: Int,
    val abstentionAllowed: Boolean,
    val estimatedValueProxy: Double,
    val estimatedEffort: Double,
    val estimatedDifficulty: Double,
    val fingerprint: String,
) {
    init {
        require(id.startsWith(ID_PREFIX))
        require(sourceGapId.startsWith(KnowledgeGap.ID_PREFIX))
        require(sourceCycleId.isNotBlank())
        require(semanticKey.isNotBlank())
        require(objective.isNotBlank())
        require(severity.isFinite() && severity in 0.0..1.0)
        require(allowedEvidenceKinds.isNotEmpty())
        require(
            allowedEvidenceKinds ==
                allowedEvidenceKinds.distinct().sortedBy { it.name }
        )
        require(successCriteria.isNotEmpty())
        require(
            successCriteria ==
                successCriteria.distinctBy { it.fingerprint() }
                    .sortedBy { it.fingerprint() }
        )
        require(maxEvidenceAttempts in 1..64)
        require(estimatedValueProxy.isFinite() && estimatedValueProxy in 0.0..1.0)
        require(estimatedEffort.isFinite() && estimatedEffort in 0.0..1.0)
        require(estimatedDifficulty.isFinite() && estimatedDifficulty in 0.0..1.0)
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        require(
            fingerprint == autonomousLearningGoalCandidateFingerprint(
                sourceGapId = sourceGapId,
                sourceCycleId = sourceCycleId,
                semanticKey = semanticKey,
                objective = objective,
                severity = severity,
                allowedEvidenceKinds = allowedEvidenceKinds,
                successCriteria = successCriteria,
                maxEvidenceAttempts = maxEvidenceAttempts,
                abstentionAllowed = abstentionAllowed,
                estimatedValueProxy = estimatedValueProxy,
                estimatedEffort = estimatedEffort,
                estimatedDifficulty = estimatedDifficulty,
            )
        ) { "Learning-goal candidate fingerprint/content mismatch" }
        require(id == ID_PREFIX + fingerprint)
    }

    val executionAuthority: Boolean
        get() = false

    val durableGoalAdmissionAuthority: Boolean
        get() = false

    val worldMutationAuthority: Boolean
        get() = false

    companion object {
        const val ID_PREFIX = "learning-goal:"
    }
}

data class AutonomousLearningGoalPlan(
    val sourceFingerprint: String,
    val candidates: List<AutonomousLearningGoalCandidate>,
    val truncated: Boolean,
    val maxGoals: Int,
    val fingerprint: String,
) {
    init {
        require(sourceFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(maxGoals in 1..1_024)
        require(candidates.size <= maxGoals)
        require(candidates == candidates.distinctBy { it.id }.sortedWith(candidateOrder()))
        require(
            fingerprint == learningGoalFingerprint(
                "autonomous-learning-goal-plan/v1",
                sourceFingerprint,
                maxGoals.toString(),
                truncated.toString(),
                *candidates.map { it.id }.toTypedArray(),
            )
        )
    }

    val executionAuthority: Boolean
        get() = false

    val durableGoalAdmissionAuthority: Boolean
        get() = false
}

/**
 * B381 maps exact B380 epistemic gaps into bounded, non-authoritative learning-goal candidates.
 *
 * This planner does not create V7 durable goal plans, schedule work, execute research, widen B380's
 * recommended evidence kinds, or mutate the current reasoning/world state.
 */
class AutonomousLearningGoalPlanner {
    fun plan(
        gaps: Collection<KnowledgeGap>,
        maxGoals: Int = DEFAULT_MAX_GOALS,
    ): AutonomousLearningGoalPlan {
        require(maxGoals in 1..1_024)
        val canonicalGaps = gaps
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<KnowledgeGap> { it.severity }
                    .thenBy { it.kind.name }
                    .thenBy { it.semanticKey }
                    .thenBy { it.id }
            )
        val sourceFingerprint = learningGoalFingerprint(
            "autonomous-learning-goal-source/v1",
            *canonicalGaps.map { it.id }.toTypedArray(),
        )
        val selected = canonicalGaps.take(maxGoals)
        val candidates = selected.map(::candidateFor).sortedWith(candidateOrder())
        val truncated = canonicalGaps.size > selected.size
        return AutonomousLearningGoalPlan(
            sourceFingerprint = sourceFingerprint,
            candidates = candidates,
            truncated = truncated,
            maxGoals = maxGoals,
            fingerprint = learningGoalFingerprint(
                "autonomous-learning-goal-plan/v1",
                sourceFingerprint,
                maxGoals.toString(),
                truncated.toString(),
                *candidates.map { it.id }.toTypedArray(),
            ),
        )
    }

    private fun candidateFor(gap: KnowledgeGap): AutonomousLearningGoalCandidate {
        val profile = profileFor(gap)
        val allowed = gap.recommendedEvidenceKinds.distinct().sortedBy { it.name }
        require(profile.requiredEvidenceKinds.all(allowed::contains)) {
            "B381 cannot widen B380 evidence recommendations"
        }
        val criteria = profile.criteria(gap)
            .distinctBy { it.fingerprint() }
            .sortedBy { it.fingerprint() }
        val valueProxy = gap.severity
        val objective = profile.objectivePrefix + gap.semanticKey
        val fingerprint = autonomousLearningGoalCandidateFingerprint(
            sourceGapId = gap.id,
            sourceCycleId = gap.sourceCycleId,
            semanticKey = gap.semanticKey,
            objective = objective,
            severity = gap.severity,
            allowedEvidenceKinds = allowed,
            successCriteria = criteria,
            maxEvidenceAttempts = profile.maxEvidenceAttempts,
            abstentionAllowed = profile.abstentionAllowed,
            estimatedValueProxy = valueProxy,
            estimatedEffort = profile.estimatedEffort,
            estimatedDifficulty = profile.estimatedDifficulty,
        )
        return AutonomousLearningGoalCandidate(
            id = AutonomousLearningGoalCandidate.ID_PREFIX + fingerprint,
            sourceGapId = gap.id,
            sourceCycleId = gap.sourceCycleId,
            semanticKey = gap.semanticKey,
            objective = objective,
            severity = gap.severity,
            allowedEvidenceKinds = allowed,
            successCriteria = criteria,
            maxEvidenceAttempts = profile.maxEvidenceAttempts,
            abstentionAllowed = profile.abstentionAllowed,
            estimatedValueProxy = valueProxy,
            estimatedEffort = profile.estimatedEffort,
            estimatedDifficulty = profile.estimatedDifficulty,
            fingerprint = fingerprint,
        )
    }

    private fun profileFor(gap: KnowledgeGap): GoalProfile = when (gap.kind) {
        KnowledgeGapKind.EXPLICIT_UNKNOWN -> GoalProfile(
            objectivePrefix = "Resolve explicit unknown: ",
            requiredEvidenceKinds = emptySet(),
            maxEvidenceAttempts = 8,
            abstentionAllowed = EvidenceActionKind.ABSTAIN in gap.recommendedEvidenceKinds,
            estimatedEffort = 0.35,
            estimatedDifficulty = 0.35,
            criteria = { g ->
                listOf(
                    criterion(
                        LearningGoalCriterionKind.RESOLVE_UNKNOWN,
                        g,
                        "Acquire independently addressable evidence that resolves or explicitly leaves this unknown unresolved.",
                    )
                )
            },
        )

        KnowledgeGapKind.SEARCH_TRUNCATED -> GoalProfile(
            objectivePrefix = "Reduce bounded-search uncertainty: ",
            requiredEvidenceKinds = setOf(EvidenceActionKind.SIMULATION),
            maxEvidenceAttempts = 4,
            abstentionAllowed = EvidenceActionKind.ABSTAIN in gap.recommendedEvidenceKinds,
            estimatedEffort = 0.45,
            estimatedDifficulty = 0.55,
            criteria = { g ->
                listOf(
                    criterion(
                        LearningGoalCriterionKind.REDUCE_SEARCH_UNCERTAINTY,
                        g,
                        "Obtain discriminating evidence or preserve an explicit bounded abstention.",
                    )
                )
            },
        )

        KnowledgeGapKind.NO_COMPLETE_REASONING_STATE -> GoalProfile(
            objectivePrefix = "Find evidence for a complete reasoning state: ",
            requiredEvidenceKinds = emptySet(),
            maxEvidenceAttempts = 8,
            abstentionAllowed = EvidenceActionKind.ABSTAIN in gap.recommendedEvidenceKinds,
            estimatedEffort = 0.70,
            estimatedDifficulty = 0.75,
            criteria = { g ->
                listOf(
                    criterion(
                        LearningGoalCriterionKind.COMPLETE_REASONING_STATE,
                        g,
                        "Produce new verified evidence capable of changing reasoning completeness, or retain explicit abstention.",
                    )
                )
            },
        )

        KnowledgeGapKind.OUTCOME_OBSERVATION_MISSING,
        KnowledgeGapKind.OUTCOME_OBSERVATION_INCOMPLETE -> GoalProfile(
            objectivePrefix = "Close outcome-observation gap: ",
            requiredEvidenceKinds = setOf(EvidenceActionKind.SOURCE_REFRESH),
            maxEvidenceAttempts = 4,
            abstentionAllowed = false,
            estimatedEffort = 0.30,
            estimatedDifficulty = 0.25,
            criteria = { g ->
                listOf(
                    criterion(
                        LearningGoalCriterionKind.OBSERVE_OUTCOME,
                        g,
                        "Obtain a bounded observation for the exact referenced outcome lineage.",
                    )
                )
            },
        )

        KnowledgeGapKind.OUTCOME_OBSERVATION_UNVERIFIED -> GoalProfile(
            objectivePrefix = "Verify outcome observation: ",
            requiredEvidenceKinds = setOf(EvidenceActionKind.SOURCE_REFRESH),
            maxEvidenceAttempts = 4,
            abstentionAllowed = false,
            estimatedEffort = 0.35,
            estimatedDifficulty = 0.35,
            criteria = { g ->
                listOf(
                    criterion(
                        LearningGoalCriterionKind.VERIFY_OUTCOME,
                        g,
                        "Obtain verification for the exact observation without treating the prior unverified observation as truth.",
                    )
                )
            },
        )

        KnowledgeGapKind.VERIFIED_FAILURE_PATTERN -> GoalProfile(
            objectivePrefix = "Discriminate verified failure cause: ",
            requiredEvidenceKinds = setOf(EvidenceActionKind.SIMULATION),
            maxEvidenceAttempts = 6,
            abstentionAllowed = false,
            estimatedEffort = 0.60,
            estimatedDifficulty = 0.70,
            criteria = { g ->
                listOf(
                    criterion(
                        LearningGoalCriterionKind.DISCRIMINATE_FAILURE,
                        g,
                        "Obtain discriminating simulation or sandbox evidence for the verified failure pattern.",
                    )
                )
            },
        )
    }

    private fun criterion(
        kind: LearningGoalCriterionKind,
        gap: KnowledgeGap,
        description: String,
    ) = LearningGoalSuccessCriterion(
        kind = kind,
        semanticKey = gap.semanticKey,
        description = description,
    )

    private data class GoalProfile(
        val objectivePrefix: String,
        val requiredEvidenceKinds: Set<EvidenceActionKind>,
        val maxEvidenceAttempts: Int,
        val abstentionAllowed: Boolean,
        val estimatedEffort: Double,
        val estimatedDifficulty: Double,
        val criteria: (KnowledgeGap) -> List<LearningGoalSuccessCriterion>,
    )

    companion object {
        const val DEFAULT_MAX_GOALS = 256
    }
}

private fun autonomousLearningGoalCandidateFingerprint(
    sourceGapId: String,
    sourceCycleId: String,
    semanticKey: String,
    objective: String,
    severity: Double,
    allowedEvidenceKinds: List<EvidenceActionKind>,
    successCriteria: List<LearningGoalSuccessCriterion>,
    maxEvidenceAttempts: Int,
    abstentionAllowed: Boolean,
    estimatedValueProxy: Double,
    estimatedEffort: Double,
    estimatedDifficulty: Double,
): String = learningGoalFingerprint(
    "autonomous-learning-goal-candidate/v1",
    sourceGapId,
    sourceCycleId,
    semanticKey,
    objective,
    java.lang.Double.toHexString(severity),
    *allowedEvidenceKinds.map { "evidence:" + it.name }.toTypedArray(),
    *successCriteria.map { "criterion:" + it.fingerprint() }.toTypedArray(),
    maxEvidenceAttempts.toString(),
    abstentionAllowed.toString(),
    java.lang.Double.toHexString(estimatedValueProxy),
    java.lang.Double.toHexString(estimatedEffort),
    java.lang.Double.toHexString(estimatedDifficulty),
)

private fun candidateOrder(): Comparator<AutonomousLearningGoalCandidate> =
    compareByDescending<AutonomousLearningGoalCandidate> { it.severity }
        .thenBy { it.semanticKey }
        .thenBy { it.id }

private fun learningGoalFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            )
        )
        digest.update(bytes)
    }
    update(domain)
    parts.forEach(::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
