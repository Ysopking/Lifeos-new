package app.lifeos.core.runtime.research

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class AutonomousStudyState {
    RESEARCH_PENDING,
    RESOLVED_FOR_REVIEW,
    TERMINAL_UNRESOLVED,
    BLOCKED,
    NON_RESEARCH_OR_DEFERRED,
}

data class AutonomousStudyGoalState(
    val learningGoalId: String,
    val sourceGapId: String,
    val curriculumOrdinal: Int?,
    val curriculumDeferred: Boolean,
    val researchMissionIds: List<RecursiveResearchMissionId>,
    val researchMissionStatuses: List<RecursiveResearchMissionStatus>,
    val priorityScores: Map<RecursiveResearchMissionId, Double>,
    val state: AutonomousStudyState,
    val fingerprint: String,
) {
    init {
        require(learningGoalId.startsWith(AutonomousLearningGoalCandidate.ID_PREFIX))
        require(sourceGapId.isNotBlank())
        require(curriculumOrdinal == null || curriculumOrdinal >= 0)
        require(curriculumDeferred == (curriculumOrdinal == null))
        require(researchMissionIds == researchMissionIds.distinct().sortedBy { it.value })
        require(researchMissionStatuses.size == researchMissionIds.size)
        require(priorityScores.keys.all(researchMissionIds::contains))
        priorityScores.values.forEach {
            require(it.isFinite() && it in 0.0..1.0)
        }
        require(
            fingerprint == studyGoalStateFingerprint(
                learningGoalId = learningGoalId,
                sourceGapId = sourceGapId,
                curriculumOrdinal = curriculumOrdinal,
                curriculumDeferred = curriculumDeferred,
                researchMissionIds = researchMissionIds,
                researchMissionStatuses = researchMissionStatuses,
                priorityScores = priorityScores,
                state = state,
            )
        )
    }

    val executionAuthority: Boolean
        get() = false

    val truthAuthority: Boolean
        get() = false

    val durableGoalAdmissionAuthority: Boolean
        get() = false
}

data class AutonomousStudyPlan(
    val learningGoalPlanFingerprint: String,
    val curriculumPlanFingerprint: String,
    val researchPlanFingerprint: String,
    val informationGainDecisionFingerprint: String?,
    val goals: List<AutonomousStudyGoalState>,
    val fingerprint: String,
) {
    init {
        require(learningGoalPlanFingerprint.matches(SHA_256_REGEX))
        require(curriculumPlanFingerprint.matches(SHA_256_REGEX))
        require(researchPlanFingerprint.matches(SHA_256_REGEX))
        require(
            informationGainDecisionFingerprint == null ||
                informationGainDecisionFingerprint.matches(SHA_256_REGEX)
        )
        require(goals.map { it.learningGoalId }.distinct().size == goals.size)
        require(
            goals == goals.sortedWith(
                compareBy<AutonomousStudyGoalState>(
                    { it.curriculumOrdinal ?: Int.MAX_VALUE },
                    { it.learningGoalId },
                )
            )
        )
        require(
            fingerprint == studyPlanFingerprint(
                learningGoalPlanFingerprint = learningGoalPlanFingerprint,
                curriculumPlanFingerprint = curriculumPlanFingerprint,
                researchPlanFingerprint = researchPlanFingerprint,
                informationGainDecisionFingerprint = informationGainDecisionFingerprint,
                goals = goals,
            )
        )
    }

    val executionAuthority: Boolean
        get() = false

    val networkAuthority: Boolean
        get() = false

    val permissionAuthority: Boolean
        get() = false

    val worldMutationAuthority: Boolean
        get() = false

    val ownerPolicyAuthority: Boolean
        get() = false
}

