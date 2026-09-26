package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds

/**
 * B531 derives explicit projection-transition evidence from an immutable shadow history.
 *
 * It does not invent hidden variables or future laws. Every sample is bound to two adjacent
 * realization revisions and the two source shadow fingerprints from which it was derived.
 */
class MetaRealizationProjectionHistoryAnalyzer(
    private val closureEvaluator: ProjectionClosureEvaluator =
        ProjectionClosureEvaluator(),
) {
    fun analyze(
        history: List<MetaRealizationShadowSnapshot>,
        componentKind: RealizationComponentKind,
        projectionId: String = "meta-shadow:${componentKind.name.lowercase()}",
    ): ProjectionClosureResult? {
        require(projectionId.isNotBlank())
        if (history.size < 2) return null

        val canonical = history.sortedBy { it.realization.revision }
        require(canonical.map { it.realization.revisionId }.distinct().size == canonical.size) {
            "Meta-realization shadow history contains duplicate revisions"
        }
        val profileFingerprint = canonical.first().cycle.realizationProfileFingerprint
        require(
            canonical.all {
                it.cycle.realizationProfileFingerprint == profileFingerprint
            }
        ) {
            "Projection history must use one frozen realization profile"
        }

        canonical.zipWithNext().forEach { (source, successor) ->
            require(
                successor.realization.predecessorRevisionId ==
                    source.realization.revisionId
            ) {
                "Projection history must be an exact predecessor-bound revision chain"
            }
        }

        val samples = canonical.zipWithNext().map { (source, successor) ->
            val sourceComponent = source.realization.component(componentKind)
            val successorComponent = successor.realization.component(componentKind)
            ProjectionTransitionSample(
                realizationProfileFingerprint = profileFingerprint,
                projectionId = projectionId,
                sourceRevisionId = source.realization.revisionId,
                sourceProjectionFingerprint = sourceComponent.semanticFingerprint,
                successorRevisionId = successor.realization.revisionId,
                successorProjectionFingerprint = successorComponent.semanticFingerprint,
                evidenceFingerprint = StableFieldIds.fingerprint(
                    "meta-shadow-projection-transition-evidence/v1",
                    source.fingerprint,
                    successor.fingerprint,
                    componentKind.name,
                ),
            )
        }

        return closureEvaluator.evaluate(samples)
    }

    private fun CanonicalRealizationState.component(
        kind: RealizationComponentKind,
    ): RealizationComponentRef =
        components.singleOrNull { it.kind == kind }
            ?: error("Realization revision $revisionId lacks required projection component $kind")
}
