package app.lifeos.core.runtime.boot

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import java.util.Locale

enum class BootContextResolutionState {
    ABSENT,
    RESOLVED,
    UNRESOLVED,
    BLOCKED,
}

data class BootContextSelection(
    val kind: BootContextKind,
    val state: BootContextResolutionState,
    val selected: BootContextProjection? = null,
    val candidates: List<BootContextProjection> = emptyList(),
    val reason: String? = null,
) {
    init {
        require(candidates == candidates.sortedWith(bootContextCandidateComparator)) {
            "Boot context candidates must be canonical"
        }
        require(candidates.all { it.kind == kind }) { "Boot context candidates must share selection kind" }
        require(selected == null || selected in candidates) { "Selected context must be one of the candidates" }
        when (state) {
            BootContextResolutionState.ABSENT -> require(selected == null && candidates.isEmpty())
            BootContextResolutionState.RESOLVED -> require(selected != null && candidates.size == 1)
            BootContextResolutionState.UNRESOLVED,
            BootContextResolutionState.BLOCKED -> require(selected == null)
        }
    }
}

data class RehydratedBootContext(
    val generationId: BootGenerationId,
    val conversation: BootContextSelection,
    val project: BootContextSelection,
    val goal: BootContextSelection,
    val relevantPhotons: List<Photon>,
    val relationEdges: Int,
    val integrityBlocking: Boolean,
) {
    init {
        require(conversation.kind == BootContextKind.CONVERSATION)
        require(project.kind == BootContextKind.PROJECT)
        require(goal.kind == BootContextKind.GOAL)
        require(relevantPhotons.map { it.id }.distinct().size == relevantPhotons.size) {
            "Rehydrated context must not contain duplicate Photon ids"
        }
        require(relevantPhotons == relevantPhotons.sortedWith(relevantPhotonComparator)) {
            "Relevant Photons must be ordered newest-first deterministically"
        }
        require(relationEdges >= 0)
    }

    val unresolvedKinds: Set<BootContextKind>
        get() = listOf(conversation, project, goal)
            .filter { it.state == BootContextResolutionState.UNRESOLVED || it.state == BootContextResolutionState.BLOCKED }
            .mapTo(linkedSetOf()) { it.kind }

    val resolvedSelections: List<BootContextProjection>
        get() = listOfNotNull(conversation.selected, project.selected, goal.selected)
}

/**
 * Rehydrates conversational/project/goal continuity only from D01 durable truth plus D02 integrity
 * evidence. It never consults ViewModel memory and never invents a winner when durable state is
 * ambiguous or partially unreadable.
 */