data class StudyKnowledgeCandidate(
    val id: String,
    val learningGoalId: String,
    val sourceGapId: String,
    val semanticKey: String,
    val researchMissionId: RecursiveResearchMissionId,
    val researchResultFingerprint: String,
    val sourceStudyPlanFingerprint: String,
    val nextCycleValidationRequired: Boolean,
    val fingerprint: String,
) {
    init {
        require(id.startsWith(ID_PREFIX))
        require(learningGoalId.startsWith(AutonomousLearningGoalCandidate.ID_PREFIX))
        require(sourceGapId.isNotBlank())
        require(semanticKey.isNotBlank())
        require(researchResultFingerprint.matches(SHA_256_REGEX))
        require(sourceStudyPlanFingerprint.matches(SHA_256_REGEX))
        require(nextCycleValidationRequired)
        require(
            fingerprint == knowledgeCandidateFingerprint(
                learningGoalId = learningGoalId,
                sourceGapId = sourceGapId,
                semanticKey = semanticKey,
                researchMissionId = researchMissionId,
                researchResultFingerprint = researchResultFingerprint,
                sourceStudyPlanFingerprint = sourceStudyPlanFingerprint,
                nextCycleValidationRequired = nextCycleValidationRequired,
            )
        )
        require(id == ID_PREFIX + fingerprint)
    }

    val truthAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    val worldMutationAuthority: Boolean
        get() = false

    val promotionAuthority: Boolean
        get() = false

    companion object {
        const val ID_PREFIX = "study-knowledge-candidate:"
    }
}

/**
 * B388 composes existing learning-goal, curriculum, recursive-research and information-gain
 * contracts into a bounded study state. It does not execute DeepSearch, perform network access,
 * admit durable goals, mutate the current world, or turn a RESOLVED research result into truth.
 */
class AutonomousStudyLoop {
    fun compose(
        learningGoals: AutonomousLearningGoalPlan,
        curriculum: SelfCurriculumPlan,
        research: RecursiveResearchPlan,
        informationGain: ResearchInformationGainDecision? = null,
    ): AutonomousStudyPlan {
        val goalsById = learningGoals.candidates.associateBy { it.id }
        require(goalsById.size == learningGoals.candidates.size)

        val curriculumGoalIds =
            (curriculum.orderedGoalIds + curriculum.deferredGoalIds).distinct()
        require(curriculumGoalIds.toSet() == goalsById.keys) {
            "Study curriculum does not cover the exact B381 learning-goal set"
        }

        val goalByGap = learningGoals.candidates.associateBy { it.sourceGapId }
        require(goalByGap.size == learningGoals.candidates.size) {
            "B388 requires one exact B381 learning goal per source gap"
        }
        require(learningGoals.candidates.all { it.sourceCycleId == research.sourceCycleId }) {
            "Study research plan belongs to another learning cycle"
        }

        val knownGapIds = goalByGap.keys
        require(research.missions.all { it.rootGapId in knownGapIds }) {
            "Study research mission references a gap outside the B381 plan"
        }
        require(research.nonResearchGapIds.all { it in knownGapIds }) {
            "Study non-research gap references a gap outside the B381 plan"
        }
        require(research.budgetDeferredGapIds.all { it in knownGapIds }) {
            "Study deferred research gap references a gap outside the B381 plan"
        }

        informationGain?.let { decision ->
            require(decision.planFingerprint == research.fingerprint) {
                "Information-gain decision belongs to another B399 plan revision"
            }
        }

        val priorityByMission = informationGain
            ?.ranked
            ?.associate { it.missionId to it.score }
            .orEmpty()
        val missionsByGap = research.missions
            .groupBy { it.rootGapId }
            .mapValues { (_, missions) -> missions.sortedBy { it.id.value } }
        val ordinalByGoal = curriculum.orderedGoalIds
            .withIndex()
            .associate { it.value to it.index }
        val deferred = curriculum.deferredGoalIds.toSet()

        val goalStates = learningGoals.candidates
            .map { goal ->
                val missions = missionsByGap[goal.sourceGapId].orEmpty()
                val missionIds = missions.map { it.id }.distinct().sortedBy { it.value }
                val statusById = missions.associate { it.id to it.status }
                val statuses = missionIds.map { id -> requireNotNull(statusById[id]) }
                val state = deriveState(
                    sourceGapId = goal.sourceGapId,
                    missions = missions,
                    research = research,
                )
                val priorities = priorityByMission
                    .filterKeys(missionIds::contains)
                    .toSortedMap(compareBy { it.value })
                val ordinal = ordinalByGoal[goal.id]
                val isDeferred = goal.id in deferred
                require((ordinal == null) == isDeferred) {
                    "Curriculum ready/deferred identity is inconsistent"
                }
                AutonomousStudyGoalState(
                    learningGoalId = goal.id,
                    sourceGapId = goal.sourceGapId,
                    curriculumOrdinal = ordinal,
                    curriculumDeferred = isDeferred,
                    researchMissionIds = missionIds,
                    researchMissionStatuses = statuses,
                    priorityScores = priorities,
                    state = state,
                    fingerprint = studyGoalStateFingerprint(
                        learningGoalId = goal.id,
                        sourceGapId = goal.sourceGapId,
                        curriculumOrdinal = ordinal,
                        curriculumDeferred = isDeferred,
                        researchMissionIds = missionIds,
                        researchMissionStatuses = statuses,
                        priorityScores = priorities,
                        state = state,
                    ),
                )
            }
            .sortedWith(
                compareBy<AutonomousStudyGoalState>(
                    { it.curriculumOrdinal ?: Int.MAX_VALUE },
                    { it.learningGoalId },
                )
            )

        return AutonomousStudyPlan(
            learningGoalPlanFingerprint = learningGoals.fingerprint,
            curriculumPlanFingerprint = curriculum.fingerprint,
            researchPlanFingerprint = research.fingerprint,
            informationGainDecisionFingerprint = informationGain?.fingerprint,
            goals = goalStates,
            fingerprint = studyPlanFingerprint(
                learningGoalPlanFingerprint = learningGoals.fingerprint,
                curriculumPlanFingerprint = curriculum.fingerprint,
                researchPlanFingerprint = research.fingerprint,
                informationGainDecisionFingerprint = informationGain?.fingerprint,
                goals = goalStates,
            ),
        )
    }

