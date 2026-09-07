package app.lifeos.core.language

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant
import kotlin.math.min

class LanguageUnderstandingEngine(
    private val normalizer: UtteranceNormalizer = UtteranceNormalizer(),
    private val intentClassifier: RuleBasedIntentClassifier = RuleBasedIntentClassifier(),
    private val entityExtractor: RuleBasedEntityExtractor = RuleBasedEntityExtractor(),
    private val constraintExtractor: RuleBasedConstraintExtractor = RuleBasedConstraintExtractor(),
    private val referenceExtractor: ReferenceExpressionExtractor = ReferenceExpressionExtractor(),
    private val referenceResolver: ReferenceResolver = ReferenceResolver(),
) {
    fun understand(text: String, context: LanguageContext = LanguageContext()): LanguageUnderstandingResult {
        val utterance = normalizer.normalize(text)
        val evidence = intentClassifier.classify(utterance)
        val topIntent = evidence.first().intent
        val entities = entityExtractor.extract(utterance)
        val references = referenceExtractor.extract(utterance, topIntent).map { referenceResolver.resolve(it, context) }
        val ambiguities = buildAmbiguities(evidence, references, topIntent)
        val constraints = buildConstraints(utterance, entities, references)
        val confidence = calculateConfidence(evidence.first().score, entities, references, ambiguities)
        val goal = GoalFrame(
            intent = topIntent,
            objective = canonicalObjective(utterance, topIntent),
            entities = entities,
            references = references,
            constraints = constraints,
            ambiguities = ambiguities,
            confidence = confidence,
            language = utterance.language,
        )
        return LanguageUnderstandingResult(utterance, evidence, goal)
    }

    private fun canonicalObjective(utterance: NormalizedUtterance, intent: IntentType): String =
        "${intent.name.lowercase()}: ${utterance.original.trim()}"

    private fun buildConstraints(
        utterance: NormalizedUtterance,
        entities: List<SemanticEntity>,
        references: List<ResolvedReference>,
    ): List<GoalConstraint> {
        val constraints = mutableListOf<GoalConstraint>()
        entities.forEach { entity ->
            constraints += GoalConstraint(
                key = entity.type.name.lowercase(),
                value = entity.normalizedValue,
                confidence = entity.confidence,
                source = "entity:${entity.rawText}",
            )
        }
        constraints += constraintExtractor.extract(utterance)
        references.filter { it.targetPhotonId != null }.forEach { reference ->
            constraints += GoalConstraint(
                key = "reference.${reference.expression.kind.name.lowercase()}",
                value = reference.targetPhotonId!!.value,
                confidence = reference.score,
                source = "reference:${reference.expression.rawText}",
            )
        }
        return constraints.distinctBy { Triple(it.key, it.value, it.source) }
    }

    private fun buildAmbiguities(
        evidence: List<IntentEvidence>,
        references: List<ResolvedReference>,
        topIntent: IntentType,
    ): List<Ambiguity> {
        val result = mutableListOf<Ambiguity>()
        if (evidence.size > 1 && evidence[0].score - evidence[1].score < 0.12) {
            result += Ambiguity(
                code = "intent_competition",
                message = "Multiple intents have similar deterministic evidence",
                alternatives = evidence.take(3).map { "${it.intent.name}:${"%.2f".format(java.util.Locale.ROOT, it.score)}" },
                severity = (1.0 - (evidence[0].score - evidence[1].score) / 0.12).coerceIn(0.0, 1.0),
            )
        }
        references.forEach { reference ->
            if (reference.targetPhotonId == null || reference.score < 0.55) {
                result += Ambiguity(
                    code = "unresolved_reference",
                    message = "Reference '${reference.expression.rawText}' could not be resolved confidently",
                    alternatives = reference.alternatives.map { it.first.value },
                    severity = (1.0 - reference.score).coerceIn(0.0, 1.0),
                )
            } else {
                val runnerUp = reference.alternatives.firstOrNull()
                if (runnerUp != null && reference.score - runnerUp.second < 0.08) {
                    result += Ambiguity(
                        code = "reference_competition",
                        message = "Reference '${reference.expression.rawText}' has multiple close candidates",
                        alternatives = listOf(reference.targetPhotonId.value, runnerUp.first.value),
                        severity = 0.70,
                    )
                }
            }
        }
        if (topIntent == IntentType.TRANSFORM_IMAGE && references.none { it.targetPhotonId != null }) {
            result += Ambiguity(
                code = "image_source_missing",
                message = "Image transformation requires a source image reference",
                alternatives = emptyList(),
                severity = 0.92,
            )
        }
        return result.distinctBy { it.code to it.message }
    }

    private fun calculateConfidence(
        intentScore: Double,
        entities: List<SemanticEntity>,
        references: List<ResolvedReference>,
        ambiguities: List<Ambiguity>,
    ): Double {
        val entitySupport = if (entities.isEmpty()) 0.55 else entities.map { it.confidence }.average()
        val referenceSupport = if (references.isEmpty()) 0.80 else references.map { it.score }.average()
        val ambiguityPenalty = min(0.45, ambiguities.sumOf { it.severity } * 0.16)
        return (intentScore * 0.60 + entitySupport * 0.20 + referenceSupport * 0.20 - ambiguityPenalty)
            .coerceIn(0.0, 1.0)
    }
}

data class GoalPhoton(
    val photon: Photon,
    val frame: GoalFrame,
)

class GoalPhotonFactory {
    fun create(
        result: LanguageUnderstandingResult,
        sourcePhotonId: PhotonId? = null,
        createdAt: Instant = Instant.now(),
    ): GoalPhoton {
        val frame = result.goal
        val parentIds = sourcePhotonId?.let { setOf(it) }.orEmpty()
        val relations = sourcePhotonId?.let {
            setOf(PhotonRelation(it, RelationType.DERIVED_FROM, frame.confidence))
        }.orEmpty()
        val photon = Photon(
            content = serialize(frame),
            mimeType = "application/vnd.lifeos.goal+text",
            phase = PhotonPhase.CREATED,
            semanticMass = 1.0 + frame.constraints.size * 0.08 + frame.references.size * 0.12,
            energy = 1.0,
            confidence = frame.confidence,
            provenance = Provenance(
                source = "language-understanding",
                actor = "LanguageUnderstandingEngine",
                createdAt = createdAt,
                parentIds = parentIds,
            ),
            relations = relations,
            tags = setOf("goal", "language-understood", "intent:${frame.intent.name.lowercase()}", "lang:${frame.language.name.lowercase()}"),
        )
        return GoalPhoton(photon, frame)
    }

    private fun serialize(frame: GoalFrame): String = buildString {
        append("goal/v1\n")
        append("intent=").append(frame.intent.name).append('\n')
        append("language=").append(frame.language.name).append('\n')
        append("confidence=").append(frame.confidence).append('\n')
        append("objective=").append(escape(frame.objective)).append('\n')
        frame.constraints.sortedWith(compareBy<GoalConstraint> { it.key }.thenBy { it.value }).forEach {
            append("constraint.").append(escape(it.key)).append('=').append(escape(it.value)).append('|').append(it.confidence).append('\n')
        }
        frame.references.forEach {
            append("reference.").append(it.expression.kind.name)
                .append('=').append(it.targetPhotonId?.value ?: "UNRESOLVED")
                .append('|').append(it.score).append('\n')
        }
        frame.ambiguities.forEach {
            append("ambiguity.").append(escape(it.code)).append('=').append(escape(it.message)).append('|').append(it.severity).append('\n')
        }
    }.trimEnd()

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("=", "\\=")
        .replace("|", "\\|")
}
