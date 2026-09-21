package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import java.time.Duration
import java.util.Locale

class ReferenceExpressionExtractor {
    private val explicitIdRegex = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")

    private val imageNouns = setOf(
        "bild", "bilder", "foto", "fotos", "image", "images", "photo", "photos", "picture", "pictures"
    )
    private val fileNouns = setOf(
        "datei", "dateien", "file", "files", "dokument", "dokumente", "document", "documents"
    )
    private val apkNouns = setOf("apk", "apks", "paket", "package")
    private val moduleNouns = setOf(
        "modul", "module", "modules", "komponente", "komponenten", "component", "components"
    )
    private val goalNouns = setOf(
        "ziel", "ziele", "goal", "goals", "plan", "aufgabe", "aufgaben", "task", "tasks"
    )
    private val resultNouns = setOf(
        "ergebnis", "ergebnisse", "antwort", "antworten", "result", "results", "answer", "answers"
    )
    private val preferredNouns = mapOf(
        "image" to imageNouns,
        "file" to fileNouns,
        "apk" to apkNouns,
        "module" to moduleNouns,
        "goal" to goalNouns,
        "result" to resultNouns,
    )

    fun extract(utterance: NormalizedUtterance, topIntent: IntentType): List<ReferenceExpression> {
        val words = utterance.tokens.filter { it.kind == TokenKind.WORD }.map { it.normalized }
        val wordSet = words.toSet()
        val preferred = buildSet {
            if (wordSet.any { it in imageNouns }) add("image")
            if (wordSet.any { it in fileNouns }) add("file")
            if (wordSet.any { it in apkNouns }) add("apk")
            if (wordSet.any { it in moduleNouns }) add("module")
            if (wordSet.any { it in goalNouns }) add("goal")
            if (wordSet.any { it in resultNouns }) add("result")
        }
        val result = mutableListOf<ReferenceExpression>()

        explicitIdRegex.findAll(utterance.original).forEach { match ->
            result += ReferenceExpression(ReferenceKind.EXPLICIT_ID, match.value, preferred, 1.0)
        }
        if (wordSet.any { it in setOf("gestern", "yesterday") }) {
            result += ReferenceExpression(ReferenceKind.YESTERDAY, utterance.original, preferred, 0.96)
        }
        if (wordSet.any { it in setOf("vorher", "zuvor", "previous", "before") }) {
            result += ReferenceExpression(ReferenceKind.PREVIOUS, utterance.original, preferred, 0.92)
        }
        if (wordSet.any { it in setOf("letzte", "letzten", "letztes", "letzter", "last") }) {
            result += ReferenceExpression(ReferenceKind.LAST_RESULT, utterance.original, preferred, 0.90)
        }

        val hasOther = hasNearbyPreferredNoun(
            words = words,
            preferredKinds = preferred,
            markers = setOf("andere", "anderen", "anderes", "anderer", "other", "another"),
            maxGap = 1,
        )
        if (hasOther) {
            result += ReferenceExpression(ReferenceKind.OTHER, utterance.original, preferred, 0.94)
        } else {
            if (
                hasNearbyPreferredNoun(
                    words = words,
                    preferredKinds = preferred,
                    markers = setOf("dieses", "diese", "diesen", "dieser", "this", "these"),
                    maxGap = 2,
                )
            ) {
                result += ReferenceExpression(ReferenceKind.THIS, utterance.original, preferred, 0.88)
            }
            if (
                hasNearbyPreferredNoun(
                    words = words,
                    preferredKinds = preferred,
                    markers = setOf(
                        "das", "die", "der", "den", "dem",
                        "jene", "jener", "jenes", "that", "those", "the",
                    ),
                    maxGap = 2,
                )
            ) {
                result += ReferenceExpression(ReferenceKind.THAT, utterance.original, preferred, 0.82)
            }
        }
        if (result.isEmpty()) {
            contextualDeictic(words, topIntent)?.let { (kind, preferredKinds) ->
                result += ReferenceExpression(kind, utterance.original, preferredKinds, 0.80)
            }
        }
        if (topIntent == IntentType.CONTINUE && result.isEmpty()) {
            result += ReferenceExpression(ReferenceKind.PREVIOUS, utterance.original, setOf("goal"), 0.98)
        }
        return result.distinctBy { Triple(it.kind, it.rawText, it.preferredKinds) }
    }

