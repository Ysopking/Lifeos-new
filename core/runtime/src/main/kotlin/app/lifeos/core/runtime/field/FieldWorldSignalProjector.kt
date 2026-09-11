package app.lifeos.core.runtime.field

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.FieldConvergenceRequest
import app.lifeos.core.field.FieldConvergenceResult
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.HypothesisState
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalCalibrationProfile
import app.lifeos.core.field.world.WorldSignalCalibrator
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.thought.ThoughtGraphAttentionReason
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet
import app.lifeos.core.runtime.world.WorldFormulaInputSnapshot

data class FieldWorldSignalProjectionConfig(
    val directGoalRelevance: Double = 1.0,
    val goalNeighborRelevance: Double = 0.65,
) {
    init {
        require(directGoalRelevance in 0.0..1.0)
        require(goalNeighborRelevance in 0.0..1.0)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "field-world-signal-projection-config/v1",
        java.lang.Double.toHexString(directGoalRelevance),
        java.lang.Double.toHexString(goalNeighborRelevance),
    )
}

data class FieldWorldSignalProjection(
    val inputs: List<WorldFormulaInputSnapshot>,
    val fieldSnapshotFingerprint: String,
    val workingSetFingerprint: String?,
    val calibrationFingerprint: String,
    val configFingerprint: String,
) {
    init {
        require(inputs.isNotEmpty()) { "Field/world projection requires at least one input" }
        require(inputs.map { it.target }.distinct().size == inputs.size) {
            "Field/world projection targets must be unique"
        }
        require(inputs == inputs.sortedWith(compareBy({ it.target.kind.name }, { it.target.key }))) {
            "Field/world projection inputs must be deterministically ordered"
        }
        require(fieldSnapshotFingerprint.isNotBlank())
        require(calibrationFingerprint.isNotBlank())
        require(configFingerprint.isNotBlank())
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "field-world-signal-projection/v1",
        fieldSnapshotFingerprint,
        workingSetFingerprint.orEmpty(),
        calibrationFingerprint,
        configFingerprint,
        *inputs.map { it.fingerprint() }.toTypedArray(),
    )
}

/**
 * Pure informational V4 projection from immutable Field/ThoughtGraph outputs into sparse world
 * vectors. It has no write authority over cognition, goals, tasks, hypotheses or capabilities.
 */
