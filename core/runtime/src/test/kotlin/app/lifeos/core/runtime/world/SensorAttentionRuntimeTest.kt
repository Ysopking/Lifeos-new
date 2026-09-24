package app.lifeos.core.runtime.world

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
