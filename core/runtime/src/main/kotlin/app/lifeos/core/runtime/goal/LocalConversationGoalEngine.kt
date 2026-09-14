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

enum class LocalConversationMove {
    GREETING,
    GRATITUDE,
    SOCIAL_CHECK_IN,
    CONTEXTUAL_REPLY,
}

sealed interface LocalConversationGoalResult {
    data class Produced(
        val move: LocalConversationMove,
        val photon: Photon,
        val evidencePhotonIds: List<PhotonId>,
    ) : LocalConversationGoalResult {
        init {
            require(evidencePhotonIds.distinct().size == evidencePhotonIds.size)
        }
    }

    data class Unsupported(val intent: IntentType) : LocalConversationGoalResult
}

/**
 * Deterministic owner-chat planner for non-side-effecting conversation.
 *
 * It turns the semantic CONVERSATION goal into one persisted response fact before surface
 * realization. Social moves stay bounded and truthful. When the utterance contains meaningful
 * subject terms, the planner may attach one matching local text/memory Photon and quotes only a
 * bounded excerpt from that durable evidence; it never invents a factual continuation.
 */
class LocalConversationGoalEngine {
    fun supports(intent: IntentType): Boolean = intent == IntentType.CONVERSATION

    fun execute(
        goal: GoalFrame,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
        photons: List<Photon>,
        createdAt: Instant = Instant.now(),
    ): LocalConversationGoalResult {
        if (!supports(goal.intent)) return LocalConversationGoalResult.Unsupported(goal.intent)

        val move = moveFor(sourcePhoton.content)
        val evidence = contextualEvidence(
            source = sourcePhoton,
            goalPhotonId = goalPhotonId,
            photons = photons,
        )
        val content = responseFact(
            move = move,
            language = goal.language,
            evidence = evidence?.photon,
        )
        val confidence = min(sourcePhoton.confidence, goal.confidence).coerceIn(0.0, 1.0)
        val evidenceIds = evidence?.let { listOf(it.photon.id) }.orEmpty()
        val photon = Photon(
            content = content,
            mimeType = RESPONSE_MIME,
            phase = PhotonPhase.CONVERGED,
            semanticMass = 1.0 + if (evidence == null) 0.0 else 0.08,
            energy = 1.0,
            confidence = if (evidence == null) confidence else min(confidence, evidence.score),
            provenance = Provenance(
                source = "local-conversation-planner",
                actor = "LocalConversationGoalEngine",
                createdAt = createdAt,
                parentIds = buildSet {
                    add(sourcePhoton.id)
                    add(goalPhotonId)
                    addAll(evidenceIds)
                },
            ),
            relations = buildSet {
                add(PhotonRelation(sourcePhoton.id, RelationType.DERIVED_FROM, confidence))
                add(PhotonRelation(goalPhotonId, RelationType.REFERENCES, goal.confidence))
                evidence?.let {
                    add(PhotonRelation(it.photon.id, RelationType.REFERENCES, it.score))
                }
            },
            tags = setOf(
                "conversation-response",
                "local-conversation-response",
                "dialogue:${move.name.lowercase()}",
                "result",
                "evidence:${if (evidence == null) "none" else "local"}",
            ),
        )
        return LocalConversationGoalResult.Produced(move, photon, evidenceIds)
    }

    private fun moveFor(content: String): LocalConversationMove {
        val normalized = normalize(content)
        val terms = terms(normalized).toSet()
        return when {
            terms.any { it in GRATITUDE_WORDS } || normalized.startsWith("thank you") ->
                LocalConversationMove.GRATITUDE
            normalized.contains("wie geht") || normalized.contains("how are you") ->
                LocalConversationMove.SOCIAL_CHECK_IN
            terms.any { it in GREETING_WORDS } || GREETING_PREFIXES.any(normalized::startsWith) ->
                LocalConversationMove.GREETING
            else -> LocalConversationMove.CONTEXTUAL_REPLY
        }
    }

