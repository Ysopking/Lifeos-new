package app.lifeos.core.runtime.trace

import app.lifeos.core.runtime.buildstudio.HotSwapActivationSnapshot
import app.lifeos.core.runtime.buildstudio.HotSwapActivationState
import app.lifeos.core.runtime.buildstudio.HotSwapActivationTraceRecorder

/** V15 projection for authoritative Hot-Swap ledger outcomes. No second trace store is introduced. */
class HotSwapDecisionTraceRecorder(
    private val ledger: DecisionTraceLedger,
) : HotSwapActivationTraceRecorder {
    override suspend fun record(snapshot: HotSwapActivationSnapshot) {
        require(snapshot.terminal) { "Hot-swap decision trace only records terminal durable outcomes" }
        val traceId = DecisionTraceId.create("buildstudio-hot-swap", snapshot.id.value)
        val candidate = DecisionTraceNode.create(
            type = DecisionTraceNodeType.CANDIDATE_ALTERNATIVE,
            sourceType = "buildstudio-candidate-artifact",
            sourceId = snapshot.candidateArtifactId,
            sourceRevision = snapshot.revision,
            reasonCodes = listOf("VERIFIED_RUNTIME_CANDIDATE_${snapshot.verifiedCandidateId}"),
            recordedAt = snapshot.lastRecordedAt,
        )
        val policy = snapshot.policyDecisionId?.let { decisionId ->
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.POLICY_CONSTRAINT,
                sourceType = "owner-policy-decision",
                sourceId = decisionId,
                sourceRevision = snapshot.revision,
                reasonCodes = listOf("PROVIDER_ACTIVATION"),
                recordedAt = snapshot.lastRecordedAt,
            )
        }
        val health = snapshot.healthEvidenceId?.let { evidenceId ->
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.OBSERVED_FACT,
                sourceType = "hot-swap-health-evidence",
                sourceId = evidenceId,
                sourceRevision = snapshot.revision,
                reasonCodes = listOf("HEALTH_GATE_VERIFIED"),
                recordedAt = snapshot.lastRecordedAt,
            )
        }
        val outcome = DecisionTraceNode.create(
            type = when (snapshot.state) {
                HotSwapActivationState.ACTIVATED -> DecisionTraceNodeType.EXECUTION_OUTCOME
                HotSwapActivationState.ROLLED_BACK -> DecisionTraceNodeType.RECOVERY_OUTCOME
                HotSwapActivationState.BLOCKED -> DecisionTraceNodeType.REJECTION
                else -> error("Non-terminal hot-swap state cannot be traced")
            },
            sourceType = "hot-swap-activation",
            sourceId = snapshot.id.value,
            sourceRevision = snapshot.revision,
            reasonCodes = buildList {
                add(snapshot.state.name)
                snapshot.lastDetail?.let { add("DETAIL_${it.take(120)}") }
            },
            recordedAt = snapshot.lastRecordedAt,
        )
        val nodes = buildList {
            add(candidate)
            policy?.let(::add)
            health?.let(::add)
            add(outcome)
        }
        val links = buildList {
            add(DecisionTraceLink(outcome.id, candidate.id, DecisionTraceLinkType.DERIVED_FROM))
            policy?.let { add(DecisionTraceLink(it.id, outcome.id, DecisionTraceLinkType.CONSTRAINS)) }
            health?.let { add(DecisionTraceLink(it.id, outcome.id, DecisionTraceLinkType.SUPPORTS)) }
        }
        ledger.append(traceId, nodes, links)
    }
}
