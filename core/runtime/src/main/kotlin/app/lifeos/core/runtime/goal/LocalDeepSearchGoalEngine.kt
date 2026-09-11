package app.lifeos.core.runtime.goal

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.runtime.deepsearch.DeepSearchBudget
import app.lifeos.core.runtime.deepsearch.DeepSearchCheckpointSink
import app.lifeos.core.runtime.deepsearch.DeepSearchEvidenceDraft
import app.lifeos.core.runtime.deepsearch.DeepSearchFindingDraft
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionDefinition
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionId
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionProduct
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionRuntimeRegistry
import app.lifeos.core.runtime.deepsearch.DeepSearchPlannerCheckpoint
import app.lifeos.core.runtime.deepsearch.DeepSearchPlannerV2
import app.lifeos.core.runtime.deepsearch.DeepSearchRequest
import app.lifeos.core.runtime.deepsearch.DeepSearchResult
import app.lifeos.core.runtime.deepsearch.DeepSearchSource
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceDescriptor
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceKind
import app.lifeos.core.runtime.deepsearch.DeepSearchStatus
import app.lifeos.core.runtime.resource.ResourceBudgetDemand
import app.lifeos.core.runtime.resource.ResourceBudgetDomain
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.resource.SharedResourceBudgetDecision
import app.lifeos.core.runtime.resource.SharedResourceBudgetGate
import app.lifeos.core.runtime.resource.SharedResourceBudgetRuntimeRegistry
import java.time.Duration
import java.time.Instant
import java.util.Locale

sealed interface LocalDeepSearchGoalResult {
    data class Produced(
        val photon: Photon,
        val result: DeepSearchResult,
        val evidencePhotonIds: List<PhotonId>,
        val missionId: DeepSearchMissionId? = null,
    ) : LocalDeepSearchGoalResult

    data class Unsupported(val intent: IntentType) : LocalDeepSearchGoalResult
}

/**
 * Private-v1 local DeepSearch adapter. V12 routes SEARCH through the resumable planner while keeping
 * the existing public call shape compatible. Production installs a durable mission coordinator;
 * legacy/unit compositions simply run V2 in-memory. World Formula/V16 bounds the planner limits and
 * no network source is claimed here.
 */
