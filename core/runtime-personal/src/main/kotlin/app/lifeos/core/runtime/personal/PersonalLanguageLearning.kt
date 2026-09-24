package app.lifeos.core.runtime.personal

import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageUnderstandingResult
import app.lifeos.core.language.LinguisticConcept
import app.lifeos.core.language.LinguisticLexiconSnapshot
import app.lifeos.core.language.SemanticSearchTerms
import app.lifeos.core.language.VersionedLanguageRuntime
import app.lifeos.core.language.WorldGroundedLanguageLearningEvidence
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds

enum class PersonalLanguageCandidateKind {
    LEXICAL_ALIAS,
    PHRASE_PATTERN,
}

enum class PersonalLanguageFeedbackKind {
    CONFIRMED,
    REJECTED,
    REFERENCE_CORRECTION,
    INTENT_CORRECTION,
    UNKNOWN,
}

data class PersonalLanguageObservation(
    val conversationId: String,
    val surface: String,
    val targetConceptId: String,
    val accepted: Boolean,
    val sourceRef: PhotonRevisionRef?,
) {
    init {
        require(conversationId.isNotBlank())
        require(surface.isNotBlank())
        require(targetConceptId.isNotBlank())
    }

    val id: String = StableCognitiveIds.fingerprint(
        "personal-language-observation/v1",
        conversationId,
        SemanticSearchTerms.normalizeToken(surface),
        targetConceptId,
        accepted.toString(),
        sourceRef?.stableKey.orEmpty(),
    )
}

data class PersonalLanguageCandidate(
    val kind: PersonalLanguageCandidateKind,
    val surface: String,
    val targetConceptId: String,
    val observations: List<PersonalLanguageObservation>,
    val fingerprint: String,
) {
    init {
        require(surface.isNotBlank())
        require(targetConceptId.isNotBlank())
        require(observations.isNotEmpty())
        require(observations.all {
            it.targetConceptId == targetConceptId &&
                SemanticSearchTerms.normalizeToken(it.surface) == SemanticSearchTerms.normalizeToken(surface)
        })
        require(fingerprint == expectedFingerprint(kind, surface, targetConceptId, observations))
    }

    val supportCount: Int get() = observations.count { it.accepted }
    val rejectionCount: Int get() = observations.count { !it.accepted }
    val distinctConversationCount: Int get() = observations.map { it.conversationId }.distinct().size
    val positiveRate: Double get() = supportCount.toDouble() / observations.size.toDouble()

    companion object {
        fun create(
            surface: String,
            targetConceptId: String,
            observations: Collection<PersonalLanguageObservation>,
            kind: PersonalLanguageCandidateKind = PersonalLanguageCandidateKind.LEXICAL_ALIAS,
        ): PersonalLanguageCandidate {
            val canonical = observations
                .distinctBy { it.id }
                .sortedBy { it.id }
            return PersonalLanguageCandidate(
                kind = kind,
                surface = surface,
                targetConceptId = targetConceptId,
                observations = canonical,
                fingerprint = expectedFingerprint(
                    kind,
                    surface,
                    targetConceptId,
                    canonical,
                ),
            )
        }

        private fun expectedFingerprint(
            kind: PersonalLanguageCandidateKind,
            surface: String,
            targetConceptId: String,
            observations: List<PersonalLanguageObservation>,
        ): String = StableCognitiveIds.fingerprint(
            "personal-language-candidate/v1",
            kind.name,
            SemanticSearchTerms.normalizeToken(surface),
            targetConceptId,
            *observations.map { it.id }.sorted().toTypedArray(),
        )
    }
}

/**
 * Conservative model-free miner. It can only add lexical aliases to an already-known concept and
 * never invents a new executable predicate or capability.
 */
data class PersonalLanguageAliasProposal(
    val surface: String,
    val targetConceptId: String,
    val kind: PersonalLanguageCandidateKind = PersonalLanguageCandidateKind.LEXICAL_ALIAS,
) {
    init {
        require(surface.isNotBlank())
        require(targetConceptId.isNotBlank())
    }
}

