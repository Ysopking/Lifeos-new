package app.lifeos.core.runtime.trace

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.capability.HotSwapSnapshot
import app.lifeos.core.runtime.capability.HotSwapState
import app.lifeos.core.runtime.capability.ToolWorkshopJobSnapshot
import app.lifeos.core.runtime.capability.ToolWorkshopJobState
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionId
import app.lifeos.core.runtime.deepsearch.DeepSearchResult
import app.lifeos.core.runtime.deepsearch.DeepSearchStatus
import app.lifeos.core.runtime.policy.OwnerPolicyAssessment
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * V15 bindings for already-authoritative subsystem evidence that does not belong in the goal-plan
 * coordinator itself. This recorder never selects a provider, executes search, mutates a workshop
 * job, activates a provider, or changes hot-swap state; it only projects durable identities into the
 * shared DecisionTrace ledger after the owning subsystem has made its decision.
 */
class SubsystemDecisionTraceRecorder(
    private val ledger: DecisionTraceLedger,
) {
    suspend fun recordCapabilityRouting(
        goalPhotonId: PhotonId,
        goalPhotonRevision: Long,
        recordedAt: Instant,
        resolution: GoalCapabilityResolution,
    ): DecisionTraceRecordResult = record("capability-routing") {
        val goal = goalNode(goalPhotonId, goalPhotonRevision, recordedAt)
        val selected = resolution.selectedProviders.entries
            .sortedBy { it.key.value }
            .map { (capabilityId, provider) ->
                DecisionTraceNode.create(
                    type = DecisionTraceNodeType.SELECTION,
                    sourceType = "capability-provider-selection",
                    sourceId = "${capabilityId.value}:${provider.providerId}",
                    sourceRevision = 0L,
                    reasonCodes = listOf(
                        "CAPABILITY_${capabilityId.value}",
                        "PROVIDER_STATE_${provider.state.name}",
                        "PROVIDER_TYPE_${provider.providerType.name}",
                        "TRUST_${provider.trustLevel.name}",
                    ),
                    recordedAt = recordedAt,
                )
            }
        val gaps = resolution.gaps
            .sortedWith(
                compareBy({ it.requirement.capabilityId.value }, { it.type.name }, { it.requirement.severity.name })
            )
            .map { gap ->
                DecisionTraceNode.create(
                    type = DecisionTraceNodeType.UNRESOLVED_UNCERTAINTY,
                    sourceType = "capability-gap",
                    sourceId = buildString {
                        append(gap.requirement.capabilityId.value)
                        append(':').append(gap.type.name)
                        gap.candidateProviderIds.distinct().sorted().forEach { append(':').append(it) }
                    },
                    sourceRevision = 0L,
                    reasonCodes = listOf(
                        gap.type.name,
                        gap.requirement.severity.name,
                    ),
                    recordedAt = recordedAt,
                )
            }
        val languageBlock = if (resolution.plan.languageBlocking) {
            listOf(
                DecisionTraceNode.create(
                    type = DecisionTraceNodeType.UNRESOLVED_UNCERTAINTY,
                    sourceType = "language-goal-routing",
                    sourceId = goalPhotonId.value,
                    sourceRevision = goalPhotonRevision,
                    reasonCodes = listOf("LANGUAGE_BLOCKING"),
                    recordedAt = recordedAt,
                )
            )
        } else emptyList()
        val nodes = listOf(goal) + selected + gaps + languageBlock
        val links = buildList {
            selected.forEach { add(DecisionTraceLink(it.id, goal.id, DecisionTraceLinkType.SUPPORTS)) }
            gaps.forEach { add(DecisionTraceLink(it.id, goal.id, DecisionTraceLinkType.CONSTRAINS)) }
            languageBlock.forEach { add(DecisionTraceLink(it.id, goal.id, DecisionTraceLinkType.CONSTRAINS)) }
        }
        ledger.append(goalTraceId(goalPhotonId), nodes, links)
    }

    suspend fun recordDeepSearch(
        goalPhotonId: PhotonId,
        goalPhotonRevision: Long,
        recordedAt: Instant,
        result: DeepSearchResult,
        missionId: DeepSearchMissionId? = null,
    ): DecisionTraceRecordResult = record("deepsearch") {
        val goal = goalNode(goalPhotonId, goalPhotonRevision, recordedAt)
        val request = DecisionTraceNode.create(
            type = DecisionTraceNodeType.OBSERVED_FACT,
            sourceType = "deepsearch-request",
            sourceId = result.requestId.value,
            sourceRevision = 1L,
            reasonCodes = listOf("STATUS_${result.status.name}", "WORK_${result.workUnitsUsed}"),
            recordedAt = recordedAt,
        )
        val mission = missionId?.let {
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.OBSERVED_FACT,
                sourceType = "deepsearch-mission",
                sourceId = it.value,
                sourceRevision = 1L,
                recordedAt = recordedAt,
            )
        }
        val best = result.best?.let { branch ->
            DecisionTraceNode.create(
                type = if (result.status == DeepSearchStatus.RESOLVED) {
                    DecisionTraceNodeType.SELECTION
                } else {
                    DecisionTraceNodeType.INFERRED_HYPOTHESIS
                },
                sourceType = "deepsearch-branch",
                sourceId = branch.id.value,
                sourceRevision = 1L,
                reasonCodes = listOf("BEST", "STATUS_${result.status.name}"),
                recordedAt = recordedAt,
            )
        }
        val alternatives = result.alternatives
            .distinctBy { it.id }
            .sortedBy { it.id.value }
            .map { branch ->
                DecisionTraceNode.create(
                    type = DecisionTraceNodeType.CANDIDATE_ALTERNATIVE,
                    sourceType = "deepsearch-branch",
                    sourceId = branch.id.value,
                    sourceRevision = 1L,
                    recordedAt = recordedAt,
                )
            }
        val evidence = result.evidence
            .sortedBy { it.id.value }
            .map { item ->
                DecisionTraceNode.create(
                    type = DecisionTraceNodeType.OBSERVED_FACT,
                    sourceType = "deepsearch-evidence",
                    sourceId = item.id.value,
                    sourceRevision = 1L,
                    reasonCodes = listOf(if (item.contradiction) "CONTRADICTION" else "SUPPORTING"),
                    recordedAt = recordedAt,
                )
            }
        val blocked = result.blockedSourceIds.sorted().map { sourceId ->
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.REJECTION,
                sourceType = "deepsearch-source",
                sourceId = sourceId,
                sourceRevision = 1L,
                reasonCodes = listOf("SOURCE_BLOCKED"),
                recordedAt = recordedAt,
            )
        }
        val failed = result.failedSourceIds.sorted().map { sourceId ->
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.REJECTION,
                sourceType = "deepsearch-source",
                sourceId = sourceId,
                sourceRevision = 1L,
                reasonCodes = listOf("SOURCE_FAILED"),
                recordedAt = recordedAt,
            )
        }
        val unresolved = if (result.status == DeepSearchStatus.RESOLVED) emptyList() else listOf(
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.UNRESOLVED_UNCERTAINTY,
                sourceType = "deepsearch-result",
                sourceId = result.requestId.value,
                sourceRevision = 1L,
                reasonCodes = listOf(result.status.name),
                recordedAt = recordedAt,
            )
        )
        val nodes = buildList {
            add(goal)
            add(request)
            mission?.let(::add)
            best?.let(::add)
            addAll(alternatives)
            addAll(evidence)
            addAll(blocked)
            addAll(failed)
            addAll(unresolved)
        }
        val links = buildList {
            add(DecisionTraceLink(request.id, goal.id, DecisionTraceLinkType.DERIVED_FROM))
            mission?.let { add(DecisionTraceLink(it.id, request.id, DecisionTraceLinkType.DERIVED_FROM)) }
            best?.let { bestNode ->
                add(DecisionTraceLink(bestNode.id, request.id, DecisionTraceLinkType.SELECTED_BY))
                evidence.forEach { add(DecisionTraceLink(it.id, bestNode.id, DecisionTraceLinkType.SUPPORTS)) }
            }
            alternatives.forEach { add(DecisionTraceLink(it.id, request.id, DecisionTraceLinkType.ALTERNATIVE_TO)) }
            blocked.forEach { add(DecisionTraceLink(it.id, request.id, DecisionTraceLinkType.REJECTED_BY)) }
            failed.forEach { add(DecisionTraceLink(it.id, request.id, DecisionTraceLinkType.REJECTED_BY)) }
            unresolved.forEach { add(DecisionTraceLink(it.id, request.id, DecisionTraceLinkType.CONSTRAINS)) }
        }
        ledger.append(goalTraceId(goalPhotonId), nodes, links)
    }

    suspend fun recordToolWorkshop(
        goalPhotonId: PhotonId,
        goalPhotonRevision: Long,
        recordedAt: Instant,
        snapshot: ToolWorkshopJobSnapshot,
        explicitReason: String? = null,
    ): DecisionTraceRecordResult = record("tool-workshop") {
        val goal = goalNode(goalPhotonId, goalPhotonRevision, recordedAt)
        val stateNode = DecisionTraceNode.create(
            type = when (snapshot.state) {
                ToolWorkshopJobState.TRIAL_READY -> DecisionTraceNodeType.EXECUTION_OUTCOME
                ToolWorkshopJobState.REJECTED,
                ToolWorkshopJobState.INTERRUPTED -> DecisionTraceNodeType.REJECTION
                else -> DecisionTraceNodeType.INFERRED_HYPOTHESIS
            },
            sourceType = "tool-workshop-job",
            sourceId = snapshot.definition.id.value,
            sourceRevision = snapshot.ledgerRevision,
            reasonCodes = buildList {
                add(snapshot.state.name)
                explicitReason?.takeIf { it.isNotBlank() }?.let { add(it) }
                snapshot.lastDetail?.takeIf { it.isNotBlank() }?.let { add(it) }
            },
            recordedAt = recordedAt,
        )
        val capability = DecisionTraceNode.create(
            type = DecisionTraceNodeType.UNRESOLVED_UNCERTAINTY,
            sourceType = "capability-gap",
            sourceId = snapshot.definition.capabilityId.value,
            sourceRevision = snapshot.definition.sourceRevision,
            reasonCodes = listOf(snapshot.definition.gapType.name, snapshot.definition.severity.name),
            recordedAt = snapshot.definition.createdAt,
        )
        val tool = DecisionTraceNode.create(
            type = DecisionTraceNodeType.CANDIDATE_ALTERNATIVE,
            sourceType = "generated-tool",
            sourceId = snapshot.toolId,
            sourceRevision = snapshot.ledgerRevision,
            reasonCodes = listOf(snapshot.state.name),
            recordedAt = recordedAt,
        )
        val artifact = snapshot.stageFingerprint?.let { fingerprint ->
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.OBSERVED_FACT,
                sourceType = "tool-workshop-stage-artifact",
                sourceId = fingerprint,
                sourceRevision = snapshot.ledgerRevision,
                reasonCodes = listOf(snapshot.state.name),
                recordedAt = recordedAt,
            )
        }
        val nodes = buildList {
            add(goal)
            add(stateNode)
            add(capability)
            add(tool)
            artifact?.let(::add)
        }
        val links = buildList {
            add(DecisionTraceLink(stateNode.id, goal.id, DecisionTraceLinkType.DERIVED_FROM))
            add(DecisionTraceLink(capability.id, stateNode.id, DecisionTraceLinkType.CONSTRAINS))
            add(DecisionTraceLink(tool.id, stateNode.id, DecisionTraceLinkType.ALTERNATIVE_TO))
            artifact?.let { add(DecisionTraceLink(it.id, stateNode.id, DecisionTraceLinkType.SUPPORTS)) }
        }
        ledger.append(goalTraceId(goalPhotonId), nodes, links)
    }

    suspend fun recordHotSwap(
        snapshot: HotSwapSnapshot,
        recordedAt: Instant,
    ): DecisionTraceRecordResult = record("hot-swap") {
        val rootId = DecisionTraceId.create("hot-swap-transaction", snapshot.transactionId.value)
        val transaction = DecisionTraceNode.create(
            type = when (snapshot.state) {
                HotSwapState.COMMITTED -> DecisionTraceNodeType.EXECUTION_OUTCOME
                HotSwapState.REVERTED,
                HotSwapState.ROLLED_BACK -> DecisionTraceNodeType.RECOVERY_OUTCOME
                HotSwapState.BLOCKED -> DecisionTraceNodeType.REJECTION
                HotSwapState.PREPARED,
                HotSwapState.CANDIDATE_PROMOTED,
                HotSwapState.REVERT_PREPARED -> DecisionTraceNodeType.INFERRED_HYPOTHESIS
            },
            sourceType = "hot-swap-transaction",
            sourceId = snapshot.transactionId.value,
            sourceRevision = snapshot.ledgerRevision,
            reasonCodes = buildList {
                add(snapshot.state.name)
                snapshot.lastDetail?.takeIf { it.isNotBlank() }?.let { add(it) }
            },
            recordedAt = recordedAt,
        )
        val previous = DecisionTraceNode.create(
            type = DecisionTraceNodeType.CANDIDATE_ALTERNATIVE,
            sourceType = "generated-provider",
            sourceId = snapshot.previousToolId,
            sourceRevision = 0L,
            reasonCodes = listOf("PREVIOUS", "CAPABILITY_${snapshot.capabilityId.value}"),
            recordedAt = recordedAt,
        )
        val candidate = DecisionTraceNode.create(
            type = DecisionTraceNodeType.CANDIDATE_ALTERNATIVE,
            sourceType = "generated-provider",
            sourceId = snapshot.candidateToolId,
            sourceRevision = 0L,
            reasonCodes = listOf("CANDIDATE", "CAPABILITY_${snapshot.capabilityId.value}"),
            recordedAt = recordedAt,
        )
        val policy = snapshot.ownerPolicyRevision?.let { revision ->
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.POLICY_CONSTRAINT,
                sourceType = "owner-policy-revision",
                sourceId = snapshot.transactionId.value,
                sourceRevision = revision,
                reasonCodes = listOf("PROVIDER_ACTIVATION"),
                recordedAt = recordedAt,
            )
        }
        val world = snapshot.worldSnapshotId?.let { worldId ->
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.RESOURCE_CONSTRAINT,
                sourceType = "world-formula-snapshot",
                sourceId = worldId,
                sourceRevision = 1L,
                reasonCodes = listOf("HOT_SWAP"),
                recordedAt = recordedAt,
            )
        }
        val nodes = buildList {
            add(transaction)
            add(previous)
            add(candidate)
            policy?.let(::add)
            world?.let(::add)
        }
        val links = buildList {
            add(DecisionTraceLink(candidate.id, transaction.id, DecisionTraceLinkType.ALTERNATIVE_TO))
            add(DecisionTraceLink(previous.id, transaction.id, DecisionTraceLinkType.ALTERNATIVE_TO))
            if (snapshot.state == HotSwapState.COMMITTED) {
                add(DecisionTraceLink(candidate.id, transaction.id, DecisionTraceLinkType.SELECTED_BY))
            }
            if (snapshot.state == HotSwapState.REVERTED || snapshot.state == HotSwapState.ROLLED_BACK) {
                add(DecisionTraceLink(previous.id, transaction.id, DecisionTraceLinkType.RECOVERED_BY))
            }
            policy?.let { add(DecisionTraceLink(it.id, transaction.id, DecisionTraceLinkType.CONSTRAINS)) }
            world?.let { add(DecisionTraceLink(it.id, transaction.id, DecisionTraceLinkType.CONSTRAINS)) }
        }
        ledger.append(rootId, nodes, links)
    }

    suspend fun recordGeneratedProviderRestore(
        record: GeneratedToolRecord,
        assessment: OwnerPolicyAssessment,
        restored: Boolean,
        recordedAt: Instant,
    ): DecisionTraceRecordResult = record("generated-provider-restore") {
        val traceId = DecisionTraceId.create("generated-provider", record.manifest.toolId)
        val provider = DecisionTraceNode.create(
            type = if (restored) DecisionTraceNodeType.RECOVERY_OUTCOME else DecisionTraceNodeType.REJECTION,
            sourceType = "generated-provider-restore",
            sourceId = record.manifest.toolId,
            sourceRevision = 1L,
            reasonCodes = listOf(if (restored) "RESTORED" else "POLICY_BLOCKED"),
            recordedAt = recordedAt,
        )
        val capability = DecisionTraceNode.create(
            type = DecisionTraceNodeType.OBSERVED_FACT,
            sourceType = "capability",
            sourceId = record.manifest.sourceCapability.value,
            sourceRevision = 0L,
            recordedAt = recordedAt,
        )
        val policy = DecisionTraceNode.create(
            type = DecisionTraceNodeType.POLICY_CONSTRAINT,
            sourceType = "owner-policy-decision",
            sourceId = assessment.decisionId.value,
            sourceRevision = assessment.policyRevision,
            reasonCodes = if (assessment.allowed) listOf("ALLOWED") else assessment.reasonCodes.map { it.name },
            recordedAt = recordedAt,
        )
        ledger.append(
            traceId,
            nodes = listOf(provider, capability, policy),
            links = listOf(
                DecisionTraceLink(provider.id, capability.id, DecisionTraceLinkType.DERIVED_FROM),
                DecisionTraceLink(policy.id, provider.id, DecisionTraceLinkType.CONSTRAINS),
            ),
        )
    }

    private fun goalTraceId(goalPhotonId: PhotonId): DecisionTraceId =
        DecisionTraceId.create("goal-photon", goalPhotonId.value)

    private fun goalNode(
        goalPhotonId: PhotonId,
        goalPhotonRevision: Long,
        recordedAt: Instant,
    ): DecisionTraceNode = DecisionTraceNode.create(
        type = DecisionTraceNodeType.OBSERVED_FACT,
        sourceType = "goal-photon",
        sourceId = goalPhotonId.value,
        sourceRevision = goalPhotonRevision,
        recordedAt = recordedAt,
    )

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

/** Process-local reference only; durable authority remains in each subsystem's own ledger/store. */
object DecisionTraceRuntimeRegistry {
    @Volatile
    private var recorder: SubsystemDecisionTraceRecorder? = null

    fun install(value: SubsystemDecisionTraceRecorder) {
        recorder = value
    }

    fun currentOrNull(): SubsystemDecisionTraceRecorder? = recorder

    internal fun clearForTests() {
        recorder = null
    }
}
