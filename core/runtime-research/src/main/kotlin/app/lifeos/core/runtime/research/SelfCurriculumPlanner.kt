package app.lifeos.core.runtime.research

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class LearningCurriculumCandidate(
    val goal: AutonomousLearningGoalCandidate,
    val prerequisiteGoalIds: List<String> = emptyList(),
    val explicitInformationGain: Double? = null,
) {
    init {
        require(
            prerequisiteGoalIds ==
                prerequisiteGoalIds.distinct().sorted()
        )
        require(goal.id !in prerequisiteGoalIds)
        explicitInformationGain?.let {
            require(it.isFinite() && it in 0.0..1.0)
        }
    }

    fun fingerprint(): String = curriculumFingerprint(
        "learning-curriculum-candidate/v1",
        goal.id,
        explicitInformationGain?.let(java.lang.Double::toHexString).orEmpty(),
        *prerequisiteGoalIds.toTypedArray(),
    )
}

data class SelfCurriculumPlan(
    val sourceFingerprint: String,
    val orderedGoalIds: List<String>,
    val blockedAtStart: Map<String, List<String>>,
    val deferredGoalIds: List<String>,
    val maxItems: Int,
    val truncated: Boolean,
    val fingerprint: String,
) {
    init {
        require(sourceFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(maxItems in 1..1_024)
        require(orderedGoalIds == orderedGoalIds.distinct())
        require(deferredGoalIds == deferredGoalIds.distinct())
        require(orderedGoalIds.none(deferredGoalIds::contains))
        require(blockedAtStart.keys.all { it in orderedGoalIds || it in deferredGoalIds })
        require(blockedAtStart.values.all { deps -> deps == deps.distinct().sorted() })
        require(truncated == deferredGoalIds.isNotEmpty())
        require(
            fingerprint == curriculumFingerprint(
                "self-curriculum-plan/v1",
                sourceFingerprint,
                maxItems.toString(),
                truncated.toString(),
                *orderedGoalIds.map { "ordered:" + it }.toTypedArray(),
                *deferredGoalIds.map { "deferred:" + it }.toTypedArray(),
                *blockedAtStart.entries.sortedBy { it.key }.flatMap { (goal, deps) ->
                    listOf("blocked:$goal") + deps.map { "dependency:$it" }
                }.toTypedArray(),
            )
        )
    }

    val executionAuthority: Boolean
        get() = false

    val durableGoalAdmissionAuthority: Boolean
        get() = false

    val ownerUtilityAuthority: Boolean
        get() = false
}

/**
 * B382 deterministically orders B381 learning-goal candidates without admitting or executing them.
 *
 * Dependency readiness is absolute. Ranking signals apply only within the currently-ready frontier.
 * Owner utility is deliberately absent until B386.
 */
class SelfCurriculumPlanner {
    fun plan(
        candidates: Collection<LearningCurriculumCandidate>,
        maxItems: Int = DEFAULT_MAX_ITEMS,
    ): SelfCurriculumPlan {
        require(maxItems in 1..1_024)
        val canonical = candidates
            .distinctBy { it.goal.id }
            .sortedBy { it.goal.id }
        require(canonical.size == candidates.map { it.goal.id }.distinct().size) {
            "Conflicting duplicate learning-goal ids are not allowed"
        }

        val byId = canonical.associateBy { it.goal.id }
        canonical.forEach { candidate ->
            require(candidate.prerequisiteGoalIds.all(byId::containsKey)) {
                "Learning curriculum prerequisite references unknown goal"
            }
        }
        requireAcyclic(canonical)

        val blockedAtStart = canonical
            .filter { it.prerequisiteGoalIds.isNotEmpty() }
            .associate { it.goal.id to it.prerequisiteGoalIds }

        val remaining = canonical.associateByTo(linkedMapOf()) { it.goal.id }
        val completed = linkedSetOf<String>()
        val fullOrder = mutableListOf<String>()

        while (remaining.isNotEmpty()) {
            val ready = remaining.values
                .filter { candidate ->
                    candidate.prerequisiteGoalIds.all(completed::contains)
                }
                .sortedWith(frontierOrder())
            require(ready.isNotEmpty()) {
                "Learning curriculum dependency graph became non-progressing"
            }
            val next = ready.first()
            remaining.remove(next.goal.id)
            completed += next.goal.id
            fullOrder += next.goal.id
        }

        val ordered = fullOrder.take(maxItems)
        val deferred = fullOrder.drop(maxItems)
        val sourceFingerprint = curriculumFingerprint(
            "self-curriculum-source/v1",
            *canonical.map { it.fingerprint() }.toTypedArray(),
        )
        val fingerprint = curriculumFingerprint(
            "self-curriculum-plan/v1",
            sourceFingerprint,
            maxItems.toString(),
            deferred.isNotEmpty().toString(),
            *ordered.map { "ordered:" + it }.toTypedArray(),
            *deferred.map { "deferred:" + it }.toTypedArray(),
            *blockedAtStart.entries.sortedBy { it.key }.flatMap { (goal, deps) ->
                listOf("blocked:$goal") + deps.map { "dependency:$it" }
            }.toTypedArray(),
        )
        return SelfCurriculumPlan(
            sourceFingerprint = sourceFingerprint,
            orderedGoalIds = ordered,
            blockedAtStart = blockedAtStart,
            deferredGoalIds = deferred,
            maxItems = maxItems,
            truncated = deferred.isNotEmpty(),
            fingerprint = fingerprint,
        )
    }

    private fun frontierOrder(): Comparator<LearningCurriculumCandidate> =
        compareByDescending<LearningCurriculumCandidate> { it.goal.severity }
            .thenByDescending { it.explicitInformationGain ?: -1.0 }
            .thenBy { it.goal.estimatedEffort }
            .thenBy { it.goal.estimatedDifficulty }
            .thenBy { it.goal.id }

    private fun requireAcyclic(candidates: List<LearningCurriculumCandidate>) {
        val dependencies = candidates.associate { it.goal.id to it.prerequisiteGoalIds }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(id: String) {
            if (id in visited) return
            require(visiting.add(id)) {
                "Learning curriculum dependency graph contains a cycle"
            }
            dependencies.getValue(id).forEach(::visit)
            visiting.remove(id)
            visited += id
        }

        dependencies.keys.sorted().forEach(::visit)
    }

    companion object {
        const val DEFAULT_MAX_ITEMS = 256
    }
}

private fun curriculumFingerprint(
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
