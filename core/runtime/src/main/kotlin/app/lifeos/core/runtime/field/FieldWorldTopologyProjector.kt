package app.lifeos.core.runtime.field

import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.FieldConvergenceRequest
import app.lifeos.core.field.FieldConvergenceResult
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeKind
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet

enum class FieldWorldSignalLinkKind {
    SOURCE_EVIDENCE,
    EVIDENCE_SUPPORTS_HYPOTHESIS,
    EVIDENCE_CONTRADICTS_HYPOTHESIS,
    EVIDENCE_REFINES_HYPOTHESIS,
    EVIDENCE_DERIVED_HYPOTHESIS,
    EVIDENCE_DUPLICATES_HYPOTHESIS,
    HYPOTHESIS_CONFLICT,
    GOAL_CONTEXT,
    DOMAIN_CONTEXT,
}

data class FieldWorldSignalLink(
    val source: WorldTargetRef,
    val target: WorldTargetRef,
    val kind: FieldWorldSignalLinkKind,
    val strength: Double,
    val provenanceFingerprint: String,
    val explanation: String,
) {
    init {
        require(source != target) { "Field/world signal link must connect distinct targets" }
        require(strength.isFinite() && strength in 0.0..1.0) {
            "Field/world signal link strength must be in 0..1"
        }
        require(provenanceFingerprint.isNotBlank())
        require(explanation.isNotBlank())
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "field-world-signal-link/v1",
        source.fingerprint(),
        target.fingerprint(),
        kind.name,
        java.lang.Double.toHexString(strength),
        provenanceFingerprint,
        explanation,
    )
}

