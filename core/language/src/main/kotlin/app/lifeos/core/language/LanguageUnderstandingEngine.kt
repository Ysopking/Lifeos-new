package app.lifeos.core.language

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min

class LanguageUnderstandingEngine(
    private val normalizer: UtteranceNormalizer = UtteranceNormalizer(),
    private val intentClassifier: RuleBasedIntentClassifier = RuleBasedIntentClassifier(),
    private val entityExtractor: RuleBasedEntityExtractor = RuleBasedEntityExtractor(),
    private val entityPipelineV2: DeterministicEntityPipelineV2 =
        DeterministicEntityPipelineV2(entityExtractor),
    private val entityEngineV3: EntityEngineV3 = EntityEngineV3(entityPipelineV2),
    private val constraintExtractor: RuleBasedConstraintExtractor = RuleBasedConstraintExtractor(),
    private val referenceExtractor: ReferenceExpressionExtractor = ReferenceExpressionExtractor(),
    private val referenceResolver: ReferenceResolver = ReferenceResolver(),
    private val linguisticFieldEngine: LinguisticFieldEngine = LinguisticFieldEngine(),
    private val fieldAdapter: FieldLanguageAdapter = FieldLanguageAdapter(),
    private val discourseIntentResolver: DiscourseIntentResolver = DiscourseIntentResolver(),
    private val semanticGraphExtractor: LanguageSemanticGraphExtractor = LanguageSemanticGraphExtractor(),
    private val speechActParser: SpeechActParser = SpeechActParser(),
    private val predicateFrameParser: PredicateFrameParser = PredicateFrameParser(),
    private val semanticActionGraphBuilder: SemanticActionGraphBuilder = SemanticActionGraphBuilder(),
    private val quantityTemporalEngine: QuantityTemporalEngine = QuantityTemporalEngine(),
    private val domainSemanticInterpreter: DomainSemanticInterpreter = DomainSemanticInterpreter(),
    private val qualityEvaluator: SemanticInterpretationQualityEvaluator =
        SemanticInterpretationQualityEvaluator(),
) {
    fun understand(text: String): LanguageUnderstandingResult =
        understand(text, LanguageContext(), retainContext = false)

    fun understand(text: String, context: LanguageContext): LanguageUnderstandingResult =
        understand(text, context, retainContext = true)

    private fun understand(
        text: String,
        context: LanguageContext,
        retainContext: Boolean,
    ): LanguageUnderstandingResult {
        val utterance = normalizer.normalize(text)
        val linguisticField = linguisticFieldEngine.converge(utterance, context)
        val ruleEvidence = intentClassifier.classify(utterance)
        val fieldEvidence = fieldAdapter.intentEvidence(linguisticField)
        val discourseEvidence = discourseIntentResolver.evidence(
            utterance = utterance,
            context = context,
            existingEvidence = ruleEvidence + fieldEvidence,
        )
        val evidence = fieldAdapter.mergeIntentEvidence(
            ruleEvidence,
            fieldEvidence,
            discourseEvidence,
        )
        val topIntent = evidence.first().intent
        val entityV3 = entityEngineV3.extract(utterance)
        val entities = fieldAdapter.mergeEntities(
            entityV3.legacyProjection,
            fieldAdapter.entities(utterance, linguisticField),
        )
        val semanticGraph = semanticGraphExtractor.extract(utterance, entities)
        val quantityTemporal = quantityTemporalEngine.parse(
            utterance = utterance,
            referenceInstant = context.now,
            zoneId = ZoneId.of(context.zoneId),
        )
        val references = referenceExtractor.extract(utterance, topIntent).map { referenceResolver.resolve(it, context) }
        val speechActs = speechActParser.parse(utterance, semanticGraph)
        val predicateFrames = predicateFrameParser.parse(
            utterance = utterance,
            graph = semanticGraph,
            speechActs = speechActs,
            references = references,
            linguisticField = linguisticField,
        )
        val semanticActionGraph = semanticActionGraphBuilder.build(
            utterance = utterance,
            semanticGraph = semanticGraph,
            frames = predicateFrames,
            references = references,
        )
        val operationalIntent = deriveOperationalIntent(topIntent, semanticActionGraph)
        val ambiguities = buildAmbiguities(
            evidence = evidence,
            references = references,
            topIntent = topIntent,
            linguisticField = linguisticField,
            actionGraph = semanticActionGraph,
        )
        val domainSemanticGraph = domainSemanticInterpreter.interpret(
            utterance = utterance,
            semanticGraph = semanticGraph,
            entities = entityV3.entities,
            quantityTemporal = quantityTemporal,
            actionGraph = semanticActionGraph,
        )
        val quality = qualityEvaluator.evaluate(
            intentEvidence = evidence,
            actionGraph = semanticActionGraph,
            ambiguities = ambiguities,
        )
        val constraints = buildConstraints(
            utterance,
            entities,
            references,
            linguisticField,
            quantityTemporal,
        )
        val confidence = calculateConfidence(evidence.first().score, entities, references, ambiguities, linguisticField)
        val goal = GoalFrame(
            intent = operationalIntent,
            objective = canonicalObjective(utterance, operationalIntent),
            entities = entities,
            references = references,
            constraints = constraints,
            ambiguities = ambiguities,
            confidence = confidence,
            language = utterance.language,
            semanticGraph = semanticGraph,
            semanticActionGraph = semanticActionGraph,
            semanticEntitiesV2 = entityV3.entities,
            quantityTemporal = quantityTemporal,
            domainSemanticGraph = domainSemanticGraph,
            interpretationQuality = quality,
        )
        return LanguageUnderstandingResult(
            utterance = utterance,
            intentEvidence = evidence,
            goal = goal,
            linguisticField = linguisticField,
            context = context.takeIf { retainContext },
        )
    }

    private fun deriveOperationalIntent(
        topicIntent: IntentType,
        actionGraph: SemanticActionGraph,
    ): IntentType {
        // Intent is descriptive metadata, never execution authority. Preserve a confidently
        // recognized topic even when arguments/references are unresolved; SemanticExecutionGate
        // is the only boundary that may authorize a side effect.
        if (topicIntent == IntentType.CONVERSATION) return IntentType.CONVERSATION

        val nodes = actionGraph.nodes
        if (nodes.any { it.type == SemanticActionNodeType.QUERY } &&
            nodes.none { it.executable }
        ) {
            return IntentType.QUERY
        }

        val executableIntents = actionGraph.executableNodes
            .mapNotNull { it.frame.predicate.toIntentTypeOrNull() }
            .distinct()
        if (executableIntents.size == 1) return executableIntents.single()

        if (topicIntent != IntentType.UNKNOWN) return topicIntent

        // Descriptive semantic recognition is allowed to be stronger than execution authority.
        // A single non-executable predicate may identify what the user is talking about while the
        // SemanticExecutionGate still blocks any side effect until readiness is independently met.
        val describedIntents = actionGraph.nodes
            .mapNotNull { it.frame.predicate.toIntentTypeOrNull() }
            .distinct()
        if (describedIntents.size == 1) return describedIntents.single()

        return IntentType.UNKNOWN
    }

    private fun canonicalObjective(utterance: NormalizedUtterance, intent: IntentType): String =
        "${intent.name.lowercase()}: ${utterance.original.trim()}"

    private fun buildConstraints(
        utterance: NormalizedUtterance,
        entities: List<SemanticEntity>,
        references: List<ResolvedReference>,
        linguisticField: LinguisticFieldResult,
        quantityTemporal: QuantityTemporalResult,
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
        linguisticField.resolutions
            .filter { it.entityType == null }
            .forEach { resolution ->
                constraints += GoalConstraint(
                    key = "field.semantic.${resolution.semanticTag.lowercase()}",
                    value = resolution.canonical,
                    confidence = resolution.confidence,
                    source = "linguistic-field:${resolution.rawToken}",
                )
            }
        references.filter { it.targetPhotonRef != null }.forEach { reference ->
            val ref = requireNotNull(reference.targetPhotonRef)
            constraints += GoalConstraint(
                key = "reference.${reference.expression.kind.name.lowercase()}",
                value = "${ref.photonId.value}@${ref.revision}",
                confidence = reference.score,
                source = "reference:${reference.expression.rawText}",
            )
        }
        quantityTemporal.quantities.forEachIndexed { index, quantity ->
            val value = buildString {
                append(quantity.comparator.name)
                append(':')
                append(quantity.value?.toPlainString().orEmpty())
                append(':')
                append(quantity.lowerBound?.toPlainString().orEmpty())
                append(':')
                append(quantity.upperBound?.toPlainString().orEmpty())
                append(':')
                append(quantity.currency?.currencyCode ?: quantity.unit.orEmpty())
            }
            constraints += GoalConstraint(
                key = "quantity.v2.$index",
                value = value,
                confidence = quantity.confidence,
                source = "quantity-temporal-engine",
            )
        }
        quantityTemporal.temporals.forEachIndexed { index, temporal ->
            constraints += GoalConstraint(
                key = "temporal.v2.$index",
                value = listOf(
                    temporal.relation.name,
                    temporal.startInclusive?.toString().orEmpty(),
                    temporal.endInclusive?.toString().orEmpty(),
                ).joinToString(":"),
                confidence = temporal.confidence,
                source = "quantity-temporal-engine",
            )
        }
        quantityTemporal.dateTimes.forEachIndexed { index, dateTime ->
            constraints += GoalConstraint(
                key = "datetime.v3.$index",
                value = listOf(dateTime.instant.toString(), dateTime.zoneId).joinToString(":"),
                confidence = dateTime.confidence,
                source = "quantity-temporal-engine-v3",
            )
        }
        return constraints.distinctBy { Triple(it.key, it.value, it.source) }
    }

    private fun buildAmbiguities(
        evidence: List<IntentEvidence>,
        references: List<ResolvedReference>,
        topIntent: IntentType,
        linguisticField: LinguisticFieldResult,
        actionGraph: SemanticActionGraph,
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
        if (!linguisticField.converged && linguisticField.iterations > 0) {
            result += Ambiguity(
                code = "linguistic_field_not_converged",
                message = "Linguistic field reached its deterministic iteration budget before convergence",
                alternatives = linguisticField.resolutions.take(5).map { "${it.rawToken}->${it.canonical}" },
                severity = 0.30,
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
        val intentPredicate = topIntent.toPredicateConcept()
        val matchingNodes = actionGraph.nodes.filter { it.frame.predicate == intentPredicate }
        if (intentPredicate != PredicateConcept.UNKNOWN && matchingNodes.isNotEmpty()) {
            val speechActs = matchingNodes.map { it.frame.speechAct.type }.toSet()
            if (
                topIntent !in setOf(IntentType.QUERY, IntentType.CONVERSATION, IntentType.UNKNOWN) &&
                SpeechActType.QUESTION in speechActs &&
                matchingNodes.none { it.executable }
            ) {
                result += Ambiguity(
                    code = "command_vs_question",
                    message = "Action topic was recognized inside a question, not an executable request",
                    alternatives = listOf(topIntent.name, SpeechActType.QUESTION.name),
                    severity = 0.95,
                )
            }
            if (matchingNodes.any { it.frame.quoted }) {
                result += Ambiguity(
                    code = "quoted_action",
                    message = "Action wording is quoted and cannot authorize execution",
                    alternatives = listOf(topIntent.name),
                    severity = 1.0,
                )
            }
            if (matchingNodes.any { it.frame.negated }) {
                result += Ambiguity(
                    code = "negated_action",
                    message = "Action predicate is explicitly negated",
                    alternatives = listOf(topIntent.name),
                    severity = 1.0,
                )
            }
            if (matchingNodes.any { it.unresolvedCondition }) {
                result += Ambiguity(
                    code = "conditional_action",
                    message = "Action depends on an unresolved condition",
                    alternatives = listOf(topIntent.name),
                    severity = 1.0,
                )
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
        linguisticField: LinguisticFieldResult,
    ): Double {
        val entitySupport = if (entities.isEmpty()) 0.55 else entities.map { it.confidence }.average()
        val referenceSupport = if (references.isEmpty()) 0.80 else references.map { it.score }.average()
        val fieldSupport = if (linguisticField.resolutions.isEmpty()) 0.60 else linguisticField.resolutions.map { it.confidence }.average()
        val ambiguityPenalty = min(0.45, ambiguities.sumOf { it.severity } * 0.16)
        return (intentScore * 0.54 + entitySupport * 0.18 + referenceSupport * 0.18 + fieldSupport * 0.10 - ambiguityPenalty)
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
            content = serialize(frame, result.linguisticField),
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

    private fun serialize(frame: GoalFrame, field: LinguisticFieldResult?): String = buildString {
        append("goal/v4\n")
        append("intent=").append(frame.intent.name).append('\n')
        append("language=").append(frame.language.name).append('\n')
        append("confidence=").append(frame.confidence).append('\n')
        append("objective=").append(escape(frame.objective)).append('\n')
        append("semantic.fingerprint=").append(frame.semanticGraph.fingerprint).append('\n')
        frame.semanticGraph.clauses.sortedBy { it.id }.forEach { clause ->
            append("semantic.clause.").append(clause.id).append('=')
                .append(clause.polarity.name).append('|')
                .append(clause.modality.name).append('|')
                .append(escape(clause.normalized)).append('\n')
            clause.quantities.forEachIndexed { index, quantity ->
                append("semantic.quantity.").append(clause.id).append('.').append(index).append('=')
                    .append(escape(quantity.comparator.orEmpty())).append('|')
                    .append(escape(quantity.value)).append('|')
                    .append(escape(quantity.unit.orEmpty())).append('\n')
            }
        }
        frame.semanticGraph.links.sortedWith(
            compareBy<SemanticLink> { it.fromClauseId }
                .thenBy { it.toClauseId }
                .thenBy { it.type.name }
                .thenBy { it.cue }
        ).forEachIndexed { index, link ->
            append("semantic.link.").append(index).append('=')
                .append(link.fromClauseId).append('|')
                .append(link.toClauseId).append('|')
                .append(link.type.name).append('|')
                .append(escape(link.cue)).append('\n')
        }
        append("action.fingerprint=").append(frame.semanticActionGraph.fingerprint).append('\n')
        frame.semanticActionGraph.nodes.forEachIndexed { index, node ->
            append("action.node.").append(index).append('=')
                .append(escape(node.id.value)).append('|')
                .append(node.type.name).append('|')
                .append(node.frame.clauseId).append('|')
                .append(node.frame.predicate.name).append('|')
                .append(node.frame.speechAct.type.name).append('|')
                .append(node.frame.speechAct.confidence).append('|')
                .append(node.frame.speechAct.span.start).append('|')
                .append(node.frame.speechAct.span.endExclusive).append('|')
                .append(node.frame.confidence).append('|')
                .append(node.frame.scopeTypes.map { it.name }.sorted().joinToString(",")).append('|')
                .append(node.requiredRoles.map { it.name }.sorted().joinToString(",")).append('|')
                .append(node.unresolvedRoles.map { it.name }.sorted().joinToString(",")).append('|')
                .append(node.unresolvedReference).append('|')
                .append(node.unresolvedCondition).append('|')
                .append(node.externalSideEffect).append('|')
                .append(node.executionReadiness).append('\n')
            node.frame.roles.entries.sortedBy { it.key.name }.forEach { (role, value) ->
                append("action.role.").append(index).append('.').append(role.name).append('=')
                    .append(escape(value.rawText)).append('|')
                    .append(escape(value.normalized)).append('|')
                    .append(value.entityType?.name.orEmpty()).append('|')
                    .append(value.resolved).append('|')
                    .append(value.confidence).append('|')
                    .append(escape(value.referencePhoton?.photonId?.value.orEmpty())).append('|')
                    .append(value.referencePhoton?.revision ?: 0L).append('|')
                    .append(escape(value.quantity?.value.orEmpty())).append('|')
                    .append(escape(value.quantity?.unit.orEmpty())).append('|')
                    .append(escape(value.quantity?.comparator.orEmpty())).append('|')
                    .append(value.quantity?.tokenStart ?: -1).append('|')
                    .append(value.quantity?.tokenEndExclusive ?: -1).append('|')
                    .append(value.quantity?.confidence ?: -1.0)
                    .append('\n')
            }
            node.frame.speechAct.evidence.forEachIndexed { evidenceIndex, evidence ->
                append("action.speech.evidence.").append(index).append('.').append(evidenceIndex).append('=')
                    .append(escape(evidence.source)).append('|')
                    .append(escape(evidence.detail)).append('|')
                    .append(evidence.strength).append('|')
                    .append(evidence.span?.start ?: -1).append('|')
                    .append(evidence.span?.endExclusive ?: -1)
                    .append('\n')
            }
            node.frame.evidence.forEachIndexed { evidenceIndex, evidence ->
                append("action.frame.evidence.").append(index).append('.').append(evidenceIndex).append('=')
                    .append(escape(evidence.source)).append('|')
                    .append(escape(evidence.detail)).append('|')
                    .append(evidence.strength).append('|')
                    .append(evidence.span?.start ?: -1).append('|')
                    .append(evidence.span?.endExclusive ?: -1)
                    .append('\n')
            }
        }
        frame.semanticActionGraph.edges.sortedWith(
            compareBy<SemanticActionEdge> { it.from.value }
                .thenBy { it.to.value }
                .thenBy { it.type.name }
        ).forEachIndexed { index, edge ->
            append("action.edge.").append(index).append('=')
                .append(escape(edge.from.value)).append('|')
                .append(escape(edge.to.value)).append('|')
                .append(edge.type.name).append('|')
                .append(edge.confidence).append('\n')
        }
        frame.semanticActionGraph.scopes.sortedWith(
            compareBy<SemanticScope> { it.span.start }
                .thenBy { it.span.endExclusive }
                .thenBy { it.type.name }
                .thenBy { it.cue }
        ).forEachIndexed { index, scope ->
            append("action.scope.").append(index).append('=')
                .append(scope.type.name).append('|')
                .append(escape(scope.targetNodeIds.map { it.value }.sorted().joinToString(","))).append('|')
                .append(scope.span.start).append('|')
                .append(scope.span.endExclusive).append('|')
                .append(escape(scope.cue)).append('|')
                .append(scope.confidence).append('\n')
        }
        field?.let {
            append("field.converged=").append(it.converged).append('\n')
            append("field.iterations=").append(it.iterations).append('\n')
            append("field.energy=").append(it.totalEnergy).append('\n')
            it.resolutions.sortedBy { resolution -> resolution.tokenIndex }.forEach { resolution ->
                append("field.token.").append(resolution.tokenIndex)
                    .append('=').append(escape(resolution.canonical))
                    .append('|').append(escape(resolution.semanticTag))
                    .append('|').append(resolution.confidence).append('\n')
            }
            it.graphemeTraces.take(MAX_SERIALIZED_FIELD_TRACES).forEachIndexed { index, trace ->
                append("field.grapheme.").append(index).append('=')
                    .append(escape(trace.observed)).append('>').append(escape(trace.expected))
                    .append('|').append(trace.orthographicAffinity)
                    .append('|').append(trace.phoneticAffinity).append('\n')
            }
            it.compoundBindings.take(MAX_SERIALIZED_FIELD_TRACES).forEach { binding ->
                append("field.compound.").append(binding.tokenIndex).append('=')
                    .append(binding.components.joinToString("+") { component -> escape(component.semanticTag) })
                    .append('|').append(binding.confidence).append('\n')
            }
            it.topDownRevisions
                .sortedByDescending { revision -> abs(revision.delta) }
                .take(MAX_SERIALIZED_FIELD_TRACES)
                .forEachIndexed { index, revision ->
                    append("field.feedback.").append(index).append('=')
                        .append(revision.tokenIndex).append('|').append(escape(revision.conceptId))
                        .append('|').append(revision.bottomUpActivation)
                        .append('|').append(revision.resolvedActivation)
                        .append('|').append(revision.semanticForce)
                        .append('|').append(revision.photonForce)
                        .append('|').append(revision.intentForce)
                        .append('|').append(revision.compositionForce).append('\n')
                }
        }
        frame.semanticEntitiesV2.sortedWith(
            compareBy<SemanticEntityV2> { it.tokenStart }
                .thenBy { it.tokenEndExclusive }
                .thenBy { it.typeId.value }
                .thenBy { it.normalizedValue }
        ).forEachIndexed { index, entity ->
            append("entity.v2.").append(index).append('=')
                .append(escape(entity.typeId.value)).append('|')
                .append(escape(entity.rawText)).append('|')
                .append(escape(entity.normalizedValue)).append('|')
                .append(entity.tokenStart).append('|')
                .append(entity.tokenEndExclusive).append('|')
                .append(entity.confidence).append('|')
                .append(escape(entity.source)).append('\n')
        }
        frame.quantityTemporal.quantities.sortedWith(
            compareBy<SemanticQuantityV2> { it.span.start }
                .thenBy { it.span.endExclusive }
                .thenBy { it.comparator.name }
                .thenBy { it.value?.toPlainString().orEmpty() }
        ).forEachIndexed { index, quantity ->
            append("canonical.quantity.").append(index).append('=')
                .append(quantity.comparator.name).append('|')
                .append(escape(quantity.value?.toPlainString().orEmpty())).append('|')
                .append(escape(quantity.lowerBound?.toPlainString().orEmpty())).append('|')
                .append(escape(quantity.upperBound?.toPlainString().orEmpty())).append('|')
                .append(escape(quantity.unit.orEmpty())).append('|')
                .append(escape(quantity.currency?.currencyCode.orEmpty())).append('|')
                .append(quantity.span.start).append('|')
                .append(quantity.span.endExclusive).append('|')
                .append(quantity.confidence).append('\n')
        }
        frame.quantityTemporal.temporals.sortedWith(
            compareBy<SemanticTemporalValue> { it.span.start }
                .thenBy { it.span.endExclusive }
                .thenBy { it.relation.name }
        ).forEachIndexed { index, temporal ->
            append("canonical.temporal.").append(index).append('=')
                .append(temporal.relation.name).append('|')
                .append(escape(temporal.startInclusive?.toString().orEmpty())).append('|')
                .append(escape(temporal.endInclusive?.toString().orEmpty())).append('|')
                .append(escape(temporal.sourceText)).append('|')
                .append(temporal.span.start).append('|')
                .append(temporal.span.endExclusive).append('|')
                .append(temporal.confidence).append('\n')
        }
        frame.quantityTemporal.dateTimes.sortedWith(
            compareBy<SemanticDateTimeValue> { it.span.start }
                .thenBy { it.span.endExclusive }
                .thenBy { it.instant }
        ).forEachIndexed { index, dateTime ->
            append("canonical.datetime.").append(index).append('=')
                .append(escape(dateTime.instant.toString())).append('|')
                .append(escape(dateTime.zoneId)).append('|')
                .append(escape(dateTime.sourceText)).append('|')
                .append(dateTime.span.start).append('|')
                .append(dateTime.span.endExclusive).append('|')
                .append(dateTime.dateSpan?.start ?: -1).append('|')
                .append(dateTime.dateSpan?.endExclusive ?: -1).append('|')
                .append(dateTime.timeSpan.start).append('|')
                .append(dateTime.timeSpan.endExclusive).append('|')
                .append(dateTime.confidence).append('\n')
        }
        append("domain.fingerprint=").append(frame.domainSemanticGraph.fingerprint).append('\n')
        frame.domainSemanticGraph.nodes.sortedBy { it.id.value }.forEachIndexed { index, node ->
            append("domain.node.").append(index).append('=')
                .append(escape(node.id.value)).append('|')
                .append(node.pack.name).append('|')
                .append(escape(node.type)).append('|')
                .append(escape(node.value)).append('|')
                .append(node.confidence).append('|')
                .append(escape(node.sourceEntityType?.value.orEmpty())).append('\n')
        }
        frame.domainSemanticGraph.relations.sortedWith(
            compareBy<DomainSemanticRelation> { it.from.value }
                .thenBy { it.to.value }
                .thenBy { it.type.name }
        ).forEachIndexed { index, relation ->
            append("domain.relation.").append(index).append('=')
                .append(escape(relation.from.value)).append('|')
                .append(escape(relation.to.value)).append('|')
                .append(relation.type.name).append('|')
                .append(relation.confidence).append('\n')
        }
        append("quality=")
            .append(frame.interpretationQuality.evidenceStrength).append('|')
            .append(frame.interpretationQuality.interpretationMargin).append('|')
            .append(frame.interpretationQuality.completeness).append('|')
            .append(frame.interpretationQuality.contradictionCount).append('|')
            .append(frame.interpretationQuality.ambiguityCount).append('|')
            .append(frame.interpretationQuality.executionReadiness).append('\n')
        frame.constraints.sortedWith(compareBy<GoalConstraint> { it.key }.thenBy { it.value }).forEach {
            append("constraint.").append(escape(it.key)).append('=').append(escape(it.value)).append('|').append(it.confidence).append('\n')
        }
        frame.references.sortedWith(
            compareBy<ResolvedReference> { it.expression.kind.name }
                .thenBy { it.targetPhotonRef?.photonId?.value.orEmpty() }
                .thenBy { it.targetPhotonRef?.revision ?: 0L }
                .thenByDescending { it.score }
        ).forEach {
            append("reference.").append(it.expression.kind.name)
                .append('=').append(it.targetPhotonId?.value ?: "UNRESOLVED")
                .append('|').append(it.score)
                .append('|').append(it.targetPhotonRef?.revision ?: 0L)
                .append('\n')
        }
        frame.ambiguities.sortedWith(
            compareBy<Ambiguity> { it.code }
                .thenByDescending { it.severity }
                .thenBy { it.message }
        ).forEach {
            append("ambiguity.").append(escape(it.code)).append('=').append(escape(it.message)).append('|').append(it.severity).append('\n')
        }
    }.trimEnd()

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("=", "\\=")
        .replace("|", "\\|")

    companion object {
        private const val MAX_SERIALIZED_FIELD_TRACES = 32
    }
}
