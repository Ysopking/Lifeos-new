package app.lifeos.core.runtime.goal

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
import app.lifeos.core.runtime.deepsearch.DeepSearchEvidenceDraft
import app.lifeos.core.runtime.deepsearch.DeepSearchFindingDraft
import app.lifeos.core.runtime.deepsearch.DeepSearchPlanner
import app.lifeos.core.runtime.deepsearch.DeepSearchRequest
import app.lifeos.core.runtime.deepsearch.DeepSearchResult
import app.lifeos.core.runtime.deepsearch.DeepSearchSource
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceDescriptor
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceKind
import app.lifeos.core.runtime.deepsearch.DeepSearchStatus
import java.time.Duration
import java.time.Instant
import java.util.Locale

sealed interface LocalDeepSearchGoalResult {
    data class Produced(
        val photon: Photon,
        val result: DeepSearchResult,
        val evidencePhotonIds: List<PhotonId>,
    ) : LocalDeepSearchGoalResult

    data class Unsupported(val intent: IntentType) : LocalDeepSearchGoalResult
}

/**
 * Private-v1 local DeepSearch adapter. It turns the already bounded DeepSearch planner into a real
 * SEARCH executor over encrypted local Photon evidence. It does not claim network access and never
 * treats derived search/query answers, goals or tool requests as fresh primary evidence.
 */
class LocalDeepSearchGoalEngine(
    private val planner: DeepSearchPlanner = DeepSearchPlanner(),
) {
    fun supports(intent: IntentType): Boolean = intent == IntentType.SEARCH

    suspend fun execute(
        goal: GoalFrame,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
        photons: List<Photon>,
        createdAt: Instant = Instant.now(),
    ): LocalDeepSearchGoalResult {
        if (!supports(goal.intent)) return LocalDeepSearchGoalResult.Unsupported(goal.intent)

        val query = extractQuery(goal)
        val excluded = setOf(sourcePhoton.id, goalPhotonId)
        val candidates = photons
            .asSequence()
            .filter { it.id !in excluded }
            .filter(::isPrimarySearchEvidence)
            .sortedWith(compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value })
            .toList()
        val request = DeepSearchRequest(
            query = query,
            contextTerms = goal.entities.map { it.normalizedValue }.filter { it.isNotBlank() }.toSet(),
            budget = DeepSearchBudget(
                maxDepth = 2,
                maxBreadth = MAX_RESULTS,
                maxWorkUnits = MAX_WORK_UNITS,
                maxElapsed = Duration.ofSeconds(MAX_SECONDS),
            ),
        )
        val result = planner.search(
            request = request,
            sources = listOf(PhotonDeepSearchSource(candidates)),
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
        )
        return LocalDeepSearchGoalResult.Produced(output, result, evidenceIds)
    }

    private fun resultPhoton(
        goal: GoalFrame,
        source: Photon,
        goalPhotonId: PhotonId,
        result: DeepSearchResult,
        evidenceIds: List<PhotonId>,
        createdAt: Instant,
    ): Photon {
        val content = render(result, goal.language)
        val confidence = result.best?.score?.total ?: NO_EVIDENCE_CONFIDENCE
        return Photon(
            content = content,
            mimeType = RESULT_MIME,
            phase = if (result.status == DeepSearchStatus.RESOLVED) PhotonPhase.CONVERGED else PhotonPhase.REFLECTING,
            semanticMass = 1.0 + evidenceIds.size * EVIDENCE_MASS,
            energy = 1.0,
            confidence = confidence.coerceIn(0.0, 1.0),
            provenance = Provenance(
                source = "local-deepsearch",
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
            tags = setOf(
                "answer",
                "deepsearch",
                "local-search",
                "deepsearch-answer",
                "evidence-backed",
                "deepsearch-status:${result.status.name.lowercase(Locale.ROOT)}",
            ),
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
            sourceId = "local-photon-evidence",
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
        private const val MAX_RESULTS = 6
        private const val MAX_WORK_UNITS = 16
        private const val MAX_SECONDS = 4L
        private const val MAX_EXCERPT_CHARS = 280
        private const val EVIDENCE_MASS = 0.08
        private const val NO_EVIDENCE_CONFIDENCE = 0.70
        private val TERM_REGEX = Regex("[\\p{L}\\p{N}]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val SEARCH_DIRECTIVE_WORDS = setOf(
            "suche", "finde", "recherchiere", "deepsearch", "search", "find", "research", "lookup",
            "nach", "bitte", "mir", "für", "fuer", "zu", "über", "ueber", "about", "for",
        )
    }
}