class PersonalLanguageCandidateMiner(
    private val grammarMiner: PersonalGrammarPatternMiner = PersonalGrammarPatternMiner(),
) {
    fun propose(
        utterance: String,
        understanding: LanguageUnderstandingResult,
        lexicon: LinguisticLexiconSnapshot,
    ): List<PersonalLanguageAliasProposal> {
        val intent = understanding.goal.intent
        if (intent !in SAFE_PERSONALIZATION_INTENTS) return emptyList()
        val quality = understanding.goal.interpretationQuality
        if (
            understanding.goal.confidence < MIN_INTERPRETATION_CONFIDENCE ||
            quality.evidenceStrength < MIN_EVIDENCE_STRENGTH ||
            quality.contradictionCount > 0 ||
            quality.ambiguityCount > 0
        ) {
            return emptyList()
        }
        val target = targetConcept(intent, lexicon) ?: return emptyList()

        val knownForms = lexicon.concepts
            .flatMap { concept -> concept.allForms }
            .map(SemanticSearchTerms::normalizeToken)
            .toSet()

        val lexical = SemanticSearchTerms.tokens(utterance)
            .filter { it.length >= MIN_ALIAS_LENGTH }
            .filterNot { it in knownForms }
            .filterNot { it in RESERVED_SURFACES }
            .take(MAX_OBSERVATIONS_PER_TURN)
            .map { surface ->
                PersonalLanguageAliasProposal(
                    surface = surface,
                    targetConceptId = target.id,
                    kind = PersonalLanguageCandidateKind.LEXICAL_ALIAS,
                )
            }

        val grammar = grammarMiner.propose(
            utterance = utterance,
            intent = intent,
            knownForms = knownForms,
        ).map { proposal ->
            PersonalLanguageAliasProposal(
                surface = proposal.surface,
                targetConceptId = target.id,
                kind = PersonalLanguageCandidateKind.PHRASE_PATTERN,
            )
        }

        return (lexical + grammar)
            .distinctBy { Triple(it.kind, it.surface, it.targetConceptId) }
            .take(MAX_OBSERVATIONS_PER_TURN + 1)
    }

    fun observe(
        utterance: String,
        understanding: LanguageUnderstandingResult,
        lexicon: LinguisticLexiconSnapshot,
        conversationId: String,
        sourceRef: PhotonRevisionRef? = null,
        accepted: Boolean,
    ): List<PersonalLanguageObservation> =
        propose(utterance, understanding, lexicon).map { proposal ->
            PersonalLanguageObservation(
                conversationId = conversationId,
                surface = proposal.surface,
                targetConceptId = proposal.targetConceptId,
                accepted = accepted,
                sourceRef = sourceRef,
            )
        }

    private fun targetConcept(
        intent: IntentType,
        lexicon: LinguisticLexiconSnapshot,
    ): LinguisticConcept? = lexicon.concepts
        .filter { intent in it.intentBias.keys }
        .maxWithOrNull(
            compareBy<LinguisticConcept> { it.intentBias[intent] ?: 0.0 }
                .thenByDescending { it.id }
        )

    private companion object {
        const val MIN_ALIAS_LENGTH = 3
        const val MAX_OBSERVATIONS_PER_TURN = 3
        const val MIN_INTERPRETATION_CONFIDENCE = 0.75
        const val MIN_EVIDENCE_STRENGTH = 0.70
        val SAFE_PERSONALIZATION_INTENTS = setOf(
            IntentType.CONTINUE,
            IntentType.SEARCH,
            IntentType.QUERY,
            IntentType.CONVERSATION,
            IntentType.STORE_OR_REMEMBER,
            IntentType.BUILD_OR_IMPLEMENT,
        )
        val RESERVED_SURFACES = setOf(
            "ja", "nein", "yes", "no", "nicht", "kein", "keine", "stop", "stopp",
        )
    }
}

