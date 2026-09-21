package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.deepsearch.DeepSearchBranch
import app.lifeos.core.runtime.deepsearch.DeepSearchBudget
import app.lifeos.core.runtime.deepsearch.DeepSearchRequest
import app.lifeos.core.runtime.deepsearch.DeepSearchResult
import app.lifeos.core.runtime.deepsearch.DeepSearchStatus
import app.lifeos.core.runtime.level7.EvidenceActionKind
import app.lifeos.core.runtime.reasoning.KnowledgeGap
import app.lifeos.core.runtime.reasoning.KnowledgeGapDetectionResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

@JvmInline
value class RecursiveResearchMissionId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "research-mission:"
    }
}

enum class RecursiveResearchMissionStatus {
    PLANNED,
    RESOLVED,
    FOLLOW_UP_PLANNED,
    TERMINAL_UNRESOLVED,
    BLOCKED,
}

data class RecursiveResearchBudget(
    val maxDepth: Int = 3,
    val maxMissions: Int = 16,
    val perMissionBudget: DeepSearchBudget = DeepSearchBudget(
        maxDepth = 4,
        maxBreadth = 8,
        maxWorkUnits = 64,
        maxElapsed = Duration.ofSeconds(10),
    ),
) {
    init {
        require(maxDepth in 0..16)
        require(maxMissions in 1..1_024)
    }

    fun fingerprint(): String = researchFingerprint(
        "recursive-research-budget/v1",
        maxDepth.toString(),
        maxMissions.toString(),
        perMissionBudget.fingerprint(),
    )
}

data class RecursiveResearchMission(
    val id: RecursiveResearchMissionId,
    val rootGapId: String,
    val rootGapSemanticKey: String,
    val rootGapSourceFingerprint: String,
    val parentMissionId: RecursiveResearchMissionId?,
    val depth: Int,
    val request: DeepSearchRequest,
    val status: RecursiveResearchMissionStatus,
    val resultFingerprint: String?,
    val resultStatus: DeepSearchStatus?,
) {
    init {
        require(rootGapId.startsWith(KnowledgeGap.ID_PREFIX))
        require(rootGapSemanticKey.isNotBlank())
        require(rootGapSourceFingerprint.isNotBlank())
        require(depth >= 0)
        require((parentMissionId == null) == (depth == 0))
        require((resultFingerprint == null) == (resultStatus == null))
        resultFingerprint?.let { require(it.matches(Regex("[0-9a-f]{64}"))) }
        when (status) {
            RecursiveResearchMissionStatus.PLANNED -> require(resultStatus == null)
            RecursiveResearchMissionStatus.RESOLVED -> require(resultStatus == DeepSearchStatus.RESOLVED)
            RecursiveResearchMissionStatus.FOLLOW_UP_PLANNED -> require(resultStatus == DeepSearchStatus.UNRESOLVED)
            RecursiveResearchMissionStatus.TERMINAL_UNRESOLVED -> require(
                resultStatus == DeepSearchStatus.UNRESOLVED ||
                    resultStatus == DeepSearchStatus.WORK_BUDGET_EXHAUSTED ||
                    resultStatus == DeepSearchStatus.TIME_BUDGET_EXHAUSTED
            )
            RecursiveResearchMissionStatus.BLOCKED -> require(
                resultStatus == DeepSearchStatus.PERMISSION_BLOCKED ||
                    resultStatus == DeepSearchStatus.NO_USABLE_SOURCE
            )
        }
        require(id == expectedId())
    }

    val executionAuthority: Boolean get() = false
    val currentCycleWorldMutationAllowed: Boolean get() = false

    fun identityFingerprint(): String = researchFingerprint(
        "recursive-research-mission-identity/v1",
        rootGapId,
        rootGapSemanticKey,
        rootGapSourceFingerprint,
        parentMissionId?.value.orEmpty(),
        depth.toString(),
        request.id.value,
    )

    fun stateFingerprint(): String = researchFingerprint(
        "recursive-research-mission-state/v1",
        identityFingerprint(),
        status.name,
        resultFingerprint.orEmpty(),
        resultStatus?.name.orEmpty(),
    )

    private fun expectedId(): RecursiveResearchMissionId =
        RecursiveResearchMissionId(RecursiveResearchMissionId.PREFIX + identityFingerprint())
}

