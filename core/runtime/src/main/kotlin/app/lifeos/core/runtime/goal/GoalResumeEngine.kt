package app.lifeos.core.runtime.goal

import app.lifeos.core.language.Ambiguity
import app.lifeos.core.language.EntityType
import app.lifeos.core.language.GoalConstraint
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.TemporalRelation
import app.lifeos.core.language.SemanticTemporalValue
import app.lifeos.core.language.SemanticQuantityV2
import app.lifeos.core.language.SemanticInterpretationQuality
import app.lifeos.core.language.QuantityTemporalResult
import app.lifeos.core.language.QuantityComparator
import app.lifeos.core.language.DomainSemanticRelationType
import app.lifeos.core.language.DomainSemanticRelation
import app.lifeos.core.language.DomainSemanticPackId
import app.lifeos.core.language.DomainSemanticNodeId
import app.lifeos.core.language.DomainSemanticNode
import app.lifeos.core.language.DomainSemanticGraph
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.language.LanguageSemanticGraph
import app.lifeos.core.language.PredicateConcept
import app.lifeos.core.language.PredicateFrame
import app.lifeos.core.language.ScopeType
import app.lifeos.core.language.SemanticActionEdge
import app.lifeos.core.language.SemanticActionEdgeType
import app.lifeos.core.language.SemanticActionGraph
import app.lifeos.core.language.SemanticActionNode
import app.lifeos.core.language.SemanticActionNodeType
import app.lifeos.core.language.SemanticEvidence
import app.lifeos.core.language.SemanticNodeId
import app.lifeos.core.language.SemanticRole
import app.lifeos.core.language.SemanticScope
import app.lifeos.core.language.SemanticValue
import app.lifeos.core.language.SpeechAct
import app.lifeos.core.language.SpeechActType
import app.lifeos.core.language.TextSpan
import app.lifeos.core.language.ReferenceExpression
import app.lifeos.core.language.ReferenceKind
import app.lifeos.core.language.ResolvedReference
import app.lifeos.core.language.SemanticClause
import app.lifeos.core.language.SemanticEntity
import app.lifeos.core.language.SemanticEntityV2
import app.lifeos.core.language.SemanticEntityTypeId
import app.lifeos.core.language.SemanticLink
import app.lifeos.core.language.SemanticLinkType
import app.lifeos.core.language.SemanticModality
import app.lifeos.core.language.SemanticPolarity
import app.lifeos.core.language.SemanticQuantity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant
import java.util.Currency

enum class GoalResumeBlockReason {
    NOT_CONTINUATION,
    TARGET_REFERENCE_MISSING,
    TARGET_NOT_FOUND,
    TARGET_NOT_GOAL,
    TARGET_ARCHIVED,
    TARGET_CHAIN_INVALID,
    TARGET_SOURCE_MISSING,
    TARGET_UNDECODABLE,
    TARGET_NOT_RESUMABLE,
}

sealed interface GoalResumeResult {
    data class Resumed(
        val targetGoal: Photon,
        val sourcePhoton: Photon,
        val frame: GoalFrame,
        val resumedPhoton: Photon,
    ) : GoalResumeResult

    data class Blocked(
        val reason: GoalResumeBlockReason,
        val message: String,
    ) : GoalResumeResult {
        init { require(message.isNotBlank()) }
    }
}

/**
 * J14 persistent goal continuation. The engine never re-interprets the old user utterance against
 * today's context. Instead it follows the already resolved CONTINUE reference, validates the exact
 * stored goal, decodes the immutable persisted goal representation and emits a new provenance-bound
 * goal photon. Both legacy goal/v2 and structured goal/v3 are accepted; unknown versions fail closed.
 * Capability routing and concrete action execution remain outside this class.
 */
