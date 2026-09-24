package app.lifeos.next.kernel

import app.lifeos.core.runtime.life.AppSensorRegistry
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.SensorAttentionMode
import app.lifeos.core.runtime.life.SensorClass
import app.lifeos.core.runtime.life.SensorDescriptor
import app.lifeos.core.runtime.life.SensorId
import app.lifeos.core.runtime.life.SensorRuntimeState
import app.lifeos.core.runtime.policy.OwnerObservationPolicyEvent
import app.lifeos.core.runtime.policy.OwnerObservationPolicyLedger
import app.lifeos.core.runtime.policy.OwnerObservationPolicyRepository
import app.lifeos.core.runtime.policy.OwnerObservationPolicyRepositoryLoadReport
import app.lifeos.core.runtime.policy.OwnerObservationType
import app.lifeos.core.runtime.thought.ThoughtGraphAttentionPolicy
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet
import app.lifeos.core.runtime.world.SensorAttentionDemand
import app.lifeos.core.runtime.world.StateDimensionId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProductivePerceptionContextRuntimeTest {
    private val sensorId = SensorId("test-app-sensor")
    private val descriptor = SensorDescriptor(
        sensorId = sensorId,
        sensorClass = SensorClass.APP_CONTENT,
        adapterVersion = "1",
        observationType = OwnerObservationType.APP_CONTENT,
        resourcePrefix = "test-app:",
        supportedSurfaces = setOf(ObservationSurfaceKind.CONTENT_PROVIDER),
        defaultMode = SensorAttentionMode.EVENT_DRIVEN,
    )

    @Test
    fun freezeBindsExactRegistryAndDurableObservationPolicyRevision() = runTest {
        val registry = AppSensorRegistry(listOf(SensorRuntimeState(descriptor)))
        val runtime = ProductivePerceptionContextRuntime(
            ownerObservationPolicy = OwnerObservationPolicyLedger(EmptyPolicyRepository()),
            sensorRegistry = registry,
        )
        val workingSet = workingSet()

        val first = runtime.freeze(workingSet)
        val second = runtime.freeze(workingSet)
        val sensorFingerprint = registry.snapshot().fingerprint()

        assertEquals(first, second)
        assertTrue(first.personalContextSnapshotId.startsWith("personal-context:"))
        assertEquals(sensorFingerprint, first.sensorRegistryFingerprint)
        assertEquals(0L, first.ownerObservationPolicyRevision)

        registry.updateMode(sensorId, SensorAttentionMode.PERIODIC)
        val changed = runtime.freeze(workingSet)
        assertNotEquals(first.sensorRegistryFingerprint, changed.sensorRegistryFingerprint)
        assertNotEquals(first.personalContextSnapshotId, changed.personalContextSnapshotId)
    }

    @Test
    fun attentionRuntimeChangesRegistryModeWithoutMintingAuthority() = runTest {
        val registry = AppSensorRegistry(listOf(SensorRuntimeState(descriptor)))
        val runtime = ProductivePerceptionContextRuntime(
            ownerObservationPolicy = OwnerObservationPolicyLedger(EmptyPolicyRepository()),
            sensorRegistry = registry,
        )

        val decision = runtime.applyAttention(
            listOf(
                SensorAttentionDemand(
                    sensorId = sensorId,
                    informationGainMicros = 700_000L,
                    goalRelevanceMicros = 800_000L,
                    verificationValueMicros = 0L,
                    energyCostMicros = 10_000L,
                    privacyCostMicros = 10_000L,
                    latencyCostMicros = 10_000L,
                    resourceCostMicros = 10_000L,
                    blockingGapCount = 1,
                    stateDimensions = setOf(StateDimensionId("test.dimension")),
                )
            )
        ).single()

        assertEquals(SensorAttentionMode.FOCUSED, decision.mode)
        assertEquals(SensorAttentionMode.FOCUSED, registry.state(sensorId)?.mode)
        assertEquals(false, decision.observationGrantAuthority)
        assertEquals(false, decision.effectAuthority)
    }

    private fun workingSet() = ThoughtGraphWorkingSet(
        sourceSnapshotId = "goal-thought-snapshot:test",
        sourceRevision = 7L,
        sourceHistoryFingerprint = "history-fingerprint",
        asOf = Instant.parse("2026-09-25T00:00:00Z"),
        policy = ThoughtGraphAttentionPolicy(),
        entries = emptyList(),
        nodes = emptyList(),
        edges = emptyList(),
        conflicts = emptyList(),
    )

    private class EmptyPolicyRepository : OwnerObservationPolicyRepository {
        override suspend fun loadReport(): OwnerObservationPolicyRepositoryLoadReport =
            OwnerObservationPolicyRepositoryLoadReport(emptyList())

        override suspend fun append(
            expectedRevision: Long,
            event: OwnerObservationPolicyEvent,
        ): Boolean = false
    }
}
