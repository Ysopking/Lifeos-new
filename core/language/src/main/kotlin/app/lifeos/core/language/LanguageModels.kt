package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import java.time.Instant

enum class LanguageCode { DE, EN, UNKNOWN }

enum class TokenKind { WORD, NUMBER, PUNCTUATION }

data class LanguageToken(
    val original: String,
    val normalized: String,
    val kind: TokenKind,
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0)
        require(endExclusive > start)
        require(original.isNotEmpty())
    }
}

data class NormalizedUtterance(
    val original: String,
    val normalized: String,
    val language: LanguageCode,
    val tokens: List<LanguageToken>,
) {
    init { require(original.isNotBlank()) }
}

enum class IntentType {
    CREATE_IMAGE,
    TRANSFORM_IMAGE,
    SEARCH,
    CONTINUE,
    BUILD_OR_IMPLEMENT,
    QUERY,
    SCHEDULE,
    COMMUNICATE,
    STORE_OR_REMEMBER,
    CONVERSATION,
    UNKNOWN,
}

data class IntentEvidence(
    val intent: IntentType,
    val score: Double,
    val reasons: List<String>,
) {
    init { require(score in 0.0..1.0) }
}

enum class EntityType {
    PERSON,
    LOCATION,
    DATE,
    TIME,
    DURATION,
    NUMBER,
    COLOR,
    FILE,
    IMAGE,
    OBJECT,
    ACTION,
    STYLE,
}

data class SemanticEntity(
    val type: EntityType,
    val rawText: String,
    val normalizedValue: String,
    val tokenStart: Int,
    val tokenEndExclusive: Int,
    val confidence: Double,
) {
    init {
        require(rawText.isNotBlank())
        require(normalizedValue.isNotBlank())
        require(tokenStart >= 0)
        require(tokenEndExclusive > tokenStart)
        require(confidence in 0.0..1.0)
    }
}

enum class ReferenceKind {
    THIS,
    THAT,
    OTHER,
    PREVIOUS,
    LAST_RESULT,
    YESTERDAY,
    EXPLICIT_ID,
}

data class ReferenceExpression(
    val kind: ReferenceKind,
    val rawText: String,
    val preferredKinds: Set<String> = emptySet(),
    val confidence: Double,
) {
    init {
        require(rawText.isNotBlank())
        require(confidence in 0.0..1.0)
    }
}

data class LanguageContextItem(
    val photonId: PhotonId,
    val kind: String,
    val tags: Set<String>,
    val createdAt: Instant,
    val active: Boolean,
    val contentTerms: Set<String>,
    val confidence: Double = 1.0,
    val revisionRef: PhotonRevisionRef? = null,
    val semanticTypes: Set<String> = emptySet(),
    val normalizedTerms: Set<String> = contentTerms,
    val conceptIds: Set<String> = emptySet(),
    val relationKeys: Set<String> = emptySet(),
    val conversationId: String? = null,
    val matterId: String? = null,
    val goalId: PhotonId? = null,
) {
    init {
        require(kind.isNotBlank())
        require(confidence in 0.0..1.0)
    }
}

data class LanguageContext(
    val items: List<LanguageContextItem> = emptyList(),
    val activeGoalId: PhotonId? = null,
    val now: Instant = Instant.now(),
    val zoneId: String = "Europe/Berlin",
)

data class ResolvedReference(
    val expression: ReferenceExpression,
    val targetPhotonId: PhotonId?,
    val score: Double,
    val alternatives: List<Pair<PhotonId, Double>> = emptyList(),
    val targetPhotonRef: PhotonRevisionRef? = null,
    val revisionAlternatives: List<Pair<PhotonRevisionRef, Double>> = emptyList(),
) {
    init { require(score in 0.0..1.0) }
}

data class Ambiguity(
    val code: String,
    val message: String,
    val alternatives: List<String>,
    val severity: Double,
) {
    init {
        require(code.isNotBlank())
        require(message.isNotBlank())
        require(severity in 0.0..1.0)
    }
}

data class GoalConstraint(
    val key: String,
    val value: String,
    val confidence: Double,
    val source: String,
) {
    init {
        require(key.isNotBlank())
        require(value.isNotBlank())
        require(confidence in 0.0..1.0)
        require(source.isNotBlank())
    }
}

data class GoalFrame(
    val intent: IntentType,
    val objective: String,
    val entities: List<SemanticEntity>,
    val references: List<ResolvedReference>,
    val constraints: List<GoalConstraint>,
    val ambiguities: List<Ambiguity>,
    val confidence: Double,
    val language: LanguageCode,
    val semanticGraph: LanguageSemanticGraph = LanguageSemanticGraph.empty(language),
    val semanticActionGraph: SemanticActionGraph = SemanticActionGraph.empty(),
    val semanticEntitiesV2: List<SemanticEntityV2> = emptyList(),
    val quantityTemporal: QuantityTemporalResult = QuantityTemporalResult(emptyList(), emptyList()),
    val domainSemanticGraph: DomainSemanticGraph = DomainSemanticGraph.empty(),
    val discourseState: DiscourseStateGraph = DiscourseStateGraph.empty(),
    val dependencySyntax: DependencySyntaxGraph = DependencySyntaxGraph.empty(),
    val interpretationLattice: SemanticInterpretationLattice = SemanticInterpretationLattice.empty(),
    val clarification: ClarificationPlan = ClarificationPlan.none(),
    val pragmaticAct: PragmaticAct = PragmaticAct.none(),
    val interpretationQuality: SemanticInterpretationQuality = SemanticInterpretationQuality.unknown(),
    val languageRealization: LanguageRealizationState = LanguageRealizationState.empty(),
    val propositionGraph: SemanticPropositionGraph = SemanticPropositionGraph.empty(),
    val referenceGrounding: LanguageReferenceGroundingState =
        LanguageReferenceGroundingState.empty(),
) {
    init {
        require(objective.isNotBlank())
        require(confidence in 0.0..1.0)
    }
}

data class LanguageUnderstandingResult(
    val utterance: NormalizedUtterance,
    val intentEvidence: List<IntentEvidence>,
    val goal: GoalFrame,
    val linguisticField: LinguisticFieldResult? = null,
    val semanticCorrections: List<SemanticCorrection> = emptyList(),
    /** Exact caller-supplied context that participated in this interpretation; null for context-free calls. */
    val context: LanguageContext? = null,
)
