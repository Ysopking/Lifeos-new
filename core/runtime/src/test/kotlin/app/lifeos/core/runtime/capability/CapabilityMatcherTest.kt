package app.lifeos.core.runtime.capability

import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthScope
import app.lifeos.core.runtime.workers.WorkerDescriptor
import app.lifeos.core.runtime.workers.WorkerRegistry
import app.lifeos.core.runtime.workers.WorkerRuntimeState
import app.lifeos.core.runtime.workers.WorkerVersion
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CapabilityMatcherTest {
    private val capability = CapabilityId("search.web")
    private val requirement = CapabilityRequirement(
        capabilityId = capability,
        requiredInputs = setOf("query"),
        requiredOutputs = setOf("results"),
    )

    @Test
    fun `filters contract permissions trust cost and latency before scoring`() = runTest {
        val registry = CapabilityRegistry()
        registry.register(provider("good", TrustLevel.HIGH, 0.95, 0.2))
        registry.register(
            provider("bad-contract", TrustLevel.HIGH, 0.99, 0.0).copy(
                contract = CapabilityContract(requiredInputs = setOf("missing-input"), outputs = setOf("results")),
            )
        )
        registry.register(provider("too-expensive", TrustLevel.SYSTEM, 1.0, 5.0))
        registry.register(provider("low-trust", TrustLevel.LOW, 1.0, 0.0))
        registry.register(provider("no-permission", TrustLevel.HIGH, 1.0, 0.0))
        registry.register(provider("slow", TrustLevel.HIGH, 1.0, 0.0))

        val profiles = profiles(
            mapOf(
                "good" to profile(setOf("network"), latency = 40),
                "bad-contract" to profile(setOf("network"), latency = 40),
                "too-expensive" to profile(setOf("network"), latency = 40),
                "low-trust" to profile(setOf("network"), latency = 40),
                "no-permission" to profile(emptySet(), latency = 40),
                "slow" to profile(setOf("network"), latency = 500),
            )
        )
        val result = CapabilityMatcher(registry, profiles = profiles).match(
            CapabilityMatchRequest(
                requirement = requirement,
                requiredPermissions = setOf("network"),
                minimumTrustLevel = TrustLevel.MEDIUM,
                maxCost = 1.0,
                maxLatencyMillis = 100,
            )
        )

        val selected = assertIs<CapabilityMatchResult.Selected>(result)
        assertEquals("good", selected.selected.provider.providerId)
        val excluded = selected.excluded.associateBy { it.provider.providerId }
        assertTrue(CapabilityFilterReason.CONTRACT_MISMATCH in excluded.getValue("bad-contract").reasons)
        assertTrue(CapabilityFilterReason.COST_EXCEEDED in excluded.getValue("too-expensive").reasons)
        assertTrue(CapabilityFilterReason.TRUST_TOO_LOW in excluded.getValue("low-trust").reasons)
        assertTrue(CapabilityFilterReason.PERMISSION_MISSING in excluded.getValue("no-permission").reasons)
        assertTrue(CapabilityFilterReason.LATENCY_EXCEEDED in excluded.getValue("slow").reasons)
    }

    @Test
    fun `score trace combines trust reliability cost latency and history deterministically`() = runTest {
        val registry = CapabilityRegistry()
        registry.register(provider("fast-cheap", TrustLevel.MEDIUM, 0.90, 0.1))
        registry.register(provider("trusted-costly", TrustLevel.HIGH, 0.99, 2.0))
        val matcher = CapabilityMatcher(
            registry = registry,
            profiles = profiles(
                mapOf(
                    "fast-cheap" to profile(
                        permissions = emptySet(),
                        latency = 20,
                        history = CapabilityExecutionHistory(successes = 90, failures = 10),
                    ),
                    "trusted-costly" to profile(
                        permissions = emptySet(),
                        latency = 400,
                        history = CapabilityExecutionHistory(successes = 95, failures = 5),
                    ),
                )
            ),
        )

        val first = assertIs<CapabilityMatchResult.Selected>(
            matcher.match(CapabilityMatchRequest(requirement))
        )
        val second = assertIs<CapabilityMatchResult.Selected>(
            matcher.match(CapabilityMatchRequest(requirement))
        )

        assertEquals("fast-cheap", first.selected.provider.providerId)
        assertEquals(first.rankedCandidates, second.rankedCandidates)
        assertEquals(CapabilityScoreComponent.entries, first.selected.terms.map { it.component })
        assertEquals(
            first.selected.totalScore,
            first.selected.terms.sumOf { it.contribution },
            absoluteTolerance = 1e-12,
        )
    }

    @Test
    fun `equal top scores remain unresolved instead of provider id tie breaking`() = runTest {
        val registry = CapabilityRegistry()
        registry.register(provider("provider-a", TrustLevel.HIGH, 0.95, 0.2))
        registry.register(provider("provider-b", TrustLevel.HIGH, 0.95, 0.2))
        val commonProfile = profile(
            permissions = emptySet(),
            latency = 50,
            history = CapabilityExecutionHistory(successes = 9, failures = 1),
        )
        val matcher = CapabilityMatcher(
            registry = registry,
            profiles = profiles(mapOf("provider-a" to commonProfile, "provider-b" to commonProfile)),
        )

        val result = assertIs<CapabilityMatchResult.Unresolved>(
            matcher.match(CapabilityMatchRequest(requirement))
        )

        assertEquals(CapabilityUnresolvedReason.EQUAL_TOP_SCORE, result.reason)
        assertEquals(listOf("provider-a", "provider-b"), result.tiedCandidates.map { it.provider.providerId })
        assertEquals(result.tiedCandidates.first().totalScore, result.tiedCandidates.last().totalScore)
    }

    @Test
    fun `worker providers require registered runnable capacity and eligible live health`() = runTest {
        val capabilityRegistry = CapabilityRegistry()
        capabilityRegistry.register(
            provider("worker-1", TrustLevel.SYSTEM, 1.0, 0.0).copy(providerType = ProviderType.WORKER)
        )
        val healthGraph = HealthGraph()
        val workers = WorkerRegistry(healthGraph)
        val worker = WorkerDescriptor(
            workerId = WorkerId("worker-1"),
            version = WorkerVersion(1),
            capabilities = setOf(capability),
            maxConcurrency = 1,
            implementationFingerprint = "worker-1/v1",
        )
        workers.register(worker, WorkerRuntimeState.READY)
        healthGraph.register(worker.healthNodeId, HealthScope.WORKER)
        val matcher = CapabilityMatcher(capabilityRegistry, workerRegistry = workers)

        val unknownHealth = assertIs<CapabilityMatchResult.Unavailable>(
            matcher.match(CapabilityMatchRequest(requirement))
        )
        assertTrue(
            CapabilityFilterReason.WORKER_HEALTH_NOT_ELIGIBLE in
                unknownHealth.excluded.single().reasons
        )

        healthGraph.recordHealthy(worker.healthNodeId, source = "test")
        assertIs<CapabilityMatchResult.Selected>(
            matcher.match(CapabilityMatchRequest(requirement))
        )

        workers.updateLoad(worker.workerId, activeWork = 1)
        val saturated = assertIs<CapabilityMatchResult.Unavailable>(
            matcher.match(CapabilityMatchRequest(requirement))
        )
        assertTrue(CapabilityFilterReason.WORKER_SATURATED in saturated.excluded.single().reasons)
    }

    @Test
    fun `missing worker registry fails worker provider closed`() = runTest {
        val registry = CapabilityRegistry()
        registry.register(provider("worker-1", TrustLevel.SYSTEM, 1.0, 0.0).copy(providerType = ProviderType.WORKER))

        val result = assertIs<CapabilityMatchResult.Unavailable>(
            CapabilityMatcher(registry).match(CapabilityMatchRequest(requirement))
        )

        assertEquals(
            listOf(CapabilityFilterReason.WORKER_NOT_REGISTERED),
            result.excluded.single().reasons,
        )
    }

    private fun provider(
        id: String,
        trust: TrustLevel,
        reliability: Double,
        cost: Double,
    ): CapabilityDescriptor = CapabilityDescriptor(
        capabilityId = capability,
        providerId = id,
        providerType = ProviderType.CONNECTOR,
        contract = CapabilityContract(requiredInputs = setOf("query"), outputs = setOf("results")),
        state = ProviderState.ACTIVE,
        trustLevel = trust,
        reliability = reliability,
        cost = cost,
    )

    private fun profile(
        permissions: Set<String>,
        latency: Long?,
        history: CapabilityExecutionHistory = CapabilityExecutionHistory(),
    ): CapabilityProviderProfile = CapabilityProviderProfile(
        grantedPermissions = permissions,
        estimatedLatencyMillis = latency,
        history = history,
    )

    private fun profiles(
        values: Map<String, CapabilityProviderProfile>,
    ): CapabilityProviderProfileSource = object : CapabilityProviderProfileSource {
        override suspend fun profileFor(provider: CapabilityDescriptor): CapabilityProviderProfile =
            values[provider.providerId] ?: CapabilityProviderProfile()
    }
}
