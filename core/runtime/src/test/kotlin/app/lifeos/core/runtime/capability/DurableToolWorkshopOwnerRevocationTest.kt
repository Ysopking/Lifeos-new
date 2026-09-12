package app.lifeos.core.runtime.capability

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEvent
import app.lifeos.core.runtime.policy.OwnerPolicyGrant
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerPolicyRepositoryLoadReport
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import app.lifeos.core.runtime.resource.HardwareAdaptiveBudgetPlan
import app.lifeos.core.runtime.resource.HardwareBudgetMode
import app.lifeos.core.runtime.resource.HardwareStateSnapshot
import app.lifeos.core.runtime.resource.HardwareThermalState
import app.lifeos.core.runtime.resource.ResourceBudgetAccount
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetDomain
import app.lifeos.core.runtime.resource.ResourceBudgetDomainAllocation
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetRepository
import app.lifeos.core.runtime.resource.ResourceBudgetRepositoryLoadReport
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.resource.SharedResourceBudgetDecision
import app.lifeos.core.runtime.resource.SharedResourceBudgetGate
import app.lifeos.core.runtime.resource.WorldFormulaBudgetAllocationPlan
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DurableToolWorkshopOwnerRevocationTest {
    @Test
    fun `revocation after durable reservation blocks stage effect and releases reservation`() = runTest {
        val jobRepository = MemoryJobRepository()
        val jobs = ToolWorkshopJobLedger(jobRepository) { NOW }
        val definition = definition()
        jobs.create(definition)

        val ownerRepository = MemoryOwnerPolicyRepository()
        val owner = OwnerPolicyLedger(ownerRepository) { NOW }
        val grant = OwnerPolicyGrant.create(
            actorId = ACTOR,
            effect = OwnerEffectType.TOOL_EXECUTION,
            resource = OwnerResourceSelector(OwnerResourceSelectorType.PREFIX, "tool-workshop:"),
            scope = SCOPE,
            validFrom = Instant.EPOCH,
        )
        owner.grant(grant)

        var revokeCalls = 0
        val budgetRepository = RevokingBudgetRepository {
            revokeCalls += 1
            owner.revoke(grant.id)
        }
        var builderCalls = 0
        val coordinator = coordinator(
            jobs = jobs,
            budgets = ResourceBudgetCoordinator(budgetRepository) { NOW },
            ownerPolicy = owner,
            builder = object : ToolSpecificationBuilder {
                override suspend fun build(gap: CapabilityGap): ToolSpecification {
                    builderCalls += 1
                    return specification()
                }
            },
        )

        val result = assertIs<ToolWorkshopStageResult.Rejected>(
            coordinator.runNext(definition.id, profile())
        )

        assertEquals(1, revokeCalls)
        assertEquals(0, builderCalls)
        assertEquals(ToolWorkshopJobState.INTERRUPTED, result.snapshot.state)
        val accountId = ResourceBudgetAccountId(
            "tool-workshop:${definition.id.value}:${ToolWorkshopJobState.SPECIFIED.name.lowercase()}"
        )
        assertEquals(
            ResourceBudgetReservationState.RELEASED,
            ResourceBudgetCoordinator(budgetRepository).current(accountId).reservations.single().state,
        )
    }

    private fun coordinator(
        jobs: ToolWorkshopJobLedger,
        budgets: ResourceBudgetCoordinator,
        ownerPolicy: OwnerPolicyLedger,
        builder: ToolSpecificationBuilder,
    ): DurableToolWorkshopCoordinator {
        val tools = GeneratedToolRegistry(now = { NOW })
        return DurableToolWorkshopCoordinator(
            jobs = jobs,
            stageArtifacts = MemoryStageArtifactRepository(),
            specificationBuilder = builder,
            designer = object : ToolDesigner {
                override suspend fun design(toolId: String, specification: ToolSpecification) = error("unused")
            },
            implementationEngine = object : ToolImplementationEngine {
                override suspend fun implement(design: ToolDesign) = error("unused")
            },
            buildRunner = object : ToolBuildRunner {
                override suspend fun build(source: GeneratedSource) = error("unused")
            },
            testRunner = object : ToolTestRunner {
                override suspend fun test(build: ToolBuildResult) = error("unused")
            },
            securityValidator = object : ToolSecurityValidator {
                override suspend fun validate(specification: ToolSpecification, source: GeneratedSource) = error("unused")
            },
            capabilityVerifier = object : GeneratedCapabilityVerifier {
                override suspend fun verify(specification: ToolSpecification, build: ToolBuildResult) = error("unused")
            },
            tools = tools,
            lifecycle = GeneratedToolLifecycleCoordinator(tools),
            ownerPolicy = ownerPolicy,
            budgets = budgets,
            actorId = ACTOR,
            ownerScope = SCOPE,
            sharedBudgets = { readyGate() },
            now = { NOW },
        )
    }

    private fun readyGate(): SharedResourceBudgetGate = SharedResourceBudgetGate { hardQuota, demands ->
        val demand = demands.single()
        val hardware = HardwareStateSnapshot(
            observedAt = NOW,
            availableProcessors = 8,
            batteryFraction = 1.0,
            charging = true,
            thermalState = HardwareThermalState.NOMINAL,
            cpuLoadFraction = 0.1,
        )
        SharedResourceBudgetDecision.Ready(
            hardwarePlan = HardwareAdaptiveBudgetPlan(
                hardQuota = hardQuota,
                effectiveQuota = hardQuota,
                requested = demand.requested,
                recommendedReservation = demand.requested,
                mode = HardwareBudgetMode.NORMAL,
                reasons = listOf("test-ready"),
                hardwareSnapshotFingerprint = hardware.fingerprint(),
                hardwareWorldInput = hardware.toWorldFormulaInput(),
            ),
            allocation = WorldFormulaBudgetAllocationPlan(
                pool = hardQuota,
                allocations = listOf(
                    ResourceBudgetDomainAllocation(
                        domain = ResourceBudgetDomain.TOOL_WORKSHOP,
                        demandFingerprint = demand.fingerprint(),
                        worldWeight = 1.0,
                        allocated = demand.requested,
                    )
                ),
                unallocated = ResourceBudgetUsage(),
                worldSnapshotId = "world:v14-owner-revocation",
                hardwareSnapshotFingerprint = hardware.fingerprint(),
            ),
        )
    }

    private fun definition(): ToolWorkshopJobDefinition = ToolWorkshopJobDefinition.fromRequest(
        request = GeneratedToolRequest.fromGap(
            gap = CapabilityGap(
                requirement = CapabilityRequirement(
                    capabilityId = CAPABILITY,
                    severity = GapSeverity.BLOCKING,
                    requiredInputs = setOf("text"),
                    requiredOutputs = setOf("normalized-text"),
                ),
                type = CapabilityGapType.CAPABILITY_MISSING,
            ),
            requestPhotonId = PhotonId("photon-v14-owner-revocation"),
            requestedBy = "test",
            requestedAt = NOW,
        ),
        sourceRevision = 1L,
        policyVersion = "policy-v14",
        workshopVersion = "workshop-v14",
    )

    private fun specification() = ToolSpecification(
        purpose = "revocation-contract",
        requiredCapability = CapabilityRequirement(
            capabilityId = CAPABILITY,
            severity = GapSeverity.BLOCKING,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
        ),
        allowedPermissions = emptySet(),
        maxSourceBytes = 32_768,
    )

    private fun profile() = ToolWorkshopExecutionProfile(
        hardQuota = HARD_QUOTA,
        stageRequests = ToolWorkshopExecutionProfile.EXECUTABLE_STAGES.associateWith { STAGE_USAGE },
    )

    private class MemoryStageArtifactRepository : ToolWorkshopStageArtifactRepository {
        private val values = linkedMapOf<Pair<ToolWorkshopJobId, ToolWorkshopJobState>, ToolWorkshopStageArtifact>()
        override suspend fun persist(artifact: ToolWorkshopStageArtifact) {
            values[artifact.jobId to artifact.stage] = artifact
        }
        override suspend fun load(jobId: ToolWorkshopJobId, stage: ToolWorkshopJobState) = values[jobId to stage]
        override suspend fun loadAll(jobId: ToolWorkshopJobId) = values.values.filter { it.jobId == jobId }
    }

    private class MemoryJobRepository : ToolWorkshopJobRepository {
        private val events = mutableListOf<ToolWorkshopJobEvent>()
        override suspend fun loadReport() = ToolWorkshopJobRepositoryLoadReport(events.toList())
        override suspend fun append(expectedRevision: Long, event: ToolWorkshopJobEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            events += event
            return true
        }
    }

    private class MemoryOwnerPolicyRepository : OwnerPolicyRepository {
        private val events = mutableListOf<OwnerPolicyEvent>()
        override suspend fun loadReport() = OwnerPolicyRepositoryLoadReport(events.toList())
        override suspend fun append(expectedRevision: Long, event: OwnerPolicyEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            events += event
            return true
        }
    }

    private class RevokingBudgetRepository(
        private val afterFirstReservation: suspend () -> Unit,
    ) : ResourceBudgetRepository {
        private val accounts = linkedMapOf<ResourceBudgetAccountId, ResourceBudgetAccount>()
        private var fired = false

        override suspend fun load(accountId: ResourceBudgetAccountId) =
            ResourceBudgetRepositoryLoadReport(accounts[accountId])

        override suspend fun create(account: ResourceBudgetAccount): Boolean {
            if (account.id in accounts) return false
            accounts[account.id] = account
            return true
        }

        override suspend fun compareAndSet(
            accountId: ResourceBudgetAccountId,
            expectedRevision: Long,
            updated: ResourceBudgetAccount,
        ): Boolean {
            val current = accounts[accountId] ?: return false
            if (current.revision != expectedRevision) return false
            accounts[accountId] = updated
            if (!fired && updated.reservations.any { it.state == ResourceBudgetReservationState.RESERVED }) {
                fired = true
                afterFirstReservation()
            }
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T20:15:00Z")
        val ACTOR = OwnerActorId("private-owner-v14-test")
        const val SCOPE = "test-tool-workshop-v14"
        val CAPABILITY = CapabilityId("text.normalize.v14")
        val STAGE_USAGE = ResourceBudgetUsage(
            elapsedMillis = 1_000,
            workUnits = 1,
            memoryBytes = 1_024,
            ioBytes = 0,
            networkBytes = 0,
            candidates = 1,
        )
        val HARD_QUOTA = ResourceBudgetQuota(
            elapsedMillis = STAGE_USAGE.elapsedMillis,
            workUnits = STAGE_USAGE.workUnits,
            memoryBytes = STAGE_USAGE.memoryBytes,
            ioBytes = STAGE_USAGE.ioBytes,
            networkBytes = STAGE_USAGE.networkBytes,
            candidates = STAGE_USAGE.candidates,
        )
    }
}
