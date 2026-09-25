package app.lifeos.core.runtime.world

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.runtime.life.AppSensorRegistry
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.SensorAttentionMode
import app.lifeos.core.runtime.life.SensorClass
import app.lifeos.core.runtime.life.SensorDescriptor
import app.lifeos.core.runtime.life.SensorHealthState
import app.lifeos.core.runtime.life.SensorId
import app.lifeos.core.runtime.policy.OwnerObservationType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensorAttentionRuntimeTest {
    private val sensorId = SensorId("bank-provider")

    @Test
    fun blockingPerceptionGapCanFocusHealthySensor() = runTest {
        val registry = registry()
        val runtime = SensorAttentionRuntime(registry)

        val decisions = runtime.apply(
            listOf(
                demand(
                    blockingGapCount = 1,
                    information = 700_000,
                    goal = 800_000,
                )
            )
        )

        assertEquals(SensorAttentionMode.FOCUSED, decisions.single().mode)
        assertEquals(
            SensorAttentionMode.FOCUSED,
            registry.state(sensorId)?.mode,
        )
        assertFalse(decisions.single().observationGrantAuthority)
        assertFalse(decisions.single().effectAuthority)
    }

    @Test
    fun quarantinedSensorIsSuspendedRegardlessOfDemand() = runTest {
        val registry = registry()
        registry.updateHealth(
            sensorId,
            SensorHealthState.QUARANTINED,
            "adapter-integrity-failure",
        )
        val decision = SensorAttentionPlanner().plan(
            registry.snapshot(),
            listOf(
                demand(
                    blockingGapCount = 4,
                    information = 1_000_000,
                    goal = 1_000_000,
                    verification = 1_000_000,
                )
            ),
        ).single()

        assertEquals(SensorAttentionMode.SUSPENDED, decision.mode)
        assertTrue(
            SensorAttentionReason.HEALTH_UNAVAILABLE in decision.reasons
        )
    }

    @Test
    fun costsCanSuspendNonBlockingSensor() = runTest {
        val registry = registry()
        val decision = SensorAttentionPlanner().plan(
            registry.snapshot(),
            listOf(
                demand(
                    information = 10_000,
                    goal = 10_000,
                    energy = 900_000,
                    privacy = 900_000,
                    latency = 900_000,
                    resource = 900_000,
                )
            ),
        ).single()

        assertEquals(SensorAttentionMode.SUSPENDED, decision.mode)
        assertTrue(SensorAttentionReason.COST_DOMINATED in decision.reasons)
    }

    @Test
    fun planOrderingIsStableBySensorId() = runTest {
        val registry = AppSensorRegistry()
        registry.register(descriptor("z"))
        registry.register(descriptor("a"))

        val plan = SensorAttentionPlanner().plan(
            registry.snapshot(),
            emptyList(),
        )

        assertEquals(listOf("a", "z"), plan.map { it.sensorId.value })
    }

    @Test
    fun worldGapCompilerTurnsOnlyDeclaredPerceptionCoverageIntoDemand() = runTest {
        val registry = registry()
        val gap = WorldGap.Perception(
            domain = FieldDomainId("finance"),
            missingDimensions = setOf(StateDimensionId("finance.balance.current")),
            reason = "balance-missing",
        )
        val plan = SensorWorldGapAttentionCompiler().compile(
            sensors = registry.snapshot(),
            gaps = listOf(gap),
            coverage = listOf(
                coverage(
                    dimensions = listOf(
                        SensorStateDimensionSelector(
                            SensorStateDimensionSelectorType.PREFIX,
                            "finance.balance.",
                        )
                    ),
                )
            ),
        )

        val compiled = plan.demands.single()
        assertEquals(sensorId, compiled.sensorId)
        assertEquals(700_000L, compiled.informationGainMicros)
        assertEquals(500_000L, compiled.goalRelevanceMicros)
        assertEquals(0L, compiled.verificationValueMicros)
        assertEquals(1, compiled.blockingGapCount)
        assertEquals(
            setOf(StateDimensionId("finance.balance.current")),
            compiled.stateDimensions,
        )
        assertEquals(listOf(gap.id), plan.matchedGapIdsBySensor.getValue(sensorId))
        assertTrue(plan.unmatchedObservationGapIds.isEmpty())
        assertTrue(plan.nonSensorGapIds.isEmpty())
        assertFalse(plan.observationGrantAuthority)
        assertFalse(plan.effectAuthority)
    }

    @Test
    fun worldGapCompilerKeepsUnmatchedAndCapabilityGapsExplicit() = runTest {
        val registry = registry()
        val perception = WorldGap.Perception(
            domain = FieldDomainId("health"),
            missingDimensions = setOf(StateDimensionId("health.sleep")),
            reason = "sleep-missing",
        )
        val capability = WorldGap.Capability(
            domain = FieldDomainId("health"),
            capabilityId = "health.resolve",
            reason = "provider-missing",
        )
        val plan = SensorWorldGapAttentionCompiler().compile(
            sensors = registry.snapshot(),
            gaps = listOf(capability, perception),
            coverage = listOf(
                coverage(
                    dimensions = listOf(
                        SensorStateDimensionSelector(
                            SensorStateDimensionSelectorType.EXACT,
                            "finance.balance",
                        )
                    ),
                )
            ),
        )

        assertTrue(plan.demands.isEmpty())
        assertEquals(listOf(perception.id), plan.unmatchedObservationGapIds)
        assertEquals(listOf(capability.id), plan.nonSensorGapIds)
    }

    @Test
    fun verificationGapUsesDeclaredObservationContractWithoutInventingState() = runTest {
        val registry = registry()
        val verification = WorldGap.Verification(
            domain = FieldDomainId("finance"),
            actionGraphId = "action:1",
            expectedStateContract = "finance.balance.expected",
            missingObservationContract = "finance.balance.readback",
            reason = "readback-required",
        )
        val plan = SensorWorldGapAttentionCompiler().compile(
            sensors = registry.snapshot(),
            gaps = listOf(verification),
            coverage = listOf(
                coverage(
                    dimensions = listOf(
                        SensorStateDimensionSelector(
                            SensorStateDimensionSelectorType.EXACT,
                            "finance.balance.current",
                        )
                    ),
                    observationContracts = setOf("finance.balance.readback"),
                )
            ),
        )

        val compiled = plan.demands.single()
        assertEquals(0L, compiled.informationGainMicros)
        assertEquals(800_000L, compiled.verificationValueMicros)
        assertTrue(compiled.stateDimensions.isEmpty())
        assertEquals(1, compiled.blockingGapCount)
    }

    private suspend fun registry(): AppSensorRegistry =
        AppSensorRegistry().also { it.register(descriptor(sensorId.value)) }

    private fun descriptor(id: String) = SensorDescriptor(
        sensorId = SensorId(id),
        sensorClass = SensorClass.APP_CONTENT,
        adapterVersion = "1",
        observationType = OwnerObservationType.APP_CONTENT,
        resourcePrefix = "provider:$id:",
        supportedSurfaces = setOf(ObservationSurfaceKind.CONTENT_PROVIDER),
        defaultMode = SensorAttentionMode.EVENT_DRIVEN,
    )

    private fun coverage(
        dimensions: List<SensorStateDimensionSelector>,
        observationContracts: Set<String> = emptySet(),
    ) = SensorAttentionCoverageProfile(
        sensorId = sensorId,
        stateDimensions = dimensions,
        observationContracts = observationContracts,
        informationGainMicros = 700_000L,
        goalRelevanceMicros = 500_000L,
        verificationValueMicros = 800_000L,
        energyCostMicros = 100_000L,
        privacyCostMicros = 100_000L,
        latencyCostMicros = 100_000L,
        resourceCostMicros = 100_000L,
    )

    private fun demand(
        blockingGapCount: Int = 0,
        information: Long = 0,
        goal: Long = 0,
        verification: Long = 0,
        energy: Long = 0,
        privacy: Long = 0,
        latency: Long = 0,
        resource: Long = 0,
    ) = SensorAttentionDemand(
        sensorId = sensorId,
        informationGainMicros = information,
        goalRelevanceMicros = goal,
        verificationValueMicros = verification,
        energyCostMicros = energy,
        privacyCostMicros = privacy,
        latencyCostMicros = latency,
        resourceCostMicros = resource,
        blockingGapCount = blockingGapCount,
        stateDimensions = setOf(StateDimensionId("finance.balance")),
    )
}
