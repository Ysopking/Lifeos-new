package app.lifeos.core.runtime.capability

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEvent
import app.lifeos.core.runtime.policy.OwnerPolicyGrant
import app.lifeos.core.runtime.policy.OwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerPolicyRepositoryLoadReport
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
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
import app.lifeos.core.runtime.resource.ResourceBudgetReservationResult
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

class DurableToolWorkshopCoordinatorTest {
    @Test
    fun `persisted stage artifact is rebound after crash without executing stage twice`() = runTest {
        val jobRepository = MemoryJobRepository()
        val jobs = ToolWorkshopJobLedger(jobRepository) { NOW }
        val definition = definition()
        val requested = jobs.create(definition)
        val artifacts = MemoryStageArtifactRepository()
        val specification = specification()
        val artifact = ToolWorkshopStageArtifact(
            jobId = definition.id,
            stage = ToolWorkshopJobState.SPECIFIED,
            payload = ToolWorkshopStagePayloadCodec.encode(specification),
            createdAt = NOW,
        )
        artifacts.persist(artifact)

        val budgetRepository = MemoryBudgetRepository()
        val budgets = ResourceBudgetCoordinator(budgetRepository) { NOW }
        val accountId = ResourceBudgetAccountId(
            "tool-workshop:${definition.id.value}:${ToolWorkshopJobState.SPECIFIED.name.lowercase()}"
        )
        budgets.createAccount(accountId, HARD_QUOTA)
        val reserved = assertIs<ResourceBudgetReservationResult.Reserved>(
            budgets.reserve(accountId, "${definition.id.value}:SPECIFIED", STAGE_USAGE)
        ).reservation

        var builderCalls = 0
        val coordinator = coordinator(
            jobs = ToolWorkshopJobLedger(jobRepository) { NOW.plusSeconds(1) },
            artifacts = artifacts,
            budgets = ResourceBudgetCoordinator(budgetRepository) { NOW.plusSeconds(1) },
            ownerPolicy = OwnerPolicyLedger(MemoryOwnerPolicyRepository()) { NOW },
            builder = object : ToolSpecificationBuilder {
                override suspend fun build(gap: CapabilityGap): ToolSpecification {
                    builderCalls += 1
                    error("persisted SPECIFIED artifact must prevent rebuild")
                }
            },
        )

        val result = assertIs<ToolWorkshopStageResult.Advanced>(
            coordinator.runNext(definition.id, profile())
        )

        assertEquals(0, builderCalls)
        assertEquals(ToolWorkshopJobState.SPECIFIED, result.snapshot.state)
        assertEquals(artifact.fingerprint, result.snapshot.stageFingerprint)
        val settled = budgets.current(accountId).reservations.single()
        assertEquals(reserved.id, settled.id)
        assertEquals(ResourceBudgetReservationState.COMMITTED, settled.state)
        assertEquals(STAGE_USAGE, settled.settledUsage)
    }

    @Test
    fun `fresh workshop stage requires owner and world budget then persists before ledger advance`() = runTest {
        val jobs = ToolWorkshopJobLedger(MemoryJobRepository()) { NOW }
        val definition = definition()
        jobs.create(definition)
        val artifacts = MemoryStageArtifactRepository()
        val budgetRepository = MemoryBudgetRepository()
        val ownerRepository = MemoryOwnerPolicyRepository()
        val owner = OwnerPolicyLedger(ownerRepository) { NOW }
        owner.grant(
            OwnerPolicyGrant.create(
                actorId = ACTOR,
                effect = OwnerEffectType.TOOL_EXECUTION,
                resource = OwnerResourceSelector(OwnerResourceSelectorType.PREFIX, "tool-workshop:"),
                scope = SCOPE,
                validFrom = Instant.EPOCH,
            )
        )

        var builderCalls = 0
        val coordinator = coordinator(
            jobs = jobs,
            artifacts = artifacts,
            budgets = ResourceBudgetCoordinator(budgetRepository) { NOW },
            ownerPolicy = owner,
            builder = object : ToolSpecificationBuilder {
                override suspend fun build(gap: CapabilityGap): ToolSpecification {
                    builderCalls += 1
                    return specification()
                }
            },
        )

        val result = assertIs<ToolWorkshopStageResult.Advanced>(
            coordinator.runNext(definition.id, profile())
        )

        assertEquals(1, builderCalls)
        assertEquals(ToolWorkshopJobState.SPECIFIED, result.snapshot.state)
        val persisted = requireNotNull(artifacts.load(definition.id, ToolWorkshopJobState.SPECIFIED))
        assertEquals(result.snapshot.stageFingerprint, persisted.fingerprint)
        val accountId = ResourceBudgetAccountId(
            "tool-workshop:${definition.id.value}:${ToolWorkshopJobState.SPECIFIED.name.lowercase()}"
        )
        assertEquals(
            ResourceBudgetReservationState.COMMITTED,
            ResourceBudgetCoordinator(budgetRepository).current(accountId).reservations.single().state,
        )
    }

