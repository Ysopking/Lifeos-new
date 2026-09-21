package app.lifeos.core.language

/**
 * B110-B111 deterministic semantic interpreter.
 *
 * Predicate detection is lexical/syntactic and deliberately independent from IntentType.
 * Intent evidence can describe a topic, but cannot create an executable node.
 */
class PredicateFrameParser(
    private val morphology: GermanMorphologyEngine = GermanMorphologyEngine(),
    private val roleBinder: SemanticRoleBinder = SemanticRoleBinder(),
    private val contracts: PredicateContractRegistry = PredicateContractRegistry(),
    private val paraphraseResolver: PredicateParaphraseResolver = PredicateParaphraseResolver(),
) {
    fun parse(
        utterance: NormalizedUtterance,
        graph: LanguageSemanticGraph,
        speechActs: Map<Int, SpeechAct>,
        references: List<ResolvedReference>,
        linguisticField: LinguisticFieldResult? = null,
        syntaxGraph: DependencySyntaxGraph = DependencySyntaxGraph.empty(),
    ): List<PredicateFrame> = graph.clauses.flatMap { clause ->
        val tokens = utterance.tokens.subList(clause.tokenStart, clause.tokenEndExclusive)
        val speechAct = requireNotNull(speechActs[clause.id]) {
            "Every semantic clause requires a speech act"
        }
        val occurrences = predicateOccurrences(
            tokens = tokens,
            speechAct = speechAct,
            clauseTokenStart = clause.tokenStart,
            linguisticField = linguisticField,
        )
        occurrences.mapIndexed { occurrenceIndex, occurrence ->
            val predicate = occurrence.predicate
            val predicateTokenIndex = clause.tokenStart + occurrence.localTokenIndex
            val nextPredicateTokenIndex = occurrences
                .getOrNull(occurrenceIndex + 1)
                ?.let { next ->
                    maxOf(
                        predicateTokenIndex + 1,
                        clause.tokenStart + next.localTokenIndex,
                    )
                }
                ?.coerceAtMost(clause.tokenEndExclusive)
                ?: clause.tokenEndExclusive
            val nodeId = SemanticNodeId.create(
                "predicate-frame/v2",
                graph.fingerprint,
                clause.id.toString(),
                predicate.name,
                predicateTokenIndex.toString(),
            )
            val scopeTypes = scopeTypes(
                utterance = utterance,
                graph = graph,
                clause = clause,
                predicateTokenIndex = predicateTokenIndex,
                speechAct = speechAct,
            )
            val roles = roles(
                utterance = utterance,
                clause = clause,
                predicate = predicate,
                predicateTokenIndex = predicateTokenIndex,
                argumentEndExclusive = nextPredicateTokenIndex,
                references = references,
            )
            val evidence = buildList {
                add(
                    SemanticEvidence(
                        source = occurrence.source,
                        detail = "predicate=" + predicate.name +
                            ";token=" + utterance.tokens[predicateTokenIndex].original +
                            ";index=" + predicateTokenIndex +
                            ";" + occurrence.detail,
                        strength = occurrence.confidence,
                        span = tokenSpan(utterance.tokens[predicateTokenIndex]),
                    )
                )
                syntaxGraph.arcs
                    .filter { it.headTokenIndex == predicateTokenIndex }
                    .takeIf { it.isNotEmpty() }
                    ?.let { arcs ->
                        add(
                            SemanticEvidence(
                                source = "dependency-syntax/v1",
                                detail = "root=" + predicateTokenIndex +
                                    ";relations=" + arcs
                                        .map { it.relation.name }
                                        .distinct()
                                        .sorted()
                                        .joinToString(","),
                                strength = arcs.maxOf { it.confidence },
                                span = tokenSpan(utterance.tokens[predicateTokenIndex]),
                            )
                        )
                    }
                addAll(speechAct.evidence)
            }
            PredicateFrame(
                nodeId = nodeId,
                clauseId = clause.id,
                predicate = predicate,
                roles = roles,
                scopeTypes = scopeTypes,
                speechAct = speechAct,
                confidence = minOf(
                    speechAct.confidence,
                    occurrence.confidence,
                ),
                evidence = evidence,
            )
        }
    }

    private fun predicateOccurrences(
        tokens: List<LanguageToken>,
        speechAct: SpeechAct,
        clauseTokenStart: Int,
        linguisticField: LinguisticFieldResult?,
    ): List<PredicateOccurrence> {
        val result = mutableListOf<PredicateOccurrence>()
        val words = tokens.withIndex().filter { it.value.kind == TokenKind.WORD }

        val make = words.firstOrNull { it.value.normalized in MAKE_FORMS }
        val hasImageNoun = words.any { it.value.normalized in IMAGE_WORDS }
        val hasTransformModifier = words.any { it.value.normalized in IMAGE_TRANSFORM_MODIFIERS }
        val hasDeicticObject = words.any { it.value.normalized in REFERENCE_PRONOUNS }
        if (make != null && (hasImageNoun || hasTransformModifier && hasDeicticObject)) {
            val concept = if (hasTransformModifier) {
                PredicateConcept.TRANSFORM_IMAGE
            } else {
                PredicateConcept.CREATE_IMAGE
            }
            result += PredicateOccurrence(
                predicate = concept,
                localTokenIndex = make.index,
                confidence = if (hasImageNoun) 0.96 else 0.90,
                source = "predicate-syntax-v2",
                detail = if (hasImageNoun) "exact-make-image" else "deictic-make-transform",
            )
        }

        val wordSet = words.mapTo(linkedSetOf()) { it.value.normalized }
        words.forEach { indexed ->
            val normalized = indexed.value.normalized
            val concept = when {
                normalized == "erinnere" && "dich" in wordSet && "mich" !in wordSet ->
                    PredicateConcept.STORE_MEMORY
                else -> PREDICATE_ORDER.firstOrNull { candidate ->
                    matchesPredicate(normalized, candidate)
                }
            } ?: return@forEach
            result += PredicateOccurrence(
                predicate = concept,
                localTokenIndex = indexed.index,
                confidence = 0.96,
                source = "predicate-syntax-v2",
                detail = "exact-or-morphological-form",
            )
        }

        val firstWord = words.firstOrNull()
        if (firstWord?.value?.normalized in CONDITION_MARKERS) {
            result += PredicateOccurrence(
                predicate = PredicateConcept.CONDITION_CHECK,
                localTokenIndex = firstWord!!.index,
                confidence = 0.82,
                source = "predicate-syntax-v2",
                detail = "condition-marker",
            )
        }

        if (result.isEmpty() && speechAct.type == SpeechActType.QUESTION) {
            result += PredicateOccurrence(
                predicate = PredicateConcept.QUERY,
                localTokenIndex = firstWord?.index ?: 0,
                confidence = 0.78,
                source = "predicate-syntax-v2",
                detail = "question-fallback",
            )
        }

        paraphraseResolver.resolve(tokens).forEach { match ->
            result += PredicateOccurrence(
                predicate = match.predicate,
                localTokenIndex = match.localTokenIndex,
                confidence = match.confidence,
                source = match.source,
                detail = match.detail,
            )
        }
        paraphraseResolver.fromField(
            field = linguisticField,
            clauseTokenStart = clauseTokenStart,
            clauseTokenEndExclusive = clauseTokenStart + tokens.size,
        ).forEach { match ->
            // Field-derived predicates are fallback semantic evidence. They must never duplicate
            // a stronger lexical/syntactic/paraphrase predicate at another token in the same
            // clause, otherwise one user action can become two executable semantic nodes.
            if (result.none { it.predicate == match.predicate }) {
                result += PredicateOccurrence(
                    predicate = match.predicate,
                    localTokenIndex = match.localTokenIndex,
                    confidence = match.confidence,
                    source = match.source,
                    detail = match.detail,
                )
            }
        }

        val positioned = result
            .groupBy { it.predicate to it.localTokenIndex }
            .map { (_, occurrences) -> occurrences.maxBy { it.confidence } }
            .sortedWith(compareBy<PredicateOccurrence> { it.localTokenIndex }.thenBy { it.predicate.name })

        val collapsed = mutableListOf<PredicateOccurrence>()
        positioned.forEach { occurrence ->
            val previousIndex = collapsed.indexOfLast { it.predicate == occurrence.predicate }
            if (previousIndex < 0) {
                collapsed += occurrence
                return@forEach
            }
            val previous = collapsed[previousIndex]
            if (hasIndependentActionBoundary(tokens, previous.localTokenIndex, occurrence.localTokenIndex)) {
                collapsed += occurrence
            } else {
                val preferred = when {
                    occurrence.confidence > previous.confidence -> occurrence
                    occurrence.confidence < previous.confidence -> previous
                    occurrence.localTokenIndex < previous.localTokenIndex -> occurrence
                    else -> previous
                }
                collapsed[previousIndex] = preferred
            }
        }
        return collapsed.sortedWith(
            compareBy<PredicateOccurrence> { it.localTokenIndex }.thenBy { it.predicate.name }
        )
    }

    private fun hasIndependentActionBoundary(
        tokens: List<LanguageToken>,
        leftIndex: Int,
        rightIndex: Int,
    ): Boolean {
        if (rightIndex <= leftIndex + 1) return false
        return (leftIndex + 1 until rightIndex).any { index ->
            val token = tokens[index]
            token.normalized in ACTION_BOUNDARY_MARKERS ||
                token.kind == TokenKind.PUNCTUATION && token.original in ACTION_BOUNDARY_PUNCTUATION
        }
    }

    private data class PredicateOccurrence(
        val predicate: PredicateConcept,
        val localTokenIndex: Int,
        val confidence: Double,
        val source: String,
        val detail: String,
    )

    private fun matchesPredicate(
        token: String,
        concept: PredicateConcept,
    ): Boolean {
        val forms = PREDICATE_FORMS[concept].orEmpty()
        if (token in forms) return true
        val morphological = morphology.candidates(token)
        return morphological.any { it in forms }
    }

    private fun conditionPredicate(tokens: List<LanguageToken>): PredicateConcept? {
        val first = tokens.firstOrNull { it.kind == TokenKind.WORD }?.normalized
        return PredicateConcept.CONDITION_CHECK.takeIf { first in CONDITION_MARKERS }
    }

    private fun scopeTypes(
        utterance: NormalizedUtterance,
        graph: LanguageSemanticGraph,
        clause: SemanticClause,
        predicateTokenIndex: Int,
        speechAct: SpeechAct,
    ): Set<ScopeType> = buildSet {
        val tokens = utterance.tokens
        val range = clause.tokenStart until clause.tokenEndExclusive
        val normalized = range.map { tokens[it].normalized }
        val predicateLocal = predicateTokenIndex - clause.tokenStart

        if (speechAct.type == SpeechActType.QUOTATION ||
            predicateInsideQuote(utterance, predicateTokenIndex)
        ) add(ScopeType.QUOTATION)
        if (speechAct.type == SpeechActType.HYPOTHETICAL) add(ScopeType.HYPOTHETICAL)
        if (clause.modality != SemanticModality.NONE) add(ScopeType.MODALITY)

        val condition = normalized.firstOrNull() in CONDITION_MARKERS ||
            graph.links.any { it.type == SemanticLinkType.CONDITION && it.fromClauseId == clause.id }
        if (condition) add(ScopeType.CONDITION)

        if (normalized.any { it in CONTRAST_MARKERS }) add(ScopeType.CONTRAST)

        val linkedContrast = graph.links.any { link ->
            link.type == SemanticLinkType.CONTRAST &&
                (link.fromClauseId == clause.id || link.toClauseId == clause.id)
        }
        val hasExclusionContrast =
            normalized.any { it in NEGATION_MARKERS } &&
                (normalized.any { it in CONTRAST_MARKERS } || linkedContrast)
        val boundedComparatorNegation = containsComparatorNegation(normalized)
        if (hasExclusionContrast || boundedComparatorNegation) {
            add(ScopeType.EXCLUSION)
        }

        val actionNegation = normalized.indices.any { index ->
            normalized[index] in NEGATION_MARKERS &&
                !negationBelongsToComparator(normalized, index) &&
                !negationBelongsToContrastObject(normalized, index) &&
                !(linkedContrast && negationTargetsScopedArgument(clause, clause.tokenStart + index)) &&
                (
                    index <= predicateLocal + ACTION_NEGATION_WINDOW ||
                        index > predicateLocal
                )
        }
        if (actionNegation) add(ScopeType.NEGATION)
    }

    private fun roles(
        utterance: NormalizedUtterance,
        clause: SemanticClause,
        predicate: PredicateConcept,
        predicateTokenIndex: Int,
        argumentEndExclusive: Int,
        references: List<ResolvedReference>,
    ): Map<SemanticRole, SemanticValue> {
        val contract = contracts.contract(predicate)
        val roles = roleBinder.bindBase(
            clause = clause,
            contract = contract,
            references = references,
            tokenStart = clause.tokenStart,
            tokenEndExclusive = argumentEndExclusive,
        )

        val personCandidates = personCandidates(utterance, clause)
        when (predicate) {
            PredicateConcept.OWE -> {
                val before = personCandidates.lastOrNull { it.first < predicateTokenIndex }
                val after = personCandidates.firstOrNull {
                    it.first > predicateTokenIndex && it.first < argumentEndExclusive
                }
                before?.second?.let { roles[SemanticRole.DEBTOR] = it }
                after?.second?.let { roles[SemanticRole.CREDITOR] = it }
            }
            PredicateConcept.COMMUNICATE,
            PredicateConcept.PAY -> {
                recipient(
                    utterance = utterance,
                    clause = clause,
                    people = personCandidates.filter { it.first < argumentEndExclusive },
                )?.let {
                    roles[SemanticRole.RECIPIENT] = it
                }
            }
            else -> Unit
        }

        val localRequirements = contract.localRoleAlternatives.flatten().toSet()
        if (SemanticRole.OBJECT in localRequirements && SemanticRole.OBJECT !in roles) {
            fallbackObject(
                utterance = utterance,
                clause = clause,
                predicateTokenIndex = predicateTokenIndex,
                argumentEndExclusive = argumentEndExclusive,
            )?.let {
                roles[SemanticRole.OBJECT] = it
            }
        }

        return roles.toMap()
    }

    private fun personCandidates(
        utterance: NormalizedUtterance,
        clause: SemanticClause,
    ): List<Pair<Int, SemanticValue>> {
        val entityPeople = clause.entities
            .filter { it.type == EntityType.PERSON }
            .map { entity ->
                entity.tokenStart to entity.asValue()
            }
        if (entityPeople.isNotEmpty()) return entityPeople

        // B113 replaces this fallback with EntityTypeRegistry/contacts. B111 only needs role
        // direction for ordinary capitalized names and does not claim durable person identity.
        return (clause.tokenStart until clause.tokenEndExclusive).mapNotNull { index ->
            val token = utterance.tokens[index]
            if (token.kind != TokenKind.WORD) return@mapNotNull null
            val original = token.original
            if (original.firstOrNull()?.isUpperCase() != true) return@mapNotNull null
            if (index == clause.tokenStart && token.normalized in SENTENCE_INITIAL_NON_NAMES) {
                return@mapNotNull null
            }
            index to SemanticValue(
                rawText = original,
                normalized = original,
                entityType = EntityType.PERSON,
                confidence = 0.72,
            )
        }
    }

    private fun recipient(
        utterance: NormalizedUtterance,
        clause: SemanticClause,
        people: List<Pair<Int, SemanticValue>>,
    ): SemanticValue? {
        val indices = clause.tokenStart until clause.tokenEndExclusive
        val tokens = utterance.tokens
        indices.firstOrNull { tokens[it].normalized in USER_RECIPIENT_MARKERS }?.let { index ->
            return SemanticValue(
                rawText = tokens[index].original,
                normalized = "user",
                confidence = 0.98,
            )
        }
        val marker = indices.firstOrNull { tokens[it].normalized in RECIPIENT_PREPOSITIONS }
        if (marker != null) {
            people.firstOrNull { it.first > marker }?.let { return it.second }
            tokens.getOrNull(marker + 1)?.takeIf { it.kind == TokenKind.WORD }?.let {
                return SemanticValue(it.original, it.original, confidence = 0.68)
            }
        }
        // A capitalized noun is not sufficient recipient evidence. Without an explicit
        // user marker or recipient preposition, keep RECIPIENT unresolved/absent instead of
        // inventing a person role that can lower or misdirect action semantics.
        return null
    }

    private fun fallbackObject(
        utterance: NormalizedUtterance,
        clause: SemanticClause,
        predicateTokenIndex: Int,
        argumentEndExclusive: Int,
    ): SemanticValue? {
        val tokens = utterance.tokens
        val words = mutableListOf<Int>()
        for (index in predicateTokenIndex + 1 until argumentEndExclusive) {
            val token = tokens[index]
            if (token.kind == TokenKind.PUNCTUATION) continue
            if (token.normalized in RECIPIENT_PREPOSITIONS && words.isNotEmpty()) break
            if (token.normalized in OBJECT_STOP_WORDS) continue
            words += index
            if (words.size >= 8) break
        }
        if (words.isEmpty()) return null
        val raw = words.joinToString(" ") { tokens[it].original }
        val normalized = words.joinToString(" ") { tokens[it].normalized }
        val deictic = normalized in REFERENCE_PRONOUNS
        return SemanticValue(
            rawText = raw,
            normalized = normalized,
            resolved = !deictic,
            confidence = if (deictic) 0.55 else 0.82,
        )
    }

    private fun predicateInsideQuote(
        utterance: NormalizedUtterance,
        predicateTokenIndex: Int,
    ): Boolean {
        val predicate = utterance.tokens[predicateTokenIndex]
        var quoted = false
        utterance.tokens.forEachIndexed { index, token ->
            if (index >= predicateTokenIndex) return@forEachIndexed
            if (token.kind == TokenKind.PUNCTUATION && token.original in QUOTE_MARKERS) {
                quoted = !quoted
            }
        }
        return quoted && predicate.start >= 0
    }

    private fun containsComparatorNegation(words: List<String>): Boolean =
        words.windowed(size = 3, step = 1, partialWindows = true).any { window ->
            window.take(2) == listOf("nicht", "mehr") ||
                window.take(3) == listOf("not", "more", "than")
        }

    private fun negationBelongsToComparator(words: List<String>, index: Int): Boolean =
        words.getOrNull(index) == "nicht" && words.getOrNull(index + 1) == "mehr" ||
            words.getOrNull(index) == "not" && words.getOrNull(index + 1) == "more"

    private fun negationBelongsToContrastObject(words: List<String>, index: Int): Boolean =
        words.drop(index + 1).take(8).any { it in CONTRAST_MARKERS }

    private fun negationTargetsScopedArgument(
        clause: SemanticClause,
        negationTokenIndex: Int,
    ): Boolean = clause.entities.any { entity ->
        entity.tokenStart > negationTokenIndex &&
            entity.tokenStart <= negationTokenIndex + ARGUMENT_SCOPE_WINDOW &&
            entity.type in SCOPED_ARGUMENT_ENTITY_TYPES
    }

    private fun SemanticEntity.asValue(): SemanticValue = SemanticValue(
        rawText = rawText,
        normalized = normalizedValue,
        entityType = type,
        confidence = confidence,
    )

    private fun tokenSpan(token: LanguageToken): TextSpan =
        TextSpan(token.start, token.endExclusive)

    private fun normalizeCurrency(unit: String): String = when (unit.lowercase()) {
        "€", "eur", "euro", "euros" -> "EUR"
        "$", "usd", "dollar" -> "USD"
        "£", "gbp", "pound", "pounds" -> "GBP"
        else -> unit.uppercase()
    }

    companion object {
        private const val ACTION_NEGATION_WINDOW = 4
        private const val ARGUMENT_SCOPE_WINDOW = 4

        val PREDICATE_FORMS: Map<PredicateConcept, Set<String>> = mapOf(
            PredicateConcept.CREATE_IMAGE to setOf("erstelle", "erzeuge", "generiere", "zeichne", "rendere", "create", "generate", "draw", "render"),
            PredicateConcept.TRANSFORM_IMAGE to setOf("ändere", "aendere", "bearbeite", "edit", "change", "transformiere", "transform"),
            PredicateConcept.SEARCH to setOf(
                "suche", "finde", "recherchiere", "nachsehen", "nachschauen",
                "schau", "sieh", "guck", "prüf", "pruef", "prüfe", "pruefe", "check", "checke",
                "search", "find", "research", "lookup",
            ),
            PredicateConcept.CONTINUE to setOf("weiter", "fortsetzen", "continue", "proceed"),
            PredicateConcept.BUILD to setOf(
                "baue", "implementiere", "entwickle", "programmiere", "umsetzen", "umsetze", "umsetz",
                "fertigstellen", "build", "implement", "develop", "code",
            ),
            PredicateConcept.QUERY to setOf("zeige", "sag", "erkläre", "erklaere", "show", "tell", "explain"),
            PredicateConcept.SCHEDULE to setOf("erinnere", "plane", "schedule", "remind"),
            PredicateConcept.COMMUNICATE to setOf(
                "sende", "schicke", "schick", "schreibe", "schreib", "maile", "mail",
                "teile", "antworte", "send", "share", "reply", "message",
            ),
            PredicateConcept.STORE_MEMORY to setOf("merke", "merk", "speichere", "remember", "store", "save"),
            PredicateConcept.OWE to setOf("schuldet", "schulde", "schulden", "owes", "owe"),
            PredicateConcept.PAY to setOf("überweise", "ueberweise", "zahle", "bezahle", "pay", "transfer"),
            PredicateConcept.DELETE to setOf("lösche", "loesche", "entferne", "delete", "remove"),
            PredicateConcept.UPLOAD to setOf("hochladen", "upload", "lade"),
            PredicateConcept.SELECT to setOf("wähle", "waehle", "select", "choose"),
        )

        private val PREDICATE_ORDER = listOf(
            PredicateConcept.TRANSFORM_IMAGE,
            PredicateConcept.CREATE_IMAGE,
            PredicateConcept.SEARCH,
            PredicateConcept.COMMUNICATE,
            PredicateConcept.SCHEDULE,
            PredicateConcept.STORE_MEMORY,
            PredicateConcept.PAY,
            PredicateConcept.OWE,
            PredicateConcept.DELETE,
            PredicateConcept.UPLOAD,
            PredicateConcept.BUILD,
            PredicateConcept.CONTINUE,
            PredicateConcept.SELECT,
            PredicateConcept.QUERY,
        )

        private val OBJECT_REQUIRED_PREDICATES = setOf(
            PredicateConcept.CREATE_IMAGE,
            PredicateConcept.TRANSFORM_IMAGE,
            PredicateConcept.SEARCH,
            PredicateConcept.COMMUNICATE,
            PredicateConcept.STORE_MEMORY,
            PredicateConcept.PAY,
            PredicateConcept.DELETE,
            PredicateConcept.UPLOAD,
            PredicateConcept.BUILD,
            PredicateConcept.SELECT,
        )
        private val REFERENCE_OBJECT_PREDICATES = setOf(
            PredicateConcept.TRANSFORM_IMAGE,
            PredicateConcept.COMMUNICATE,
            PredicateConcept.DELETE,
            PredicateConcept.UPLOAD,
            PredicateConcept.SELECT,
        )
        private val CONDITION_MARKERS = setOf("wenn", "falls", "sofern", "if", "unless")
        private val NEGATION_MARKERS = setOf(
            "nicht", "nie", "niemals", "kein", "keine", "keinen", "keinem", "keiner",
            "not", "never", "no",
        )
        private val CONTRAST_MARKERS = setOf("sondern", "aber", "stattdessen", "but", "rather", "instead")
        private val SCOPED_ARGUMENT_ENTITY_TYPES = setOf(
            EntityType.DATE,
            EntityType.TIME,
            EntityType.COLOR,
            EntityType.OBJECT,
            EntityType.IMAGE,
        )
        private val USER_RECIPIENT_MARKERS = setOf("mir", "mich", "me", "myself")
        private val RECIPIENT_PREPOSITIONS = setOf("an", "to")
        private val CURRENCY_UNITS = setOf("€", "eur", "euro", "euros", "$", "usd", "dollar", "£", "gbp", "pound", "pounds")
        private val MAKE_FORMS = setOf("mach", "mache", "macht", "make")
        private val IMAGE_WORDS = setOf("bild", "foto", "grafik", "image", "photo", "picture")
        private val IMAGE_TRANSFORM_MODIFIERS = setOf(
            "heller", "dunkler", "wärmer", "waermer", "schaerfer", "schärfer",
            "größer", "groesser", "kleiner",
            "brighter", "darker", "warmer", "sharper", "larger", "smaller",
        )
        private val REFERENCE_PRONOUNS = setOf(
            "das", "dies", "diese", "diesen", "dieses", "ihn", "sie", "es", "andere", "anderen",
            "it", "this", "that", "him", "her", "them", "other",
        )
        private val QUOTE_MARKERS = setOf("\"", "„", "“", "”", "«", "»")
        private val ACTION_BOUNDARY_MARKERS = setOf(
            "und", "oder", "dann", "danach", "anschließend", "anschliessend",
            "and", "or", "then", "afterwards",
        )
        private val ACTION_BOUNDARY_PUNCTUATION = setOf(";", ":")

        private val OBJECT_STOP_WORDS = setOf(
            "bitte", "please", "mir", "mich", "me", "an", "to", "nicht", "not",
            "um", "ein", "nach", "durch", "heraus",
            "anschließend", "anschliessend", "danach", "then", "und", "and", "oder", "or",
        )
        private val SENTENCE_INITIAL_NON_NAMES = setOf(
            "der", "die", "das", "ein", "eine", "ich", "du", "wir", "sie", "er", "es",
            "the", "a", "an", "i", "you", "we", "he", "she", "it",
        )
    }
}
