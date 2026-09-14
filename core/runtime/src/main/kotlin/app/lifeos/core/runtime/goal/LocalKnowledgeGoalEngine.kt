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
import java.time.Instant
import java.util.Locale
import kotlin.math.min

enum class LocalKnowledgeGoalKind {
    MEMORY_STORED,
    QUERY_ANSWER,
}

sealed interface LocalKnowledgeGoalResult {
    data class Produced(
        val kind: LocalKnowledgeGoalKind,
        val photon: Photon,
        val evidencePhotonIds: List<PhotonId>,
    ) : LocalKnowledgeGoalResult

    data class Unsupported(val intent: IntentType) : LocalKnowledgeGoalResult
}

/**
 * J13 deterministic offline execution for the two knowledge operations that LIFEOS can already
 * support from its encrypted Photon store. It never synthesizes facts that are absent from local
 * evidence: QUERY returns verbatim bounded excerpts and MEMORY stores a derived memory Photon.
 */
class LocalKnowledgeGoalEngine {
    fun supports(intent: IntentType): Boolean =
        intent == IntentType.QUERY || intent == IntentType.STORE_OR_REMEMBER

    fun execute(
        goal: GoalFrame,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
        photons: List<Photon>,
        createdAt: Instant = Instant.now(),
    ): LocalKnowledgeGoalResult = when (goal.intent) {
        IntentType.STORE_OR_REMEMBER -> memory(goal, sourcePhoton, goalPhotonId, createdAt)
        IntentType.QUERY -> query(goal, sourcePhoton, goalPhotonId, photons, createdAt)
        else -> LocalKnowledgeGoalResult.Unsupported(goal.intent)
    }

    private fun memory(
        goal: GoalFrame,
        source: Photon,
        goalPhotonId: PhotonId,
        createdAt: Instant,
    ): LocalKnowledgeGoalResult.Produced {
        val payload = extractMemoryPayload(source.content)
        val confidence = min(source.confidence, goal.confidence)
        val photon = Photon(
            content = payload,
            mimeType = MEMORY_MIME,
            phase = PhotonPhase.ACTIVE,
            semanticMass = maxOf(1.0, source.semanticMass + MEMORY_MASS_BOOST),
            energy = maxOf(1.0, source.energy),
            confidence = confidence,
            provenance = Provenance(
                source = "local-memory-store",
                actor = "LocalKnowledgeGoalEngine",
                createdAt = createdAt,
                parentIds = setOf(source.id, goalPhotonId),
            ),
            relations = setOf(
                PhotonRelation(source.id, RelationType.DERIVED_FROM, confidence),
                PhotonRelation(goalPhotonId, RelationType.REFERENCES, goal.confidence),
            ),
            tags = setOf("memory", "local-memory", "intent:store_or_remember"),
        )
        return LocalKnowledgeGoalResult.Produced(
            kind = LocalKnowledgeGoalKind.MEMORY_STORED,
            photon = photon,
            evidencePhotonIds = listOf(source.id),
        )
    }