    fun consolidationCandidates(
        plan: AutonomousStudyPlan,
        learningGoals: AutonomousLearningGoalPlan,
        research: RecursiveResearchPlan,
    ): List<StudyKnowledgeCandidate> {
        require(plan.learningGoalPlanFingerprint == learningGoals.fingerprint) {
            "Study plan does not match supplied B381 learning-goal plan"
        }
        require(plan.researchPlanFingerprint == research.fingerprint) {
            "Study plan does not match supplied B399 research plan"
        }

        val goalByGap = learningGoals.candidates.associateBy { it.sourceGapId }
        val candidates = research.missions
            .asSequence()
            .filter { it.status == RecursiveResearchMissionStatus.RESOLVED }
            .map { mission ->
                val resultFingerprint = requireNotNull(mission.resultFingerprint) {
                    "Resolved research mission lacks result fingerprint"
                }
                val goal = requireNotNull(goalByGap[mission.rootGapId]) {
                    "Resolved research mission has no B381 learning goal"
                }
                val fingerprint = knowledgeCandidateFingerprint(
                    learningGoalId = goal.id,
                    sourceGapId = goal.sourceGapId,
                    semanticKey = goal.semanticKey,
                    researchMissionId = mission.id,
                    researchResultFingerprint = resultFingerprint,
                    sourceStudyPlanFingerprint = plan.fingerprint,
                    nextCycleValidationRequired = true,
                )
                StudyKnowledgeCandidate(
                    id = StudyKnowledgeCandidate.ID_PREFIX + fingerprint,
                    learningGoalId = goal.id,
                    sourceGapId = goal.sourceGapId,
                    semanticKey = goal.semanticKey,
                    researchMissionId = mission.id,
                    researchResultFingerprint = resultFingerprint,
                    sourceStudyPlanFingerprint = plan.fingerprint,
                    nextCycleValidationRequired = true,
                    fingerprint = fingerprint,
                )
            }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .toList()

        return candidates
    }

