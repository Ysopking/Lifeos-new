package app.lifeos.core.runtime.escalation

import app.lifeos.core.model.health.ProtectionActor
import app.lifeos.core.model.health.ProtectionMode
import app.lifeos.core.model.health.ProtectionReason
import app.lifeos.core.model.health.ProtectionReasonCode
import app.lifeos.core.model.health.ProtectionResumePolicy
import app.lifeos.core.model.health.RuntimeProtectionState
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityMatchRequest
import app.lifeos.core.runtime.capability.CapabilityMatchResult
import app.lifeos.core.runtime.capability.CapabilityProviderProfile
import app.lifeos.core.runtime.capability.CapabilityProviderScore
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.CapabilityScoreTerm
import app.lifeos.core.runtime.capability.CapabilityScoreComponent
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import app.lifeos.core.runtime.genesis.GenesisHandoff
import app.lifeos.core.runtime.genesis.GenesisProposal
import app.lifeos.core.runtime.genesis.GenesisRequest
import app.lifeos.core.runtime.genesis.GenesisResult
import app.lifeos.core.runtime.genesis.GenesisSolutionCandidate
import app.lifeos.core.runtime.genesis.GenesisSolutionKind
import app.lifeos.core.runtime.genesis.GenesisHandoffTarget
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.tasks.RetryPolicy
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class EscalationSubsystemExecutorsTest {
    private val at = Instant.parse("2026-09-19T01:00:00Z")

    @Test
    fun retryExecutorUsesExistingRetryPolicyAndStableSchedulerBoundary() = runBlocking {
        val task = LifeTask(
            id = TaskId("escalation-retry-task"),
            type = TaskType.PROCESS_PHOTON,
            idempotencyKey = "escalation-retry",
            attempt = 1,
            maxAttempts = 3,
            createdAt = at,
            updatedAt = at,
        )
        val executor = RetryPolicyEscalationExecutor(
            policy = RetryPolicy(),
            contexts = RetryEscalationContextSource {
                RetryEscalationContext(
                    task = task,
                    failures = listOf(
                        RuntimeFailure(
                            category = RuntimeFailureCategory.TIMEOUT,
                            source = "worker",
                            message = "timeout",
                            recoverable = true,
                        )
                    ),
                    scheduledAt = at,
                )
            },
            scheduler = EscalationRetryScheduler { request, _, schedule ->
                EscalationExecutionResult.Succeeded(
                    "retry-scheduled:" + schedule.retryAt,
                    setOf("retry-execution:" + request.executionId.value),
                )
            },
        )

        val result = assertIs<EscalationExecutionResult.Succeeded>(
            executor.execute(request(EscalationLevel.L0_RETRY))
        )
        assertTrue(result.detail.startsWith("retry-scheduled:"))
    }

    @Test
    fun quarantineExecutorReplaysSameDurableProtectionGeneration() = runBlocking {
        val reason = ProtectionReason(
            code = ProtectionReasonCode.REPEATED_FAILURE,
            source = "escalation-test",
            message = "repeated failure",
        )
        val authority = MemoryProtectionAuthority()
        val executor = ProtectionQuarantineEscalationExecutor(
            contexts = ProtectionEscalationContextSource {
                ProtectionEscalationContext(
                    reasons = listOf(reason),
                    provenanceTag = "test-quarantine",
                    resumePolicy = ProtectionResumePolicy.AUTO_AFTER_VERIFICATION,
                )
            },
            authority = authority,
        )
        val request = request(EscalationLevel.L3_QUARANTINE)

        assertIs<EscalationExecutionResult.Succeeded>(executor.execute(request))
        val firstGeneration = authority.snapshot().generation
        assertIs<EscalationExecutionResult.Succeeded>(executor.execute(request))
        assertEquals(firstGeneration, authority.snapshot().generation)
        assertEquals(1, authority.quarantineCalls)
    }

    @Test
    fun fallbackExecutorNeverActivatesUnexpectedProvider() = runBlocking {
        val selected = providerScore("cpu-reference")
        var activationCalls = 0
        val executor = CapabilityFallbackEscalationExecutor(
            contexts = FallbackEscalationContextSource {
                FallbackEscalationContext(
                    matchRequest = CapabilityMatchRequest(
                        requirement = CapabilityRequirement(CapabilityId("render")),
                    ),
                    expectedProviderId = "known-good",
                )
            },
            matcher = EscalationCapabilityMatcher {
                CapabilityMatchResult.Selected(
                    request = it,
                    selected = selected,
                    rankedCandidates = listOf(selected),
                    excluded = emptyList(),
                )
            },
            activation = FallbackActivationSink { _, _ ->
                activationCalls += 1
                EscalationExecutionResult.Succeeded("activated")
            },
        )

        val result = assertIs<EscalationExecutionResult.Blocked>(
            executor.execute(request(EscalationLevel.L4_FALLBACK))
        )
        assertTrue(result.detail.startsWith("fallback-selected-unexpected-provider:"))
        assertEquals(0, activationCalls)
    }

    @Test
    fun genesisExecutorAcceptsProposalEvidenceButNeverActivationAuthority() = runBlocking {
        val requirement = CapabilityRequirement(
            capabilityId = CapabilityId("missing-capability"),
            severity = GapSeverity.BLOCKING,
            requiredOutputs = setOf("text"),
        )
        val genesisRequest = GenesisRequest(requirement = requirement, query = null)
        val gap = CapabilityGap(requirement, CapabilityGapType.CAPABILITY_MISSING)
        val candidate = GenesisSolutionCandidate(
            kind = GenesisSolutionKind.CODE_TOOL,
            referenceId = "tool-proposal",
            reliability = 0.0,
            expectedCost = 1.0,
            rationale = "test",
            requiresExplicitApproval = true,
        )
        val handoff = GenesisHandoff(
            proposalId = "proposal-1",
            target = GenesisHandoffTarget.TOOL_WORKSHOP,
            referenceId = "tool-proposal",
            payloadFingerprint = "payload",
            requiresExplicitApproval = true,
        )
        val proposal = GenesisProposal(
            id = "proposal-1",
            requestId = genesisRequest.id,
            gap = gap,
            selected = candidate,
            consideredKinds = listOf(GenesisSolutionKind.CODE_TOOL),
            evidenceRefs = emptySet(),
            handoff = handoff,
        )
        val executor = GenesisRepairEscalationExecutor(
            contexts = GenesisRepairContextSource { genesisRequest },
            authority = GenesisRepairAuthority { GenesisResult.Proposed(proposal) },
        )

        val result = assertIs<EscalationExecutionResult.Succeeded>(
            executor.execute(request(EscalationLevel.L7_REPAIR_PROPOSAL))
        )
        assertTrue(!proposal.handoff.activationAllowed)
        assertTrue(result.evidenceRefs.contains("genesis-proposal:proposal-1"))
    }

    private fun request(level: EscalationLevel): EscalationExecutionRequest {
        val id = EscalationId("escalation:" + "d".repeat(64))
        return EscalationExecutionRequest(
            escalationId = id,
            executionId = EscalationExecutionId.create(id, level),
            level = level,
            nodeId = HealthNodeId("node-r04"),
            triggerFingerprint = "trigger-r04",
            evidenceRefs = emptySet(),
            resuming = false,
        )
    }

    private fun providerScore(providerId: String): CapabilityProviderScore {
        val provider = CapabilityDescriptor(
            capabilityId = CapabilityId("render"),
            providerId = providerId,
            providerType = ProviderType.LOCAL,
            contract = CapabilityContract(outputs = setOf("image")),
            state = ProviderState.ACTIVE,
            trustLevel = TrustLevel.HIGH,
            reliability = 1.0,
            cost = 0.0,
        )
        return CapabilityProviderScore(
            provider = provider,
            profile = CapabilityProviderProfile(),
            terms = listOf(
                CapabilityScoreTerm(
                    component = CapabilityScoreComponent.TRUST,
                    rawScore = 1.0,
                    weight = 1.0,
                    contribution = 1.0,
                )
            ),
            score = 1.0,
        )
    }

    private class MemoryProtectionAuthority : ProtectionEscalationAuthority {
        private var current = RuntimeProtectionState.normal()
        var quarantineCalls: Int = 0
            private set

        override fun snapshot(): RuntimeProtectionState = current

        override suspend fun enterQuarantine(
            nodes: Set<HealthNodeId>,
            reasons: List<ProtectionReason>,
            provenance: String,
            resumePolicy: ProtectionResumePolicy,
        ): RuntimeProtectionState {
            quarantineCalls += 1
            current = current.nextRevision(
                mode = ProtectionMode.QUARANTINED,
                reasons = reasons,
                affectedNodes = nodes.mapTo(linkedSetOf()) {
                    app.lifeos.core.model.health.ProtectionNodeRef(it.value)
                },
                enteredAt = at,
                lastVerifiedAt = null,
                actor = ProtectionActor.ESCALATION,
                provenance = provenance,
                resumePolicy = resumePolicy,
                advanceGeneration = true,
            )
            return current
        }

        override suspend fun enterSafeMode(
            reasons: List<ProtectionReason>,
            affectedNodes: Set<HealthNodeId>,
            provenance: String,
            resumePolicy: ProtectionResumePolicy,
        ): RuntimeProtectionState = error("not used")
    }
}
