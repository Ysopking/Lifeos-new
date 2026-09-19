package app.lifeos.next.kernel

import app.lifeos.core.data.LiveSourceSyncSnapshot
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.runtime.CognitiveSnapshot
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatus
import app.lifeos.core.runtime.health.HealthSnapshot
import app.lifeos.core.runtime.resource.HardwareStateSnapshot
import app.lifeos.core.runtime.resource.HardwareThermalState
import app.lifeos.core.runtime.self.SelfObservationAuthorityReader
import app.lifeos.core.runtime.world.ProductiveWorldHead
import app.lifeos.core.runtime.world.WorldEquationHead
import app.lifeos.core.runtime.boot.BootEngineCycle
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfObservationRuntimeTest {
    @Test
    fun captureReadsSourcesWithoutMutatingThemAndKeepsMissingMemoryUnavailable() = runTest {
        var photonReads = 0
        var toolReads = 0
        val now = Instant.parse("2026-09-19T12:00:00Z")
        val runtime = SelfObservationRuntime(
            photonIndex = {
                photonReads += 1
                PhotonIndexReport(3, 0, 0, 0, emptyMap())
            },
            memorySnapshot = { null },
            authorityReader = object : SelfObservationAuthorityReader {
                override suspend fun loadProductiveWorldHead(): ProductiveWorldHead? = null
                override suspend fun loadWorldEquationHead(): WorldEquationHead? = null
                override suspend fun loadCommittedBootCycle(): BootEngineCycle? = null
                override suspend fun loadCognitiveSnapshot(): CognitiveSnapshot? = null
            },
            topologySnapshot = { null },
            healthSnapshot = { HealthSnapshot(emptyList(), now) },
            hardwareSnapshot = {
                HardwareStateSnapshot(
                    observedAt = now,
                    availableProcessors = 4,
                    thermalState = HardwareThermalState.NOMINAL,
                )
            },
            toolStatus = {
                toolReads += 1
                GeneratedToolRuntimeStatus(emptyList())
            },
            activeRepairs = { emptyList() },
            liveSourceSnapshot = { LiveSourceSyncSnapshot(emptyList()) },
            liveSourceFailure = { null },
            runtimeTelemetry = AndroidRuntimeTelemetryReader(
                now = { now },
                elapsedRealtimeNanos = { 100L },
                processCpuTimeMillis = { 10L },
                heapMetrics = {
                    AndroidRuntimeTelemetryReader.HeapMetrics(
                        totalBytes = 100L,
                        freeBytes = 50L,
                        maxBytes = 200L,
                    )
                },
                nativeHeapAllocatedBytes = { 25L },
                activeThreadCount = { 4 },
                processUid = { 1 },
                uidRxBytes = { -1L },
                uidTxBytes = { -1L },
            ),
            now = { now },
        )

        val result = runtime.capture()

        assertEquals(1, photonReads)
        assertEquals(1, toolReads)
        assertNull(result.snapshot.memory.authoritativePhotonCount)
        assertEquals(0, result.snapshot.liveSources.sourceCount)
        assertEquals(0.75, result.snapshot.runtime.telemetry?.heapHeadroom())
        assertNull(result.snapshot.runtime.telemetry?.uidRxBytes)
        assertTrue(result.issues.any { it.detail == "life-memory-not-projected" })
        assertTrue(result.issues.any { it.detail == "runtime-topology-not-ready" })
    }
}
