package app.lifeos.core.runtime.personal

import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageContext
import app.lifeos.core.language.LanguageUnderstandingResult
import app.lifeos.core.language.LinguisticConcept
import app.lifeos.core.language.LinguisticLexiconSnapshot
import app.lifeos.core.language.SemanticSearchTerms
import app.lifeos.core.language.VersionedLanguageRuntime
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant

data class PersonalCorpusAliasHypothesis(
    val surface: String,
    val targetConceptId: String,
    val targetIntent: IntentType,
    val supportRefs: List<PhotonRevisionRef>,
    val distinctConversationCount: Int,
    val consistency: Double,
    val fingerprint: String,
) {
    init {
        require(surface.isNotBlank())
        require(targetConceptId.isNotBlank())
        require(targetIntent in PersonalCorpusLanguageRuntime.SAFE_CORPUS_INTENTS)
        require(supportRefs.size >= PersonalCorpusLanguageRuntime.MIN_CORPUS_SUPPORT)
        require(supportRefs.distinct().size == supportRefs.size)
        require(distinctConversationCount >= PersonalCorpusLanguageRuntime.MIN_DISTINCT_CONVERSATIONS)
        require(consistency in PersonalCorpusLanguageRuntime.MIN_CORPUS_CONSISTENCY..1.0)
        require(fingerprint == expectedFingerprint(
            surface = surface,
            targetConceptId = targetConceptId,
            targetIntent = targetIntent,
            supportRefs = supportRefs,
            distinctConversationCount = distinctConversationCount,
            consistency = consistency,
        ))
    }

    companion object {
        fun create(
            surface: String,
            targetConceptId: String,
            targetIntent: IntentType,
            supportRefs: Collection<PhotonRevisionRef>,
            distinctConversationCount: Int,
            consistency: Double,
        ): PersonalCorpusAliasHypothesis {
            val canonicalRefs = supportRefs
                .distinct()
                .sortedWith(compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision })
            return PersonalCorpusAliasHypothesis(
                surface = SemanticSearchTerms.normalizeToken(surface),
                targetConceptId = targetConceptId,
                targetIntent = targetIntent,
                supportRefs = canonicalRefs,
                distinctConversationCount = distinctConversationCount,
                consistency = consistency,
                fingerprint = expectedFingerprint(
                    surface = surface,
                    targetConceptId = targetConceptId,
                    targetIntent = targetIntent,
                    supportRefs = canonicalRefs,
                    distinctConversationCount = distinctConversationCount,
                    consistency = consistency,
                ),
            )
        }

        private fun expectedFingerprint(
            surface: String,
            targetConceptId: String,
            targetIntent: IntentType,
            supportRefs: List<PhotonRevisionRef>,
            distinctConversationCount: Int,
            consistency: Double,
        ): String = StableCognitiveIds.fingerprint(
            "personal-corpus-alias-hypothesis/v1",
            SemanticSearchTerms.normalizeToken(surface),
            targetConceptId,
            targetIntent.name,
            distinctConversationCount.toString(),
            java.lang.Double.toHexString(consistency),
            *supportRefs.map { it.stableKey }.sorted().toTypedArray(),
        )
    }
}