    private fun deriveState(
        sourceGapId: String,
        missions: List<RecursiveResearchMission>,
        research: RecursiveResearchPlan,
    ): AutonomousStudyState {
        if (missions.any { it.status == RecursiveResearchMissionStatus.RESOLVED }) {
            return AutonomousStudyState.RESOLVED_FOR_REVIEW
        }
        if (
            missions.any {
                it.status == RecursiveResearchMissionStatus.PLANNED ||
                    it.status == RecursiveResearchMissionStatus.FOLLOW_UP_PLANNED
            }
        ) {
            return AutonomousStudyState.RESEARCH_PENDING
        }
        if (missions.isNotEmpty() && missions.all { it.status == RecursiveResearchMissionStatus.BLOCKED }) {
            return AutonomousStudyState.BLOCKED
        }
        if (
            missions.isNotEmpty() &&
                missions.all {
                    it.status == RecursiveResearchMissionStatus.TERMINAL_UNRESOLVED ||
                        it.status == RecursiveResearchMissionStatus.BLOCKED
                }
        ) {
            return AutonomousStudyState.TERMINAL_UNRESOLVED
        }
        require(
            sourceGapId in research.nonResearchGapIds ||
                sourceGapId in research.budgetDeferredGapIds ||
                missions.isEmpty()
        )
        return AutonomousStudyState.NON_RESEARCH_OR_DEFERRED
    }
}

private fun studyGoalStateFingerprint(
    learningGoalId: String,
    sourceGapId: String,
    curriculumOrdinal: Int?,
    curriculumDeferred: Boolean,
    researchMissionIds: List<RecursiveResearchMissionId>,
    researchMissionStatuses: List<RecursiveResearchMissionStatus>,
    priorityScores: Map<RecursiveResearchMissionId, Double>,
    state: AutonomousStudyState,
): String = studyFingerprint(
    "autonomous-study-goal-state/v1",
    learningGoalId,
    sourceGapId,
    curriculumOrdinal?.toString().orEmpty(),
    curriculumDeferred.toString(),
    researchMissionIds.joinToString("\u001f") { it.value },
    researchMissionStatuses.joinToString("\u001f") { it.name },
    priorityScores.toSortedMap(compareBy { it.value })
        .entries
        .joinToString("\u001f") { (id, score) ->
            id.value + "=" + java.lang.Double.toHexString(score)
        },
    state.name,
)

private fun studyPlanFingerprint(
    learningGoalPlanFingerprint: String,
    curriculumPlanFingerprint: String,
    researchPlanFingerprint: String,
    informationGainDecisionFingerprint: String?,
    goals: List<AutonomousStudyGoalState>,
): String = studyFingerprint(
    "autonomous-study-plan/v1",
    learningGoalPlanFingerprint,
    curriculumPlanFingerprint,
    researchPlanFingerprint,
    informationGainDecisionFingerprint.orEmpty(),
    *goals.map { it.fingerprint }.toTypedArray(),
)

private fun knowledgeCandidateFingerprint(
    learningGoalId: String,
    sourceGapId: String,
    semanticKey: String,
    researchMissionId: RecursiveResearchMissionId,
    researchResultFingerprint: String,
    sourceStudyPlanFingerprint: String,
    nextCycleValidationRequired: Boolean,
): String = studyFingerprint(
    "study-knowledge-candidate/v1",
    learningGoalId,
    sourceGapId,
    semanticKey,
    researchMissionId.value,
    researchResultFingerprint,
    sourceStudyPlanFingerprint,
    nextCycleValidationRequired.toString(),
)

private fun studyFingerprint(
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

private val SHA_256_REGEX = Regex("[0-9a-f]{64}")
