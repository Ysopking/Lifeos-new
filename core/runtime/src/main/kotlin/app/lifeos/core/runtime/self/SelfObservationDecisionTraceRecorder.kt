package app.lifeos.core.runtime.self

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.trace.DecisionTrace
import app.lifeos.core.runtime.trace.DecisionTraceId
import app.lifeos.core.runtime.trace.DecisionTraceLedger
import app.lifeos.core.runtime.trace.DecisionTraceLink
import app.lifeos.core.runtime.trace.DecisionTraceLinkType
import app.lifeos.core.runtime.trace.DecisionTraceNode
import app.lifeos.core.runtime.trace.DecisionTraceNodeType
import app.lifeos.core.runtime.world.SelfStateWorldFormulaAssessment
import app.lifeos.core.runtime.world.WorldFormulaExecutionState
import app.lifeos.core.runtime.world.WorldFormulaStatus
import java.time.Instant

class SelfObservationDecisionTraceRecorder(
    private val ledger: DecisionTraceLedger,
) {
    suspend fun record(
        snapshot: LifeOsSelfStateSnapshot,
        assessment: SelfStateWorldFormulaAssessment,
        recordedAt: Instant = snapshot.capturedAt,
    ): DecisionTrace {
        require(snapshot.authorityFingerprint == assessment.authorityFingerprint)
        val traceId = DecisionTraceId.create(
            "lifeos-self-observation",
            snapshot.authorityFingerprint,
        )
        val authorityRevision = maxOf(
            snapshot.world.worldHeadRevision ?: 0L,
            snapshot.world.worldEquationRevision ?: 0L,
        )
        val authority = DecisionTraceNode.create(
            type = DecisionTraceNodeType.OBSERVED_FACT,
            sourceType = "lifeos-self-authority",
            sourceId = snapshot.authorityFingerprint,
            sourceRevision = authorityRevision,
            reasonCodes = listOf("AUTHORITATIVE_SELF_STATE"),
            recordedAt = recordedAt,
        )
        val resource = if (snapshot.resource.hardwareFingerprint != null) {
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.RESOURCE_CONSTRAINT,
                sourceType = "lifeos-self-resource-state",
                sourceId = resourceEvidenceFingerprint(snapshot),
                sourceRevision = authorityRevision,
                reasonCodes = listOf("READ_ONLY_RESOURCE_EVIDENCE"),
                recordedAt = recordedAt,
            )
        } else {
            null
        }
        val unresolved = if (
            assessment.execution.state != WorldFormulaExecutionState.COMPLETED ||
            assessment.execution.status != WorldFormulaStatus.CONVERGED
        ) {
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.UNRESOLVED_UNCERTAINTY,
                sourceType = "lifeos-self-world-formula",
                sourceId = assessment.sourceFingerprint,
                sourceRevision = authorityRevision,
                reasonCodes = buildList {
                    add("STATE_${assessment.execution.state.name}")
                    add("STATUS_${assessment.execution.status.name}")
                },
                recordedAt = recordedAt,
            )
        } else {
            null
        }
        val outcome = DecisionTraceNode.create(
            type = DecisionTraceNodeType.EXECUTION_OUTCOME,
            sourceType = "lifeos-self-world-band",
            sourceId = assessment.analysisId,
            sourceRevision = authorityRevision,
            reasonCodes = buildList {
                add("BAND_${assessment.band.name}")
                add("WORLD_${assessment.execution.status.name}")
                addAll(assessment.reasonCodes)
            },
            recordedAt = recordedAt,
        )

        val nodes = buildList {
            add(authority)
            resource?.let(::add)
            unresolved?.let(::add)
            add(outcome)
        }
        val links = buildList {
            add(DecisionTraceLink(authority.id, outcome.id, DecisionTraceLinkType.SUPPORTS))
            resource?.let { add(DecisionTraceLink(it.id, outcome.id, DecisionTraceLinkType.CONSTRAINS)) }
            unresolved?.let { add(DecisionTraceLink(it.id, outcome.id, DecisionTraceLinkType.CONSTRAINS)) }
        }
        return ledger.append(traceId, nodes, links)
    }

    private fun resourceEvidenceFingerprint(snapshot: LifeOsSelfStateSnapshot): String =
        StableFieldIds.fingerprint(
            "lifeos-self-resource-evidence/v1",
            snapshot.resource.hardwareFingerprint.orEmpty(),
            encode(snapshot.resource.memoryHeadroom),
            encode(snapshot.resource.storageHeadroom),
            encode(snapshot.resource.thermalHeadroom),
            encode(snapshot.resource.energyAvailability),
            encode(snapshot.resource.capabilityReadiness),
        )

    private fun encode(value: Double?): String =
        value?.let(java.lang.Double::toHexString) ?: "null"
}
