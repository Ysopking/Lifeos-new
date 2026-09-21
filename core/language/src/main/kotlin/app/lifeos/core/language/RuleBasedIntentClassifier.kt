package app.lifeos.core.language

import kotlin.math.min

class RuleBasedIntentClassifier {
    private data class Rule(
        val intent: IntentType,
        val weight: Double,
        val reason: String,
        val predicate: (Set<String>, String, Int) -> Boolean,
    )

    private val imageNouns = setOf("bild", "bilder", "foto", "fotos", "grafik", "grafiken", "image", "images", "picture", "pictures", "photo", "photos")
    private val scheduleWords = setOf("termin", "plane", "planen", "schedule", "remind", "appointment", "calendar")
    private val greetingWords = setOf("hallo", "hi", "hey", "moin", "servus", "hello")
    private val courtesyWords = setOf("danke", "dankeschön", "dankeschoen", "thanks")
    private val taskWords = setOf(
        "erzeuge", "erstelle", "generiere", "zeichne", "render", "rendere", "create", "generate", "draw",
        "ändere", "aendere", "bearbeite", "edit", "change",
        "suche", "finde", "recherchiere", "deepsearch", "nachsehen", "nachschauen", "schau", "sieh", "guck", "prüf", "pruef", "check", "search", "find", "research", "lookup",
        "weiter", "los", "fortsetzen", "continue", "proceed", "go",
        "implementiere", "implementieren", "baue", "bauen", "entwickle", "entwickeln", "programmiere", "umsetzen", "umsetze", "umsetz", "build", "implement", "develop", "code",
        "termin", "plane", "planen", "schedule", "remind", "appointment", "calendar", "erinnere",
        "schreibe", "schreib", "sende", "schicke", "schick", "antworte", "mail", "maile", "email", "nachricht", "teile", "teilen", "share", "send", "reply", "message",
        "merke", "speichere", "remember", "store", "save",
    )
    private val temporalCueRegex = Regex(
        "(?i)(?:\\b(?:heute|morgen|today|tomorrow)\\b|\\b(?:[01]?\\d|2[0-3]):[0-5]\\d\\b|\\b(?:[01]?\\d|2[0-3])\\s*(?:uhr|am|pm)\\b|\\b(?:0?[1-9]|[12]\\d|3[01])[./-](?:0?[1-9]|1[0-2])(?:[./-](?:19|20)\\d{2})?\\b)"
    )

    private val rules = listOf(
        Rule(IntentType.CREATE_IMAGE, 0.62, "image creation verb + image noun") { w, _, _ ->
            w.any { it in setOf("erzeuge", "erstelle", "generiere", "zeichne", "render", "rendere", "create", "generate", "draw", "render") } &&
                w.any { it in imageNouns }
        },
        Rule(IntentType.CREATE_IMAGE, 0.28, "visual scene vocabulary") { w, _, _ ->
            w.any { it in imageNouns } &&
                w.any { it in setOf("szene", "szenen", "scene", "scenes", "menschen", "people", "person", "personen", "leute", "spielen", "playing") }
        },
        Rule(IntentType.TRANSFORM_IMAGE, 0.72, "image transformation vocabulary") { w, _, _ ->
            w.any { it in imageNouns } &&
                w.any { it in setOf("ändere", "aendere", "bearbeite", "wärmer", "waermer", "heller", "dunkler", "schaerfer", "schärfer", "edit", "change", "warmer", "brighter", "darker", "sharper") }
        },
        Rule(IntentType.SEARCH, 0.75, "explicit search verb") { w, _, _ ->
            w.any {
                it in setOf(
                    "suche", "finde", "recherchiere", "deepsearch", "nachsehen", "nachschauen",
                    "schau", "sieh", "guck", "prüf", "pruef", "prüfe", "pruefe", "check", "checke",
                    "search", "find", "research", "lookup",
                )
            }
        },
        Rule(IntentType.CONTINUE, 0.90, "short continuation command") { w, _, count ->
            count <= 3 && w.any { it in setOf("weiter", "los", "fortsetzen", "continue", "proceed", "go") }
        },
        Rule(IntentType.BUILD_OR_IMPLEMENT, 0.72, "implementation/build verb") { w, _, _ ->
            w.any {
                it in setOf(
                    "implementiere", "implementieren", "baue", "bauen", "entwickle", "entwickeln",
                    "programmiere", "umsetzen", "umsetze", "umsetz", "fertigstellen",
                    "build", "implement", "develop", "code",
                )
            }
        },
        Rule(IntentType.SCHEDULE, 0.88, "reminder target or temporal scheduling cue") { w, text, _ ->
            w.any { it in scheduleWords } ||
                ("erinnere" in w && ("mich" in w || temporalCueRegex.containsMatchIn(text)))
        },
        Rule(IntentType.COMMUNICATE, 0.82, "explicit communication or share verb") { w, _, _ ->
            w.any {
                it in setOf(
                    "schreibe", "schreib", "sende", "schicke", "schick", "antworte", "mail", "maile", "email", "nachricht",
                    "teile", "teilen", "share", "send", "reply", "message",
                )
            }
        },
        Rule(IntentType.STORE_OR_REMEMBER, 0.80, "memory request vocabulary") { w, _, _ ->
            w.any { it in setOf("merke", "speichere", "remember", "store", "save") } ||
                ("erinnere" in w && "dich" in w && "mich" !in w)
        },
        Rule(IntentType.CONVERSATION, 0.86, "standalone greeting or courtesy") { w, text, count ->
            val normalized = text.trim().lowercase()
            val greetingPhrase = w.any { it in greetingWords } ||
                normalized.startsWith("guten morgen") || normalized.startsWith("guten tag") ||
                normalized.startsWith("guten abend") || normalized.startsWith("good morning") ||
                normalized.startsWith("good afternoon") || normalized.startsWith("good evening")
            val courtesyPhrase = w.any { it in courtesyWords } || normalized.startsWith("thank you")
            count <= 6 && (greetingPhrase || courtesyPhrase) && w.none { it in taskWords }
        },
        Rule(IntentType.CONVERSATION, 0.84, "short social check-in") { _, text, count ->
            val normalized = text.trim().lowercase()
            count <= 8 && (normalized.contains("wie geht") || normalized.contains("how are you"))
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