    private fun contextualEvidence(
        source: Photon,
        goalPhotonId: PhotonId,
        photons: List<Photon>,
    ): ScoredPhoton? {
        val queryTerms = terms(source.content)
            .filterNot { it in SOCIAL_STOP_WORDS }
            .toSet()
        if (queryTerms.isEmpty()) return null
        val excluded = setOf(source.id, goalPhotonId)
        return photons.asSequence()
            .filter { it.id !in excluded && it.phase != PhotonPhase.ARCHIVED }
            .filter(::isContextCandidate)
            .mapNotNull { candidate ->
                val candidateTerms = terms(candidate.content).toSet()
                val hits = queryTerms.intersect(candidateTerms).size
                if (hits == 0 || candidateTerms.isEmpty()) return@mapNotNull null
                val coverage = hits.toDouble() / queryTerms.size.toDouble()
                val specificity = hits.toDouble() / candidateTerms.size.toDouble()
                val memoryBoost = if (
                    "memory" in candidate.tags ||
                    "memory-atom" in candidate.tags ||
                    "memory-crystal" in candidate.tags
                ) 0.08 else 0.0
                val score = (
                    coverage * 0.68 +
                        specificity.coerceAtMost(1.0) * 0.12 +
                        candidate.confidence * 0.12 +
                        memoryBoost
                    ).coerceIn(0.0, 1.0)
                ScoredPhoton(candidate, score)
            }
            .filter { it.score >= MIN_EVIDENCE_SCORE }
            .sortedWith(
                compareByDescending<ScoredPhoton> { it.score }
                    .thenByDescending { it.photon.provenance.createdAt }
                    .thenBy { it.photon.id.value }
            )
            .firstOrNull()
    }

    private fun responseFact(
        move: LocalConversationMove,
        language: LanguageCode,
        evidence: Photon?,
    ): String {
        val resolvedLanguage = if (language == LanguageCode.EN) LanguageCode.EN else LanguageCode.DE
        val base = when (resolvedLanguage) {
            LanguageCode.DE -> when (move) {
                LocalConversationMove.GREETING -> "Hallo. Ich bin bereit."
                LocalConversationMove.GRATITUDE -> "Gern."
                LocalConversationMove.SOCIAL_CHECK_IN -> "Ich bin bereit und kann direkt weiterarbeiten."
                LocalConversationMove.CONTEXTUAL_REPLY -> "Ich habe deinen Gesprächsbeitrag im aktuellen Kontext erfasst."
            }
            LanguageCode.EN -> when (move) {
                LocalConversationMove.GREETING -> "Hello. I am ready."
                LocalConversationMove.GRATITUDE -> "You're welcome."
                LocalConversationMove.SOCIAL_CHECK_IN -> "I am ready and can continue directly."
                LocalConversationMove.CONTEXTUAL_REPLY -> "I captured your message in the current conversation context."
            }
            LanguageCode.UNKNOWN -> error("resolved above")
        }
        if (evidence == null) return base
        val excerpt = excerpt(evidence.content)
        return when (resolvedLanguage) {
            LanguageCode.DE -> "$base Dazu passt aus deinem lokalen Kontext: $excerpt"
            LanguageCode.EN -> "$base Relevant local context: $excerpt"
            LanguageCode.UNKNOWN -> error("resolved above")
        }
    }

    private fun isContextCandidate(photon: Photon): Boolean {
        if ("goal" in photon.tags || "conversation-response" in photon.tags) return false
        if ("scene-graph" in photon.tags || "tool-request" in photon.tags) return false
        return photon.mimeType.startsWith("text/") ||
            photon.mimeType == "application/vnd.lifeos.memory+text" ||
            "memory" in photon.tags ||
            "memory-atom" in photon.tags ||
            "memory-crystal" in photon.tags ||
            "chat:user" in photon.tags
    }

    private fun excerpt(content: String): String {
        val normalized = content.replace(WHITESPACE_REGEX, " ").trim()
        if (normalized.length <= MAX_EXCERPT_CHARS) return normalized
        return normalized.take(MAX_EXCERPT_CHARS - 1).trimEnd() + "…"
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT).replace("ß", "ss")

    private fun terms(value: String): List<String> = TERM_REGEX.findAll(normalize(value))
        .map { it.value }
        .filter { it.length >= 2 }
        .toList()

    private data class ScoredPhoton(val photon: Photon, val score: Double)

    companion object {
        const val RESPONSE_MIME = "application/vnd.lifeos.conversation-response+text"
        private const val MIN_EVIDENCE_SCORE = 0.34
        private const val MAX_EXCERPT_CHARS = 220
        private val TERM_REGEX = Regex("[\\p{L}\\p{N}]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val GREETING_WORDS = setOf("hallo", "hi", "hey", "moin", "servus", "hello")
        private val GRATITUDE_WORDS = setOf("danke", "dankeschon", "dankeschoen", "thanks")
        private val GREETING_PREFIXES = setOf(
            "guten morgen", "guten tag", "guten abend",
            "good morning", "good afternoon", "good evening",
        )
        private val SOCIAL_STOP_WORDS = setOf(
            "hallo", "hi", "hey", "moin", "servus", "hello", "danke", "dankeschon", "dankeschoen", "thanks",
            "thank", "you", "wie", "geht", "dir", "how", "are", "guten", "morgen", "tag", "abend", "good",
            "morning", "afternoon", "evening", "ich", "i", "bin", "am", "the", "a", "an", "und", "and",
        )
    }
}