data class PersonalLanguagePromotionPolicy(
    val minimumSupport: Int = 5,
    val minimumDistinctConversations: Int = 3,
    val minimumPositiveRate: Double = 0.85,
    val maximumRejections: Int = 1,
) {
    init {
        require(minimumSupport >= 1)
        require(minimumDistinctConversations >= 1)
        require(minimumPositiveRate in 0.0..1.0)
        require(maximumRejections >= 0)
    }

    fun allows(candidate: PersonalLanguageCandidate): Boolean =
        candidate.supportCount >= minimumSupport &&
            candidate.distinctConversationCount >= minimumDistinctConversations &&
            candidate.positiveRate >= minimumPositiveRate &&
            candidate.rejectionCount <= maximumRejections
}

class PersonalLanguagePromotionCoordinator(
    private val runtime: VersionedLanguageRuntime,
    private val policy: PersonalLanguagePromotionPolicy = PersonalLanguagePromotionPolicy(),
) {
    @Synchronized
    fun promote(candidate: PersonalLanguageCandidate): LinguisticLexiconSnapshot? {
        if (!policy.allows(candidate)) return null
        val current = runtime.current().lexicon
        val normalizedAlias = SemanticSearchTerms.normalizeToken(candidate.surface)
        val target = current.byId(candidate.targetConceptId) ?: return null
        val alreadyActive = when (candidate.kind) {
            PersonalLanguageCandidateKind.LEXICAL_ALIAS ->
                target.allForms.map(SemanticSearchTerms::normalizeToken).contains(normalizedAlias)
            PersonalLanguageCandidateKind.PHRASE_PATTERN ->
                target.phraseVariants.map(SemanticSearchTerms::normalizeToken).contains(normalizedAlias)
        }
        if (alreadyActive) return current
        val updated = current.concepts.map { concept ->
            if (concept.id != target.id) concept
            else when (candidate.kind) {
                PersonalLanguageCandidateKind.LEXICAL_ALIAS ->
                    concept.copy(variants = concept.variants + candidate.surface)
                PersonalLanguageCandidateKind.PHRASE_PATTERN ->
                    concept.copy(phraseVariants = concept.phraseVariants + candidate.surface)
            }
        }
        return runtime.promote(
            concepts = updated,
            promotionEvidenceFingerprint = candidate.fingerprint,
        ).lexicon
    }
}


data class PersonalLanguageShadowReport(
    val candidateFingerprint: String,
    val candidateIntent: IntentType,
    val candidateRecognized: Boolean,
    val protectedCasesStable: Boolean,
    val passed: Boolean,
) {
    init {
        require(candidateFingerprint.isNotBlank())
        require(passed == (candidateRecognized && protectedCasesStable))
    }
}

/**
 * Deterministic holdout/shadow gate for one lexical extension. The candidate is evaluated in an
 * isolated language runtime; it cannot change the productive head until every protected case stays
 * semantically identical and the new surface resolves to the intended existing intent.
 */