class LocalDeepSearchGoalEngine(
    private val planner: DeepSearchPlannerV2 = DeepSearchPlannerV2(),
    private val sharedBudgets: SharedResourceBudgetGate? = SharedResourceBudgetRuntimeRegistry.current(),
) {
    fun supports(intent: IntentType): Boolean = intent == IntentType.SEARCH

    suspend fun execute(
        goal: GoalFrame,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
        photons: List<Photon>,
        createdAt: Instant = Instant.now(),
        resume: DeepSearchPlannerCheckpoint? = null,
        checkpointSink: DeepSearchCheckpointSink? = null,
        missionId: DeepSearchMissionId? = null,
    ): LocalDeepSearchGoalResult {
        if (!supports(goal.intent)) return LocalDeepSearchGoalResult.Unsupported(goal.intent)

        if (missionId == null && resume == null && checkpointSink == null) {
            val missionRuntime = DeepSearchMissionRuntimeRegistry.currentOrNull()
            if (missionRuntime != null) {
                val definition = DeepSearchMissionDefinition.create(
                    goalPhotonId = goalPhotonId,
                    sourcePhotonId = sourcePhoton.id,
                    sourceRevision = sourcePhoton.revision,
                    query = goal.objective,
                    searchPolicyVersion = SEARCH_POLICY_VERSION,
                    sourceScopeIds = setOf(LOCAL_SOURCE_ID),
                    createdAt = createdAt,
                )
                val product = missionRuntime.run(definition) { durableResume, durableSink, durableMissionId ->
                    when (
                        val nested = execute(
                            goal = goal,
                            sourcePhoton = sourcePhoton,
                            goalPhotonId = goalPhotonId,
                            photons = photons,
                            createdAt = createdAt,
                            resume = durableResume,
                            checkpointSink = durableSink,
                            missionId = durableMissionId,
                        )
                    ) {
                        is LocalDeepSearchGoalResult.Produced -> DeepSearchMissionProduct(
                            photon = nested.photon,
                            result = nested.result,
                            evidencePhotonIds = nested.evidencePhotonIds,
                            missionId = durableMissionId,
                        )
                        is LocalDeepSearchGoalResult.Unsupported -> error(
                            "Durable DeepSearch mission became unsupported"
                        )
                    }
                }
                return LocalDeepSearchGoalResult.Produced(
                    photon = product.photon,
                    result = product.result,
                    evidencePhotonIds = product.evidencePhotonIds,
                    missionId = product.missionId,
                )
            }
        }

        val query = extractQuery(goal)
        val excluded = setOf(sourcePhoton.id, goalPhotonId)
        val candidates = photons
            .asSequence()
            .filter { it.id !in excluded }
            .filter(::isPrimarySearchEvidence)
            .sortedWith(compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value })
            .toList()
        val freshRequest = DeepSearchRequest(
            query = query,
            contextTerms = goal.entities.map { it.normalizedValue }.filter { it.isNotBlank() }.toSet(),
            budget = effectiveBudget(goal),
        )
        val request = if (resume == null) {
            freshRequest
        } else {
            require(resume.request.query == freshRequest.query) {
                "DeepSearch mission query changed during resume"
            }
            require(resume.request.contextTerms == freshRequest.contextTerms) {
                "DeepSearch mission context changed during resume"
            }
            require(resume.request.minimumResolutionScore == freshRequest.minimumResolutionScore)
            require(resume.request.minimumWinnerMargin == freshRequest.minimumWinnerMargin)
            require(budgetFitsWithin(resume.request.budget, freshRequest.budget)) {
                "deepsearch-resume-budget-tightened"
            }
            // Preserve the original request identity; a looser current allocation never expands a running mission.
            resume.request
        }
        val result = planner.search(
            request = request,
            sources = listOf(PhotonDeepSearchSource(candidates)),
            resume = resume,
            checkpointSink = checkpointSink,
        )
        val evidenceIds = result.evidence
            .mapNotNull { it.sourcePhotonId }
            .distinct()
            .sortedBy { it.value }
        val output = resultPhoton(
            goal = goal,
            source = sourcePhoton,
            goalPhotonId = goalPhotonId,
            result = result,
            evidenceIds = evidenceIds,
            createdAt = createdAt,
            missionId = missionId,
        )
        return LocalDeepSearchGoalResult.Produced(
            photon = output,
            result = result,
            evidencePhotonIds = evidenceIds,
            missionId = missionId,
        )
    }

    private suspend fun effectiveBudget(goal: GoalFrame): DeepSearchBudget {
        val broker = sharedBudgets ?: return DEFAULT_BUDGET
        val demand = ResourceBudgetDemand(
            domain = ResourceBudgetDomain.DEEP_SEARCH,
            requested = DEEP_SEARCH_REQUEST,
            goalRelevance = goal.confidence.coerceIn(0.0, 1.0),
            priority = 0.80,
            expectedUtility = 0.90,
            confidence = goal.confidence.coerceIn(0.0, 1.0),
        )
        val allocation = when (val decision = broker.allocate(DEEP_SEARCH_HARD_QUOTA, listOf(demand))) {
            is SharedResourceBudgetDecision.Blocked -> error(
                "deepsearch-world-formula-budget-blocked:${decision.reason}"
            )
            is SharedResourceBudgetDecision.Ready -> requireNotNull(
                decision.allocation.allocation(ResourceBudgetDomain.DEEP_SEARCH)
            ) { "World Formula allocation omitted DEEP_SEARCH domain" }
        }
        val work = minOf(MAX_WORK_UNITS.toLong(), allocation.allocated.workUnits).toInt()
        val breadth = minOf(MAX_RESULTS.toLong(), allocation.allocated.candidates).toInt()
        val elapsedMillis = minOf(MAX_SECONDS * 1_000L, allocation.allocated.elapsedMillis)
        require(work > 0 && breadth > 0 && elapsedMillis > 0L) {
            "deepsearch-world-formula-allocation-too-small"
        }
        return DeepSearchBudget(
            maxDepth = if (work >= 4) 2 else 1,
            maxBreadth = breadth,
            maxWorkUnits = work,
            maxElapsed = Duration.ofMillis(elapsedMillis),
        )
    }

    private fun budgetFitsWithin(
        requested: DeepSearchBudget,
        currentLimit: DeepSearchBudget,
    ): Boolean = requested.maxDepth <= currentLimit.maxDepth &&
        requested.maxBreadth <= currentLimit.maxBreadth &&
        requested.maxWorkUnits <= currentLimit.maxWorkUnits &&
        requested.maxElapsed <= currentLimit.maxElapsed

    private fun resultPhoton(
        goal: GoalFrame,
        source: Photon,
        goalPhotonId: PhotonId,
        result: DeepSearchResult,
        evidenceIds: List<PhotonId>,
        createdAt: Instant,
        missionId: DeepSearchMissionId?,
    ): Photon {
        val content = render(result, goal.language)
        val confidence = result.best?.score?.total ?: NO_EVIDENCE_CONFIDENCE
        val deterministicId = missionId?.let {
            PhotonId(
                "deep-search-result_" + StableFieldIds.fingerprint(
                    "deep-search-result-photon/v2",
                    it.value,
                    result.requestId.value,
                )
            )
        }
        return Photon(
            id = deterministicId ?: PhotonId.new(),
            content = content,
            mimeType = RESULT_MIME,
            phase = if (result.status == DeepSearchStatus.RESOLVED) PhotonPhase.CONVERGED else PhotonPhase.REFLECTING,
            semanticMass = 1.0 + evidenceIds.size * EVIDENCE_MASS,
            energy = 1.0,
            confidence = confidence.coerceIn(0.0, 1.0),
            provenance = Provenance(
                source = "local-deepsearch-v2",
                actor = "LocalDeepSearchGoalEngine",
                createdAt = createdAt,
                parentIds = buildSet {
                    add(source.id)
                    add(goalPhotonId)
                    addAll(evidenceIds)
                },
            ),
            relations = buildSet {
                add(PhotonRelation(source.id, RelationType.DERIVED_FROM, goal.confidence))
                add(PhotonRelation(goalPhotonId, RelationType.REFERENCES, goal.confidence))
                result.evidence.forEach { evidence ->
                    evidence.sourcePhotonId?.let { id ->
                        add(PhotonRelation(id, RelationType.REFERENCES, evidence.confidence))
                    }
                }
            },
            tags = buildSet {
                add("answer")
                add("deepsearch")
                add("deepsearch-v2")
                add("local-search")
                add("deepsearch-answer")
                add("evidence-backed")
                add("deepsearch-status:${result.status.name.lowercase(Locale.ROOT)}")
                add("deepsearch-work:${result.workUnitsUsed}")
                missionId?.let { add("deepsearch-mission:${it.value}") }
            },
        )
    }

    private fun render(result: DeepSearchResult, language: LanguageCode): String {
        val best = result.best
        if (best == null || result.evidence.isEmpty()) {
            return when (language) {
                LanguageCode.DE -> "Keine passende lokale DeepSearch-Evidenz gefunden."
                else -> "No matching local DeepSearch evidence found."
            }
        }

        val ordered = (listOf(best) + result.alternatives)
            .distinctBy { it.id }
            .take(MAX_RESULTS)
        val heading = when (language) {
            LanguageCode.DE -> if (result.status == DeepSearchStatus.RESOLVED) {
                "Lokale DeepSearch-Evidenz:"
            } else {
                "Lokale DeepSearch-Evidenz (noch nicht eindeutig aufgelöst):"
            }
            else -> if (result.status == DeepSearchStatus.RESOLVED) {
                "Local DeepSearch evidence:"
            } else {
                "Local DeepSearch evidence (not uniquely resolved):"
            }
        }
        return buildString {
            appendLine(heading)
            ordered.forEachIndexed { index, branch ->
                append(index + 1).append(". ").appendLine(excerpt(branch.hypothesis.statement))
            }
        }.trimEnd()
    }

    private fun extractQuery(goal: GoalFrame): String {
        val raw = goal.objective.substringAfter(": ", goal.objective).trim()
        val meaningful = TERM_REGEX.findAll(raw)
            .map { it.value }
            .filterNot { it.lowercase(Locale.ROOT) in SEARCH_DIRECTIVE_WORDS }
            .joinToString(" ")
            .trim()
        return meaningful.ifBlank { raw.ifBlank { goal.objective } }
    }

    private fun isPrimarySearchEvidence(photon: Photon): Boolean {
        if (photon.phase == PhotonPhase.ARCHIVED) return false
        if ("goal" in photon.tags || "scene-graph" in photon.tags) return false
        if ("tool-request" in photon.tags || "capability-gap" in photon.tags) return false
        if ("deepsearch-answer" in photon.tags || "local-query-answer" in photon.tags) return false
        return photon.mimeType.startsWith("text/") ||
            photon.mimeType == LocalKnowledgeGoalEngine.MEMORY_MIME ||
            "memory" in photon.tags
    }

    private fun excerpt(value: String): String {
        val normalized = value.replace(WHITESPACE_REGEX, " ").trim()
        return if (normalized.length <= MAX_EXCERPT_CHARS) normalized
        else normalized.take(MAX_EXCERPT_CHARS - 1).trimEnd() + "…"
    }

    private class PhotonDeepSearchSource(
        private val photons: List<Photon>,
    ) : DeepSearchSource {
        override val descriptor = DeepSearchSourceDescriptor(
            sourceId = LOCAL_SOURCE_ID,
            kind = DeepSearchSourceKind.LOCAL,
            reliability = 1.0,
            workUnitsPerExpansion = 1,
        )

        override suspend fun expand(
            request: DeepSearchRequest,
            branch: app.lifeos.core.runtime.deepsearch.DeepSearchBranch,
        ): List<DeepSearchFindingDraft> {
            if (branch.depth > 0) return emptyList()
            val queryTerms = request.queryTerms + request.contextTerms.flatMap(::terms)
            if (queryTerms.isEmpty()) return emptyList()
            return photons.mapNotNull { photon ->
                val candidateTerms = terms(photon.content).toSet()
                val hits = queryTerms.intersect(candidateTerms)
                if (hits.isEmpty()) return@mapNotNull null
                val coverage = hits.size.toDouble() / queryTerms.size.toDouble()
                val confidence = (photon.confidence * (0.65 + coverage * 0.35)).coerceIn(0.0, 1.0)
                DeepSearchFindingDraft(
                    statement = excerptStatic(photon.content),
                    semanticTerms = candidateTerms.intersect(queryTerms),
                    confidence = confidence,
                    evidence = listOf(
                        DeepSearchEvidenceDraft(
                            statement = excerptStatic(photon.content),
                            confidence = photon.confidence,
                            sourcePhotonId = photon.id,
                        )
                    ),
                )
            }
        }

        private companion object {
            fun terms(value: String): Set<String> = TERM_REGEX.findAll(value)
                .map { it.value.lowercase(Locale.ROOT) }
                .filter { it.length >= 2 }
                .filterNot { it in SEARCH_DIRECTIVE_WORDS }
                .toSet()

            fun excerptStatic(value: String): String {
                val normalized = value.replace(WHITESPACE_REGEX, " ").trim()
                return if (normalized.length <= MAX_EXCERPT_CHARS) normalized
                else normalized.take(MAX_EXCERPT_CHARS - 1).trimEnd() + "…"
            }
        }
    }

    companion object {
        const val RESULT_MIME = "application/vnd.lifeos.deepsearch+text"
        const val LOCAL_SOURCE_ID = "local-photon-evidence"
        const val SEARCH_POLICY_VERSION = "deepsearch-v2-local-2026-09"
        private const val MAX_RESULTS = 6
        private const val MAX_WORK_UNITS = 16
        private const val MAX_SECONDS = 4L
        private const val MAX_EXCERPT_CHARS = 280
        private const val EVIDENCE_MASS = 0.08
        private const val NO_EVIDENCE_CONFIDENCE = 0.70
        private val DEFAULT_BUDGET = DeepSearchBudget(
            maxDepth = 2,
            maxBreadth = MAX_RESULTS,
            maxWorkUnits = MAX_WORK_UNITS,
            maxElapsed = Duration.ofSeconds(MAX_SECONDS),
        )
        private val DEEP_SEARCH_HARD_QUOTA = ResourceBudgetQuota(
            elapsedMillis = 6_000,
            workUnits = 24,
            memoryBytes = 96L * 1024L * 1024L,
            ioBytes = 12L * 1024L * 1024L,
            networkBytes = 0,
            candidates = 8,
        )
        private val DEEP_SEARCH_REQUEST = ResourceBudgetUsage(
            elapsedMillis = MAX_SECONDS * 1_000L,
            workUnits = MAX_WORK_UNITS.toLong(),
            memoryBytes = 48L * 1024L * 1024L,
            ioBytes = 4L * 1024L * 1024L,
            networkBytes = 0,
            candidates = MAX_RESULTS.toLong(),
        )
        private val TERM_REGEX = Regex("[\\p{L}\\p{N}]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val SEARCH_DIRECTIVE_WORDS = setOf(
            "suche", "finde", "recherchiere", "deepsearch", "search", "find", "research", "lookup",
            "nach", "bitte", "mir", "für", "fuer", "zu", "über", "ueber", "about", "for",
        )
    }
}
