package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.goal.GoalPlanDefinition
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.goal.GoalPlanTransition
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.core.runtime.level7.StrategyLearningCandidate

data class ProceduralSkillStep(
    val key: String,
    val objective: String,
    val dependencyKeys: List<String>,
    val priority: Int,
) {
    init {
        require(key.isNotBlank())
        require(objective.isNotBlank())
        require(dependencyKeys == dependencyKeys.distinct().sorted())
        require(key !in dependencyKeys)
        require(priority in -1_000..1_000)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "procedural-skill-step/v1",
        key,
        objective,
        priority.toString(),
        *dependencyKeys.map { "depends:" + it }.toTypedArray(),
    )
}

data class ProceduralSkillTrace private constructor(
    val episodeId: LearningEpisodeId,
    val sourceCycleId: String,
    val planId: GoalPlanId,
    val planFingerprint: String,
    val steps: List<ProceduralSkillStep>,
    val completionTransitionFingerprints: List<String>,
    val strategyLearningFingerprint: String?,
    val shapeFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(sourceCycleId.isNotBlank())
        require(planFingerprint.isNotBlank())
        require(steps.isNotEmpty())
        require(steps == steps.sortedBy { it.key })
        require(completionTransitionFingerprints.isNotEmpty())
        require(completionTransitionFingerprints == completionTransitionFingerprints.distinct().sorted())
        strategyLearningFingerprint?.let { require(it.isNotBlank()) }
        require(shapeFingerprint == skillShapeFingerprint(steps))
        require(
            fingerprint == traceFingerprint(
                episodeId = episodeId,
                sourceCycleId = sourceCycleId,
                planId = planId,
                planFingerprint = planFingerprint,
                steps = steps,
                completionTransitionFingerprints = completionTransitionFingerprints,
                strategyLearningFingerprint = strategyLearningFingerprint,
                shapeFingerprint = shapeFingerprint,
            )
        )
    }

    companion object {
        fun from(
            episode: LearningEpisode,
            plan: GoalPlanDefinition,
            transitions: Collection<GoalPlanTransition>,
            strategyLearning: StrategyLearningCandidate? = null,
        ): ProceduralSkillTrace {
            require(
                episode.status == LearningEpisodeStatus.VERIFIED_OUTCOME ||
                    episode.status == LearningEpisodeStatus.VERIFIED_WITH_CAUSAL_CREDIT
            ) {
                "Procedural skill induction requires a verified learning episode"
            }
            require(transitions.isNotEmpty()) {
                "Procedural skill trace requires goal-plan completion transitions"
            }
            require(transitions.all { it.planId == plan.id }) {
                "Procedural skill trace contains transitions from another plan"
            }
            val completedByStep = transitions
                .filter { it.toState == GoalStepState.COMPLETED }
                .groupBy { it.stepId }
            require(plan.steps.all { step -> completedByStep[step.id]?.size == 1 }) {
                "Every goal-plan step must have exactly one COMPLETED transition"
            }
            require(completedByStep.keys == plan.steps.mapTo(linkedSetOf()) { it.id }) {
                "Completion transitions contain unknown goal-plan steps"
            }

            val keyById = plan.steps.associate { it.id to it.key }
            val steps = plan.steps.map { step ->
                ProceduralSkillStep(
                    key = step.key,
                    objective = step.objective,
                    dependencyKeys = step.dependencyIds.map { keyById.getValue(it) }.sorted(),
                    priority = step.priority,
                )
            }.sortedBy { it.key }
            val completionFingerprints = plan.steps
                .map { step -> completedByStep.getValue(step.id).single().contentFingerprint() }
                .sorted()
            val shape = skillShapeFingerprint(steps)
            val strategyFingerprint = strategyLearning?.fingerprint()
            val fingerprint = traceFingerprint(
                episodeId = episode.id,
                sourceCycleId = episode.sourceCycleId,
                planId = plan.id,
                planFingerprint = plan.contentFingerprint(),
                steps = steps,
                completionTransitionFingerprints = completionFingerprints,
                strategyLearningFingerprint = strategyFingerprint,
                shapeFingerprint = shape,
            )
            return ProceduralSkillTrace(
                episodeId = episode.id,
                sourceCycleId = episode.sourceCycleId,
                planId = plan.id,
                planFingerprint = plan.contentFingerprint(),
                steps = steps,
                completionTransitionFingerprints = completionFingerprints,
                strategyLearningFingerprint = strategyFingerprint,
                shapeFingerprint = shape,
                fingerprint = fingerprint,
            )
        }
    }
}