data class PersonalCorpusLanguageDecision(
    val understanding: LanguageUnderstandingResult,
    val baseline: LanguageUnderstandingResult,
    val hypothesis: PersonalCorpusAliasHypothesis? = null,
    val baselineLexiconFingerprint: String,
    val shadowLexiconFingerprint: String? = null,
) {
    init {
        require(baselineLexiconFingerprint.isNotBlank())
        require((hypothesis == null) == (shadowLexiconFingerprint == null))
    }

    val usedCorpusShadow: Boolean get() = hypothesis != null

    fun evidencePhoton(source: Photon): Photon? {
        val activeHypothesis = hypothesis ?: return null
        val shadowFingerprint = requireNotNull(shadowLexiconFingerprint)
        val evidenceFingerprint = StableCognitiveIds.fingerprint(
            "personal-corpus-language-evidence/v1",
            source.id.value,
            source.revision.toString(),
            activeHypothesis.fingerprint,
            baselineLexiconFingerprint,
            shadowFingerprint,
            baseline.goal.intent.name,
            understanding.goal.intent.name,
            java.lang.Double.toHexString(baseline.goal.confidence),
            java.lang.Double.toHexString(understanding.goal.confidence),
        )
        return Photon(
            id = PhotonId("personal-corpus-language-$evidenceFingerprint"),
            content = listOf(
                "personal-corpus-language-evidence/v1",
                "surface=" + activeHypothesis.surface,
                "target_concept=" + activeHypothesis.targetConceptId,
                "target_intent=" + activeHypothesis.targetIntent.name,
                "support_count=" + activeHypothesis.supportRefs.size,
                "distinct_conversations=" + activeHypothesis.distinctConversationCount,
                "consistency=" + java.lang.Double.toHexString(activeHypothesis.consistency),
                "baseline_intent=" + baseline.goal.intent.name,
                "selected_intent=" + understanding.goal.intent.name,
                "baseline_lexicon=" + baselineLexiconFingerprint,
                "shadow_lexicon=" + shadowFingerprint,
                "support_refs=" + activeHypothesis.supportRefs.joinToString(",") { it.stableKey },
            ).joinToString("\n"),
            mimeType = EVIDENCE_MIME,
            phase = PhotonPhase.ARCHIVED,
            semanticMass = 0.05,
            energy = 0.0,
            confidence = activeHypothesis.consistency,
            provenance = Provenance(
                source = "personal-corpus-language-shadow",
                actor = "lifeos",
                createdAt = source.provenance.createdAt,
                parentIds = buildSet {
                    add(source.id)
                    activeHypothesis.supportRefs.forEach { add(it.photonId) }
                },
            ),
            tags = setOf(
                "language-learning-state",
                "personal-corpus-language-evidence",
                "privacy:private-conversation",
                "privacy:local-only",
                "privacy:no-external-export",
                "privacy:no-deepsearch-export",
                "privacy:no-autonomous-share",
            ),
        )
    }

    companion object {
        const val EVIDENCE_MIME = "application/vnd.lifeos.personal-corpus-language-evidence+text"
    }
}

/**
 * Model-free, non-authoritative language adaptation from the owner's imported conversation corpus.
 *
 * Archive examples can create a one-turn lexical shadow only. The productive VersionedLanguageRuntime
 * is never mutated here; durable promotion remains owned by the live feedback + holdout pipeline.
 */