class FieldWorldSignalProjector(
    calibration: WorldSignalCalibrationProfile = WorldSignalCalibrationProfile.V1,
    private val config: FieldWorldSignalProjectionConfig = FieldWorldSignalProjectionConfig(),
) {
    private val calibrator = WorldSignalCalibrator(calibration)

    fun project(
        photon: Photon,
        request: FieldConvergenceRequest,
        result: FieldConvergenceResult,
        workingSet: ThoughtGraphWorkingSet? = null,
    ): FieldWorldSignalProjection {
        require(request.domainId == result.state.domainId) { "Field/world projection domain mismatch" }
        val snapshotFingerprint = result.snapshot.contentFingerprint()
        val traceFingerprint = result.trace.fingerprint()
        val calibrationFingerprint = calibrator.profile.fingerprint()
        val configFingerprint = config.fingerprint()
        val evidenceById = request.evidence.associateBy { it.id }
        val evidenceBreakdowns = result.trace.seed.nodeEvidenceForces
            .groupBy { it.breakdown.evidenceId }
        val attentionBySource = workingSet?.nodes
            ?.mapIndexed { index, node -> node.provenance.sourceId to workingSet.entries[index] }
            ?.toMap()
            .orEmpty()

        val inputs = buildList {
            add(
                photonInput(
                    photon = photon,
                    evidence = request.evidence.filter {
                        it.sourcePhotonId == photon.id && it.sourceRevision == photon.revision
                    },
                    evidenceBreakdowns = evidenceBreakdowns,
                    snapshotFingerprint = snapshotFingerprint,
                    traceFingerprint = traceFingerprint,
                    calibrationFingerprint = calibrationFingerprint,
                )
            )
            request.evidence.sortedBy { it.id.value }.forEach { evidence ->
                add(
                    evidenceInput(
                        evidence = evidence,
                        breakdowns = evidenceBreakdowns[evidence.id].orEmpty().map { it.breakdown },
                        snapshotFingerprint = snapshotFingerprint,
                        traceFingerprint = traceFingerprint,
                        calibrationFingerprint = calibrationFingerprint,
                    )
                )
            }
            result.hypotheses.sortedBy { it.id.value }.forEach { hypothesis ->
                add(
                    hypothesisInput(
                        hypothesis = hypothesis,
                        result = result,
                        evidenceById = evidenceById,
                        goalRelevance = goalRelevance(attentionBySource[hypothesis.id.value]?.reasons.orEmpty()),
                        snapshotFingerprint = snapshotFingerprint,
                        traceFingerprint = traceFingerprint,
                        calibrationFingerprint = calibrationFingerprint,
                    )
                )
            }
            add(
                domainInput(
                    request = request,
                    result = result,
                    evidenceById = evidenceById,
                    snapshotFingerprint = snapshotFingerprint,
                    traceFingerprint = traceFingerprint,
                    calibrationFingerprint = calibrationFingerprint,
                )
            )
            if (workingSet != null) {
                workingSet.nodes.forEachIndexed { index, node ->
                    if (node.kind != ThoughtGraphNodeKind.GOAL) return@forEachIndexed
                    val entry = workingSet.entries[index]
                    add(
                        goalInput(
                            sourceId = node.provenance.sourceId,
                            confidence = node.confidence,
                            authority = node.authority,
                            goalRelevance = goalRelevance(entry.reasons),
                            nodeFingerprint = node.fingerprint,
                            workingSetFingerprint = workingSet.fingerprint,
                            calibrationFingerprint = calibrationFingerprint,
                        )
                    )
                }
            }
        }.sortedWith(compareBy({ it.target.kind.name }, { it.target.key }))

        require(inputs.map { it.target }.distinct().size == inputs.size) {
            "Field/world projection produced duplicate targets"
        }
        return FieldWorldSignalProjection(
            inputs = inputs,
            fieldSnapshotFingerprint = snapshotFingerprint,
            workingSetFingerprint = workingSet?.fingerprint,
            calibrationFingerprint = calibrationFingerprint,
            configFingerprint = configFingerprint,
        )
    }

    private fun photonInput(
        photon: Photon,
        evidence: List<FieldEvidence>,
        evidenceBreakdowns: Map<app.lifeos.core.field.EvidenceId, List<app.lifeos.core.field.NodeEvidenceForceTrace>>,
        snapshotFingerprint: String,
        traceFingerprint: String,
        calibrationFingerprint: String,
    ): WorldFormulaInputSnapshot {
        val matchingBreakdowns = evidence.flatMap { item -> evidenceBreakdowns[item.id].orEmpty().map { it.breakdown } }
        val reliability = evidence.averageOr(photon.confidence) { it.reliability.score }
        val authority = evidence.averageOr(0.0) { it.authority.defaultWeight }
        val temporal = matchingBreakdowns.averageOr(1.0) { it.temporalValidity }
        val semantic = matchingBreakdowns.averageOr(0.0) { it.contextCoherence }
        val provenance = setOf(
            runtimePhotonFingerprint(photon),
            snapshotFingerprint,
            traceFingerprint,
            calibrationFingerprint,
        )
        return input(
            target = WorldTargetRef(WorldNodeKind.PHOTON, photon.id.value),
            values = listOf(
                value(WorldSignalDimension.EVIDENCE_SUPPORT, photon.confidence, photon.confidence, provenance),
                value(WorldSignalDimension.RELIABILITY, reliability, photon.confidence, provenance),
                value(WorldSignalDimension.AUTHORITY, authority, photon.confidence, provenance),
                value(WorldSignalDimension.UNCERTAINTY, 1.0 - photon.confidence, photon.confidence, provenance),
                value(WorldSignalDimension.TEMPORAL_FRESHNESS, temporal, photon.confidence, provenance),
                value(WorldSignalDimension.SEMANTIC_RELEVANCE, semantic, photon.confidence, provenance),
            ),
            source = StableFieldIds.fingerprint("world-input/photon/v1", *provenance.sorted().toTypedArray()),
        )
    }

    private fun evidenceInput(
        evidence: FieldEvidence,
        breakdowns: List<app.lifeos.core.field.EvidenceForceBreakdown>,
        snapshotFingerprint: String,
        traceFingerprint: String,
        calibrationFingerprint: String,
    ): WorldFormulaInputSnapshot {
        val support = breakdowns.averageOr(evidence.confidence) { it.composite }
        val temporal = breakdowns.averageOr(if (evidence.validity.contains(evidence.observedAt)) 1.0 else 0.0) {
            it.temporalValidity
        }
        val semantic = breakdowns.averageOr(0.0) { it.contextCoherence }
        val provenance = setOf(
            evidence.sourceFingerprint,
            snapshotFingerprint,
            traceFingerprint,
            calibrationFingerprint,
        )
        return input(
            target = WorldTargetRef(WorldNodeKind.EVIDENCE, evidence.id.value),
            values = listOf(
                value(WorldSignalDimension.EVIDENCE_SUPPORT, support, evidence.confidence, provenance),
                value(WorldSignalDimension.RELIABILITY, evidence.reliability.score, evidence.confidence, provenance),
                value(WorldSignalDimension.AUTHORITY, evidence.authority.defaultWeight, evidence.confidence, provenance),
                value(WorldSignalDimension.UNCERTAINTY, 1.0 - evidence.confidence, evidence.confidence, provenance),
                value(WorldSignalDimension.TEMPORAL_FRESHNESS, temporal, evidence.confidence, provenance),
                value(WorldSignalDimension.SEMANTIC_RELEVANCE, semantic, evidence.confidence, provenance),
                value(WorldSignalDimension.CONTEXT_RELEVANCE, semantic, evidence.confidence, provenance),
            ),
            source = StableFieldIds.fingerprint("world-input/evidence/v1", *provenance.sorted().toTypedArray()),
        )
    }

    private fun hypothesisInput(
        hypothesis: FieldHypothesis,
        result: FieldConvergenceResult,
        evidenceById: Map<app.lifeos.core.field.EvidenceId, FieldEvidence>,
        goalRelevance: Double,
        snapshotFingerprint: String,
        traceFingerprint: String,
        calibrationFingerprint: String,
    ): WorldFormulaInputSnapshot {
        val linked = hypothesis.evidenceLinks.mapNotNull { link -> evidenceById[link.evidenceId]?.let { link to it } }
        val denominator = linked.sumOf { it.first.weight }.takeIf { it > 0.0 } ?: 1.0
        val reliability = linked.sumOf { (link, evidence) -> link.weight * evidence.reliability.score } / denominator
        val authority = linked.sumOf { (link, evidence) -> link.weight * evidence.authority.defaultWeight } / denominator
        val fieldConflict = result.conflicts
            .filter { conflict -> conflict.nodeIds.any(hypothesis.nodeIds::contains) }
            .maxOfOrNull { it.severity } ?: 0.0
        val conflict = maxOf(hypothesis.conflicts.maxOfOrNull { it.strength } ?: 0.0, fieldConflict)
        val provenance = buildSet {
            add(snapshotFingerprint)
            add(traceFingerprint)
            add(calibrationFingerprint)
            linked.mapTo(this) { it.second.sourceFingerprint }
        }
        return input(
            target = WorldTargetRef(WorldNodeKind.HYPOTHESIS, hypothesis.id.value),
            values = listOf(
                value(WorldSignalDimension.EVIDENCE_SUPPORT, hypothesis.score.evidence, hypothesis.score.total, provenance),
                value(WorldSignalDimension.RELIABILITY, reliability, hypothesis.score.total, provenance),
                value(WorldSignalDimension.AUTHORITY, authority, hypothesis.score.total, provenance),
                value(WorldSignalDimension.UNCERTAINTY, hypothesisUncertainty(hypothesis), hypothesis.score.total, provenance),
                value(WorldSignalDimension.CONFLICT_PRESSURE, conflict, hypothesis.score.total, provenance),
                value(WorldSignalDimension.SEMANTIC_RELEVANCE, hypothesis.score.context, hypothesis.score.total, provenance),
                value(WorldSignalDimension.CONTEXT_RELEVANCE, hypothesis.score.context, hypothesis.score.total, provenance),
                value(WorldSignalDimension.GOAL_RELEVANCE, goalRelevance, hypothesis.score.total, provenance),
                value(WorldSignalDimension.ANALYTIC_SALIENCE, hypothesis.score.total, hypothesis.score.total, provenance),
            ),
            source = StableFieldIds.fingerprint(
                "world-input/hypothesis/v1",
                hypothesis.id.value,
                hypothesis.state.name,
                *provenance.sorted().toTypedArray(),
            ),
        )
    }

    private fun domainInput(
        request: FieldConvergenceRequest,
        result: FieldConvergenceResult,
        evidenceById: Map<app.lifeos.core.field.EvidenceId, FieldEvidence>,
        snapshotFingerprint: String,
        traceFingerprint: String,
        calibrationFingerprint: String,
    ): WorldFormulaInputSnapshot {
        val hypotheses = result.hypotheses
        val linkedEvidence = hypotheses.flatMap { it.evidenceLinks }.mapNotNull { evidenceById[it.evidenceId] }.distinctBy { it.id }
        val maxSalience = hypotheses.maxOfOrNull { it.score.total } ?: 0.0
        val maxConflict = result.conflicts.maxOfOrNull { it.severity } ?: 0.0
        val context = hypotheses.averageOr(0.0) { it.score.context }
        val reliability = linkedEvidence.averageOr(0.0) { it.reliability.score }
        val authority = linkedEvidence.averageOr(0.0) { it.authority.defaultWeight }
        val uncertainty = when (result.status) {
            ConvergenceStatus.CONVERGED -> 1.0 - maxSalience
            ConvergenceStatus.UNRESOLVED -> maxOf(1.0 - maxSalience, 0.5)
            ConvergenceStatus.MAX_ITERATIONS -> 1.0
        }
        val provenance = buildSet {
            add(snapshotFingerprint)
            add(traceFingerprint)
            add(calibrationFingerprint)
            linkedEvidence.mapTo(this) { it.sourceFingerprint }
        }
        return input(
            target = WorldTargetRef(WorldNodeKind.DOMAIN_FIELD, request.domainId.value),
            values = listOf(
                value(WorldSignalDimension.EVIDENCE_SUPPORT, maxSalience, maxSalience, provenance),
                value(WorldSignalDimension.RELIABILITY, reliability, maxSalience, provenance),
                value(WorldSignalDimension.AUTHORITY, authority, maxSalience, provenance),
                value(WorldSignalDimension.UNCERTAINTY, uncertainty, maxSalience, provenance),
                value(WorldSignalDimension.CONFLICT_PRESSURE, maxConflict, maxSalience, provenance),
                value(WorldSignalDimension.SEMANTIC_RELEVANCE, context, maxSalience, provenance),
                value(WorldSignalDimension.CONTEXT_RELEVANCE, context, maxSalience, provenance),
                value(WorldSignalDimension.ANALYTIC_SALIENCE, maxSalience, maxSalience, provenance),
            ),
            source = StableFieldIds.fingerprint("world-input/domain-field/v1", request.domainId.value, *provenance.sorted().toTypedArray()),
        )
    }

    private fun goalInput(
        sourceId: String,
        confidence: Double,
        authority: Double,
        goalRelevance: Double,
        nodeFingerprint: String,
        workingSetFingerprint: String,
        calibrationFingerprint: String,
    ): WorldFormulaInputSnapshot {
        val provenance = setOf(nodeFingerprint, workingSetFingerprint, calibrationFingerprint)
        return input(
            target = WorldTargetRef(WorldNodeKind.GOAL, sourceId),
            values = listOf(
                value(WorldSignalDimension.EVIDENCE_SUPPORT, confidence, confidence, provenance),
                value(WorldSignalDimension.AUTHORITY, authority, confidence, provenance),
                value(WorldSignalDimension.GOAL_RELEVANCE, goalRelevance, confidence, provenance),
                value(WorldSignalDimension.ANALYTIC_SALIENCE, goalRelevance, confidence, provenance),
            ),
            source = StableFieldIds.fingerprint("world-input/goal/v1", sourceId, *provenance.sorted().toTypedArray()),
        )
    }

    private fun goalRelevance(reasons: List<ThoughtGraphAttentionReason>): Double = when {
        ThoughtGraphAttentionReason.GOAL in reasons -> config.directGoalRelevance
        ThoughtGraphAttentionReason.GOAL_NEIGHBOR in reasons -> config.goalNeighborRelevance
        else -> 0.0
    }

    private fun hypothesisUncertainty(hypothesis: FieldHypothesis): Double = when (hypothesis.state) {
        HypothesisState.UNRESOLVED,
        HypothesisState.COMPETING -> maxOf(1.0 - hypothesis.score.total, 0.5)
        else -> 1.0 - hypothesis.score.total
    }

    private fun input(
        target: WorldTargetRef,
        values: List<app.lifeos.core.field.world.WorldDimensionValue>,
        source: String,
    ): WorldFormulaInputSnapshot = WorldFormulaInputSnapshot(
        target = target,
        vector = WorldFieldVector(values.sortedBy { it.dimension.name }),
        sourceSnapshotFingerprint = source,
    )

    private fun value(
        dimension: WorldSignalDimension,
        raw: Double,
        confidence: Double,
        provenance: Set<String>,
    ) = calibrator.value(dimension, raw, confidence, provenance)

    private inline fun <T> List<T>.averageOr(default: Double, selector: (T) -> Double): Double =
        if (isEmpty()) default else sumOf(selector) / size.toDouble()
}