data class RecursiveResearchPlan(
    val sourceCycleId: String,
    val knowledgeGapDetectionFingerprint: String,
    val budget: RecursiveResearchBudget,
    val missions: List<RecursiveResearchMission>,
    val nonResearchGapIds: List<String>,
    val budgetDeferredGapIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(sourceCycleId.isNotBlank())
        require(knowledgeGapDetectionFingerprint.isNotBlank())
        require(missions == missions.distinctBy { it.id }.sortedWith(missionOrder()))
        require(nonResearchGapIds == nonResearchGapIds.distinct().sorted())
        require(budgetDeferredGapIds == budgetDeferredGapIds.distinct().sorted())
        require(missions.size <= budget.maxMissions)
        require(missions.all { it.depth <= budget.maxDepth })
        require(
            fingerprint == planFingerprint(
                sourceCycleId,
                knowledgeGapDetectionFingerprint,
                budget,
                missions,
                nonResearchGapIds,
                budgetDeferredGapIds,
            )
        )
    }

    val executionAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false
    val currentCycleWorldMutationAllowed: Boolean get() = false
}

/**
 * B399 plans bounded research missions over B380 epistemic gaps and delegates actual search to
 * the existing DeepSearchPlannerV2 stack. It never executes sources, authorizes network access,
 * mutates current-cycle world state, or bypasses per-mission DeepSearch budgets.
 */
class RecursiveResearchPlanner {
    fun seed(
        sourceCycleId: String,
        gaps: KnowledgeGapDetectionResult,
        budget: RecursiveResearchBudget = RecursiveResearchBudget(),
    ): RecursiveResearchPlan {
        require(sourceCycleId.isNotBlank())
        require(gaps.gaps.all { it.sourceCycleId == sourceCycleId }) {
            "Recursive research gaps belong to another source cycle"
        }

        val researchable = gaps.gaps.filter(::isResearchable).sortedWith(gapResearchOrder())
        val selected = researchable.take(budget.maxMissions)
        val missions = selected.map { missionForGap(it, budget) }.sortedWith(missionOrder())
        val nonResearch = gaps.gaps.filterNot(::isResearchable).map { it.id }.distinct().sorted()
        val deferred = researchable.drop(selected.size).map { it.id }.distinct().sorted()
        return createPlan(
            sourceCycleId,
            gaps.fingerprint,
            budget,
            missions,
            nonResearch,
            deferred,
        )
    }

    fun advance(
        plan: RecursiveResearchPlan,
        results: Map<RecursiveResearchMissionId, DeepSearchResult>,
    ): RecursiveResearchPlan {
        if (results.isEmpty()) return plan
        val existing = plan.missions.associateBy { it.id }
        require(results.keys.all { it in existing }) {
            "Recursive research result references an unknown mission"
        }

        val updated = existing.toMutableMap()
        val children = mutableListOf<RecursiveResearchMission>()
        var remaining = plan.budget.maxMissions - plan.missions.size

        plan.missions.sortedWith(missionOrder()).forEach { mission ->
            val result = results[mission.id] ?: return@forEach
            require(result.requestId == mission.request.id) {
                "Recursive research result belongs to another DeepSearch request"
            }
            val resultFingerprint = deepSearchResultFingerprint(result)

            if (mission.status != RecursiveResearchMissionStatus.PLANNED) {
                require(
                    mission.resultFingerprint == resultFingerprint &&
                        mission.resultStatus == result.status
                ) { "Recursive research replay changed an already-recorded mission result" }
                return@forEach
            }

            when (result.status) {
                DeepSearchStatus.RESOLVED -> updated[mission.id] = mission.completed(
                    RecursiveResearchMissionStatus.RESOLVED,
                    result,
                    resultFingerprint,
                )
                DeepSearchStatus.PERMISSION_BLOCKED,
                DeepSearchStatus.NO_USABLE_SOURCE -> updated[mission.id] = mission.completed(
                    RecursiveResearchMissionStatus.BLOCKED,
                    result,
                    resultFingerprint,
                )
                DeepSearchStatus.WORK_BUDGET_EXHAUSTED,
                DeepSearchStatus.TIME_BUDGET_EXHAUSTED -> updated[mission.id] = mission.completed(
                    RecursiveResearchMissionStatus.TERMINAL_UNRESOLVED,
                    result,
                    resultFingerprint,
                )
                DeepSearchStatus.UNRESOLVED -> {
                    val canFollowUp = mission.depth < plan.budget.maxDepth && remaining > 0
                    if (canFollowUp) {
                        val child = followUpMission(mission, result, plan.budget)
                        if (child.id !in updated && children.none { it.id == child.id }) {
                            children += child
                            remaining -= 1
                        }
                        updated[mission.id] = mission.completed(
                            RecursiveResearchMissionStatus.FOLLOW_UP_PLANNED,
                            result,
                            resultFingerprint,
                        )
                    } else {
                        updated[mission.id] = mission.completed(
                            RecursiveResearchMissionStatus.TERMINAL_UNRESOLVED,
                            result,
                            resultFingerprint,
                        )
                    }
                }
            }
        }

        return createPlan(
            plan.sourceCycleId,
            plan.knowledgeGapDetectionFingerprint,
            plan.budget,
            (updated.values + children).sortedWith(missionOrder()),
            plan.nonResearchGapIds,
            plan.budgetDeferredGapIds,
        )
    }