class BootContextRehydrator(
    private val maxRelevantPhotons: Int = DEFAULT_MAX_RELEVANT_PHOTONS,
    private val relationDepth: Int = DEFAULT_RELATION_DEPTH,
) {
    init {
        require(maxRelevantPhotons > 0) { "Relevant Photon limit must be positive" }
        require(relationDepth >= 0) { "Relation depth must not be negative" }
    }

    fun rehydrate(
        snapshot: DurableBootSnapshot,
        integrity: BootIntegrityReport,
    ): RehydratedBootContext {
        require(integrity.generationId == snapshot.generationId) {
            "Boot integrity report belongs to another durable generation"
        }

        val photonSourceBlocked = integrity.findings.any { finding ->
            finding.severity >= BootIntegritySeverity.ERROR &&
                finding.area == BootIntegrityArea.SOURCE &&
                finding.message.startsWith("PHOTON ")
        }
        val conversation = select(BootContextKind.CONVERSATION, snapshot, integrity, photonSourceBlocked)
        val project = select(BootContextKind.PROJECT, snapshot, integrity, photonSourceBlocked)
        val goal = select(BootContextKind.GOAL, snapshot, integrity, photonSourceBlocked)
        val selections = listOf(conversation, project, goal)
        val relevant = collectRelevantPhotons(snapshot.photons, selections)
        val relevantIds = relevant.mapTo(hashSetOf()) { it.id }
        val relationEdges = relevant.sumOf { photon ->
            photon.relations.count { it.target in relevantIds } +
                photon.provenance.parentIds.count { it in relevantIds }
        }

        return RehydratedBootContext(
            generationId = snapshot.generationId,
            conversation = conversation,
            project = project,
            goal = goal,
            relevantPhotons = relevant,
            relationEdges = relationEdges,
            integrityBlocking = integrity.blockingFindings.isNotEmpty(),
        )
    }

    private fun select(
        kind: BootContextKind,
        snapshot: DurableBootSnapshot,
        integrity: BootIntegrityReport,
        photonSourceBlocked: Boolean,
    ): BootContextSelection {
        val candidates = snapshot.contexts
            .asSequence()
            .filter { it.kind == kind && it.explicitlyActive }
            .sortedWith(bootContextCandidateComparator)
            .toList()

        if (photonSourceBlocked) {
            return BootContextSelection(
                kind = kind,
                state = BootContextResolutionState.BLOCKED,
                candidates = candidates,
                reason = "photon-source-integrity-blocked",
            )
        }
        if (candidates.isEmpty()) {
            return BootContextSelection(kind = kind, state = BootContextResolutionState.ABSENT)
        }

        val invalidCandidate = candidates.firstOrNull { candidate ->
            val entityId = "${candidate.kind.name}:${candidate.contextId}"
            integrity.findings.any { finding ->
                finding.severity >= BootIntegritySeverity.ERROR &&
                    finding.area == BootIntegrityArea.CONTEXT &&
                    finding.entityId == entityId
            }
        }
        if (invalidCandidate != null) {
            return BootContextSelection(
                kind = kind,
                state = BootContextResolutionState.BLOCKED,
                candidates = candidates,
                reason = "context-source-integrity-blocked:${invalidCandidate.contextId}",
            )
        }

        return if (candidates.size == 1) {
            BootContextSelection(
                kind = kind,
                state = BootContextResolutionState.RESOLVED,
                selected = candidates.single(),
                candidates = candidates,
            )
        } else {
            BootContextSelection(
                kind = kind,
                state = BootContextResolutionState.UNRESOLVED,
                candidates = candidates,
                reason = "multiple-active-${kind.name.lowercase(Locale.ROOT)}-contexts",
            )
        }
    }

    private fun collectRelevantPhotons(
        photons: List<Photon>,
        selections: List<BootContextSelection>,
    ): List<Photon> {
        val uniqueById = photons.groupBy { it.id }.mapNotNull { (_, records) ->
            records.singleOrNull()
        }.associateBy { it.id }
        if (uniqueById.isEmpty()) return emptyList()

        val selectedContexts = selections.mapNotNull { it.selected }
        if (selectedContexts.isEmpty()) return emptyList()
        val contextTags = selectedContexts.mapTo(linkedSetOf()) { contextTag(it.kind, it.contextId) }
        val seedIds = linkedSetOf<PhotonId>()
        selectedContexts.forEach { seedIds += it.sourcePhotonId }
        uniqueById.values
            .filter { photon -> photon.tags.any { it in contextTags } }
            .sortedWith(relevantPhotonComparator)
            .forEach { seedIds += it.id }

        val reverseEdges = mutableMapOf<PhotonId, MutableSet<PhotonId>>()
        uniqueById.values.forEach { photon ->
            photon.relations.forEach { relation ->
                reverseEdges.getOrPut(relation.target) { linkedSetOf() } += photon.id
            }
            photon.provenance.parentIds.forEach { parentId ->
                reverseEdges.getOrPut(parentId) { linkedSetOf() } += photon.id
            }
        }

        val selected = linkedSetOf<PhotonId>()
        var frontier = seedIds.filter { it in uniqueById }.toSet()
        repeat(relationDepth + 1) { depth ->
            if (frontier.isEmpty() || selected.size >= maxRelevantPhotons) return@repeat
            val ordered = frontier
                .mapNotNull(uniqueById::get)
                .sortedWith(relevantPhotonComparator)
            ordered.forEach { photon ->
                if (selected.size < maxRelevantPhotons) selected += photon.id
            }
            if (depth == relationDepth) return@repeat

            val next = linkedSetOf<PhotonId>()
            frontier.forEach { id ->
                val photon = uniqueById[id] ?: return@forEach
                photon.relations.mapTo(next) { it.target }
                next += photon.provenance.parentIds
                next += reverseEdges[id].orEmpty()
            }
            frontier = next.filter { it !in selected && it in uniqueById }.toSet()
        }

        return selected.mapNotNull(uniqueById::get)
            .sortedWith(relevantPhotonComparator)
            .take(maxRelevantPhotons)
    }

    private fun contextTag(kind: BootContextKind, id: String): String =
        "context:${kind.name.lowercase(Locale.ROOT)}:$id"

    private companion object {
        const val DEFAULT_MAX_RELEVANT_PHOTONS = 64
        const val DEFAULT_RELATION_DEPTH = 2
    }
}

sealed interface BootContinuationResolution {
    data object NotContinuation : BootContinuationResolution

    data class Resolved(
        val context: BootContextProjection,
        val relevantPhotonIds: List<PhotonId>,
    ) : BootContinuationResolution

    data class Unresolved(
        val kind: BootContextKind?,
        val reason: String,
        val candidateContextIds: List<String> = emptyList(),
    ) : BootContinuationResolution
}

/** Deterministic bridge for terse first-post-restart continuation utterances such as "weiter". */
class BootContinuationResolver {
    fun resolve(input: String, context: RehydratedBootContext): BootContinuationResolution {
        if (!isContinuation(input)) return BootContinuationResolution.NotContinuation

        val ordered = listOf(context.conversation, context.project, context.goal)
        ordered.forEach { selection ->
            when (selection.state) {
                BootContextResolutionState.RESOLVED -> return BootContinuationResolution.Resolved(
                    context = requireNotNull(selection.selected),
                    relevantPhotonIds = context.relevantPhotons.map { it.id },
                )
                BootContextResolutionState.UNRESOLVED,
                BootContextResolutionState.BLOCKED -> return BootContinuationResolution.Unresolved(
                    kind = selection.kind,
                    reason = selection.reason ?: selection.state.name.lowercase(Locale.ROOT),
                    candidateContextIds = selection.candidates.map { it.contextId }.distinct().sorted(),
                )
                BootContextResolutionState.ABSENT -> Unit
            }
        }

        return BootContinuationResolution.Unresolved(
            kind = null,
            reason = "no-active-durable-context",
        )
    }

    private fun isContinuation(input: String): Boolean {
        val normalized = input
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[.!?,;:]+$"), "")
            .replace(Regex("\\s+"), " ")
        return normalized in continuationMarkers
    }

    private companion object {
        val continuationMarkers = setOf(
            "weiter",
            "weitermachen",
            "weiter machen",
            "mach weiter",
            "bitte weiter",
            "fortsetzen",
            "weiterführen",
            "continue",
            "go on",
            "keep going",
        )
    }
}

private val bootContextCandidateComparator = compareBy<BootContextProjection>(
    { it.contextId },
    { it.sourceCreatedAt },
    { it.sourcePhotonId.value },
    { it.sourcePhotonRevision },
)

private val relevantPhotonComparator = compareByDescending<Photon> { it.provenance.createdAt }
    .thenByDescending { it.revision }
    .thenBy { it.id.value }