class GoalResumeEngine {
    fun resume(
        request: GoalFrame,
        requestSource: Photon,
        requestGoalPhotonId: PhotonId,
        photons: List<Photon>,
        createdAt: Instant = Instant.now(),
    ): GoalResumeResult {
        if (request.intent != IntentType.CONTINUE) {
            return blocked(GoalResumeBlockReason.NOT_CONTINUATION, "Goal resume requires CONTINUE intent")
        }

        val targetId = request.references
            .asSequence()
            .filter { it.targetPhotonId != null }
            .sortedWith(
                compareByDescending<ResolvedReference> { it.score }
                    .thenBy { it.targetPhotonId!!.value }
            )
            .firstOrNull()
            ?.targetPhotonId
            ?: return blocked(
                GoalResumeBlockReason.TARGET_REFERENCE_MISSING,
                "Continuation does not contain a resolved target goal",
            )

        val byId = photons.associateBy { it.id }
        val referenced = byId[targetId]
            ?: return blocked(GoalResumeBlockReason.TARGET_NOT_FOUND, "Referenced goal photon is missing")
        if (!referenced.isGoalPhoton()) {
            return blocked(GoalResumeBlockReason.TARGET_NOT_GOAL, "Continuation target is not a goal photon")
        }
        if (referenced.phase == PhotonPhase.ARCHIVED) {
            return blocked(GoalResumeBlockReason.TARGET_ARCHIVED, "Archived goals cannot be resumed")
        }

        val target = unwrapResumedGoal(referenced, byId)
            ?: return blocked(
                GoalResumeBlockReason.TARGET_CHAIN_INVALID,
                "Resumed goal provenance chain is invalid or cyclic",
            )
        if (target.phase == PhotonPhase.ARCHIVED) {
            return blocked(GoalResumeBlockReason.TARGET_ARCHIVED, "Archived goals cannot be resumed")
        }

        val frame = PersistedGoalFrameDecoder.decode(target)
            ?: return blocked(
                GoalResumeBlockReason.TARGET_UNDECODABLE,
                "Persisted target goal cannot be decoded safely",
            )
        if (frame.intent == IntentType.CONTINUE || frame.intent == IntentType.UNKNOWN) {
            return blocked(
                GoalResumeBlockReason.TARGET_NOT_RESUMABLE,
                "Continuation cannot target CONTINUE or UNKNOWN goals",
            )
        }

        val sourceCandidates = target.provenance.parentIds
            .mapNotNull(byId::get)
            .filter { "chat" in it.tags && !it.isGoalPhoton() }
            .sortedWith(compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value })
        if (sourceCandidates.size != 1) {
            return blocked(
                GoalResumeBlockReason.TARGET_SOURCE_MISSING,
                "Target goal must bind exactly one persisted source utterance",
            )
        }
        val source = sourceCandidates.single()

        val resumed = Photon(
            content = target.content,
            mimeType = target.mimeType,
            phase = PhotonPhase.CREATED,
            semanticMass = target.semanticMass,
            energy = target.energy,
            confidence = target.confidence,
            provenance = Provenance(
                source = "goal-resume",
                actor = "GoalResumeEngine",
                createdAt = createdAt,
                parentIds = setOf(target.id, requestGoalPhotonId, requestSource.id),
            ),
            relations = setOf(
                PhotonRelation(target.id, RelationType.DERIVED_FROM, 1.0),
                PhotonRelation(requestGoalPhotonId, RelationType.REFERENCES, request.confidence),
                PhotonRelation(requestSource.id, RelationType.REFERENCES, request.confidence),
            ),
            tags = target.tags + setOf(
                "goal-resumed",
                "resume-origin:${target.id.value}",
            ),
        )
        return GoalResumeResult.Resumed(
            targetGoal = target,
            sourcePhoton = source,
            frame = frame,
            resumedPhoton = resumed,
        )
    }

    private fun unwrapResumedGoal(
        start: Photon,
        byId: Map<PhotonId, Photon>,
    ): Photon? {
        var current = start
        val visited = linkedSetOf<PhotonId>()
        repeat(MAX_RESUME_CHAIN_DEPTH) {
            if (!visited.add(current.id)) return null
            if (current.phase == PhotonPhase.ARCHIVED) return null
            if ("goal-resumed" !in current.tags) return current
            val parents = current.relations
                .asSequence()
                .filter { it.type == RelationType.DERIVED_FROM }
                .mapNotNull { byId[it.target] }
                .filter { it.isGoalPhoton() }
                .distinctBy { it.id }
                .toList()
            if (parents.size != 1) return null
            current = parents.single()
        }
        return null
    }

    private fun Photon.isGoalPhoton(): Boolean =
        "goal" in tags && mimeType == GOAL_MIME

    private fun blocked(reason: GoalResumeBlockReason, message: String) =
        GoalResumeResult.Blocked(reason, message)

    private companion object {
        const val GOAL_MIME = "application/vnd.lifeos.goal+text"
        const val MAX_RESUME_CHAIN_DEPTH = 32
    }
}

