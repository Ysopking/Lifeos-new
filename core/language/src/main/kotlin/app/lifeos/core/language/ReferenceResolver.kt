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
            if (wordSet.any { it in setOf("ziel", "ziele", "goal", "goals", "plan", "aufgabe", "aufgaben", "task", "tasks") }) add("goal")
        }
        val result = mutableListOf<ReferenceExpression>()

        explicitIdRegex.findAll(utterance.original).forEach { match ->
            result += ReferenceExpression(ReferenceKind.EXPLICIT_ID, match.value, preferred, 1.0)
        }
        if (wordSet.any { it in setOf("gestern", "yesterday") } && preferred.isNotEmpty()) {
            result += ReferenceExpression(ReferenceKind.YESTERDAY, "yesterday", preferred, 0.96)
        }
        if (wordSet.any { it in setOf("vorher", "zuvor", "previous", "before") }) {
            result += ReferenceExpression(ReferenceKind.PREVIOUS, "previous", preferred, 0.92)
        }
        if (wordSet.any { it in setOf("letzte", "letzten", "letztes", "letzter", "last") }) {
            result += ReferenceExpression(ReferenceKind.LAST_RESULT, "last", preferred, 0.90)
        }
        if (wordSet.any { it in setOf("dieses", "diese", "diesen", "dieser", "this", "these") } && preferred.isNotEmpty()) {
            result += ReferenceExpression(ReferenceKind.THIS, "this", preferred, 0.88)
        }
        if (wordSet.any { it in setOf("das", "jene", "jener", "jenes", "that", "those") } && preferred.isNotEmpty()) {
            result += ReferenceExpression(ReferenceKind.THAT, "that", preferred, 0.82)
        }
        if (topIntent == IntentType.CONTINUE && result.isEmpty()) {
            result += ReferenceExpression(ReferenceKind.PREVIOUS, utterance.original, setOf("goal"), 0.98)
        }
        return result.distinctBy { Triple(it.kind, it.rawText, it.preferredKinds) }
    }
}

class ReferenceResolver {
    fun resolve(expression: ReferenceExpression, context: LanguageContext): ResolvedReference {
        if (expression.kind == ReferenceKind.EXPLICIT_ID) {
            val id = PhotonId(expression.rawText)
            val found = context.items.firstOrNull { it.photonId == id }
            return ResolvedReference(expression, found?.photonId, if (found != null) 1.0 else 0.0)
        }
        if (context.items.isEmpty()) return ResolvedReference(expression, null, 0.0)

        val candidates = context.items.map { item -> item.photonId to score(item, expression, context) }
            .filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Pair<PhotonId, Double>> { it.second }.thenBy { it.first.value })
        val best = candidates.firstOrNull()
        return ResolvedReference(
            expression = expression,
            targetPhotonId = best?.first,
            score = best?.second ?: 0.0,
            alternatives = candidates.drop(1).take(3),
        )
    }

    private fun score(item: LanguageContextItem, expression: ReferenceExpression, context: LanguageContext): Double {
        var score = 0.05
        if (expression.preferredKinds.isEmpty() || item.kind in expression.preferredKinds || item.tags.any { it in expression.preferredKinds }) {
            score += 0.34
        } else {
            score -= 0.20
        }
        if (item.active) score += 0.16
        if (item.photonId == context.activeGoalId && expression.kind in setOf(ReferenceKind.PREVIOUS, ReferenceKind.LAST_RESULT, ReferenceKind.THIS, ReferenceKind.THAT)) {
            score += 0.32
        }

        val ageHours = Duration.between(item.createdAt, context.now).toMinutes().coerceAtLeast(0).toDouble() / 60.0
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