class PersonalCorpusLanguageRuntime(
    private val corpus: PersonalCorpusRetriever,
    private val runtime: VersionedLanguageRuntime,
    private val minimumSupport: Int = MIN_CORPUS_SUPPORT,
    private val minimumDistinctConversations: Int = MIN_DISTINCT_CONVERSATIONS,
    private val minimumConsistency: Double = MIN_CORPUS_CONSISTENCY,
) {
    init {
        require(minimumSupport >= MIN_CORPUS_SUPPORT)
        require(minimumDistinctConversations >= MIN_DISTINCT_CONVERSATIONS)
        require(minimumConsistency in MIN_CORPUS_CONSISTENCY..1.0)
    }

    suspend fun understand(
        utterance: String,
        context: LanguageContext,
        now: Instant,
    ): PersonalCorpusLanguageDecision {
        val baselineSnapshot = runtime.current()
        val baseline = baselineSnapshot.understanding.understand(utterance, context)
        if (!needsCorpusAssistance(baseline)) {
            return PersonalCorpusLanguageDecision(
                understanding = baseline,
                baseline = baseline,
                baselineLexiconFingerprint = baselineSnapshot.lexicon.fingerprint,
            )
        }

        val hypothesis = inferHypothesis(
            utterance = utterance,
            now = now,
            zoneId = context.zoneId,
            snapshot = baselineSnapshot.lexicon,
            baselineEngine = baselineSnapshot.understanding,
        ) ?: return PersonalCorpusLanguageDecision(
            understanding = baseline,
            baseline = baseline,
            baselineLexiconFingerprint = baselineSnapshot.lexicon.fingerprint,
        )

        val shadowLexicon = shadowLexicon(
            current = baselineSnapshot.lexicon,
            hypothesis = hypothesis,
        )
        val shadowRuntime = VersionedLanguageRuntime(shadowLexicon)
        val shadowEngine = shadowRuntime.current().understanding
        if (!shadowSafe(
                baselineEngine = baselineSnapshot.understanding,
                shadowEngine = shadowEngine,
                hypothesis = hypothesis,
            )
        ) {
            return PersonalCorpusLanguageDecision(
                understanding = baseline,
                baseline = baseline,
                baselineLexiconFingerprint = baselineSnapshot.lexicon.fingerprint,
            )
        }

        val candidate = shadowEngine.understand(utterance, context)
        if (!candidateImprovesSafely(baseline, candidate, hypothesis)) {
            return PersonalCorpusLanguageDecision(
                understanding = baseline,
                baseline = baseline,
                baselineLexiconFingerprint = baselineSnapshot.lexicon.fingerprint,
            )
        }

        // The productive runtime must still point to the exact baseline snapshot.
        check(runtime.current().lexicon.fingerprint == baselineSnapshot.lexicon.fingerprint) {
            "Personal corpus shadow must not mutate productive LanguageRuntime"
        }
        return PersonalCorpusLanguageDecision(
            understanding = candidate,
            baseline = baseline,
            hypothesis = hypothesis,
            baselineLexiconFingerprint = baselineSnapshot.lexicon.fingerprint,
            shadowLexiconFingerprint = shadowLexicon.fingerprint,
        )
    }

    private suspend fun inferHypothesis(
        utterance: String,
        now: Instant,
        zoneId: String,
        snapshot: LinguisticLexiconSnapshot,
        baselineEngine: app.lifeos.core.language.LanguageUnderstandingEngine,
    ): PersonalCorpusAliasHypothesis? {
        val knownForms = snapshot.concepts
            .flatMap { it.allForms }
            .map(SemanticSearchTerms::normalizeToken)
            .toSet()
        val surfaces = SemanticSearchTerms.tokens(utterance)
            .filter { it.length >= MIN_ALIAS_LENGTH }
            .filterNot { it in knownForms }
            .filterNot { it in RESERVED_SURFACES }
            .take(MAX_SURFACES_PER_TURN)
        if (surfaces.isEmpty()) return null

        val examples = corpus.retrieveOwnerLanguageExamples(utterance, now)
        if (examples.size < minimumSupport) return null
        val evidence = mutableListOf<CorpusEvidence>()
        for (example in examples) {
            val exampleTokens = SemanticSearchTerms.tokens(example.photon.content)
            val relevantSurfaces = surfaces.filter { it in exampleTokens }
            if (relevantSurfaces.isEmpty()) continue

            val understood = baselineEngine.understand(
                example.photon.content,
                LanguageContext(
                    now = example.photon.provenance.createdAt,
                    zoneId = zoneId,
                ),
            )
            val intent = understood.goal.intent
            if (intent !in SAFE_CORPUS_INTENTS) continue
            val quality = understood.goal.interpretationQuality
            if (
                understood.goal.confidence < MIN_EXAMPLE_CONFIDENCE ||
                quality.evidenceStrength < MIN_EXAMPLE_EVIDENCE ||
                quality.contradictionCount > 0
            ) {
                continue
            }

            // Corpus examples may be understood by either the deterministic rule layer or
            // the linguistic field. Both are local LIFEOS evidence; requiring a field-only
            // contribution would discard valid personal phrasing that is disambiguated by a
            // known cue in the same historical turn.
            val target = targetConcept(intent, snapshot) ?: continue

            val conversationKey = example.photon.tags
                .firstOrNull { it.startsWith("conversation:") }
                ?.substringAfter(':')
                ?.takeIf { it.isNotBlank() }
                ?: example.ref.photonId.value

            relevantSurfaces.forEach { surface ->
                evidence += CorpusEvidence(
                    surface = surface,
                    targetConceptId = target.id,
                    targetIntent = intent,
                    ref = example.ref,
                    conversationKey = conversationKey,
                )
            }
        }

        val candidates = surfaces.mapNotNull { surface ->
            val forSurface = evidence.filter { it.surface == surface }
            if (forSurface.size < minimumSupport) return@mapNotNull null
            val grouped = forSurface.groupBy { it.targetConceptId }
            val winning = grouped.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, List<CorpusEvidence>>> { it.value.size }
                        .thenBy { it.key }
                )
                .firstOrNull()
                ?: return@mapNotNull null
            val support = winning.value
            val consistency = support.size.toDouble() / forSurface.size.toDouble()
            val conversations = support.map { it.conversationKey }.distinct()
            if (
                support.size < minimumSupport ||
                conversations.size < minimumDistinctConversations ||
                consistency < minimumConsistency
            ) {
                return@mapNotNull null
            }
            val intent = support.first().targetIntent
            if (support.any { it.targetIntent != intent }) return@mapNotNull null
            PersonalCorpusAliasHypothesis.create(
                surface = surface,
                targetConceptId = winning.key,
                targetIntent = intent,
                supportRefs = support.map { it.ref },
                distinctConversationCount = conversations.size,
                consistency = consistency,
            )
        }

        return candidates.sortedWith(
            compareByDescending<PersonalCorpusAliasHypothesis> { it.supportRefs.size }
                .thenByDescending { it.consistency }
                .thenByDescending { it.distinctConversationCount }
                .thenBy { it.surface }
        ).firstOrNull()
    }

    private fun shadowLexicon(
        current: LinguisticLexiconSnapshot,
        hypothesis: PersonalCorpusAliasHypothesis,
    ): LinguisticLexiconSnapshot {
        val normalizedAlias = SemanticSearchTerms.normalizeToken(hypothesis.surface)
        val target = requireNotNull(current.byId(hypothesis.targetConceptId)) {
            "Personal corpus target concept disappeared"
        }
        require(target.allForms.none {
            SemanticSearchTerms.normalizeToken(it) == normalizedAlias
        }) {
            "Personal corpus shadow alias is already productive"
        }

        return LinguisticLexiconSnapshot.create(
            revision = current.revision + 1L,
            concepts = current.concepts.map { concept ->
                if (concept.id == target.id) {
                    // The productive rule layer assigns UNKNOWN a fixed fallback score. A newly
                    // learned alias has no rule entry by design, so the one-turn shadow must give
                    // its exact lexical field enough mass to compete with that fallback. This lift
                    // exists only in the ephemeral shadow snapshot; the productive concept and its
                    // semantic mass remain byte/fingerprint-identical.
                    concept.copy(
                        variants = concept.variants + hypothesis.surface,
                        semanticMass = maxOf(concept.semanticMass, MIN_SHADOW_ALIAS_SEMANTIC_MASS),
                    )
                } else {
                    concept
                }
            },
            predecessorFingerprint = current.fingerprint,
            promotionEvidenceFingerprint = hypothesis.fingerprint,
        )
    }

    private fun shadowSafe(
        baselineEngine: app.lifeos.core.language.LanguageUnderstandingEngine,
        shadowEngine: app.lifeos.core.language.LanguageUnderstandingEngine,
        hypothesis: PersonalCorpusAliasHypothesis,
    ): Boolean {
        val aliasResult = shadowEngine.understand(hypothesis.surface)
        val directContribution = aliasResult.linguisticField
            ?.intentField
            ?.firstOrNull { it.intent == hypothesis.targetIntent }
            ?.contributingConcepts
            ?.contains(hypothesis.targetConceptId) == true
        if (
            aliasResult.goal.intent != hypothesis.targetIntent ||
            !directContribution ||
            aliasResult.goal.interpretationQuality.contradictionCount > 0
        ) {
            return false
        }

        return PROTECTED_CASES.all { text ->
            val baseline = baselineEngine.understand(text)
            val shadow = shadowEngine.understand(text)
            baseline.goal.intent == shadow.goal.intent &&
                baseline.goal.semanticActionGraph.fingerprint ==
                    shadow.goal.semanticActionGraph.fingerprint &&
                baseline.goal.semanticActionGraph.hasExecutableExternalSideEffect() ==
                    shadow.goal.semanticActionGraph.hasExecutableExternalSideEffect()
        }
    }

    private fun candidateImprovesSafely(
        baseline: LanguageUnderstandingResult,
        candidate: LanguageUnderstandingResult,
        hypothesis: PersonalCorpusAliasHypothesis,
    ): Boolean {
        if (candidate.goal.intent != hypothesis.targetIntent) return false
        if (candidate.goal.intent !in SAFE_CORPUS_INTENTS) return false
        if (candidate.goal.interpretationQuality.contradictionCount > 0) return false
        if (
            !baseline.goal.semanticActionGraph.hasExecutableExternalSideEffect() &&
            candidate.goal.semanticActionGraph.hasExecutableExternalSideEffect()
        ) {
            return false
        }

        // UNKNOWN is a rule-layer fallback, not positive semantic authority. Its composite
        // GoalFrame confidence includes default entity/reference/field support and therefore is
        // not directly comparable to a field-backed exact alias. At this point the alias already
        // passed corpus support/consistency, exact field contribution, protected-semantic holdout,
        // safe-intent and no-new-side-effect gates, so an UNKNOWN baseline may be replaced without
        // imposing the replacement confidence floor used for already-understood live turns.
        if (baseline.goal.intent == IntentType.UNKNOWN) return true
        if (candidate.goal.confidence < MIN_SELECTED_CONFIDENCE) return false

        return when {
            baseline.goal.intent == candidate.goal.intent ->
                candidate.goal.confidence >= baseline.goal.confidence + MIN_CONFIDENCE_GAIN
            else ->
                baseline.goal.confidence < MAX_REPLACEABLE_BASELINE_CONFIDENCE &&
                    candidate.goal.confidence >= baseline.goal.confidence + MIN_CONFIDENCE_GAIN
        }
    }

    private fun needsCorpusAssistance(result: LanguageUnderstandingResult): Boolean =
        result.goal.intent == IntentType.UNKNOWN ||
            result.goal.confidence < ASSISTANCE_CONFIDENCE_CEILING ||
            result.goal.interpretationQuality.ambiguityCount > 0

    private fun targetConcept(
        intent: IntentType,
        lexicon: LinguisticLexiconSnapshot,
    ): LinguisticConcept? = lexicon.concepts
        .filter { intent in it.intentBias.keys }
        .maxWithOrNull(
            compareBy<LinguisticConcept> { it.intentBias[intent] ?: 0.0 }
                .thenByDescending { it.id }
        )

    private data class CorpusEvidence(
        val surface: String,
        val targetConceptId: String,
        val targetIntent: IntentType,
        val ref: PhotonRevisionRef,
        val conversationKey: String,
    )

    companion object {
        const val MIN_CORPUS_SUPPORT = 4
        const val MIN_DISTINCT_CONVERSATIONS = 3
        const val MIN_CORPUS_CONSISTENCY = 0.85
        private const val MIN_ALIAS_LENGTH = 3
        private const val MAX_SURFACES_PER_TURN = 3
        private const val MIN_SHADOW_ALIAS_SEMANTIC_MASS = 1.25
        private const val MIN_EXAMPLE_CONFIDENCE = 0.75
        private const val MIN_EXAMPLE_EVIDENCE = 0.65
        // A newly injected exact alias is field-backed rather than rule-backed, so its composite
        // GoalFrame confidence is intentionally lower than a built-in lexical rule. The separate
        // corpus support/consistency gate plus protected shadow suite provide the stronger authority
        // boundary; 0.55 keeps the one-turn alias usable without granting execution authority.
        private const val MIN_SELECTED_CONFIDENCE = 0.55
        private const val MIN_CONFIDENCE_GAIN = 0.05
        private const val ASSISTANCE_CONFIDENCE_CEILING = 0.78
        private const val MAX_REPLACEABLE_BASELINE_CONFIDENCE = 0.72

        val SAFE_CORPUS_INTENTS = setOf(
            IntentType.CONTINUE,
            IntentType.SEARCH,
            IntentType.QUERY,
            IntentType.CONVERSATION,
        )

        private val RESERVED_SURFACES = setOf(
            "ja", "nein", "yes", "no", "nicht", "kein", "keine", "stop", "stopp",
        )

        private val PROTECTED_CASES = listOf(
            "Sende diese Mail nicht.",
            "Er sagte: „Sende die Mail.“",
            "Wenn X passiert, sende die Mail.",
            "Wie erstelle ich ein Bild?",
            "Überweise nicht mehr als 100 Euro.",
        )
    }
}