    private fun query(
        goal: GoalFrame,
        source: Photon,
        goalPhotonId: PhotonId,
        photons: List<Photon>,
        createdAt: Instant,
    ): LocalKnowledgeGoalResult.Produced {
        val queryText = goal.objective.substringAfter(": ", goal.objective).trim()
        val rawQueryTerms = terms(queryText)
        val significant = rawQueryTerms.filterNot { it in STOP_WORDS }.toSet()
        val queryTerms = if (significant.isNotEmpty()) significant else rawQueryTerms.toSet()
        val excluded = setOf(source.id, goalPhotonId)

        // A resolved conversational reference is stronger than lexical overlap. It may only become
        // evidence when language understanding found no unresolved/competing reference. Otherwise
        // the query deliberately falls back to ordinary lexical retrieval instead of guessing.
        val referencedMatches = resolvedReferenceMatches(goal, photons, excluded)
        val referencedIds = referencedMatches.mapTo(mutableSetOf()) { it.photon.id }
        val lexicalMatches = photons.asSequence()
            .filter { candidate -> candidate.id !in excluded && candidate.id !in referencedIds }
            .filter(::isKnowledgeCandidate)
            .mapNotNull { candidate -> score(candidate, queryTerms, queryText) }
            .sortedWith(
                compareByDescending<ScoredPhoton> { it.score }
                    .thenByDescending { it.photon.provenance.createdAt }
                    .thenBy { it.photon.id.value }
            )
            .toList()
        val matches = (referencedMatches + lexicalMatches)
            .take(MAX_QUERY_RESULTS)

        val content = if (matches.isEmpty()) {
            when (goal.language) {
                LanguageCode.DE -> "Keine passende lokale Information gefunden."
                else -> "No matching local information found."
            }
        } else {
            val heading = when (goal.language) {
                LanguageCode.DE -> "Lokale Evidenz:"
                else -> "Local evidence:"
            }
            buildString {
                appendLine(heading)
                matches.forEachIndexed { index, match ->
                    append(index + 1)
                        .append(". ")
                        .appendLine(excerpt(match.photon.content))
                }
            }.trimEnd()
        }

        val evidenceIds = matches.map { it.photon.id }
        val confidence = if (matches.isEmpty()) {
            NO_MATCH_CONFIDENCE
        } else {
            matches.map { it.score }.average().coerceIn(0.0, 1.0)
        }
        val relations = buildSet {
            add(PhotonRelation(source.id, RelationType.DERIVED_FROM, goal.confidence))
            add(PhotonRelation(goalPhotonId, RelationType.REFERENCES, goal.confidence))
            matches.forEach { match ->
                add(PhotonRelation(match.photon.id, RelationType.REFERENCES, match.score))
            }
        }
        val photon = Photon(
            content = content,
            mimeType = ANSWER_MIME,
            phase = PhotonPhase.CONVERGED,
            semanticMass = 1.0 + matches.size * ANSWER_EVIDENCE_MASS,
            energy = 1.0,
            confidence = confidence,
            provenance = Provenance(
                source = "local-knowledge-resolver",
                actor = "LocalKnowledgeGoalEngine",
                createdAt = createdAt,
                parentIds = buildSet {
                    add(source.id)
                    add(goalPhotonId)
                    addAll(evidenceIds)
                },
            ),
            relations = relations,
            tags = setOf("answer", "local-query-answer", "evidence-backed"),
        )
        return LocalKnowledgeGoalResult.Produced(
            kind = LocalKnowledgeGoalKind.QUERY_ANSWER,
            photon = photon,
            evidencePhotonIds = evidenceIds,
        )
    }

    private fun resolvedReferenceMatches(
        goal: GoalFrame,
        photons: List<Photon>,
        excluded: Set<PhotonId>,
    ): List<ScoredPhoton> {
        if (goal.ambiguities.any { it.code in REFERENCE_BLOCKING_AMBIGUITIES }) return emptyList()
        val byId = photons.associateBy { it.id }
        return goal.references.asSequence()
            .filter { it.score >= MIN_REFERENCE_SCORE }
            .mapNotNull { reference ->
                val id = reference.targetPhotonId ?: return@mapNotNull null
                val photon = byId[id] ?: return@mapNotNull null
                if (id in excluded || !isKnowledgeCandidate(photon)) return@mapNotNull null
                ScoredPhoton(photon, reference.score)
            }
            .groupBy { it.photon.id }
            .map { (_, candidates) -> candidates.maxBy { it.score } }
            .sortedWith(
                compareByDescending<ScoredPhoton> { it.score }
                    .thenByDescending { it.photon.provenance.createdAt }
                    .thenBy { it.photon.id.value }
            )
    }

    private fun score(
        photon: Photon,
        queryTerms: Set<String>,
        queryText: String,
    ): ScoredPhoton? {
        if (queryTerms.isEmpty()) return null
        val candidateTerms = terms(photon.content).toSet()
        if (candidateTerms.isEmpty()) return null
        val hits = queryTerms.intersect(candidateTerms).size
        if (hits == 0) return null

        val coverage = hits.toDouble() / queryTerms.size
        val specificity = hits.toDouble() / candidateTerms.size
        val normalizedQuery = queryText.lowercase(Locale.ROOT).trim()
        val phraseBonus = if (
            normalizedQuery.length >= MIN_PHRASE_LENGTH &&
            photon.content.lowercase(Locale.ROOT).contains(normalizedQuery)
        ) PHRASE_BONUS else 0.0
        val memoryBoost = if ("memory" in photon.tags) MEMORY_SCORE_BOOST else 0.0
        val value = (
            coverage * COVERAGE_WEIGHT +
                specificity.coerceAtMost(1.0) * SPECIFICITY_WEIGHT +
                photon.confidence * CONFIDENCE_WEIGHT +
                phraseBonus +
                memoryBoost
            ).coerceIn(0.0, 1.0)
        return ScoredPhoton(photon, value)
    }

