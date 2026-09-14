package app.lifeos.core.language

import kotlin.math.abs

/**
 * Semantic target for deterministic language generation.
 *
 * Generation deliberately starts from the same structures produced by [LanguageUnderstandingEngine]
 * instead of from free-form templates. The surface realizer proposes bounded candidates and every
 * candidate is parsed again through the productive understanding engine. The winning sentence is
 * therefore selected by semantic round-trip preservation, not by an opaque language model.
 */
data class LanguageGenerationTarget(
    val intent: IntentType,
    val language: LanguageCode,
    val entities: List<SemanticEntity> = emptyList(),
    val constraints: List<GoalConstraint> = emptyList(),
    val semanticTags: Set<String> = emptySet(),
    val semanticGraph: LanguageSemanticGraph = LanguageSemanticGraph.empty(language),
    val confidence: Double = 1.0,
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(semanticTags.none { it.isBlank() })
    }

    companion object {
        fun fromGoal(goal: GoalFrame): LanguageGenerationTarget = LanguageGenerationTarget(
            intent = goal.intent,
            language = goal.language,
            entities = goal.entities,
            constraints = goal.constraints,
            semanticTags = goal.constraints.asSequence()
                .filter { it.key.startsWith("field.semantic.") }
                .map { it.key.removePrefix("field.semantic.").uppercase() }
                .toSet(),
            semanticGraph = goal.semanticGraph,
            confidence = goal.confidence,
        )
    }
}

data class LanguageGenerationCandidate(
    val text: String,
    val roundTrip: LanguageUnderstandingResult,
    val semanticPreservation: Double,
    val intentPreserved: Boolean,
    val entityCoverage: Double,
    val semanticCoverage: Double,
    val semanticGraphCoverage: Double,
) {
    init {
        require(text.isNotBlank())
        require(semanticPreservation.isFinite() && semanticPreservation in 0.0..1.0)
        require(entityCoverage.isFinite() && entityCoverage in 0.0..1.0)
        require(semanticCoverage.isFinite() && semanticCoverage in 0.0..1.0)
        require(semanticGraphCoverage.isFinite() && semanticGraphCoverage in 0.0..1.0)
    }
}

data class LanguageGenerationResult(
    val text: String,
    val target: LanguageGenerationTarget,
    val winner: LanguageGenerationCandidate,
    val alternatives: List<LanguageGenerationCandidate>,
) {
    init {
        require(text == winner.text)
        require(alternatives.none { it.text == winner.text })
    }
}

/**
 * Reverse path for LIFEOS language processing.
 *
 * Understanding is bottom-up: surface text -> linguistic field -> semantic goal/graph.
 * Generation is top-down: semantic graph/goal -> lexical/surface candidates -> linguistic field ->
 * semantic verification. This makes generation auditable and keeps the same language engine on
 * both sides of the communication loop.
 */
