package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.runtime.resource.HardwareBudgetMode
import app.lifeos.core.runtime.resource.HardwareThermalState
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.world.WorldFormulaExecutionState
import app.lifeos.core.runtime.world.WorldFormulaStatus
import app.lifeos.next.kernel.AndroidHardwareStateReader
import app.lifeos.next.kernel.HardwareResourceDecision
import app.lifeos.next.kernel.HardwareResourceIntelligenceRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V17 device proof for the productive hardware-adaptive resource path. The test reads real Android
 * battery, thermal, RAM and storage signals, then proves that those measurements flow through the
 * persisted World Formula before the optimizer can recommend work. No synthetic capacity is added.
 */
@RunWith(AndroidJUnit4::class)
class HardwareResourceIntelligenceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun localSignalsFeedPersistedWorldFormulaWithoutExpandingHardQuota() = runBlocking {
        val context = instrumentation.targetContext
        val observed = AndroidHardwareStateReader(context).read()

        assertTrue(observed.availableProcessors > 0)
        assertNotNull(observed.batteryFraction)
        assertNotNull(observed.charging)
        assertTrue(observed.thermalState != HardwareThermalState.UNKNOWN)
        assertNotNull(observed.availableMemoryBytes)
        assertNotNull(observed.totalMemoryBytes)
        assertNotNull(observed.availableStorageBytes)
        assertNotNull(observed.totalStorageBytes)
        assertTrue(observed.availableMemoryBytes!! in 0L..observed.totalMemoryBytes!!)
        assertTrue(observed.availableStorageBytes!! in 0L..observed.totalStorageBytes!!)

        val hardQuota = ResourceBudgetQuota(
            elapsedMillis = 15_000L,
            workUnits = 1_000L,
            memoryBytes = 64L * 1024L * 1024L,
            ioBytes = 16L * 1024L * 1024L,
            networkBytes = 8L * 1024L * 1024L,
            candidates = 100L,
        )
        val requested = ResourceBudgetUsage(
            elapsedMillis = 5_000L,
            workUnits = 100L,
            memoryBytes = 8L * 1024L * 1024L,
            ioBytes = 2L * 1024L * 1024L,
            networkBytes = 1L * 1024L * 1024L,
            candidates = 10L,
        )

        val decision = HardwareResourceIntelligenceRuntime(context).evaluate(
            hardQuota = hardQuota,
            requested = requested,
        )
        assertTrue(
            "Healthy emulator hardware must produce a persisted resource decision, got $decision",
            decision is HardwareResourceDecision.Ready,
        )
        val ready = decision as HardwareResourceDecision.Ready

        assertEquals(WorldFormulaExecutionState.COMPLETED, ready.world.state)
        assertEquals(WorldFormulaStatus.CONVERGED, ready.world.status)
        assertTrue(ready.world.persisted)
        assertEquals(ready.hardware.fingerprint(), ready.plan.hardwareSnapshotFingerprint)
        assertTrue(ready.plan.mode != HardwareBudgetMode.SUSPENDED)
        assertQuotaDoesNotExpand(ready.plan.effectiveQuota, hardQuota)
        assertTrue(ready.plan.recommendedReservation.isWithin(requested))
    }

    private fun assertQuotaDoesNotExpand(
        effective: ResourceBudgetQuota,
        hard: ResourceBudgetQuota,
    ) {
        assertTrue(effective.elapsedMillis <= hard.elapsedMillis)
        assertTrue(effective.workUnits <= hard.workUnits)
        assertTrue(effective.memoryBytes <= hard.memoryBytes)
        assertTrue(effective.ioBytes <= hard.ioBytes)
        assertTrue(effective.networkBytes <= hard.networkBytes)
        assertTrue(effective.candidates <= hard.candidates)
    }
}
