package app.lifeos.core.runtime.goal

import app.lifeos.core.language.Ambiguity
import app.lifeos.core.language.EntityType
import app.lifeos.core.language.GoalConstraint
import app.lifeos.core.language.GoalFrame
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
import app.lifeos.core.language.SemanticLink
import app.lifeos.core.language.SemanticLinkType
import app.lifeos.core.language.SemanticModality
import app.lifeos.core.language.SemanticPolarity
import app.lifeos.core.language.SemanticQuantity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant

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
                val payload = requireNotNull(splitUnescaped(assignment.second, '|'))
                val rawTarget = unescape(payload.first)
                val target = rawTarget.takeUnless { it == "UNRESOLVED" }?.let(::PhotonId)
                val score = payload.second.toDouble().also { require(it in 0.0..1.0) }
                ResolvedReference(
                    expression = ReferenceExpression(
                        kind = kind,
                        rawText = target?.value ?: kind.name.lowercase(),
                        preferredKinds = emptySet(),
                        confidence = score,
                    ),
                    targetPhotonId = target,
                    score = score,
                )
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
        )
    }.getOrNull()

    private fun decodeSemanticActionGraph(lines: List<String>): SemanticActionGraph {
        val fingerprint = unescape(required(lines, "action.fingerprint"))
            .also { require(it.isNotBlank()) }

        val roleLines = lines
            .filter { it.startsWith("action.role.") }
            .associate { line ->
                val assignment = requireNotNull(splitUnescaped(line.removePrefix("action.role."), '='))
                val key = assignment.first.split('.')
                require(key.size == 2) { "Malformed action role key" }
                val nodeIndex = key[0].toInt().also { require(it >= 0) }
                val role = SemanticRole.valueOf(key[1])
                val fields = splitAllUnescaped(assignment.second, '|')
                require(fields.size == 5) { "Malformed action role" }
                (nodeIndex to role) to SemanticValue(
                    rawText = unescape(fields[0]),
                    normalized = unescape(fields[1]),
                    entityType = unescape(fields[2]).takeIf { it.isNotBlank() }?.let(EntityType::valueOf),
                    resolved = fields[3].toBooleanStrict(),
                    confidence = fields[4].toDouble().also { require(it in 0.0..1.0) },
                )
            }

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
                val scopeTypes = enumSet(fields[9], ScopeType::valueOf)
                val requiredRoles = enumSet(fields[10], SemanticRole::valueOf)
                val unresolvedRoles = enumSet(fields[11], SemanticRole::valueOf)
                val unresolvedReference = fields[12].toBooleanStrict()
                val unresolvedCondition = fields[13].toBooleanStrict()
                val externalSideEffect = fields[14].toBooleanStrict()
                val executionReadiness = fields[15].toDouble().also { require(it in 0.0..1.0) }
                val roles = roleLines
                    .filterKeys { it.first == index }
                    .mapKeys { it.key.second }

                val speechAct = SpeechAct(
                    type = speechType,
                    confidence = speechConfidence,
                    evidence = listOf(
                        SemanticEvidence(
                            source = "persisted-goal-v4",
                            detail = "persisted speech act",
                            strength = speechConfidence,
                            span = speechSpan,
                        )
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
                    evidence = listOf(
                        SemanticEvidence(
                            source = "persisted-goal-v4",
                            detail = "persisted predicate frame",
                            strength = frameConfidence,
                            span = speechSpan,
                        )
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
            .sortedBy { it.id.value }

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
                    .mapTo(linkedSetOf(), ::SemanticNodeId)
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