    /**
     * Detects noun-free conversational follow-ups such as "Kannst du das genauer erklären?" or
     * "Can you explain that in more detail?". The fallback is deliberately restricted to QUERY
     * and CONVERSATION and requires a follow-up cue (or a terminal deictic) so ordinary articles
     * such as "das Wetter" / "the weather" never become references merely because context exists.
     */
    private fun contextualDeictic(
        words: List<String>,
        topIntent: IntentType,
    ): Pair<ReferenceKind, Set<String>>? {
        if (words.isEmpty()) return null
        val allowed = topIntent in setOf(
            IntentType.QUERY,
            IntentType.CONVERSATION,
            IntentType.TRANSFORM_IMAGE,
        )
        if (!allowed) return null

        val thisMarkers = setOf("dies", "dieses", "diese", "diesen", "dieser", "this", "these")
        val thatMarkers = setOf(
            "das", "jene", "jener", "jenes", "that", "those",
            "es", "it", "dazu", "damit", "davon", "darüber",
        )
        val preferredKinds = if (topIntent == IntentType.TRANSFORM_IMAGE) setOf("image") else emptySet()
        val followUpCues = setOf(
            "erkläre", "erklären", "erklärst", "erklärt", "erklärung",
            "genauer", "näher", "ausführen", "ausführlicher", "meinen", "meinst",
            "bedeuten", "bedeutet", "mehr", "detail", "details", "weiter",
            "explain", "clarify", "elaborate", "expand", "mean", "means", "more", "further",
            "true", "correct", "wahr", "richtig",
        )

        words.forEachIndexed { index, word ->
            val kind = when (word) {
                in thisMarkers -> ReferenceKind.THIS
                in thatMarkers -> ReferenceKind.THAT
                else -> null
            } ?: return@forEachIndexed

            if (topIntent == IntentType.TRANSFORM_IMAGE) {
                val nearbyTransformCue = words.any {
                    it in setOf(
                        "heller", "dunkler", "wärmer", "waermer", "schaerfer", "schärfer",
                        "größer", "groesser", "kleiner", "brighter", "darker", "warmer",
                        "sharper", "larger", "smaller",
                    )
                }
                if (nearbyTransformCue) return kind to preferredKinds
            }
            if (index == words.lastIndex) return kind to preferredKinds
            val start = maxOf(0, index - 3)
            val end = minOf(words.lastIndex, index + 3)
            val nearbyFollowUpCue = (start..end).any { cueIndex ->
                cueIndex != index && words[cueIndex] in followUpCues
            }
            val compactStateFollowUp = words.getOrNull(index + 1) in setOf("so", "true", "correct", "wahr", "richtig")
            if (nearbyFollowUpCue || compactStateFollowUp) return kind to preferredKinds
        }
        return null
    }

    private fun hasNearbyPreferredNoun(
        words: List<String>,
        preferredKinds: Set<String>,
        markers: Set<String>,
        maxGap: Int,
    ): Boolean {
        if (preferredKinds.isEmpty()) return false
        return words.indices.any { markerIndex ->
            if (words[markerIndex] !in markers) return@any false
            val end = minOf(words.lastIndex, markerIndex + maxGap + 1)
            if (markerIndex + 1 > end) return@any false
            (markerIndex + 1..end).any { nounIndex ->
                preferredKinds.any { kind -> words[nounIndex] in preferredNouns[kind].orEmpty() }
            }
        }
    }
}

/**
 * Semantic reference scorer. Runtime durability is deliberately outside this class: callers may
 * provide process-local or durable context candidates, then reuse the same deterministic ranking.
 */