/** Pure topology projection. It describes allowed influence paths but owns no numerical physics. */
class FieldWorldTopologyProjector {
    fun project(
        request: FieldConvergenceRequest,
        result: FieldConvergenceResult,
        workingSet: ThoughtGraphWorkingSet? = null,
    ): List<FieldWorldSignalLink> {
        val fieldFingerprint = result.snapshot.contentFingerprint()
        val traceFingerprint = result.trace.fingerprint()
        val hypothesisIds = result.hypotheses.mapTo(mutableSetOf()) { it.id.value }
        val links = buildList {
            request.evidence.sortedBy { it.id.value }.forEach { evidence ->
                add(
                    link(
                        source = WorldTargetRef(WorldNodeKind.PHOTON, evidence.sourcePhotonId.value),
                        target = WorldTargetRef(WorldNodeKind.EVIDENCE, evidence.id.value),
                        kind = FieldWorldSignalLinkKind.SOURCE_EVIDENCE,
                        strength = 1.0,
                        provenance = StableFieldIds.fingerprint(
                            "field-world/source-evidence/v1",
                            evidence.sourceFingerprint,
                            fieldFingerprint,
                        ),
                        explanation = "immutable source Photon provides evidence",
                    )
                )
            }
            result.hypotheses.sortedBy { it.id.value }.forEach { hypothesis ->
                hypothesis.evidenceLinks
                    .sortedWith(compareBy({ it.evidenceId.value }, { it.relation.name }))
                    .forEach { evidenceLink ->
                        val evidence = request.evidence.first { it.id == evidenceLink.evidenceId }
                        add(
                            link(
                                source = WorldTargetRef(WorldNodeKind.EVIDENCE, evidence.id.value),
                                target = WorldTargetRef(WorldNodeKind.HYPOTHESIS, hypothesis.id.value),
                                kind = evidenceLink.relation.toWorldKind(),
                                strength = evidenceLink.weight,
                                provenance = StableFieldIds.fingerprint(
                                    "field-world/evidence-hypothesis/v1",
                                    evidence.sourceFingerprint,
                                    hypothesis.id.value,
                                    evidenceLink.relation.name,
                                    traceFingerprint,
                                ),
                                explanation = "${evidenceLink.relation.name.lowercase()} field evidence relation",
                            )
                        )
                    }
                hypothesis.conflicts
                    .filter { it.competingHypothesisId.value in hypothesisIds }
                    .sortedBy { it.competingHypothesisId.value }
                    .forEach { conflict ->
                        add(
                            link(
                                source = WorldTargetRef(WorldNodeKind.HYPOTHESIS, hypothesis.id.value),
                                target = WorldTargetRef(WorldNodeKind.HYPOTHESIS, conflict.competingHypothesisId.value),
                                kind = FieldWorldSignalLinkKind.HYPOTHESIS_CONFLICT,
                                strength = conflict.strength,
                                provenance = StableFieldIds.fingerprint(
                                    "field-world/hypothesis-conflict/v1",
                                    hypothesis.id.value,
                                    conflict.competingHypothesisId.value,
                                    traceFingerprint,
                                ),
                                explanation = conflict.reason,
                            )
                        )
                    }
                add(
                    link(
                        source = WorldTargetRef(WorldNodeKind.DOMAIN_FIELD, request.domainId.value),
                        target = WorldTargetRef(WorldNodeKind.HYPOTHESIS, hypothesis.id.value),
                        kind = FieldWorldSignalLinkKind.DOMAIN_CONTEXT,
                        strength = 1.0,
                        provenance = StableFieldIds.fingerprint(
                            "field-world/domain-context/v1",
                            request.domainId.value,
                            hypothesis.id.value,
                            fieldFingerprint,
                        ),
                        explanation = "domain field context influences hypothesis",
                    )
                )
            }
            if (workingSet != null) {
                val nodes = workingSet.nodes.associateBy { it.id }
                workingSet.edges
                    .filter { it.kind == ThoughtGraphEdgeKind.TARGETS_GOAL }
                    .forEach { edge ->
                        val sourceNode = nodes[edge.sourceNodeId] ?: return@forEach
                        val targetNode = nodes[edge.targetNodeId] ?: return@forEach
                        if (sourceNode.kind != ThoughtGraphNodeKind.HYPOTHESIS) return@forEach
                        if (sourceNode.provenance.sourceId !in hypothesisIds) return@forEach
                        val goalSourceId = targetNode.provenance.sourceId
                        add(
                            link(
                                source = WorldTargetRef(WorldNodeKind.GOAL, goalSourceId),
                                target = WorldTargetRef(WorldNodeKind.HYPOTHESIS, sourceNode.provenance.sourceId),
                                kind = FieldWorldSignalLinkKind.GOAL_CONTEXT,
                                strength = edge.confidence,
                                provenance = StableFieldIds.fingerprint(
                                    "field-world/goal-context/v1",
                                    edge.fingerprint,
                                    workingSet.fingerprint,
                                ),
                                explanation = edge.explanation,
                            )
                        )
                    }
            }
        }
        return links
            .distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }
    }

    private fun link(
        source: WorldTargetRef,
        target: WorldTargetRef,
        kind: FieldWorldSignalLinkKind,
        strength: Double,
        provenance: String,
        explanation: String,
    ) = FieldWorldSignalLink(source, target, kind, strength, provenance, explanation)

    private fun EvidenceRelationType.toWorldKind(): FieldWorldSignalLinkKind = when (this) {
        EvidenceRelationType.SUPPORTS -> FieldWorldSignalLinkKind.EVIDENCE_SUPPORTS_HYPOTHESIS
        EvidenceRelationType.CONTRADICTS -> FieldWorldSignalLinkKind.EVIDENCE_CONTRADICTS_HYPOTHESIS
        EvidenceRelationType.REFINES -> FieldWorldSignalLinkKind.EVIDENCE_REFINES_HYPOTHESIS
        EvidenceRelationType.DERIVED_FROM -> FieldWorldSignalLinkKind.EVIDENCE_DERIVED_HYPOTHESIS
        EvidenceRelationType.DUPLICATES -> FieldWorldSignalLinkKind.EVIDENCE_DUPLICATES_HYPOTHESIS
    }
}