/** Strict decoder for persisted goal/v2-v4 contracts emitted by GoalPhotonFactory. */
private object PersistedGoalFrameDecoder {
    fun decode(photon: Photon): GoalFrame? = runCatching {
        val lines = photon.content.lines()
        val version = lines.firstOrNull()
        require(version == GOAL_V2 || version == GOAL_V3 || version == GOAL_V4) {
            "Unsupported persisted goal version"
        }
        val intent = IntentType.valueOf(required(lines, "intent"))
        val language = LanguageCode.valueOf(required(lines, "language"))
        val confidence = required(lines, "confidence").toDouble().also { require(it in 0.0..1.0) }
        val objective = unescape(required(lines, "objective")).also { require(it.isNotBlank()) }

        val constraints = lines
            .filter { it.startsWith("constraint.") }
            .map { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("constraint."), '='))
                val payload = requireNotNull(splitUnescaped(assignment.second, '|'))
                GoalConstraint(
                    key = unescape(assignment.first),
                    value = unescape(payload.first),
                    confidence = payload.second.toDouble().also { require(it in 0.0..1.0) },
                    source = "persisted-goal:${photon.id.value}",
                )
            }

        val entities = constraints.mapIndexedNotNull { index, constraint ->
            val type = EntityType.entries.firstOrNull {
                it.name.equals(constraint.key, ignoreCase = true)
            } ?: return@mapIndexedNotNull null
            SemanticEntity(
                type = type,
                rawText = constraint.value,
                normalizedValue = constraint.value,
                tokenStart = index * 2,
                tokenEndExclusive = index * 2 + 1,
                confidence = constraint.confidence,
            )
        }

        val references = lines
            .filter { it.startsWith("reference.") }
            .map { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("reference."), '='))
                val kind = ReferenceKind.valueOf(unescape(assignment.first))
                val fields = splitAllUnescaped(assignment.second, '|')
                require(fields.size in 2..3) { "Malformed persisted reference" }
                val rawTarget = unescape(fields[0])
                val target = rawTarget.takeUnless { it == "UNRESOLVED" }?.let(::PhotonId)
                val score = fields[1].toDouble().also { require(it in 0.0..1.0) }
                val revision = fields.getOrNull(2)?.toLong()?.also { require(it >= 0L) } ?: 0L
                val targetRef = target?.takeIf { revision > 0L }?.let {
                    PhotonRevisionRef(it, revision)
                }
                ResolvedReference(
                    expression = ReferenceExpression(
                        kind = kind,
                        rawText = target?.value ?: kind.name.lowercase(),
                        preferredKinds = emptySet(),
                        confidence = score,
                    ),
                    targetPhotonId = target,
                    score = score,
                    targetPhotonRef = targetRef,
                )
            }

        val semanticEntitiesV2 = if (version == GOAL_V4) {
            lines.filter { it.startsWith("entity.v2.") }.map { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("entity.v2."), '='))
                assignment.first.toInt().also { require(it >= 0) }
                val fields = splitAllUnescaped(assignment.second, '|')
                require(fields.size == 7) { "Malformed persisted v2 entity" }
                SemanticEntityV2(
                    typeId = SemanticEntityTypeId(unescape(fields[0])),
                    rawText = unescape(fields[1]),
                    normalizedValue = unescape(fields[2]),
                    tokenStart = fields[3].toInt().also { require(it >= 0) },
                    tokenEndExclusive = fields[4].toInt(),
                    confidence = fields[5].toDouble().also { require(it in 0.0..1.0) },
                    source = unescape(fields[6]),
                ).also { require(it.tokenEndExclusive > it.tokenStart) }
            }
        } else {
            emptyList()
        }

        val quantityTemporal = if (version == GOAL_V4) {
            val quantities = lines.filter { it.startsWith("canonical.quantity.") }.map { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("canonical.quantity."), '='))
                assignment.first.toInt().also { require(it >= 0) }
                val fields = splitAllUnescaped(assignment.second, '|')
                require(fields.size == 9) { "Malformed canonical quantity" }
                SemanticQuantityV2(
                    comparator = QuantityComparator.valueOf(fields[0]),
                    value = unescape(fields[1]).takeIf { it.isNotBlank() }?.toBigDecimal(),
                    lowerBound = unescape(fields[2]).takeIf { it.isNotBlank() }?.toBigDecimal(),
                    upperBound = unescape(fields[3]).takeIf { it.isNotBlank() }?.toBigDecimal(),
                    unit = unescape(fields[4]).takeIf { it.isNotBlank() },
                    currency = unescape(fields[5]).takeIf { it.isNotBlank() }?.let(Currency::getInstance),
                    span = TextSpan(fields[6].toInt(), fields[7].toInt()),
                    confidence = fields[8].toDouble().also { require(it in 0.0..1.0) },
                )
            }
            val temporals = lines.filter { it.startsWith("canonical.temporal.") }.map { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("canonical.temporal."), '='))
                assignment.first.toInt().also { require(it >= 0) }
                val fields = splitAllUnescaped(assignment.second, '|')
                require(fields.size == 7) { "Malformed canonical temporal" }
                SemanticTemporalValue(
                    relation = TemporalRelation.valueOf(fields[0]),
                    startInclusive = unescape(fields[1]).takeIf { it.isNotBlank() }?.let(Instant::parse),
                    endInclusive = unescape(fields[2]).takeIf { it.isNotBlank() }?.let(Instant::parse),
                    sourceText = unescape(fields[3]),
                    span = TextSpan(fields[4].toInt(), fields[5].toInt()),
                    confidence = fields[6].toDouble().also { require(it in 0.0..1.0) },
                )
            }
            QuantityTemporalResult(quantities, temporals)
        } else {
            QuantityTemporalResult(emptyList(), emptyList())
        }

        val domainSemanticGraph = if (version == GOAL_V4 && optional(lines, "domain.fingerprint") != null) {
            val fingerprint = unescape(requireNotNull(optional(lines, "domain.fingerprint")))
            val nodes = lines.filter { it.startsWith("domain.node.") }.map { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("domain.node."), '='))
                assignment.first.toInt().also { require(it >= 0) }
                val fields = splitAllUnescaped(assignment.second, '|')
                require(fields.size == 6) { "Malformed domain semantic node" }
                DomainSemanticNode(
                    id = DomainSemanticNodeId(unescape(fields[0])),
                    pack = DomainSemanticPackId.valueOf(fields[1]),
                    type = unescape(fields[2]),
                    value = unescape(fields[3]),
                    confidence = fields[4].toDouble().also { require(it in 0.0..1.0) },
                    sourceEntityType = unescape(fields[5])
                        .takeIf { it.isNotBlank() }
                        ?.let(::SemanticEntityTypeId),
                )
            }
            val relations = lines.filter { it.startsWith("domain.relation.") }.map { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("domain.relation."), '='))
                assignment.first.toInt().also { require(it >= 0) }
                val fields = splitAllUnescaped(assignment.second, '|')
                require(fields.size == 4) { "Malformed domain semantic relation" }
                DomainSemanticRelation(
                    from = DomainSemanticNodeId(unescape(fields[0])),
                    to = DomainSemanticNodeId(unescape(fields[1])),
                    type = DomainSemanticRelationType.valueOf(fields[2]),
                    confidence = fields[3].toDouble().also { require(it in 0.0..1.0) },
                )
            }
            DomainSemanticGraph(
                packs = nodes.mapTo(linkedSetOf()) { it.pack },
                nodes = nodes,
                relations = relations,
                fingerprint = fingerprint,
            )
        } else {
            DomainSemanticGraph.empty()
        }

        val interpretationQuality = if (version == GOAL_V4) {
            optional(lines, "quality")?.let { encoded ->
                val fields = splitAllUnescaped(encoded, '|')
                require(fields.size == 6) { "Malformed semantic interpretation quality" }
                SemanticInterpretationQuality(
                    evidenceStrength = fields[0].toDouble(),
                    interpretationMargin = fields[1].toDouble(),
                    completeness = fields[2].toDouble(),
                    contradictionCount = fields[3].toInt(),
                    ambiguityCount = fields[4].toInt(),
                    executionReadiness = fields[5].toDouble(),
                )
            } ?: SemanticInterpretationQuality.unknown()
        } else {
            SemanticInterpretationQuality.unknown()
        }

        val ambiguities = lines
            .filter { it.startsWith("ambiguity.") }
            .map { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("ambiguity."), '='))
                val payload = requireNotNull(splitUnescaped(assignment.second, '|'))
                val severity = payload.second.toDouble().also { require(it in 0.0..1.0) }
                Ambiguity(
                    code = unescape(assignment.first),
                    message = unescape(payload.first),
                    alternatives = emptyList(),
                    severity = severity,
                )
            }

        val semanticGraph = if (version == GOAL_V3 || version == GOAL_V4) {
            decodeSemanticGraph(lines, language)
        } else {
            LanguageSemanticGraph.empty(language)
        }
        val semanticActionGraph = if (version == GOAL_V4) {
            decodeSemanticActionGraph(lines)
        } else {
            SemanticActionGraph.empty()
        }

        GoalFrame(
            intent = intent,
            objective = objective,
            entities = entities,
            references = references,
            constraints = constraints,
            ambiguities = ambiguities,
            confidence = confidence,
            language = language,
            semanticGraph = semanticGraph,
            semanticActionGraph = semanticActionGraph,
            semanticEntitiesV2 = semanticEntitiesV2,
            quantityTemporal = quantityTemporal,
            domainSemanticGraph = domainSemanticGraph,
            interpretationQuality = interpretationQuality,
        )
    }.getOrNull()

    private fun decodeSemanticActionGraph(lines: List<String>): SemanticActionGraph {
        val fingerprint = unescape(required(lines, "action.fingerprint"))
            .also { require(it.isNotBlank()) }

        fun decodeEvidence(
            prefix: String,
            nodeIndex: Int,
            fallback: SemanticEvidence,
        ): List<SemanticEvidence> {
            val marker = "$prefix.$nodeIndex."
            val parsed = lines
                .filter { it.startsWith(marker) }
                .map { line ->
                    val assignment = requireNotNull(splitUnescaped(line.removePrefix(marker), '='))
                    val evidenceIndex = assignment.first.toInt().also { require(it >= 0) }
                    val fields = splitAllUnescaped(assignment.second, '|')
                    require(fields.size == 5) { "Malformed semantic evidence" }
                    val spanStart = fields[3].toInt()
                    val spanEnd = fields[4].toInt()
                    val span = if (spanStart < 0 && spanEnd < 0) {
                        null
                    } else {
                        TextSpan(spanStart, spanEnd)
                    }
                    evidenceIndex to SemanticEvidence(
                        source = unescape(fields[0]),
                        detail = unescape(fields[1]),
                        strength = fields[2].toDouble().also { require(it in 0.0..1.0) },
                        span = span,
                    )
                }
            require(parsed.map { it.first }.distinct().size == parsed.size) {
                "Persisted goal must not duplicate semantic evidence"
            }
            return parsed.sortedBy { it.first }.map { it.second }.ifEmpty { listOf(fallback) }
        }

        val parsedRoles = lines
            .filter { it.startsWith("action.role.") }
            .map { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("action.role."), '='))
                val key = assignment.first.split('.')
                require(key.size == 2) { "Malformed action role key" }
                val nodeIndex = key[0].toInt().also { require(it >= 0) }
                val role = SemanticRole.valueOf(key[1])
                val fields = splitAllUnescaped(assignment.second, '|')
                require(fields.size == 5 || fields.size == 7 || fields.size == 13) {
                    "Malformed action role"
                }
                val refId = fields.getOrNull(5)?.let(::unescape).orEmpty()
                val refRevision = fields.getOrNull(6)?.toLong()?.also { require(it >= 0L) } ?: 0L
                require((refId.isBlank() && refRevision == 0L) || (refId.isNotBlank() && refRevision > 0L)) {
                    "Persisted Photon reference must contain both id and positive revision"
                }
                val referencePhoton = if (refId.isNotBlank()) {
                    PhotonRevisionRef(PhotonId(refId), refRevision)
                } else {
                    null
                }
                val quantity = if (fields.size == 13 && unescape(fields[7]).isNotBlank()) {
                    SemanticQuantity(
                        value = unescape(fields[7]),
                        unit = unescape(fields[8]).ifBlank { null },
                        comparator = unescape(fields[9]).ifBlank { null },
                        tokenStart = fields[10].toInt().also { require(it >= 0) },
                        tokenEndExclusive = fields[11].toInt(),
                        confidence = fields[12].toDouble().also { require(it in 0.0..1.0) },
                    )
                } else {
                    null
                }
                (nodeIndex to role) to SemanticValue(
                    rawText = unescape(fields[0]),
                    normalized = unescape(fields[1]),
                    entityType = unescape(fields[2]).takeIf { it.isNotBlank() }?.let(EntityType::valueOf),
                    quantity = quantity,
                    referencePhoton = referencePhoton,
                    resolved = fields[3].toBooleanStrict(),
                    confidence = fields[4].toDouble().also { require(it in 0.0..1.0) },
                )
            }
        require(parsedRoles.map { it.first }.distinct().size == parsedRoles.size) {
            "Persisted goal must not duplicate action roles"
        }
        val roleLines = parsedRoles.toMap()

        val nodes = lines
            .filter { it.startsWith("action.node.") }
            .map { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("action.node."), '='))
                val index = assignment.first.toInt().also { require(it >= 0) }
                val fields = splitAllUnescaped(assignment.second, '|')
                require(fields.size == 16) { "Malformed semantic action node" }

                val nodeId = SemanticNodeId(unescape(fields[0]))
                val type = SemanticActionNodeType.valueOf(fields[1])
                val clauseId = fields[2].toInt().also { require(it >= 0) }
                val predicate = PredicateConcept.valueOf(fields[3])
                val speechType = SpeechActType.valueOf(fields[4])
                val speechConfidence = fields[5].toDouble().also { require(it in 0.0..1.0) }
                val speechSpan = TextSpan(fields[6].toInt(), fields[7].toInt())
                val frameConfidence = fields[8].toDouble().also { require(it in 0.0..1.0) }
                val scopeTypes = enumSet(fields[9]) { ScopeType.valueOf(it) }
                val requiredRoles = enumSet(fields[10]) { SemanticRole.valueOf(it) }
                val unresolvedRoles = enumSet(fields[11]) { SemanticRole.valueOf(it) }
                val unresolvedReference = fields[12].toBooleanStrict()
                val unresolvedCondition = fields[13].toBooleanStrict()
                val externalSideEffect = fields[14].toBooleanStrict()
                val executionReadiness = fields[15].toDouble().also { require(it in 0.0..1.0) }
                val roles = roleLines
                    .filterKeys { it.first == index }
                    .mapKeys { it.key.second }

                val speechFallback = SemanticEvidence(
                    source = "persisted-goal-v4",
                    detail = "persisted speech act",
                    strength = speechConfidence,
                    span = speechSpan,
                )
                val frameFallback = SemanticEvidence(
                    source = "persisted-goal-v4",
                    detail = "persisted predicate frame",
                    strength = frameConfidence,
                    span = speechSpan,
                )
                val speechAct = SpeechAct(
                    type = speechType,
                    confidence = speechConfidence,
                    evidence = decodeEvidence(
                        prefix = "action.speech.evidence",
                        nodeIndex = index,
                        fallback = speechFallback,
                    ),
                    span = speechSpan,
                )
                val frame = PredicateFrame(
                    nodeId = nodeId,
                    clauseId = clauseId,
                    predicate = predicate,
                    roles = roles,
                    scopeTypes = scopeTypes,
                    speechAct = speechAct,
                    confidence = frameConfidence,
                    evidence = decodeEvidence(
                        prefix = "action.frame.evidence",
                        nodeIndex = index,
                        fallback = frameFallback,
                    ),
                )
                SemanticActionNode(
                    id = nodeId,
                    type = type,
                    frame = frame,
                    requiredRoles = requiredRoles,
                    unresolvedRoles = unresolvedRoles,
                    unresolvedReference = unresolvedReference,
                    unresolvedCondition = unresolvedCondition,
                    externalSideEffect = externalSideEffect,
                    executionReadiness = executionReadiness,
                )
            }

        val edges = lines
            .filter { it.startsWith("action.edge.") }
            .map { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("action.edge."), '='))
                assignment.first.toInt().also { require(it >= 0) }
                val fields = splitAllUnescaped(assignment.second, '|')
                require(fields.size == 4) { "Malformed semantic action edge" }
                SemanticActionEdge(
                    from = SemanticNodeId(unescape(fields[0])),
                    to = SemanticNodeId(unescape(fields[1])),
                    type = SemanticActionEdgeType.valueOf(fields[2]),
                    confidence = fields[3].toDouble().also { require(it in 0.0..1.0) },
                )
            }

        val scopes = lines
            .filter { it.startsWith("action.scope.") }
            .map { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("action.scope."), '='))
                assignment.first.toInt().also { require(it >= 0) }
                val fields = splitAllUnescaped(assignment.second, '|')
                require(fields.size == 6) { "Malformed semantic action scope" }
                val targets = unescape(fields[1])
                    .split(',')
                    .filter { it.isNotBlank() }
                    .mapTo(linkedSetOf()) { SemanticNodeId(it) }
                SemanticScope(
                    type = ScopeType.valueOf(fields[0]),
                    targetNodeIds = targets,
                    span = TextSpan(fields[2].toInt(), fields[3].toInt()),
                    cue = unescape(fields[4]),
                    confidence = fields[5].toDouble().also { require(it in 0.0..1.0) },
                )
            }

        return SemanticActionGraph(
            nodes = nodes,
            edges = edges,
            scopes = scopes,
            fingerprint = fingerprint,
        )
    }

    private fun <T> enumSet(
        encoded: String,
        parser: (String) -> T,
    ): Set<T> = encoded
        .split(',')
        .filter { it.isNotBlank() }
        .mapTo(linkedSetOf(), parser)

    private fun decodeSemanticGraph(
        lines: List<String>,
        language: LanguageCode,
    ): LanguageSemanticGraph {
        val fingerprint = unescape(required(lines, "semantic.fingerprint")).also { require(it.isNotBlank()) }
        val rawClauses = lines
            .filter { it.startsWith("semantic.clause.") }
            .map { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("semantic.clause."), '='))
                val id = assignment.first.toInt().also { require(it >= 0) }
                val fields = splitAllUnescaped(assignment.second, '|')
                require(fields.size == 3) { "Malformed semantic clause" }
                PersistedClause(
                    id = id,
                    polarity = SemanticPolarity.valueOf(unescape(fields[0])),
                    modality = SemanticModality.valueOf(unescape(fields[1])),
                    normalized = unescape(fields[2]).also { require(it.isNotBlank()) },
                )
            }
            .sortedBy { it.id }
        require(rawClauses.isNotEmpty()) { "Structured goal requires semantic clauses" }
        require(rawClauses.map { it.id }.distinct().size == rawClauses.size) {
            "Duplicate semantic clause id"
        }

        var tokenCursor = 0
        val baseClauses = rawClauses.map { persisted ->
            val tokenCount = persisted.normalized
                .split(Regex("\\s+"))
                .count { it.isNotBlank() }
                .coerceAtLeast(1)
            val start = tokenCursor
            tokenCursor += tokenCount
            SemanticClause(
                id = persisted.id,
                text = persisted.normalized,
                normalized = persisted.normalized,
                tokenStart = start,
                tokenEndExclusive = tokenCursor,
                polarity = persisted.polarity,
                modality = persisted.modality,
                entities = emptyList(),
                quantities = emptyList(),
                confidence = 1.0,
            )
        }
        val clausesById = baseClauses.associateBy { it.id }

        val quantitiesByClause = lines
            .filter { it.startsWith("semantic.quantity.") }
            .map { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("semantic.quantity."), '='))
                val keyParts = assignment.first.split('.')
                require(keyParts.size == 2)
                val clauseId = keyParts[0].toInt()
                keyParts[1].toInt().also { require(it >= 0) }
                val clause = requireNotNull(clausesById[clauseId]) { "Quantity references unknown clause" }
                val fields = splitAllUnescaped(assignment.second, '|')
                require(fields.size == 3) { "Malformed semantic quantity" }
                val comparator = unescape(fields[0]).ifBlank { null }
                val value = unescape(fields[1]).also { require(it.isNotBlank()) }
                val unit = unescape(fields[2]).ifBlank { null }
                val localTokens = clause.normalized.split(Regex("\\s+"))
                val localIndex = localTokens.indexOfFirst { normalizeForMatch(it) == normalizeForMatch(value) }
                    .takeIf { it >= 0 }
                    ?: 0
                val tokenStart = clause.tokenStart + localIndex
                val tokenEnd = tokenStart + if (unit == null) 1 else 2
                clauseId to SemanticQuantity(
                    value = value,
                    unit = unit,
                    comparator = comparator,
                    tokenStart = tokenStart,
                    tokenEndExclusive = tokenEnd,
                    confidence = if (unit == null) 0.92 else 0.97,
                )
            }
            .groupBy({ it.first }, { it.second })

        val clauses = baseClauses.map { clause ->
            clause.copy(quantities = quantitiesByClause[clause.id].orEmpty())
        }

        val links = lines
            .filter { it.startsWith("semantic.link.") }
            .map { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("semantic.link."), '='))
                assignment.first.toInt().also { require(it >= 0) }
                val fields = splitAllUnescaped(assignment.second, '|')
                require(fields.size == 4) { "Malformed semantic link" }
                val from = unescape(fields[0]).toInt()
                val to = unescape(fields[1]).toInt()
                require(from in clausesById && to in clausesById)
                val type = SemanticLinkType.valueOf(unescape(fields[2]))
                val cue = unescape(fields[3]).also { require(it.isNotBlank()) }
                SemanticLink(
                    fromClauseId = from,
                    toClauseId = to,
                    type = type,
                    cue = cue,
                    confidence = if (type == SemanticLinkType.CONDITION) 0.94 else 0.92,
                )
            }
        require(links.distinctBy { Triple(it.fromClauseId, it.toClauseId, it.type) }.size == links.size) {
            "Duplicate semantic links"
        }

        return LanguageSemanticGraph(
            language = language,
            clauses = clauses,
            links = links,
            fingerprint = fingerprint,
        )
    }

    private fun optional(lines: List<String>, key: String): String? {
        val matches = lines.filter { it.startsWith("$key=") }
        require(matches.size <= 1) { "Persisted goal must not duplicate $key field" }
        return matches.singleOrNull()?.substringAfter('=')
    }

    private fun required(lines: List<String>, key: String): String {
        val matches = lines.filter { it.startsWith("$key=") }
        require(matches.size == 1) { "Persisted goal must contain exactly one $key field" }
        return matches.single().substringAfter('=')
    }

    private fun splitUnescaped(value: String, delimiter: Char): Pair<String, String>? {
        var escaped = false
        value.forEachIndexed { index, char ->
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == delimiter) {
                return value.substring(0, index) to value.substring(index + 1)
            }
        }
        return null
    }

    private fun splitAllUnescaped(value: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        var escaped = false
        var start = 0
        value.forEachIndexed { index, char ->
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == delimiter) {
                result += value.substring(start, index)
                start = index + 1
            }
        }
        result += value.substring(start)
        return result
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\' || index == value.lastIndex) {
                append(char)
                index += 1
                continue
            }
            val next = value[index + 1]
            append(if (next == 'n') '\n' else next)
            index += 2
        }
    }

    private fun normalizeForMatch(value: String): String = value
        .lowercase()
        .replace("ß", "ss")
        .trim()

    private data class PersistedClause(
        val id: Int,
        val polarity: SemanticPolarity,
        val modality: SemanticModality,
        val normalized: String,
    )

    private const val GOAL_V2 = "goal/v2"
    private const val GOAL_V3 = "goal/v3"
    private const val GOAL_V4 = "goal/v4"
}