    @Test
    fun `owner denial releases stage reservation and interrupts before work`() = runTest {
        val jobs = ToolWorkshopJobLedger(MemoryJobRepository()) { NOW }
        val definition = definition()
        jobs.create(definition)
        val budgetRepository = MemoryBudgetRepository()
        var builderCalls = 0
        val coordinator = coordinator(
            jobs = jobs,
            artifacts = MemoryStageArtifactRepository(),
            budgets = ResourceBudgetCoordinator(budgetRepository) { NOW },
            ownerPolicy = OwnerPolicyLedger(MemoryOwnerPolicyRepository()) { NOW },
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
        artifacts: ToolWorkshopStageArtifactRepository,
        budgets: ResourceBudgetCoordinator,
        ownerPolicy: OwnerPolicyLedger,
        builder: ToolSpecificationBuilder,
    ): DurableToolWorkshopCoordinator {
        val tools = GeneratedToolRegistry(now = { NOW })
        val lifecycle = GeneratedToolLifecycleCoordinator(tools)
        return DurableToolWorkshopCoordinator(
            jobs = jobs,
            stageArtifacts = artifacts,
            specificationBuilder = builder,
            designer = object : ToolDesigner {
                override suspend fun design(toolId: String, specification: ToolSpecification) =
                    error("unused")
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
                override suspend fun validate(specification: ToolSpecification, source: GeneratedSource) =
                    error("unused")
            },
            capabilityVerifier = object : GeneratedCapabilityVerifier {
                override suspend fun verify(specification: ToolSpecification, build: ToolBuildResult) =
                    error("unused")
            },
            tools = tools,
            lifecycle = lifecycle,
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
                worldSnapshotId = "world:test-workshop",
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
            requestPhotonId = PhotonId("photon-v11-crash-test"),
            requestedBy = "test",
            requestedAt = NOW,
        ),
        sourceRevision = 1L,
        policyVersion = "policy-v1",
        workshopVersion = "workshop-v11",
    )

    private fun specification() = ToolSpecification(
        purpose = "bounded-test",
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
            val key = artifact.jobId to artifact.stage
            values[key]?.let { existing ->
                require(existing == artifact)
                return
            }
            values[key] = artifact
        }

        override suspend fun load(jobId: ToolWorkshopJobId, stage: ToolWorkshopJobState) =
            values[jobId to stage]

        override suspend fun loadAll(jobId: ToolWorkshopJobId) =
            values.values.filter { it.jobId == jobId }.sortedBy { it.stage.ordinal }
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

    private class MemoryBudgetRepository : ResourceBudgetRepository {
        private val accounts = linkedMapOf<ResourceBudgetAccountId, ResourceBudgetAccount>()

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
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T14:30:00Z")
        val ACTOR = OwnerActorId("private-owner-test")
        const val SCOPE = "test-tool-workshop"
        val CAPABILITY = CapabilityId("text.normalize.v11")
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
