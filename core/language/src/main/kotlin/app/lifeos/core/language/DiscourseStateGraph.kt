package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds

data class DiscourseFocusNode(
    val ref: PhotonRevisionRef,
    val kind: String,
    val semanticTypes: Set<String>,
    val conceptIds: Set<String>,
    val active: Boolean,
    val score: Double,
) {
    init {
        require(kind.isNotBlank())
        require(score.isFinite() && score in 0.0..1.0)
    }
}

data class DiscourseStateGraph(
    val activeGoalId: PhotonId?,
    val focus: List<DiscourseFocusNode>,
    val fingerprint: String,
) {
    init {
        require(fingerprint.isNotBlank())
        require(focus.map { it.ref }.distinct().size == focus.size)
    }

    fun score(ref: PhotonRevisionRef): Double =
        focus.firstOrNull { it.ref == ref }?.score ?: 0.0

    companion object {
        fun empty(): DiscourseStateGraph = DiscourseStateGraph(
            activeGoalId = null,
            focus = emptyList(),
            fingerprint = StableCognitiveIds.fingerprint("discourse-state/v1", "empty"),
        )
    }
}

class DiscourseStateProjector(
    private val maxFocusNodes: Int = 24,
) {
    init { require(maxFocusNodes in 4..128) }

    fun project(context: LanguageContext): DiscourseStateGraph {
        val ranked = context.items
            .asSequence()
            .filter { it.revisionRef != null }
            .map { item ->
                val score = (
                    item.confidence * 0.34 +
                        (if (item.active) 0.32 else 0.0) +
                        (if (item.photonId == context.activeGoalId || item.goalId == context.activeGoalId) 0.18 else 0.0) +
                        (if ("result" in item.semanticTypes) 0.08 else 0.0) +
                        (if ("image" in item.semanticTypes) 0.04 else 0.0) +
                        (if (item.matterId != null) 0.04 else 0.0)
                    ).coerceIn(0.0, 1.0)
                DiscourseFocusNode(
                    ref = requireNotNull(item.revisionRef),
                    kind = item.kind,
                    semanticTypes = item.semanticTypes,
                    conceptIds = item.conceptIds,
                    active = item.active,
                    score = score,
                )
            }
            .sortedWith(
                compareByDescending<DiscourseFocusNode> { it.score }
                    .thenByDescending { it.ref.revision }
                    .thenBy { it.ref.photonId.value }
            )
            .take(maxFocusNodes)
            .toList()

        val fingerprint = StableCognitiveIds.fingerprint(
            "discourse-state/v1",
            context.activeGoalId?.value.orEmpty(),
            *ranked.map { node ->
                listOf(
                    node.ref.stableKey,
                    node.kind,
                    node.semanticTypes.sorted().joinToString(","),
                    node.conceptIds.sorted().joinToString(","),
                    node.active.toString(),
                    java.lang.Double.toHexString(node.score),
                ).joinToString(":")
            }.toTypedArray(),
        )
        return DiscourseStateGraph(context.activeGoalId, ranked, fingerprint)
    }
}