    private fun isKnowledgeCandidate(photon: Photon): Boolean {
        if ("goal" in photon.tags || "scene-graph" in photon.tags) return false
        if ("tool-request" in photon.tags || "local-query-answer" in photon.tags) return false
        return photon.mimeType.startsWith("text/") ||
            photon.mimeType == MEMORY_MIME ||
            "memory" in photon.tags
    }

    private fun terms(value: String): List<String> = TERM_REGEX.findAll(value)
        .map { it.value.lowercase(Locale.ROOT) }
        .filter { it.length >= MIN_TERM_LENGTH }
        .toList()

    private fun extractMemoryPayload(content: String): String {
        val trimmed = content.trim()
        val stripped = MEMORY_PREFIX.replace(trimmed, "").trim()
        return stripped.ifBlank { trimmed }
    }

    private fun excerpt(content: String): String {
        val normalized = content.replace(WHITESPACE_REGEX, " ").trim()
        if (normalized.length <= MAX_EXCERPT_CHARS) return normalized
        return normalized.take(MAX_EXCERPT_CHARS - 1).trimEnd() + "…"
    }

    private data class ScoredPhoton(val photon: Photon, val score: Double)

    companion object {
        const val MEMORY_MIME = "application/vnd.lifeos.memory+text"
        const val ANSWER_MIME = "application/vnd.lifeos.answer+text"
        private const val MAX_QUERY_RESULTS = 5
        private const val MAX_EXCERPT_CHARS = 240
        private const val MIN_TERM_LENGTH = 2
        private const val MIN_PHRASE_LENGTH = 4
        private const val MIN_REFERENCE_SCORE = 0.55
        private const val MEMORY_MASS_BOOST = 0.35
        private const val ANSWER_EVIDENCE_MASS = 0.08
        private const val NO_MATCH_CONFIDENCE = 0.75
        private const val COVERAGE_WEIGHT = 0.72
        private const val SPECIFICITY_WEIGHT = 0.13
        private const val CONFIDENCE_WEIGHT = 0.10
        private const val PHRASE_BONUS = 0.03
        private const val MEMORY_SCORE_BOOST = 0.02
        private val REFERENCE_BLOCKING_AMBIGUITIES = setOf("unresolved_reference", "reference_competition")
        private val TERM_REGEX = Regex("[\\p{L}\\p{N}]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val MEMORY_PREFIX = Regex(
            "^(?:merk(?:e)?\\s+dir(?:\\s+bitte)?(?:[,.:]?\\s+dass)?|" +
                "erinnere\\s+dich(?:\\s+bitte)?(?:\\s+daran)?(?:[,.:]?\\s+dass)?|" +
                "speichere(?:\\s+dir)?(?:\\s+bitte)?|" +
                "remember(?:\\s+that)?|save(?:\\s+that)?)\\s*[:,-]?\\s*",
            RegexOption.IGNORE_CASE,
        )
        private val STOP_WORDS = setOf(
            "a", "an", "and", "are", "about", "do", "does", "did", "have", "has", "how", "i",
            "is", "me", "my", "of", "or", "the", "to", "was", "were", "what", "when", "where",
            "which", "who", "why", "you", "your", "know", "remember",
            "aber", "als", "am", "an", "auf", "aus", "bei", "das", "dass", "der", "die", "du",
            "ein", "eine", "einer", "eines", "für", "habe", "haben", "hat", "ich", "im", "in",
            "ist", "mir", "mit", "nach", "oder", "sind", "über", "und", "von", "war", "waren",
            "was", "welche", "welcher", "welches", "wer", "wie", "wissen", "wo", "wann", "warum",
            "zu", "zum", "zur",
        )
    }
}
