package app.lifeos.core.runtime.goal

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import java.time.Instant

@JvmInline
value class GoalPlanId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid goal plan id prefix" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid goal plan id digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "goal-plan:"
    }
}

@JvmInline
value class GoalStepId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid goal step id prefix" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid goal step id digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "goal-step:"
    }
}

enum class GoalStepState {
    PLANNED,
    READY,
    RUNNING,
    BLOCKED,
    WAITING_EVIDENCE,
    WAITING_CAPABILITY,
    PAUSED,
    COMPLETED,
    CANCELLED,
    FAILED,
    REPLAN_REQUIRED,
}

data class GoalStepSpec(
    val key: String,
    val objective: String,
    val dependencyKeys: Set<String> = emptySet(),
    val deadline: Instant? = null,
    val priority: Int = 0,
) {
    init {
        require(key.isNotBlank()) { "Goal step key must not be blank" }
        require(objective.isNotBlank()) { "Goal step objective must not be blank" }
        require(dependencyKeys.none { it.isBlank() }) { "Goal step dependency key must not be blank" }
        require(key !in dependencyKeys) { "Goal step cannot depend on itself" }
        require(priority in -1_000..1_000) { "Goal step priority is outside bounded range" }
    }
}

data class GoalStepDefinition(
    val id: GoalStepId,
    val key: String,
    val objective: String,
    val dependencyIds: Set<GoalStepId>,
    val deadline: Instant?,
    val priority: Int,
) {
    init {
        require(key.isNotBlank()) { "Goal step key must not be blank" }
        require(objective.isNotBlank()) { "Goal step objective must not be blank" }
        require(id !in dependencyIds) { "Goal step cannot depend on itself" }
        require(priority in -1_000..1_000) { "Goal step priority is outside bounded range" }
    }
}

