package app.lifeos.core.runtime.personal

import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageUnderstandingResult
import app.lifeos.core.language.LinguisticConcept
import app.lifeos.core.language.LinguisticLexiconSnapshot
import app.lifeos.core.language.SemanticSearchTerms
import app.lifeos.core.language.VersionedLanguageRuntime
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds

enum class PersonalLanguageCandidateKind {
    LEXICAL_ALIAS,
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
        ): PersonalLanguageCandidate {
            val canonical = observations
                .distinctBy { it.id }
                .sortedBy { it.id }
            return PersonalLanguageCandidate(
                kind = PersonalLanguageCandidateKind.LEXICAL_ALIAS,
                surface = surface,
                targetConceptId = targetConceptId,
                observations = canonical,
                fingerprint = expectedFingerprint(
                    PersonalLanguageCandidateKind.LEXICAL_ALIAS,
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
) {
    init {
        require(surface.isNotBlank())
        require(targetConceptId.isNotBlank())
    }
}

class PersonalLanguageCandidateMiner {
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

        return SemanticSearchTerms.tokens(utterance)
            .filter { it.length >= MIN_ALIAS_LENGTH }
            .filterNot { it in knownForms }
            .filterNot { it in RESERVED_SURFACES }
            .take(MAX_OBSERVATIONS_PER_TURN)
            .map { surface -> PersonalLanguageAliasProposal(surface, target.id) }
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
        if (target.allForms.map(SemanticSearchTerms::normalizeToken).contains(normalizedAlias)) {
            return current
        }
        val updated = current.concepts.map { concept ->
            if (concept.id == target.id) concept.copy(variants = concept.variants + candidate.surface)
            else concept
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
            if (concept.id == target.id) concept.copy(variants = concept.variants + candidate.surface)
            else concept
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
        val fieldContribution = candidateResult.linguisticField.intentField
            .firstOrNull { it.intent == targetIntent }
            ?.contributingConcepts
            ?.contains(target.id) == true
        val candidateRecognized =
            candidateResult.goal.intent == targetIntent &&
                fieldContribution &&
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
        if (target.allForms.map(SemanticSearchTerms::normalizeToken).contains(normalizedAlias)) {
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
            if (concept.id == target.id) concept.copy(variants = concept.variants + candidate.surface)
            else concept
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
