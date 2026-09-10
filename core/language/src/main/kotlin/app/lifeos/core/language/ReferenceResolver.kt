package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import java.time.Duration

class ReferenceExpressionExtractor {
    private val explicitIdRegex = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")

    fun extract(utterance: NormalizedUtterance, topIntent: IntentType): List<ReferenceExpression> {
        val words = utterance.tokens.filter { it.kind == TokenKind.WORD }.map { it.normalized }
        val wordSet = words.toSet()
        val preferred = buildSet {
            if (wordSet.any { it in setOf("bild", "bilder", "foto", "fotos", "image", "images", "photo", "photos", "picture", "pictures") }) add("image")
            if (wordSet.any { it in setOf("datei", "dateien", "file", "files", "dokument", "dokumente", "document", "documents") }) add("file")
            if (wordSet.any { it in setOf("apk", "apks", "paket", "package") }) add("apk")
            if (wordSet.any { it in setOf("modul", "module", "modules", "komponente", "komponenten", "component", "components") }) add("module")
            if (wordSet.any { it in setOf("ziel", "ziele", "goal", "goals", "plan", "aufgabe", "aufgaben", "task", "tasks") }) add("goal")
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
        if (
            preferred.isNotEmpty() &&
            wordSet.any { it in setOf("andere", "anderen", "anderes", "anderer", "other", "another") }
        ) {
            result += ReferenceExpression(ReferenceKind.OTHER, utterance.original, preferred, 0.94)
        } else {
            if (wordSet.any { it in setOf("dieses", "diese", "diesen", "dieser", "this", "these") } && preferred.isNotEmpty()) {
                result += ReferenceExpression(ReferenceKind.THIS, utterance.original, preferred, 0.88)
            }
            if (
                wordSet.any {
                    it in setOf(
                        "das", "die", "der", "den", "dem",
                        "jene", "jener", "jenes", "that", "those", "the",
                    )
                } && preferred.isNotEmpty()
            ) {
                result += ReferenceExpression(ReferenceKind.THAT, utterance.original, preferred, 0.82)
            }
        }
        if (topIntent == IntentType.CONTINUE && result.isEmpty()) {
            result += ReferenceExpression(ReferenceKind.PREVIOUS, utterance.original, setOf("goal"), 0.98)
        }
        return result.distinctBy { Triple(it.kind, it.rawText, it.preferredKinds) }
    }
}

/**
 * Semantic reference scorer. Runtime durability is deliberately outside this class: callers may
 * provide process-local or durable context candidates, then reuse the same deterministic ranking.
 */
class ReferenceResolver {
    fun resolve(expression: ReferenceExpression, context: LanguageContext): ResolvedReference {
        val candidates = rank(expression, context)
        val best = candidates.firstOrNull()
        return ResolvedReference(
            expression = expression,
            targetPhotonId = best?.first,
            score = best?.second ?: 0.0,
            alternatives = candidates.drop(1).take(3),
        )
    }

    fun rank(expression: ReferenceExpression, context: LanguageContext): List<Pair<PhotonId, Double>> {
        if (expression.kind == ReferenceKind.EXPLICIT_ID) {
            val id = PhotonId(expression.rawText)
            val found = context.items.any { it.photonId == id }
            return if (found) listOf(id to 1.0) else emptyList()
        }
        if (context.items.isEmpty()) return emptyList()

        return context.items
            .map { item -> item.photonId to score(item, expression, context) }
            .filter { it.second > 0.0 }
            .groupBy { it.first }
            .map { (id, scored) -> id to scored.maxOf { it.second } }
            .sortedWith(compareByDescending<Pair<PhotonId, Double>> { it.second }.thenBy { it.first.value })
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
        score *= item.confidence
        return score.coerceIn(0.0, 1.0)
    }
}