class ReferenceResolver {
    fun resolve(
        expression: ReferenceExpression,
        context: LanguageContext,
        discourse: DiscourseStateGraph = DiscourseStateGraph.empty(),
    ): ResolvedReference {
        val revisionCandidates = rankRevisionRefs(expression, context, discourse)
        if (revisionCandidates.isNotEmpty()) {
            val best = revisionCandidates.first()
            val compatibility = revisionCandidates
                .groupBy { it.first.photonId }
                .map { (id, scored) -> id to scored.maxOf { it.second } }
                .sortedWith(compareByDescending<Pair<PhotonId, Double>> { it.second }.thenBy { it.first.value })
            return ResolvedReference(
                expression = expression,
                targetPhotonId = best.first.photonId,
                score = best.second,
                alternatives = compatibility
                    .filterNot { it.first == best.first.photonId }
                    .take(3),
                targetPhotonRef = best.first,
                revisionAlternatives = revisionCandidates.drop(1).take(3),
            )
        }

        // Compatibility-only path for legacy/in-memory contexts that predate revision binding.
        // Never invent a revision: execution remains blocked because targetPhotonRef stays null.
        val legacy = rankLegacyIds(expression, context)
        val best = legacy.firstOrNull()
        return ResolvedReference(
            expression = expression,
            targetPhotonId = best?.first,
            score = best?.second ?: 0.0,
            alternatives = legacy.drop(1).take(3),
            targetPhotonRef = null,
            revisionAlternatives = emptyList(),
        )
    }

    fun rank(
        expression: ReferenceExpression,
        context: LanguageContext,
        discourse: DiscourseStateGraph = DiscourseStateGraph.empty(),
    ): List<Pair<PhotonId, Double>> {
        val revisionRank = rankRevisionRefs(expression, context, discourse)
        if (revisionRank.isNotEmpty()) {
            return revisionRank
                .groupBy { it.first.photonId }
                .map { (id, scored) -> id to scored.maxOf { it.second } }
                .sortedWith(compareByDescending<Pair<PhotonId, Double>> { it.second }.thenBy { it.first.value })
        }
        return rankLegacyIds(expression, context)
    }

    private fun rankLegacyIds(
        expression: ReferenceExpression,
        context: LanguageContext,
    ): List<Pair<PhotonId, Double>> {
        if (context.items.isEmpty()) return emptyList()
        val candidates = ReferenceCandidateIndexV3(context)
            .candidates(expression, MAX_REFERENCE_CANDIDATES)
            .filterNot { candidate ->
                val item = candidate.item
                expression.kind == ReferenceKind.PREVIOUS &&
                    "goal" in expression.preferredKinds &&
                    ("intent:continue" in item.tags || "goal-resumed" in item.tags)
            }
        return candidates
            .map { candidate ->
                candidate.item.photonId to score(
                    item = candidate.item,
                    expression = expression,
                    context = context,
                    indexScore = candidate.indexScore,
                    discourse = discourse,
                )
            }
            .filter { it.second > 0.0 }
            .groupBy { it.first }
            .map { (id, scored) -> id to scored.maxOf { it.second } }
            .sortedWith(
                compareByDescending<Pair<PhotonId, Double>> { it.second }
                    .thenBy { it.first.value }
            )
            .take(MAX_RANKED_REFERENCES)
    }

    fun rankRevisionRefs(
        expression: ReferenceExpression,
        context: LanguageContext,
        discourse: DiscourseStateGraph = DiscourseStateGraph.empty(),
    ): List<Pair<PhotonRevisionRef, Double>> {
        if (context.items.isEmpty()) return emptyList()

        val candidates = ReferenceCandidateIndexV3(context)
            .candidates(expression, MAX_REFERENCE_CANDIDATES)
            .filterNot { candidate ->
                val item = candidate.item
                expression.kind == ReferenceKind.PREVIOUS &&
                    "goal" in expression.preferredKinds &&
                    ("intent:continue" in item.tags || "goal-resumed" in item.tags)
            }

        return candidates
            .mapNotNull { candidate ->
                val item = candidate.item
                val ref = item.revisionRef ?: return@mapNotNull null
                ref to score(
                    item = item,
                    expression = expression,
                    context = context,
                    indexScore = candidate.indexScore,
                )
            }
            .filter { it.second > 0.0 }
            .groupBy { it.first }
            .map { (ref, scored) -> ref to scored.maxOf { it.second } }
            .sortedWith(
                compareByDescending<Pair<PhotonRevisionRef, Double>> { it.second }
                    .thenByDescending { it.first.revision }
                    .thenBy { it.first.photonId.value }
            )
            .take(MAX_RANKED_REFERENCES)
    }