    private fun RecursiveResearchMission.completed(
        next: RecursiveResearchMissionStatus,
        result: DeepSearchResult,
        resultFingerprint: String,
    ): RecursiveResearchMission = copy(
        status = next,
        resultFingerprint = resultFingerprint,
        resultStatus = result.status,
    )

    private fun missionForGap(
        gap: KnowledgeGap,
        budget: RecursiveResearchBudget,
    ): RecursiveResearchMission {
        val request = DeepSearchRequest(
            query = boundedQuery("${gap.semanticKey}: ${gap.rationale}"),
            contextTerms = (gap.relatedRefs + gap.semanticKey + gap.id)
                .filter { it.isNotBlank() }
                .take(128)
                .toSortedSet(),
            budget = budget.perMissionBudget,
        )
        return createMission(
            rootGapId = gap.id,
            rootGapSemanticKey = gap.semanticKey,
            rootGapSourceFingerprint = gap.sourceFingerprint,
            parentMissionId = null,
            depth = 0,
            request = request,
        )
    }

    private fun followUpMission(
        parent: RecursiveResearchMission,
        result: DeepSearchResult,
        budget: RecursiveResearchBudget,
    ): RecursiveResearchMission {
        val basis = result.best?.hypothesis?.statement?.takeIf { it.isNotBlank() }
            ?: parent.request.query
        val request = DeepSearchRequest(
            query = boundedQuery(
                "Resolve remaining uncertainty for ${parent.rootGapSemanticKey}: $basis"
            ),
            contextTerms = buildSet {
                add(parent.rootGapSemanticKey)
                add(parent.rootGapId)
                add("parent:${parent.id.value}")
                add("research-depth:${parent.depth + 1}")
                addAll(parent.request.contextTerms)
                result.best?.hypothesis?.semanticTerms?.let(::addAll)
            }.filter { it.isNotBlank() }.take(128).toSortedSet(),
            budget = budget.perMissionBudget,
        )
        return createMission(
            rootGapId = parent.rootGapId,
            rootGapSemanticKey = parent.rootGapSemanticKey,
            rootGapSourceFingerprint = parent.rootGapSourceFingerprint,
            parentMissionId = parent.id,
            depth = parent.depth + 1,
            request = request,
        )
    }

    private fun createMission(
        rootGapId: String,
        rootGapSemanticKey: String,
        rootGapSourceFingerprint: String,
        parentMissionId: RecursiveResearchMissionId?,
        depth: Int,
        request: DeepSearchRequest,
    ): RecursiveResearchMission = RecursiveResearchMission(
        id = missionId(
            rootGapId,
            rootGapSemanticKey,
            rootGapSourceFingerprint,
            parentMissionId,
            depth,
            request,
        ),
        rootGapId = rootGapId,
        rootGapSemanticKey = rootGapSemanticKey,
        rootGapSourceFingerprint = rootGapSourceFingerprint,
        parentMissionId = parentMissionId,
        depth = depth,
        request = request,
        status = RecursiveResearchMissionStatus.PLANNED,
        resultFingerprint = null,
        resultStatus = null,
    )
}