data class ProceduralSkillInductionPolicy(
    val minimumIndependentCycles: Int = 2,
    val maximumSupportTraces: Int = 64,
) {
    init {
        require(minimumIndependentCycles in 2..64)
        require(maximumSupportTraces in minimumIndependentCycles..256)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "procedural-skill-induction-policy/v1",
        minimumIndependentCycles.toString(),
        maximumSupportTraces.toString(),
    )
}

data class ProceduralSkillCandidate(
    val id: String,
    val semanticKey: String,
    val shapeFingerprint: String,
    val steps: List<ProceduralSkillStep>,
    val supportingEpisodeIds: List<LearningEpisodeId>,
    val supportingCycleIds: List<String>,
    val traceFingerprints: List<String>,
    val strategyLearningFingerprints: List<String>,
    val confidence: Double,
    val inductionPolicyFingerprint: String,
) {
    init {
        require(id.startsWith(ID_PREFIX))
        require(semanticKey.isNotBlank())
        require(shapeFingerprint == skillShapeFingerprint(steps))
        require(steps.isNotEmpty())
        require(steps == steps.sortedBy { it.key })
        require(supportingEpisodeIds.size >= 2)
        require(supportingEpisodeIds == supportingEpisodeIds.distinct().sortedBy { it.value })
        require(supportingCycleIds.size >= 2)
        require(supportingCycleIds == supportingCycleIds.distinct().sorted())
        require(traceFingerprints == traceFingerprints.distinct().sorted())
        require(strategyLearningFingerprints == strategyLearningFingerprints.distinct().sorted())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(inductionPolicyFingerprint.isNotBlank())
        require(id == expectedId())
    }

    val executionAuthority: Boolean
        get() = false

    val activationAllowed: Boolean
        get() = false

    val promotionAllowed: Boolean
        get() = false

    fun fingerprint(): String = candidateFingerprint(
        semanticKey = semanticKey,
        shapeFingerprint = shapeFingerprint,
        steps = steps,
        supportingEpisodeIds = supportingEpisodeIds,
        supportingCycleIds = supportingCycleIds,
        traceFingerprints = traceFingerprints,
        strategyLearningFingerprints = strategyLearningFingerprints,
        confidence = confidence,
        inductionPolicyFingerprint = inductionPolicyFingerprint,
    )

    private fun expectedId(): String = ID_PREFIX + fingerprint()

    companion object {
        const val ID_PREFIX = "procedural-skill-candidate:"
    }
}

/**
 * B376 induces reusable procedure candidates from repeated successful GoalPlan shapes.
 *
 * GoalPlan remains the execution-plan authority and StrategyLearningCandidate remains the
 * independently verified strategy-learning carrier. A ProceduralSkillCandidate is inactive and
 * non-executable until later shadow validation/generalization/promotion blocks.
 */