    private fun score(
        item: LanguageContextItem,
        expression: ReferenceExpression,
        context: LanguageContext,
        indexScore: Double,
        discourse: DiscourseStateGraph = DiscourseStateGraph.empty(),
    ): Double {
        var score = 0.05 + indexScore * 0.10
        val expressionTerms = referenceTerms(expression.rawText)
        val semanticKindMatch =
            expression.preferredKinds.isEmpty() ||
                item.kind in expression.preferredKinds ||
                item.tags.any { it in expression.preferredKinds } ||
                item.semanticTypes.any { semantic ->
                    expression.preferredKinds.any { preferred ->
                        semantic == preferred || semantic.endsWith(":" + preferred)
                    }
                }
        if (semanticKindMatch) {
            score += 0.30
        } else {
            score -= 0.24
        }

        if (expressionTerms.isNotEmpty()) {
            val overlap = expressionTerms.count(item.normalizedTerms::contains).toDouble() /
                expressionTerms.size.toDouble()
            val exactPreferredTerms = expressionTerms.filterNot { it in REFERENCE_STOP_WORDS }
            val exactOverlap = exactPreferredTerms.count(item.normalizedTerms::contains)
            score += overlap * 0.26
            if (exactOverlap > 0) score += minOf(0.18, exactOverlap * 0.06)
        }
        if (expression.kind == ReferenceKind.OTHER) {
            score += if (item.active) -0.18 else 0.20
        } else if (item.active) {
            score += 0.08
        }
        if (
            item.photonId == context.activeGoalId &&
            expression.kind in setOf(
                ReferenceKind.PREVIOUS,
                ReferenceKind.LAST_RESULT,
                ReferenceKind.THIS,
                ReferenceKind.THAT,
            )
        ) {
            score += 0.32
        }

        val ageHours = Duration.between(item.createdAt, context.now)
            .toMinutes()
            .coerceAtLeast(0)
            .toDouble() / 60.0
        val recency = (1.0 - ageHours / 168.0).coerceIn(0.0, 1.0)
        score += recency * 0.22
        if (expression.kind == ReferenceKind.YESTERDAY) {
            score += if (ageHours in 8.0..40.0) 0.28 else -0.20
        }
        if (expression.kind == ReferenceKind.LAST_RESULT && "result" in item.tags) score += 0.18

        if (expression.kind == ReferenceKind.LAST_RESULT && "result" in item.semanticTypes) {
            score += 0.08
        }
        if (item.goalId != null && item.goalId == context.activeGoalId) {
            score += 0.08
        }
        if (item.matterId != null && expressionTerms.any { it in MATTER_TERMS }) {
            score += 0.08
        }

        val discourseBonus = item.revisionRef
            ?.let(discourse::score)
            ?.times(0.22)
            ?: 0.0
        score += discourseBonus

        val confidenceWeighted = score * item.confidence
        val activeGoalAnchor =
            item.photonId == context.activeGoalId &&
                expression.kind == ReferenceKind.PREVIOUS &&
                "goal" in expression.preferredKinds
        val resolved = if (activeGoalAnchor) {
            maxOf(confidenceWeighted, expression.confidence)
        } else {
            confidenceWeighted
        }
        return resolved.coerceIn(0.0, 1.0)
    }

    private fun referenceTerms(value: String): Set<String> =
        TERM_REGEX.findAll(value)
            .map { it.value.lowercase(Locale.ROOT).replace("ß", "ss") }
            .filter { it.length > 1 }
            .filterNot { it in REFERENCE_STOP_WORDS }
            .toSet()

    private companion object {
        const val MAX_REFERENCE_CANDIDATES = 32
        const val MAX_RANKED_REFERENCES = 12
        val TERM_REGEX = Regex("[\\p{L}\\p{N}]+")
        val REFERENCE_STOP_WORDS = setOf(
            "das", "die", "der", "den", "dem", "dies", "diese", "dieses", "diesen",
            "andere", "anderen", "bitte", "mit", "und", "oder", "mach", "mache",
            "it", "this", "that", "the", "other", "with", "and", "or", "please", "make",
        )
        val MATTER_TERMS = setOf(
            "bescheid", "jobcenter", "behorde", "behoerde", "schuld", "forderung",
            "vertrag", "frist", "matter", "case", "debt", "claim",
        )
    }
}