class PersonalLanguageShadowEvaluator {
    fun evaluate(
        current: LinguisticLexiconSnapshot,
        candidate: PersonalLanguageCandidate,
    ): PersonalLanguageShadowReport {
        val target = current.byId(candidate.targetConceptId)
            ?: return PersonalLanguageShadowReport(
                candidateFingerprint = candidate.fingerprint,
                candidateIntent = IntentType.UNKNOWN,
                candidateRecognized = false,
                protectedCasesStable = false,
                passed = false,
            )
        val targetIntent = target.intentBias.entries
            .maxWithOrNull(compareBy<Map.Entry<IntentType, Double>> { it.value }.thenBy { it.key.name })
            ?.key
            ?: IntentType.UNKNOWN
        if (targetIntent !in SHADOW_ELIGIBLE_INTENTS) {
            return PersonalLanguageShadowReport(
                candidateFingerprint = candidate.fingerprint,
                candidateIntent = targetIntent,
                candidateRecognized = false,
                protectedCasesStable = false,
                passed = false,
            )
        }

        val updated = current.concepts.map { concept ->
            if (concept.id != target.id) concept
            else when (candidate.kind) {
                PersonalLanguageCandidateKind.LEXICAL_ALIAS ->
                    concept.copy(variants = concept.variants + candidate.surface)
                PersonalLanguageCandidateKind.PHRASE_PATTERN ->
                    concept.copy(phraseVariants = concept.phraseVariants + candidate.surface)
            }
        }
        val candidateSnapshot = LinguisticLexiconSnapshot.create(
            revision = current.revision + 1L,
            concepts = updated,
            predecessorFingerprint = current.fingerprint,
            promotionEvidenceFingerprint = candidate.fingerprint,
        )
        val baselineEngine = VersionedLanguageRuntime(current).current().understanding
        val candidateEngine = VersionedLanguageRuntime(candidateSnapshot).current().understanding

        val candidateResult = candidateEngine.understand(candidate.surface)
        val fieldContribution = candidateResult.linguisticField
            ?.intentField
            ?.firstOrNull { it.intent == targetIntent }
            ?.contributingConcepts
            ?.contains(target.id) == true
        val personalGrammarContribution = candidateResult.goal.semanticActionGraph.nodes.any { node ->
            node.frame.evidence.any { evidence -> evidence.source == "personal-grammar/v1" }
        }
        val candidateRecognized =
            candidateResult.goal.intent == targetIntent &&
                (fieldContribution || personalGrammarContribution) &&
                candidateResult.goal.interpretationQuality.contradictionCount == 0

        val protectedStable = PROTECTED_CASES.all { text ->
            val baseline = baselineEngine.understand(text)
            val shadow = candidateEngine.understand(text)
            baseline.goal.intent == shadow.goal.intent &&
                baseline.goal.semanticActionGraph.fingerprint == shadow.goal.semanticActionGraph.fingerprint &&
                baseline.goal.semanticActionGraph.hasExecutableExternalSideEffect() ==
                    shadow.goal.semanticActionGraph.hasExecutableExternalSideEffect()
        }

        return PersonalLanguageShadowReport(
            candidateFingerprint = candidate.fingerprint,
            candidateIntent = targetIntent,
            candidateRecognized = candidateRecognized,
            protectedCasesStable = protectedStable,
            passed = candidateRecognized && protectedStable,
        )
    }

    private companion object {
        val SHADOW_ELIGIBLE_INTENTS = setOf(
            IntentType.CONTINUE,
            IntentType.SEARCH,
            IntentType.QUERY,
            IntentType.CONVERSATION,
            IntentType.STORE_OR_REMEMBER,
        )
        val PROTECTED_CASES = listOf(
            "Sende diese Mail nicht.",
            "Er sagte: „Sende die Mail.“",
            "Wenn X passiert, sende die Mail.",
            "Wie erstelle ich ein Bild?",
        )
    }
}

sealed interface DurablePersonalLanguagePromotionResult {
    data class Promoted(
        val snapshot: LinguisticLexiconSnapshot,
        val shadow: PersonalLanguageShadowReport,
    ) : DurablePersonalLanguagePromotionResult

    data class Rejected(
        val reason: String,
        val shadow: PersonalLanguageShadowReport? = null,
    ) : DurablePersonalLanguagePromotionResult {
        init { require(reason.isNotBlank()) }
    }
}

/**
 * Productive promotion path. Policy and shadow validation happen before the encrypted durable
 * language-runtime authority advances its CAS head.
 */
