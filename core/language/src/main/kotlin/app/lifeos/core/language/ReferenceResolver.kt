package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import java.time.Duration

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
            conversationalDeicticKind(words, topIntent)?.let { kind ->
                result += ReferenceExpression(kind, utterance.original, emptySet(), 0.80)
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
    private fun conversationalDeicticKind(words: List<String>, topIntent: IntentType): ReferenceKind? {
        if (topIntent !in setOf(IntentType.QUERY, IntentType.CONVERSATION) || words.isEmpty()) return null

        val thisMarkers = setOf("dies", "dieses", "diese", "diesen", "dieser", "this", "these")
        val thatMarkers = setOf(
            "das", "jene", "jener", "jenes", "that", "those",
            "es", "it", "dazu", "damit", "davon", "darüber",
        )
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

            if (index == words.lastIndex) return kind
            val start = maxOf(0, index - 3)
            val end = minOf(words.lastIndex, index + 3)
            val nearbyFollowUpCue = (start..end).any { cueIndex ->
                cueIndex != index && words[cueIndex] in followUpCues
            }
            val compactStateFollowUp = words.getOrNull(index + 1) in setOf("so", "true", "correct", "wahr", "richtig")
            if (nearbyFollowUpCue || compactStateFollowUp) return kind
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
    fun resolve(expression: ReferenceExpression, context: LanguageContext): ResolvedReference {
        val revisionCandidates = rankRevisionRefs(expression, context)
        val best = revisionCandidates.firstOrNull()
        val compatibility = revisionCandidates
            .groupBy { it.first.photonId }
            .map { (id, scored) -> id to scored.maxOf { it.second } }
            .sortedWith(compareByDescending<Pair<PhotonId, Double>> { it.second }.thenBy { it.first.value })
        return ResolvedReference(
            expression = expression,
            targetPhotonId = best?.first?.photonId,
            score = best?.second ?: 0.0,
            alternatives = compatibility
                .filterNot { it.first == best?.first?.photonId }
                .take(3),
            targetPhotonRef = best?.first,
            revisionAlternatives = revisionCandidates.drop(1).take(3),
        )
    }

    fun rank(expression: ReferenceExpression, context: LanguageContext): List<Pair<PhotonId, Double>> =
        rankRevisionRefs(expression, context)
            .groupBy { it.first.photonId }
            .map { (id, scored) -> id to scored.maxOf { it.second } }
            .sortedWith(compareByDescending<Pair<PhotonId, Double>> { it.second }.thenBy { it.first.value })

    fun rankRevisionRefs(
        expression: ReferenceExpression,
        context: LanguageContext,
    ): List<Pair<PhotonRevisionRef, Double>> {
        if (expression.kind == ReferenceKind.EXPLICIT_ID) {
            val id = PhotonId(expression.rawText)
            val found = context.items
                .filter { it.photonId == id }
                .mapNotNull { item -> item.revisionRef?.let { it to 1.0 } }
                .maxByOrNull { it.first.revision }
            return found?.let(::listOf).orEmpty()
        }
        if (context.items.isEmpty()) return emptyList()

        val candidates = context.items.filterNot { item ->
            expression.kind == ReferenceKind.PREVIOUS &&
                "goal" in expression.preferredKinds &&
                ("intent:continue" in item.tags || "goal-resumed" in item.tags)
        }

        return candidates
            .mapNotNull { item ->
                val ref = item.revisionRef ?: return@mapNotNull null
                ref to score(item, expression, context)
            }
            .filter { it.second > 0.0 }
            .groupBy { it.first }
            .map { (ref, scored) -> ref to scored.maxOf { it.second } }
            .sortedWith(
                compareByDescending<Pair<PhotonRevisionRef, Double>> { it.second }
                    .thenByDescending { it.first.revision }
                    .thenBy { it.first.photonId.value }
            )
    }

    private fun score(item: LanguageContextItem, expression: ReferenceExpression, context: LanguageContext): Double {
        var score = 0.05
        if (
            expression.preferredKinds.isEmpty() ||
            item.kind in expression.preferredKinds ||
            item.tags.any { it in expression.preferredKinds }
        ) {
            score += 0.34
        } else {
            score -= 0.20
        }
        if (expression.kind == ReferenceKind.OTHER) {
            score += if (item.active) -0.18 else 0.20
        } else if (item.active) {
            score += 0.16
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
}