private fun missionId(
    rootGapId: String,
    rootGapSemanticKey: String,
    rootGapSourceFingerprint: String,
    parentMissionId: RecursiveResearchMissionId?,
    depth: Int,
    request: DeepSearchRequest,
): RecursiveResearchMissionId = RecursiveResearchMissionId(
    RecursiveResearchMissionId.PREFIX + researchFingerprint(
        "recursive-research-mission-identity/v1",
        rootGapId,
        rootGapSemanticKey,
        rootGapSourceFingerprint,
        parentMissionId?.value.orEmpty(),
        depth.toString(),
        request.id.value,
    )
)

private fun isResearchable(gap: KnowledgeGap): Boolean =
    EvidenceActionKind.DEEP_SEARCH in gap.recommendedEvidenceKinds ||
        EvidenceActionKind.SOURCE_REFRESH in gap.recommendedEvidenceKinds

private fun gapResearchOrder(): Comparator<KnowledgeGap> =
    compareByDescending<KnowledgeGap> { it.severity }
        .thenBy { it.kind.name }
        .thenBy { it.semanticKey }
        .thenBy { it.id }

private fun missionOrder(): Comparator<RecursiveResearchMission> =
    compareBy<RecursiveResearchMission> { it.depth }
        .thenBy { it.rootGapId }
        .thenBy { it.parentMissionId?.value.orEmpty() }
        .thenBy { it.id.value }

private fun boundedQuery(value: String): String =
    value.trim().replace(Regex("\\s+"), " ").take(MAX_QUERY_CHARS)
        .ifBlank { "Resolve research uncertainty" }

private fun createPlan(
    sourceCycleId: String,
    gapDetectionFingerprint: String,
    budget: RecursiveResearchBudget,
    missions: List<RecursiveResearchMission>,
    nonResearchGapIds: List<String>,
    budgetDeferredGapIds: List<String>,
): RecursiveResearchPlan {
    val canonicalMissions = missions.distinctBy { it.id }.sortedWith(missionOrder())
    val nonResearch = nonResearchGapIds.distinct().sorted()
    val deferred = budgetDeferredGapIds.distinct().sorted()
    val fingerprint = planFingerprint(
        sourceCycleId,
        gapDetectionFingerprint,
        budget,
        canonicalMissions,
        nonResearch,
        deferred,
    )
    return RecursiveResearchPlan(
        sourceCycleId = sourceCycleId,
        knowledgeGapDetectionFingerprint = gapDetectionFingerprint,
        budget = budget,
        missions = canonicalMissions,
        nonResearchGapIds = nonResearch,
        budgetDeferredGapIds = deferred,
        fingerprint = fingerprint,
    )
}

private fun planFingerprint(
    sourceCycleId: String,
    knowledgeGapDetectionFingerprint: String,
    budget: RecursiveResearchBudget,
    missions: List<RecursiveResearchMission>,
    nonResearchGapIds: List<String>,
    budgetDeferredGapIds: List<String>,
): String = researchFingerprint(
    "recursive-research-plan/v1",
    sourceCycleId,
    knowledgeGapDetectionFingerprint,
    budget.fingerprint(),
    *missions.sortedWith(missionOrder()).map { "mission:${it.stateFingerprint()}" }.toTypedArray(),
    *nonResearchGapIds.sorted().map { "non-research:$it" }.toTypedArray(),
    *budgetDeferredGapIds.sorted().map { "deferred:$it" }.toTypedArray(),
)

private fun deepSearchResultFingerprint(result: DeepSearchResult): String = researchFingerprint(
    "recursive-research-deepsearch-result/v1",
    result.requestId.value,
    result.status.name,
    result.best?.id?.value.orEmpty(),
    result.workUnitsUsed.toString(),
    *result.alternatives.sortedBy { it.id.value }.map { "alt:${it.id.value}" }.toTypedArray(),
    *result.evidence.sortedBy { it.id.value }.map { "evidence:${it.id.value}" }.toTypedArray(),
    *result.trace.sortedBy { it.sequence }.map {
        "trace:${it.sequence}:${it.type.name}:${it.branchId?.value.orEmpty()}:${it.sourceId.orEmpty()}:${it.hypothesisId?.value.orEmpty()}:${it.detail}"
    }.toTypedArray(),
    *result.blockedSourceIds.sorted().map { "blocked:$it" }.toTypedArray(),
    *result.failedSourceIds.sorted().map { "failed:$it" }.toTypedArray(),
)

private fun researchFingerprint(
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

private const val MAX_QUERY_CHARS = 2_048
