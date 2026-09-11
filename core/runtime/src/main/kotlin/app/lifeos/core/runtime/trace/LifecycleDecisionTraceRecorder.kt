package app.lifeos.core.runtime.trace

import app.lifeos.core.runtime.artifact.ArtifactFinalizationResult
import app.lifeos.core.runtime.evolution.EvolutionCanaryKillSwitchEvidence
import app.lifeos.core.runtime.evolution.EvolutionCanaryOutcome
import app.lifeos.core.runtime.health.DurableSelfHealingResult
import app.lifeos.core.runtime.health.SelfHealingIncidentSnapshot
import app.lifeos.core.runtime.health.SelfHealingIncidentState
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * Read-only V15 projection for durable lifecycle evidence outside the goal execution path.
 * The owning subsystem must persist its authoritative state before invoking this recorder.
 */
class LifecycleDecisionTraceRecorder(
    private val ledger: DecisionTraceLedger,
) {
    suspend fun recordEvolutionOutcome(
        outcome: EvolutionCanaryOutcome,
        killSwitch: EvolutionCanaryKillSwitchEvidence?,
    ): DecisionTraceRecordResult = record("evolution-outcome") {
        val traceId = DecisionTraceId.create("evolution-adoption", outcome.adoptionEvidenceId)
        val adoption = DecisionTraceNode.create(
            type = DecisionTraceNodeType.OBSERVED_FACT,
            sourceType = "evolution-adoption-evidence",
            sourceId = outcome.adoptionEvidenceId,
            sourceRevision = 1L,
            recordedAt = outcome.recordedAt,
        )
        val invocation = DecisionTraceNode.create(
            type = DecisionTraceNodeType.OBSERVED_FACT,
            sourceType = "evolution-canary-invocation",
            sourceId = outcome.invocationId,
            sourceRevision = 1L,
            reasonCodes = listOf("INPUT_${outcome.inputFingerprint}"),
            recordedAt = outcome.recordedAt,
        )
        val candidate = DecisionTraceNode.create(
            type = DecisionTraceNodeType.CANDIDATE_ALTERNATIVE,
            sourceType = "generated-tool",
            sourceId = outcome.candidateToolId,
            sourceRevision = 0L,
            reasonCodes = listOf("CANDIDATE", "RECORD_${outcome.candidateRecordFingerprint}"),
            recordedAt = outcome.recordedAt,
        )
        val reservation = DecisionTraceNode.create(
            type = DecisionTraceNodeType.RESOURCE_CONSTRAINT,
            sourceType = "evolution-canary-reservation",
            sourceId = outcome.reservationId,
            sourceRevision = 1L,
            reasonCodes = listOf("RESERVED_FOR_CANARY"),
            recordedAt = outcome.recordedAt,
        )
        val outcomeNode = DecisionTraceNode.create(
            type = if (outcome.success && outcome.hardFailures.isEmpty()) {
                DecisionTraceNodeType.EXECUTION_OUTCOME
            } else {
                DecisionTraceNodeType.REJECTION
            },
            sourceType = "evolution-canary-outcome",
            sourceId = outcome.id,
            sourceRevision = 1L,
            reasonCodes = buildList {
                add(if (outcome.success) "SUCCESS" else "FAILED")
                add(if (outcome.producedExpectedOutput) "EXPECTED_OUTPUT" else "EXPECTED_OUTPUT_MISSING")
                outcome.hardFailures.sortedBy { it.name }.forEach { add("HARD_FAILURE_${it.name}") }
            },
            recordedAt = outcome.recordedAt,
        )
        val kill = killSwitch?.let { evidence ->
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.REJECTION,
                sourceType = "evolution-kill-switch",
                sourceId = evidence.id,
                sourceRevision = 1L,
                reasonCodes = buildList {
                    add(evidence.reason.name)
                    evidence.hardFailures.sortedBy { it.name }.forEach { add("HARD_FAILURE_${it.name}") }
                },
                recordedAt = evidence.trippedAt,
            )
        }
        val nodes = buildList {
            add(adoption)
            add(invocation)
            add(candidate)
            add(reservation)
            add(outcomeNode)
            kill?.let(::add)
        }
        val links = buildList {
            add(DecisionTraceLink(invocation.id, adoption.id, DecisionTraceLinkType.DERIVED_FROM))
            add(DecisionTraceLink(candidate.id, invocation.id, DecisionTraceLinkType.ALTERNATIVE_TO))
            add(DecisionTraceLink(reservation.id, invocation.id, DecisionTraceLinkType.CONSTRAINS))
            add(DecisionTraceLink(candidate.id, outcomeNode.id, DecisionTraceLinkType.PRODUCED))
            add(DecisionTraceLink(outcomeNode.id, invocation.id, DecisionTraceLinkType.DERIVED_FROM))
            kill?.let { add(DecisionTraceLink(it.id, outcomeNode.id, DecisionTraceLinkType.DERIVED_FROM)) }
        }
        ledger.append(traceId, nodes, links)
    }

    suspend fun recordSelfHealing(
        result: DurableSelfHealingResult,
    ): DecisionTraceRecordResult = record("self-healing") {
        val incident = when (result) {
            is DurableSelfHealingResult.Recovered -> result.incident
            is DurableSelfHealingResult.Exhausted -> result.incident
            is DurableSelfHealingResult.Blocked -> result.incident
        }
        val traceId = DecisionTraceId.create("self-healing-incident", incident.incidentId.value)
        val incidentNode = DecisionTraceNode.create(
            type = when (incident.state) {
                SelfHealingIncidentState.RECOVERED -> DecisionTraceNodeType.RECOVERY_OUTCOME
                SelfHealingIncidentState.EXHAUSTED,
                SelfHealingIncidentState.BLOCKED,
                SelfHealingIncidentState.QUARANTINED -> DecisionTraceNodeType.REJECTION
                SelfHealingIncidentState.OPEN,
                SelfHealingIncidentState.ACTION_IN_FLIGHT -> DecisionTraceNodeType.INFERRED_HYPOTHESIS
            },
            sourceType = "self-healing-incident",
            sourceId = incident.incidentId.value,
            sourceRevision = incident.ledgerRevision,
            reasonCodes = selfHealingReasons(result, incident),
            recordedAt = incident.lastRecordedAt,
        )
        val healthNode = DecisionTraceNode.create(
            type = DecisionTraceNodeType.OBSERVED_FACT,
            sourceType = "health-node",
            sourceId = incident.nodeId.value,
            sourceRevision = incident.ledgerRevision,
            reasonCodes = listOf("PLAN_${incident.planFingerprint}"),
            recordedAt = incident.lastRecordedAt,
        )
        val actions = incident.attemptedActionIds.mapIndexed { index, actionId ->
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.OBSERVED_FACT,
                sourceType = "self-healing-action",
                sourceId = actionId,
                sourceRevision = incident.ledgerRevision,
                reasonCodes = listOf("ATTEMPT_${index + 1}"),
                recordedAt = incident.lastRecordedAt,
            )
        }
        val evidence = incident.lastEvidenceSummary?.let { summary ->
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.OBSERVED_FACT,
                sourceType = "self-healing-verification-evidence",
                sourceId = summary,
                sourceRevision = incident.ledgerRevision,
                recordedAt = incident.lastRecordedAt,
            )
        }
        val nodes = buildList {
            add(healthNode)
            addAll(actions)
            evidence?.let(::add)
            add(incidentNode)
        }
        val links = buildList {
            add(DecisionTraceLink(incidentNode.id, healthNode.id, DecisionTraceLinkType.DERIVED_FROM))
            actions.forEach { add(DecisionTraceLink(it.id, incidentNode.id, DecisionTraceLinkType.SUPPORTS)) }
            evidence?.let { add(DecisionTraceLink(it.id, incidentNode.id, DecisionTraceLinkType.SUPPORTS)) }
        }
        ledger.append(traceId, nodes, links)
    }

    suspend fun recordArtifact(
        result: ArtifactFinalizationResult,
    ): DecisionTraceRecordResult = record("collaborative-artifact") {
        val artifact = result.artifact
        val request = artifact.request
        val photon = artifact.photon
        val traceId = DecisionTraceId.create("artifact", request.id.value)
        val requestNode = DecisionTraceNode.create(
            type = DecisionTraceNodeType.OBSERVED_FACT,
            sourceType = "artifact-request",
            sourceId = request.id.value,
            sourceRevision = 1L,
            reasonCodes = listOf("KIND_${request.kind.name}", "MIME_${request.targetMimeType}"),
            recordedAt = request.requestedAt,
        )
        val contributionNodes = artifact.contributions.map { contribution ->
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.OBSERVED_FACT,
                sourceType = "artifact-contribution",
                sourceId = contribution.id,
                sourceRevision = 1L,
                reasonCodes = listOf(
                    "FIELD_${contribution.field}",
                    "MODULE_${contribution.module}",
                    "SOURCE_${contribution.source}",
                ),
                recordedAt = contribution.contributedAt,
            )
        }
        val parentIds = artifact.contributions
            .flatMap { it.provenance.parentIds }
            .distinct()
            .sortedBy { it.value }
        val parentNodes = parentIds.map { parentId ->
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.OBSERVED_FACT,
                sourceType = "artifact-parent-photon",
                sourceId = parentId.value,
                sourceRevision = 0L,
                reasonCodes = listOf("REVISION_NOT_RECORDED_IN_ARTIFACT_PROVENANCE"),
                recordedAt = artifact.finalizedAt,
            )
        }
        val artifactNode = DecisionTraceNode.create(
            type = DecisionTraceNodeType.EXECUTION_OUTCOME,
            sourceType = "artifact-photon",
            sourceId = photon.id.value,
            sourceRevision = photon.revision,
            reasonCodes = listOf("CONVERGED", "MIME_${photon.mimeType}"),
            recordedAt = photon.provenance.createdAt,
        )
        val reentryNode = DecisionTraceNode.create(
            type = DecisionTraceNodeType.RECOVERY_OUTCOME,
            sourceType = "artifact-cognition-reentry",
            sourceId = result.reentry.durableTaskId ?: "artifact-reentry:${photon.id.value}",
            sourceRevision = photon.revision,
            reasonCodes = listOf(if (result.reentry.accepted) "ACCEPTED" else "DEDUPLICATED_OR_REJECTED"),
            recordedAt = artifact.finalizedAt,
        )
        val contributionsById = artifact.contributions.associateBy { it.id }
        val contributionNodeById = contributionNodes.associateBy { it.sourceId }
        val parentNodeById = parentNodes.associateBy { it.sourceId }
        val links = buildList {
            add(DecisionTraceLink(artifactNode.id, requestNode.id, DecisionTraceLinkType.DERIVED_FROM))
            contributionNodes.forEach { add(DecisionTraceLink(it.id, artifactNode.id, DecisionTraceLinkType.SUPPORTS)) }
            contributionsById.forEach { (id, contribution) ->
                val contributionNode = contributionNodeById.getValue(id)
                contribution.provenance.parentIds.forEach { parentId ->
                    add(
                        DecisionTraceLink(
                            contributionNode.id,
                            parentNodeById.getValue(parentId.value).id,
                            DecisionTraceLinkType.DERIVED_FROM,
                        )
                    )
                }
            }
            add(DecisionTraceLink(artifactNode.id, reentryNode.id, DecisionTraceLinkType.PRODUCED))
        }
        ledger.append(
            traceId,
            nodes = listOf(requestNode) + contributionNodes + parentNodes + artifactNode + reentryNode,
            links = links,
        )
    }

    private fun selfHealingReasons(
        result: DurableSelfHealingResult,
        incident: SelfHealingIncidentSnapshot,
    ): List<String> = buildList {
        add(incident.state.name)
        incident.lastDetail?.takeIf { it.isNotBlank() }?.let(::add)
        when (result) {
            is DurableSelfHealingResult.Recovered -> {
                if (result.recoveredAfterRestart) add("RECOVERED_AFTER_RESTART")
                if (result.replayedTerminal) add("REPLAYED_TERMINAL")
            }
            is DurableSelfHealingResult.Exhausted -> if (result.quarantined) add("QUARANTINED")
            is DurableSelfHealingResult.Blocked -> add(result.reason)
        }
    }

    private suspend fun record(
        stage: String,
        operation: suspend () -> DecisionTrace,
    ): DecisionTraceRecordResult = try {
        DecisionTraceRecordResult.Recorded(operation())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        DecisionTraceRecordResult.Unavailable(
            stage = stage,
            reason = error.message ?: error::class.simpleName.orEmpty().ifBlank { "trace-unavailable" },
        )
    }
}

/** Process-local observer registry; it carries no lifecycle authority. */
object LifecycleDecisionTraceRuntimeRegistry {
    @Volatile
    private var recorder: LifecycleDecisionTraceRecorder? = null

    fun install(value: LifecycleDecisionTraceRecorder) {
        recorder = value
    }

    fun currentOrNull(): LifecycleDecisionTraceRecorder? = recorder

    internal fun clearForTests() {
        recorder = null
    }
}