class LanguageGenerationEngine(
    private val understanding: LanguageUnderstandingEngine = LanguageUnderstandingEngine(),
    private val lexicon: DeterministicLinguisticFieldLexicon = DeterministicLinguisticFieldLexicon(),
    private val maximumCandidates: Int = 12,
) {
    init { require(maximumCandidates in 1..64) }

    fun generate(
        goal: GoalFrame,
        context: LanguageContext = LanguageContext(),
    ): LanguageGenerationResult = generate(LanguageGenerationTarget.fromGoal(goal), context)

    fun generate(
        target: LanguageGenerationTarget,
        context: LanguageContext = LanguageContext(),
    ): LanguageGenerationResult {
        val proposed = propose(target)
            .map(::normalizeSurface)
            .filter { it.isNotBlank() }
            .distinct()
            .take(maximumCandidates)
        require(proposed.isNotEmpty()) { "Language generation produced no candidate" }

        val ranked = proposed.map { text ->
            evaluate(target, text, context)
        }.sortedWith(
            compareByDescending<LanguageGenerationCandidate> { it.semanticPreservation }
                .thenByDescending { it.semanticGraphCoverage }
                .thenByDescending { it.roundTrip.goal.confidence }
                .thenBy { it.text.length }
                .thenBy { it.text }
        )
        val winner = ranked.first()
        return LanguageGenerationResult(
            text = winner.text,
            target = target,
            winner = winner,
            alternatives = ranked.drop(1).take(4),
        )
    }

    private fun evaluate(
        target: LanguageGenerationTarget,
        text: String,
        context: LanguageContext,
    ): LanguageGenerationCandidate {
        val parsed = understanding.understand(text, context)
        val intentPreserved = parsed.goal.intent == target.intent
        val entityCoverage = entityCoverage(target.entities, parsed.goal.entities)
        val semanticCoverage = semanticCoverage(target, parsed)
        val graphCoverage = semanticGraphCoverage(target.semanticGraph, parsed.goal.semanticGraph)
        val confidenceAgreement = 1.0 - abs(target.confidence - parsed.goal.confidence)
        val score = (
            (if (intentPreserved) 0.34 else 0.0) +
                entityCoverage * 0.20 +
                semanticCoverage * 0.14 +
                graphCoverage * 0.24 +
                confidenceAgreement.coerceIn(0.0, 1.0) * 0.08
            ).coerceIn(0.0, 1.0)
        return LanguageGenerationCandidate(
            text = text,
            roundTrip = parsed,
            semanticPreservation = score,
            intentPreserved = intentPreserved,
            entityCoverage = entityCoverage,
            semanticCoverage = semanticCoverage,
            semanticGraphCoverage = graphCoverage,
        )
    }

    private fun entityCoverage(
        expected: List<SemanticEntity>,
        actual: List<SemanticEntity>,
    ): Double {
        if (expected.isEmpty()) return 1.0
        val matches = expected.count { wanted ->
            actual.any { found ->
                found.type == wanted.type && (
                    normalizeFieldText(found.normalizedValue) == normalizeFieldText(wanted.normalizedValue) ||
                        lexicalConceptMatch(wanted, found)
                    )
            }
        }
        return matches.toDouble() / expected.size.toDouble()
    }

    private fun lexicalConceptMatch(left: SemanticEntity, right: SemanticEntity): Boolean {
        val leftConcepts = conceptsFor(left)
        if (leftConcepts.isEmpty()) return false
        val rightConceptIds = conceptsFor(right).mapTo(mutableSetOf()) { it.id }
        return leftConcepts.any { it.id in rightConceptIds }
    }

    private fun conceptsFor(entity: SemanticEntity): List<LinguisticConcept> {
        val normalized = normalizeFieldText(entity.normalizedValue)
        return lexicon.concepts.filter { concept ->
            concept.entityType == entity.type && concept.allForms.any { it == normalized }
        }
    }

    private fun semanticCoverage(
        target: LanguageGenerationTarget,
        parsed: LanguageUnderstandingResult,
    ): Double {
        val expected = buildSet {
            addAll(target.semanticTags.map { it.uppercase() })
            target.entities.forEach { entity ->
                conceptsFor(entity).forEach { add(it.semanticTag) }
            }
        }
        if (expected.isEmpty()) return 1.0
        val field = parsed.linguisticField ?: return 0.0
        return expected.map { tag -> field.semanticActivation(tag).coerceIn(0.0, 1.0) }.average()
    }

    private fun propose(target: LanguageGenerationTarget): List<String> {
        val language = when (target.language) {
            LanguageCode.UNKNOWN -> LanguageCode.DE
            else -> target.language
        }
        val payload = payload(target, language)
        val ordinary = when (language) {
            LanguageCode.DE -> germanCandidates(target.intent, payload)
            LanguageCode.EN -> englishCandidates(target.intent, payload)
            LanguageCode.UNKNOWN -> error("resolved above")
        }
        val structural = target.semanticGraph
            .takeIf { it.requiresStructuralPreservation() && it.clauses.isNotEmpty() }
            ?.sourceSurface()
            ?.takeIf { it.isNotBlank() }
        return if (structural == null) ordinary else listOf(structural) + ordinary
    }

    private fun payload(target: LanguageGenerationTarget, language: LanguageCode): String {
        val ordered = target.entities.sortedWith(
            compareBy<SemanticEntity> { entityPriority(it.type) }
                .thenBy { it.tokenStart }
                .thenBy { it.normalizedValue }
        )
        val surfaces = ordered.map { entity -> entitySurface(entity, language) }.filter { it.isNotBlank() }
        if (surfaces.isNotEmpty()) return surfaces.joinToString(" ")

        val semantic = target.semanticTags.asSequence()
            .mapNotNull { tag ->
                lexicon.concepts
                    .filter { it.semanticTag.equals(tag, ignoreCase = true) }
                    .maxByOrNull { it.semanticMass }
            }
            .map { conceptSurface(it, language) }
            .distinct()
            .toList()
        if (semantic.isNotEmpty()) return semantic.joinToString(" ")

        return target.constraints.asSequence()
            .filterNot { it.key.startsWith("reference.") }
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)
            .joinToString(" ")
    }

    private fun entitySurface(entity: SemanticEntity, language: LanguageCode): String {
        if (entity.type == EntityType.NUMBER) {
            return when (entity.normalizedValue) {
                "1" -> if (language == LanguageCode.DE) "ein" else "one"
                "2" -> if (language == LanguageCode.DE) "zwei" else "two"
                "3" -> if (language == LanguageCode.DE) "drei" else "three"
                else -> entity.normalizedValue
            }
        }
        val concept = conceptsFor(entity).maxByOrNull { it.semanticMass }
        return concept?.let { conceptSurface(it, language) } ?: entity.normalizedValue
    }

    private fun conceptSurface(concept: LinguisticConcept, language: LanguageCode): String = when (language) {
        LanguageCode.DE -> when (concept.id) {
            "person" -> "Leute"
            "football" -> "Fußball"
            "action.play" -> "spielen"
            "action.run" -> "laufen"
            "image" -> "Bild"
            "create" -> "erzeugen"
            "transform" -> "verändern"
            "search" -> "suchen"
            "continue" -> "weiter"
            "implement" -> "implementieren"
            else -> concept.canonical
        }
        LanguageCode.EN -> when (concept.id) {
            "person" -> "people"
            "football" -> "football"
            "action.play" -> "playing"
            "action.run" -> "running"
            "image" -> "image"
            "create" -> "create"
            "transform" -> "transform"
            "search" -> "search"
            "continue" -> "continue"
            "implement" -> "implement"
            else -> concept.variants.firstOrNull { form -> form.all { it.code < 128 } } ?: concept.canonical
        }
        LanguageCode.UNKNOWN -> concept.canonical
    }

    private fun germanCandidates(intent: IntentType, payload: String): List<String> = when (intent) {
        IntentType.CREATE_IMAGE -> listOf(
            "Erzeuge ein Bild von $payload.",
            "Erstelle ein Bild mit $payload.",
            "Generiere ein Bild: $payload.",
        )
        IntentType.TRANSFORM_IMAGE -> listOf(
            "Verändere das Bild: $payload.",
            "Bearbeite das Bild mit $payload.",
        )
        IntentType.SEARCH -> listOf(
            "Suche $payload.",
            "Finde $payload.",
            "Recherchiere $payload.",
        )
        IntentType.CONTINUE -> listOf("Weiter.", "Fortsetzen.")
        IntentType.BUILD_OR_IMPLEMENT -> listOf(
            "Implementiere $payload.",
            "Baue $payload.",
        )
        IntentType.QUERY -> listOf(
            "Was weißt du über $payload?",
            "Beantworte die Frage zu $payload.",
        )
        IntentType.SCHEDULE -> listOf(
            "Erinnere mich an $payload.",
            "Plane $payload.",
        )
        IntentType.COMMUNICATE -> listOf(
            "Teile $payload.",
            "Sende $payload.",
        )
        IntentType.STORE_OR_REMEMBER -> listOf(
            "Merke dir $payload.",
            "Erinnere dich an $payload.",
        )
        IntentType.CONVERSATION -> listOf(
            payload.ifBlank { "Hallo." },
            "Lass uns über ${payload.ifBlank { "das Thema" }} sprechen.",
        )
        IntentType.UNKNOWN -> listOf(payload.ifBlank { "Unbekannte Aussage." })
    }

    private fun englishCandidates(intent: IntentType, payload: String): List<String> = when (intent) {
        IntentType.CREATE_IMAGE -> listOf("Create an image of $payload.", "Generate an image with $payload.")
        IntentType.TRANSFORM_IMAGE -> listOf("Transform the image: $payload.", "Edit the image with $payload.")
        IntentType.SEARCH -> listOf("Search for $payload.", "Find $payload.")
        IntentType.CONTINUE -> listOf("Continue.", "Proceed.")
        IntentType.BUILD_OR_IMPLEMENT -> listOf("Implement $payload.", "Build $payload.")
        IntentType.QUERY -> listOf("What do you know about $payload?", "Answer the question about $payload.")
        IntentType.SCHEDULE -> listOf("Remind me about $payload.", "Schedule $payload.")
        IntentType.COMMUNICATE -> listOf("Share $payload.", "Send $payload.")
        IntentType.STORE_OR_REMEMBER -> listOf("Remember $payload.", "Save $payload.")
        IntentType.CONVERSATION -> listOf(payload.ifBlank { "Hello." }, "Let us discuss ${payload.ifBlank { "this" }}.")
        IntentType.UNKNOWN -> listOf(payload.ifBlank { "Unknown statement." })
    }

    private fun entityPriority(type: EntityType): Int = when (type) {
        EntityType.NUMBER -> 0
        EntityType.PERSON -> 1
        EntityType.IMAGE -> 2
        EntityType.OBJECT -> 3
        EntityType.ACTION -> 4
        EntityType.LOCATION -> 5
        EntityType.DATE -> 6
        EntityType.TIME -> 7
        EntityType.DURATION -> 8
        EntityType.COLOR -> 9
        EntityType.STYLE -> 10
        EntityType.FILE -> 11
    }

    private fun normalizeSurface(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .replace(" :", ":")
        .trim()
}