class DurablePersonalLanguagePromotionCoordinator(
    private val runtime: DurableLanguageRuntimeCoordinator,
    private val policy: PersonalLanguagePromotionPolicy = PersonalLanguagePromotionPolicy(),
    private val shadow: PersonalLanguageShadowEvaluator = PersonalLanguageShadowEvaluator(),
) {
    suspend fun promote(candidate: PersonalLanguageCandidate): DurablePersonalLanguagePromotionResult {
        if (!policy.allows(candidate)) {
            return DurablePersonalLanguagePromotionResult.Rejected("insufficient-evidence")
        }
        val current = runtime.current().lexicon
        val target = current.byId(candidate.targetConceptId)
            ?: return DurablePersonalLanguagePromotionResult.Rejected("target-concept-missing")
        val normalizedAlias = SemanticSearchTerms.normalizeToken(candidate.surface)
        val alreadyActive = when (candidate.kind) {
            PersonalLanguageCandidateKind.LEXICAL_ALIAS ->
                target.allForms.map(SemanticSearchTerms::normalizeToken).contains(normalizedAlias)
            PersonalLanguageCandidateKind.PHRASE_PATTERN ->
                target.phraseVariants.map(SemanticSearchTerms::normalizeToken).contains(normalizedAlias)
        }
        if (alreadyActive) {
            return DurablePersonalLanguagePromotionResult.Rejected("alias-already-active")
        }

        val report = shadow.evaluate(current, candidate)
        if (!report.passed) {
            return DurablePersonalLanguagePromotionResult.Rejected(
                reason = "shadow-regression",
                shadow = report,
            )
        }

        val updated = current.concepts.map { concept ->
            if (concept.id != target.id) concept
            else when (candidate.kind) {
                PersonalLanguageCandidateKind.LEXICAL_ALIAS ->
                    concept.copy(variants = concept.variants + candidate.surface)
                PersonalLanguageCandidateKind.PHRASE_PATTERN ->
                    concept.copy(phraseVariants = concept.phraseVariants + candidate.surface)
            }
        }
        val promoted = runtime.promote(
            concepts = updated,
            promotionEvidenceFingerprint = candidate.fingerprint,
        ).lexicon
        return DurablePersonalLanguagePromotionResult.Promoted(promoted, report)
    }
}

object PersonalLanguageFeedbackProjector {
    fun classify(text: String): PersonalLanguageFeedbackKind {
        val normalized = text.trim().lowercase()
        if (normalized.isBlank()) return PersonalLanguageFeedbackKind.UNKNOWN
        if (
            normalized.startsWith("nein, ich meinte") ||
            normalized.startsWith("nicht das") ||
            normalized.contains("das andere")
        ) return PersonalLanguageFeedbackKind.REFERENCE_CORRECTION
        if (
            normalized.startsWith("ich meinte") ||
            normalized.startsWith("nein, gemeint")
        ) return PersonalLanguageFeedbackKind.INTENT_CORRECTION
        if (normalized in CONFIRMATIONS) return PersonalLanguageFeedbackKind.CONFIRMED
        if (normalized in REJECTIONS || normalized.startsWith("nein")) {
            return PersonalLanguageFeedbackKind.REJECTED
        }
        return PersonalLanguageFeedbackKind.UNKNOWN
    }

    private val CONFIRMATIONS = setOf(
        "ja", "genau", "ja genau", "genau so", "richtig", "passt", "yes", "exactly", "correct",
    )
    private val REJECTIONS = setOf(
        "nein", "falsch", "so nicht", "no", "wrong",
    )
}

data class PersonalGrammarPattern(
    val tokens: List<String>,
    val intent: IntentType,
    val support: Int,
    val conversations: Int,
) {
    init {
        require(tokens.size in 2..6)
        require(support > 0)
        require(conversations > 0)
        require(intent in SAFE_GRAMMAR_INTENTS)
    }

    val normalized: String = tokens.joinToString(" ") {
        SemanticSearchTerms.normalizeToken(it)
    }

    companion object {
        val SAFE_GRAMMAR_INTENTS = setOf(
            IntentType.SEARCH,
            IntentType.CONTINUE,
            IntentType.QUERY,
            IntentType.BUILD_OR_IMPLEMENT,
        )
    }
}

data class PersonalGrammarProposal(
    val surface: String,
    val intent: IntentType,
) {
    init {
        require(surface.isNotBlank())
        require(intent in PersonalGrammarPattern.SAFE_GRAMMAR_INTENTS)
        require(SemanticSearchTerms.tokens(surface).size in 2..6)
    }
}

