package app.lifeos.core.language

import kotlin.math.min

class RuleBasedIntentClassifier {
    private data class Rule(
        val intent: IntentType,
        val weight: Double,
        val reason: String,
        val predicate: (Set<String>, String, Int) -> Boolean,
    )

    private val rules = listOf(
        Rule(IntentType.CREATE_IMAGE, 0.62, "image creation verb + image noun") { w, _, _ ->
            w.any { it in setOf("erzeuge", "erstelle", "generiere", "zeichne", "render", "create", "generate", "draw", "render") } &&
                w.any { it in setOf("bild", "foto", "grafik", "image", "picture", "photo") }
        },
        Rule(IntentType.CREATE_IMAGE, 0.28, "visual scene vocabulary") { w, _, _ ->
            w.any { it in setOf("bild", "foto", "image", "picture") } &&
                w.any { it in setOf("szene", "scene", "menschen", "people", "person", "leute", "spielen", "playing") }
        },
        Rule(IntentType.TRANSFORM_IMAGE, 0.72, "image transformation vocabulary") { w, _, _ ->
            w.any { it in setOf("bild", "foto", "image", "photo") } &&
                w.any { it in setOf("andere", "aendere", "bearbeite", "wärmer", "waermer", "heller", "dunkler", "schaerfer", "schärfer", "edit", "change", "warmer", "brighter", "darker", "sharper") }
        },
        Rule(IntentType.SEARCH, 0.75, "explicit search verb") { w, _, _ ->
            w.any { it in setOf("suche", "finde", "recherchiere", "deepsearch", "search", "find", "research", "lookup") }
        },
        Rule(IntentType.CONTINUE, 0.90, "short continuation command") { w, _, count ->
            count <= 3 && w.any { it in setOf("weiter", "los", "fortsetzen", "continue", "proceed", "go") }
        },
        Rule(IntentType.BUILD_OR_IMPLEMENT, 0.72, "implementation/build verb") { w, _, _ ->
            w.any { it in setOf("implementiere", "implementieren", "baue", "bauen", "entwickle", "entwickeln", "programmiere", "build", "implement", "develop", "code") }
        },
        Rule(IntentType.SCHEDULE, 0.78, "schedule/reminder vocabulary") { w, _, _ ->
            w.any { it in setOf("erinnere", "termin", "plane", "planen", "schedule", "remind", "appointment", "calendar") }
        },
        Rule(IntentType.COMMUNICATE, 0.72, "communication verb") { w, _, _ ->
            w.any { it in setOf("schreibe", "sende", "antworte", "mail", "email", "nachricht", "send", "reply", "message") }
        },
        Rule(IntentType.STORE_OR_REMEMBER, 0.80, "memory request vocabulary") { w, _, _ ->
            w.any { it in setOf("merke", "speichere", "erinnere", "remember", "store", "save") }
        },
        Rule(IntentType.QUERY, 0.55, "question marker") { w, text, _ ->
            text.trimEnd().endsWith("?") || w.any { it in setOf("wie", "warum", "was", "wer", "wo", "wann", "wieso", "how", "why", "what", "who", "where", "when") }
        },
    )

    fun classify(utterance: NormalizedUtterance): List<IntentEvidence> {
        val words = utterance.tokens.filter { it.kind == TokenKind.WORD }.map { it.normalized }.toSet()
        val reasons = linkedMapOf<IntentType, MutableList<String>>()
        val scores = linkedMapOf<IntentType, Double>()
        for (rule in rules) {
            if (!rule.predicate(words, utterance.original, utterance.tokens.size)) continue
            scores[rule.intent] = min(1.0, (scores[rule.intent] ?: 0.0) + rule.weight)
            reasons.getOrPut(rule.intent) { mutableListOf() }.add(rule.reason)
        }
        if (scores.isEmpty()) {
            return listOf(IntentEvidence(IntentType.UNKNOWN, 0.35, listOf("no deterministic intent rule matched")))
        }
        return scores.entries
            .map { (intent, score) -> IntentEvidence(intent, score, reasons[intent].orEmpty()) }
            .sortedWith(compareByDescending<IntentEvidence> { it.score }.thenBy { it.intent.name })
    }
}
