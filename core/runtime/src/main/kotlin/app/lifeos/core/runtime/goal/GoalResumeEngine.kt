package app.lifeos.core.runtime.goal

import app.lifeos.core.language.Ambiguity
import app.lifeos.core.language.EntityType
import app.lifeos.core.language.GoalConstraint
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.language.ReferenceExpression
import app.lifeos.core.language.ReferenceKind
import app.lifeos.core.language.ResolvedReference
import app.lifeos.core.language.SemanticEntity
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
 * stored goal, decodes the immutable goal/v2 representation and emits a new provenance-bound goal
 * photon. Capability routing and concrete action execution remain outside this class.
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

/** Strict decoder for the persisted goal/v2 contract emitted by GoalPhotonFactory. */
private object PersistedGoalFrameDecoder {
    fun decode(photon: Photon): GoalFrame? = runCatching {
        val lines = photon.content.lines()
        require(lines.firstOrNull() == "goal/v2")
        val intent = required(lines, "intent").let(IntentType::valueOf)
        val language = required(lines, "language").let(LanguageCode::valueOf)
        val confidence = required(lines, "confidence").toDouble().also { require(it in 0.0..1.0) }
        val objective = unescape(required(lines, "objective")).also { require(it.isNotBlank()) }

        val constraints = lines.mapNotNull { line ->
            if (!line.startsWith("constraint.")) return@mapNotNull null
            val assignment = splitUnescaped(line.removePrefix("constraint."), '=') ?: return@mapNotNull null
            val payload = splitUnescaped(assignment.second, '|') ?: return@mapNotNull null
            GoalConstraint(
                key = unescape(assignment.first),
                value = unescape(payload.first),
                confidence = payload.second.toDouble(),
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

        val references = lines.mapNotNull { line ->
            if (!line.startsWith("reference.")) return@mapNotNull null
            val assignment = splitUnescaped(line.removePrefix("reference."), '=') ?: return@mapNotNull null
            val kind = ReferenceKind.valueOf(unescape(assignment.first))
            val payload = splitUnescaped(assignment.second, '|') ?: return@mapNotNull null
            val rawTarget = unescape(payload.first)
            val target = rawTarget.takeUnless { it == "UNRESOLVED" }?.let(::PhotonId)
            val score = payload.second.toDouble()
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

        val ambiguities = lines.mapNotNull { line ->
            if (!line.startsWith("ambiguity.")) return@mapNotNull null
            val assignment = splitUnescaped(line.removePrefix("ambiguity."), '=') ?: return@mapNotNull null
            val payload = splitUnescaped(assignment.second, '|') ?: return@mapNotNull null
            Ambiguity(
                code = unescape(assignment.first),
                message = unescape(payload.first),
                alternatives = emptyList(),
                severity = payload.second.toDouble(),
            )
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
        )
    }.getOrNull()

    private fun required(lines: List<String>, key: String): String =
        lines.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?: error("missing $key")

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
}