/** Immutable V7 plan definition. Runtime progress is represented only by append-only transitions. */
data class GoalPlanDefinition(
    val id: GoalPlanId,
    val sourceGoalPhotonId: PhotonId,
    val sourceGoalPhotonRevision: Long,
    val planRevision: Long,
    val steps: List<GoalStepDefinition>,
    val createdAt: Instant,
) {
    init {
        require(sourceGoalPhotonRevision > 0L) { "Goal plan source photon revision must be positive" }
        require(planRevision > 0L) { "Goal plan revision must be positive" }
        require(steps.isNotEmpty()) { "Goal plan requires at least one step" }
        require(steps.map { it.id }.distinct().size == steps.size) { "Goal plan step ids must be unique" }
        require(steps.map { it.key }.distinct().size == steps.size) { "Goal plan step keys must be unique" }
        val ids = steps.mapTo(mutableSetOf()) { it.id }
        require(steps.all { step -> step.dependencyIds.all(ids::contains) }) {
            "Goal plan dependency references an unknown step"
        }
        requireAcyclic(steps)
        require(id == expectedId()) { "Goal plan id/content mismatch" }
    }

    fun contentFingerprint(): String = fingerprint(
        sourceGoalPhotonId = sourceGoalPhotonId,
        sourceGoalPhotonRevision = sourceGoalPhotonRevision,
        planRevision = planRevision,
        steps = steps,
        createdAt = createdAt,
    )

    private fun expectedId(): GoalPlanId = GoalPlanId("${GoalPlanId.PREFIX}${contentFingerprint()}")

    companion object {
        fun create(
            sourceGoalPhotonId: PhotonId,
            sourceGoalPhotonRevision: Long,
            planRevision: Long = 1L,
            stepSpecs: List<GoalStepSpec>,
            createdAt: Instant,
        ): GoalPlanDefinition {
            require(sourceGoalPhotonRevision > 0L)
            require(planRevision > 0L)
            require(stepSpecs.isNotEmpty()) { "Goal plan requires at least one step" }
            require(stepSpecs.map { it.key }.distinct().size == stepSpecs.size) {
                "Goal plan step keys must be unique"
            }
            val byKey = stepSpecs.associateBy { it.key }
            require(stepSpecs.all { spec -> spec.dependencyKeys.all(byKey::containsKey) }) {
                "Goal plan dependency references an unknown step key"
            }
            requireAcyclicSpecs(stepSpecs)

            val idsByKey = stepSpecs.associate { spec ->
                spec.key to GoalStepId(
                    "${GoalStepId.PREFIX}${StableFieldIds.fingerprint(
                        "goal-step/v1",
                        sourceGoalPhotonId.value,
                        sourceGoalPhotonRevision.toString(),
                        planRevision.toString(),
                        spec.key,
                    )}"
                )
            }
            val steps = stepSpecs.map { spec ->
                GoalStepDefinition(
                    id = idsByKey.getValue(spec.key),
                    key = spec.key,
                    objective = spec.objective,
                    dependencyIds = spec.dependencyKeys.mapTo(linkedSetOf()) { idsByKey.getValue(it) },
                    deadline = spec.deadline,
                    priority = spec.priority,
                )
            }.sortedBy { it.id.value }
            val fingerprint = fingerprint(
                sourceGoalPhotonId = sourceGoalPhotonId,
                sourceGoalPhotonRevision = sourceGoalPhotonRevision,
                planRevision = planRevision,
                steps = steps,
                createdAt = createdAt,
            )
            return GoalPlanDefinition(
                id = GoalPlanId("${GoalPlanId.PREFIX}$fingerprint"),
                sourceGoalPhotonId = sourceGoalPhotonId,
                sourceGoalPhotonRevision = sourceGoalPhotonRevision,
                planRevision = planRevision,
                steps = steps,
                createdAt = createdAt,
            )
        }

        private fun fingerprint(
            sourceGoalPhotonId: PhotonId,
            sourceGoalPhotonRevision: Long,
            planRevision: Long,
            steps: List<GoalStepDefinition>,
            createdAt: Instant,
        ): String = StableFieldIds.fingerprint(
            "goal-plan/v1",
            sourceGoalPhotonId.value,
            sourceGoalPhotonRevision.toString(),
            planRevision.toString(),
            createdAt.toString(),
            *steps.sortedBy { it.id.value }.flatMap { step ->
                listOf(
                    "step:${step.id.value}",
                    "key:${step.key}",
                    "objective:${step.objective}",
                    "deadline:${step.deadline?.toString().orEmpty()}",
                    "priority:${step.priority}",
                ) + step.dependencyIds.sortedBy { it.value }.map { dependency ->
                    "dependency:${dependency.value}"
                }
            }.toTypedArray(),
        )

        private fun requireAcyclicSpecs(specs: List<GoalStepSpec>) {
            val dependencies = specs.associate { it.key to it.dependencyKeys }
            val visiting = mutableSetOf<String>()
            val visited = mutableSetOf<String>()
            fun visit(key: String) {
                if (key in visited) return
                require(visiting.add(key)) { "Goal plan dependency graph contains a cycle" }
                dependencies.getValue(key).sorted().forEach(::visit)
                visiting.remove(key)
                visited.add(key)
            }
            dependencies.keys.sorted().forEach(::visit)
        }

        private fun requireAcyclic(steps: List<GoalStepDefinition>) {
            val dependencies = steps.associate { it.id to it.dependencyIds }
            val visiting = mutableSetOf<GoalStepId>()
            val visited = mutableSetOf<GoalStepId>()
            fun visit(id: GoalStepId) {
                if (id in visited) return
                require(visiting.add(id)) { "Goal plan dependency graph contains a cycle" }
                dependencies.getValue(id).sortedBy { it.value }.forEach(::visit)
                visiting.remove(id)
                visited.add(id)
            }
            dependencies.keys.sortedBy { it.value }.forEach(::visit)
        }
    }
}