class ProceduralSkillInductionEngine(
    private val policy: ProceduralSkillInductionPolicy = ProceduralSkillInductionPolicy(),
) {
    fun induce(
        traces: Collection<ProceduralSkillTrace>,
        semanticKeysByShape: Map<String, String> = emptyMap(),
    ): List<ProceduralSkillCandidate> {
        require(traces.isNotEmpty()) { "Procedural skill induction requires traces" }
        val canonical = traces
            .distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }
        require(canonical.size == traces.size) {
            "Duplicate procedural skill traces are not allowed"
        }

        return canonical
            .groupBy { it.shapeFingerprint }
            .mapNotNull { (shape, grouped) ->
                val independent = grouped
                    .groupBy { it.sourceCycleId }
                    .map { (_, cycleTraces) -> cycleTraces.minBy { it.fingerprint } }
                    .sortedBy { it.fingerprint }
                if (independent.size < policy.minimumIndependentCycles) {
                    return@mapNotNull null
                }
                val support = independent.take(policy.maximumSupportTraces)
                require(support.map { it.steps }.distinct().size == 1) {
                    "Equal procedural shape fingerprint must imply equal step structure"
                }
                val steps = support.first().steps
                val semanticKey = semanticKeysByShape[shape] ?: "skill:" + shape
                val episodes = support.map { it.episodeId }.distinct().sortedBy { it.value }
                val cycles = support.map { it.sourceCycleId }.distinct().sorted()
                val traceFingerprints = support.map { it.fingerprint }.distinct().sorted()
                val strategyFingerprints = support
                    .mapNotNull { it.strategyLearningFingerprint }
                    .distinct()
                    .sorted()
                val confidence = (
                    cycles.size.toDouble() / (cycles.size.toDouble() + 1.0)
                ).coerceIn(0.0, 1.0)
                val policyFingerprint = policy.fingerprint()
                val fingerprint = candidateFingerprint(
                    semanticKey = semanticKey,
                    shapeFingerprint = shape,
                    steps = steps,
                    supportingEpisodeIds = episodes,
                    supportingCycleIds = cycles,
                    traceFingerprints = traceFingerprints,
                    strategyLearningFingerprints = strategyFingerprints,
                    confidence = confidence,
                    inductionPolicyFingerprint = policyFingerprint,
                )
                ProceduralSkillCandidate(
                    id = ProceduralSkillCandidate.ID_PREFIX + fingerprint,
                    semanticKey = semanticKey,
                    shapeFingerprint = shape,
                    steps = steps,
                    supportingEpisodeIds = episodes,
                    supportingCycleIds = cycles,
                    traceFingerprints = traceFingerprints,
                    strategyLearningFingerprints = strategyFingerprints,
                    confidence = confidence,
                    inductionPolicyFingerprint = policyFingerprint,
                )
            }
            .sortedWith(
                compareByDescending<ProceduralSkillCandidate> { it.confidence }
                    .thenBy { it.id }
            )
    }
}

private fun skillShapeFingerprint(
    steps: List<ProceduralSkillStep>,
): String = StableFieldIds.fingerprint(
    "procedural-skill-shape/v1",
    *steps.sortedBy { it.key }.map { it.fingerprint() }.toTypedArray(),
)

private fun traceFingerprint(
    episodeId: LearningEpisodeId,
    sourceCycleId: String,
    planId: GoalPlanId,
    planFingerprint: String,
    steps: List<ProceduralSkillStep>,
    completionTransitionFingerprints: List<String>,
    strategyLearningFingerprint: String?,
    shapeFingerprint: String,
): String = StableFieldIds.fingerprint(
    "procedural-skill-trace/v1",
    episodeId.value,
    sourceCycleId,
    planId.value,
    planFingerprint,
    shapeFingerprint,
    strategyLearningFingerprint.orEmpty(),
    *steps.sortedBy { it.key }.map { "step:" + it.fingerprint() }.toTypedArray(),
    *completionTransitionFingerprints.sorted()
        .map { "completion:" + it }
        .toTypedArray(),
)

private fun candidateFingerprint(
    semanticKey: String,
    shapeFingerprint: String,
    steps: List<ProceduralSkillStep>,
    supportingEpisodeIds: List<LearningEpisodeId>,
    supportingCycleIds: List<String>,
    traceFingerprints: List<String>,
    strategyLearningFingerprints: List<String>,
    confidence: Double,
    inductionPolicyFingerprint: String,
): String = StableFieldIds.fingerprint(
    "procedural-skill-candidate/v1",
    semanticKey,
    shapeFingerprint,
    java.lang.Double.toHexString(confidence),
    inductionPolicyFingerprint,
    *steps.sortedBy { it.key }.map { "step:" + it.fingerprint() }.toTypedArray(),
    *supportingEpisodeIds.map { "episode:" + it.value }.sorted().toTypedArray(),
    *supportingCycleIds.map { "cycle:" + it }.sorted().toTypedArray(),
    *traceFingerprints.map { "trace:" + it }.sorted().toTypedArray(),
    *strategyLearningFingerprints.map { "strategy:" + it }.sorted().toTypedArray(),
)