class PersonalGrammarPatternMiner {
    fun propose(
        utterance: String,
        intent: IntentType,
        knownForms: Set<String>,
    ): List<PersonalGrammarProposal> {
        if (intent !in PersonalGrammarPattern.SAFE_GRAMMAR_INTENTS) return emptyList()
        val tokens = TOKEN_REGEX.findAll(utterance)
            .map { SemanticSearchTerms.normalizeToken(it.value) }
            .filter { it.isNotBlank() }
            .toList()
        if (tokens.size !in 2..6) return emptyList()
        if (tokens.all { it in knownForms }) return emptyList()
        val surface = tokens.joinToString(" ")
        return listOf(PersonalGrammarProposal(surface, intent))
    }

    private companion object {
        val TOKEN_REGEX = Regex("[\\p{L}\\p{N}._-]+")
    }
}


// ---- B476 World-grounded language outcome promotion ----

data class PersonalLanguageOutcomeEvidenceBinding(
    val candidateFingerprint: String,
    val learningEvidence: WorldGroundedLanguageLearningEvidence,
) {
    init {
        require(candidateFingerprint.isNotBlank())
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "personal-language-outcome-evidence-binding/v1",
        candidateFingerprint,
        learningEvidence.fingerprint,
    )

    val promotionAuthority: Boolean
        get() = false

    companion object {
        fun bind(
            candidate: PersonalLanguageCandidate,
            evidence: WorldGroundedLanguageLearningEvidence,
        ): PersonalLanguageOutcomeEvidenceBinding =
            PersonalLanguageOutcomeEvidenceBinding(
                candidateFingerprint = candidate.fingerprint,
                learningEvidence = evidence,
            )
    }
}

sealed interface OutcomeGuardedPersonalLanguagePromotionResult {
    data class Promoted(
        val delegate: DurablePersonalLanguagePromotionResult.Promoted,
        val evidenceFingerprint: String,
    ) : OutcomeGuardedPersonalLanguagePromotionResult

    data class Rejected(
        val reason: String,
        val delegate: DurablePersonalLanguagePromotionResult.Rejected? = null,
    ) : OutcomeGuardedPersonalLanguagePromotionResult {
        init {
            require(reason.isNotBlank())
        }
    }
}

/**
 * B476 evidence gate in front of the existing durable promotion coordinator.
 *
 * It does not replace the existing support policy or shadow regression gate. It only ensures that
 * outcome/owner feedback attached to a candidate is exact, positive and unopposed before the
 * existing promotion authority is invoked.
 */
class OutcomeGuardedPersonalLanguagePromotionCoordinator(
    private val delegate: DurablePersonalLanguagePromotionCoordinator,
    private val minimumPositiveEvidence: Int = 1,
) {
    init {
        require(minimumPositiveEvidence >= 1)
    }

    suspend fun promote(
        candidate: PersonalLanguageCandidate,
        evidence: Collection<PersonalLanguageOutcomeEvidenceBinding>,
    ): OutcomeGuardedPersonalLanguagePromotionResult {
        val bound = evidence
            .filter { it.candidateFingerprint == candidate.fingerprint }
            .distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }

        if (bound.any { it.learningEvidence.negativeLearningEvidence }) {
            return OutcomeGuardedPersonalLanguagePromotionResult.Rejected(
                "negative-owner-evidence"
            )
        }

        val positive = bound.filter {
            it.learningEvidence.positiveLearningEligible
        }
        if (positive.size < minimumPositiveEvidence) {
            return OutcomeGuardedPersonalLanguagePromotionResult.Rejected(
                "insufficient-world-grounded-learning-evidence"
            )
        }

        return when (val result = delegate.promote(candidate)) {
            is DurablePersonalLanguagePromotionResult.Promoted ->
                OutcomeGuardedPersonalLanguagePromotionResult.Promoted(
                    delegate = result,
                    evidenceFingerprint = StableCognitiveIds.fingerprint(
                        "personal-language-promotion-evidence-set/v1",
                        candidate.fingerprint,
                        *positive.map { it.fingerprint }.toTypedArray(),
                    ),
                )
            is DurablePersonalLanguagePromotionResult.Rejected ->
                OutcomeGuardedPersonalLanguagePromotionResult.Rejected(
                    reason = result.reason,
                    delegate = result,
                )
        }
    }
}
