package app.lifeos.core.language

/**
 * B110-B111 deterministic semantic interpreter.
 *
 * Predicate detection is lexical/syntactic and deliberately independent from IntentType.
 * Intent evidence can describe a topic, but cannot create an executable node.
 */
class PredicateFrameParser(
    private val morphology: GermanMorphologyEngine = GermanMorphologyEngine(),
) {
    fun parse(
        utterance: NormalizedUtterance,
        graph: LanguageSemanticGraph,
        speechActs: Map<Int, SpeechAct>,
        references: List<ResolvedReference>,
    ): List<PredicateFrame> = graph.clauses.mapNotNull { clause ->
        val tokens = utterance.tokens.subList(clause.tokenStart, clause.tokenEndExclusive)
        val speechAct = requireNotNull(speechActs[clause.id]) {
            "Every semantic clause requires a speech act"
        }
        val predicate = predicate(tokens)
            ?: conditionPredicate(tokens)
            ?: PredicateConcept.QUERY.takeIf { speechAct.type == SpeechActType.QUESTION }
        if (predicate == null) return@mapNotNull null

        val predicateLocalIndex = tokens.indexOfFirst { token ->
            matchesPredicate(token.normalized, predicate)
        }.takeIf { it >= 0 } ?: 0
        val predicateTokenIndex = clause.tokenStart + predicateLocalIndex
        val nodeId = SemanticNodeId.create(
            "predicate-frame/v1",
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
            references = references,
        )
        val evidence = buildList {
            add(
                SemanticEvidence(
                    source = "predicate-lexicon",
                    detail = "predicate=" + predicate.name + ";token=" + utterance.tokens[predicateTokenIndex].original,
                    strength = if (predicate == PredicateConcept.CONDITION_CHECK) 0.82 else 0.96,
                    span = tokenSpan(utterance.tokens[predicateTokenIndex]),
                )
            )
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
                if (predicate == PredicateConcept.CONDITION_CHECK) 0.82 else 0.96,
            ),
            evidence = evidence,
        )
    }

    private fun predicate(tokens: List<LanguageToken>): PredicateConcept? {
        val words = tokens.filter { it.kind == TokenKind.WORD }.map { it.normalized }
        if (words.any { it in MAKE_FORMS } && words.any { it in IMAGE_WORDS }) {
            return if (words.any { it in IMAGE_TRANSFORM_MODIFIERS }) {
                PredicateConcept.TRANSFORM_IMAGE
            } else {
                PredicateConcept.CREATE_IMAGE
            }
        }
        return PREDICATE_ORDER.firstOrNull { concept ->
            words.any { matchesPredicate(it, concept) }
        }
    }

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
        references: List<ResolvedReference>,
    ): Map<SemanticRole, SemanticValue> {
        val roles = linkedMapOf<SemanticRole, SemanticValue>()
        val clauseEntities = clause.entities.sortedBy { it.tokenStart }
        val clauseQuantities = clause.quantities.sortedBy { it.tokenStart }

        clauseEntities.firstOrNull { it.type == EntityType.IMAGE }?.let {
            roles[SemanticRole.OBJECT] = it.asValue()
        }
        clauseEntities.firstOrNull { it.type == EntityType.FILE || it.type == EntityType.OBJECT }?.let {
            roles.putIfAbsent(SemanticRole.OBJECT, it.asValue())
        }
        clauseEntities.firstOrNull { it.type == EntityType.LOCATION }?.let {
            roles[SemanticRole.LOCATION] = it.asValue()
        }
        clauseEntities.firstOrNull { it.type == EntityType.DATE }?.let {
            roles[SemanticRole.DATE] = it.asValue()
        }
        clauseEntities.firstOrNull { it.type == EntityType.TIME }?.let {
            roles[SemanticRole.TIME] = it.asValue()
        }
        clauseEntities.firstOrNull { it.type == EntityType.DURATION }?.let {
            roles[SemanticRole.DURATION] = it.asValue()
        }

        clauseQuantities.firstOrNull()?.let { quantity ->
            roles[SemanticRole.AMOUNT] = SemanticValue(
                rawText = quantity.value + quantity.unit?.let { " " + it }.orEmpty(),
                normalized = quantity.value,
                quantity = quantity,
                confidence = quantity.confidence,
            )
            quantity.unit?.let { unit ->
                roles[SemanticRole.UNIT] = SemanticValue(
                    rawText = unit,
                    normalized = unit,
                    confidence = quantity.confidence,
                )
                if (unit.lowercase() in CURRENCY_UNITS) {
                    roles[SemanticRole.CURRENCY] = SemanticValue(
                        rawText = unit,
                        normalized = normalizeCurrency(unit),
                        confidence = quantity.confidence,
                    )
                }
            }
        }

        val personCandidates = personCandidates(utterance, clause)
        when (predicate) {
            PredicateConcept.OWE -> {
                val before = personCandidates.lastOrNull { it.first < predicateTokenIndex }
                val after = personCandidates.firstOrNull { it.first > predicateTokenIndex }
                before?.second?.let { roles[SemanticRole.DEBTOR] = it }
                after?.second?.let { roles[SemanticRole.CREDITOR] = it }
            }
            PredicateConcept.COMMUNICATE -> {
                recipient(utterance, clause, personCandidates)?.let {
                    roles[SemanticRole.RECIPIENT] = it
                }
            }
            else -> Unit
        }

        if (predicate in OBJECT_REQUIRED_PREDICATES && SemanticRole.OBJECT !in roles) {
            fallbackObject(utterance, clause, predicateTokenIndex)?.let {
                roles[SemanticRole.OBJECT] = it
            }
        }

        val resolvedReference = references
            .filter { it.targetPhotonRef != null }
            .maxByOrNull { it.score }
        if (resolvedReference != null && predicate in REFERENCE_OBJECT_PREDICATES) {
            val ref = requireNotNull(resolvedReference.targetPhotonRef)
            roles[SemanticRole.OBJECT] = SemanticValue(
                rawText = resolvedReference.expression.rawText,
                normalized = ref.photonId.value,
                referencePhoton = ref,
                resolved = true,
                confidence = resolvedReference.score,
            )
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
    ): SemanticValue? {
        val tokens = utterance.tokens
        val words = mutableListOf<Int>()
        for (index in predicateTokenIndex + 1 until clause.tokenEndExclusive) {
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
            PredicateConcept.SEARCH to setOf("suche", "finde", "recherchiere", "search", "find", "research", "lookup"),
            PredicateConcept.CONTINUE to setOf("weiter", "fortsetzen", "continue", "proceed"),
            PredicateConcept.BUILD to setOf("baue", "implementiere", "entwickle", "programmiere", "build", "implement", "develop", "code"),
            PredicateConcept.QUERY to setOf("zeige", "sag", "erkläre", "erklaere", "show", "tell", "explain"),
            PredicateConcept.SCHEDULE to setOf("erinnere", "plane", "schedule", "remind"),
            PredicateConcept.COMMUNICATE to setOf("sende", "schicke", "schick", "teile", "antworte", "send", "share", "reply", "message"),
            PredicateConcept.STORE_MEMORY to setOf("merke", "speichere", "remember", "store", "save"),
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
            "brighter", "darker", "warmer", "sharper",
        )
        private val REFERENCE_PRONOUNS = setOf(
            "das", "dies", "diese", "diesen", "dieses", "ihn", "sie", "es", "andere", "anderen",
            "it", "this", "that", "him", "her", "them", "other",
        )
        private val QUOTE_MARKERS = setOf("\"", "„", "“", "”", "«", "»")
        private val OBJECT_STOP_WORDS = setOf(
            "bitte", "please", "mir", "mich", "me", "an", "to", "nicht", "not",
            "anschließend", "anschliessend", "danach", "then", "und", "and", "oder", "or",
        )
        private val SENTENCE_INITIAL_NON_NAMES = setOf(
            "der", "die", "das", "ein", "eine", "ich", "du", "wir", "sie", "er", "es",
            "the", "a", "an", "i", "you", "we", "he", "she", "it",
        )
    }
}
