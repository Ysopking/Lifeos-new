package app.lifeos.next.kernel

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidRuntimeTelemetryReaderTest {
    @Test
    fun negativeTrafficStatsAreProjectedAsUnknown() {
        val reader = reader(rx = -1L, tx = -1L)

        val snapshot = reader.read()

        assertNull(snapshot.uidRxBytes)
        assertNull(snapshot.uidTxBytes)
        assertEquals(256L, snapshot.heapUsedBytes)
        assertEquals(1024L, snapshot.heapMaxBytes)
        assertEquals(0.75, snapshot.heapHeadroom())
    }

    @Test
    fun localCountersAreReadExactlyWithoutPermissionsOrMutationHooks() {
        val reader = reader(rx = 123L, tx = 456L)

        val snapshot = reader.read()

        assertEquals(777L, snapshot.elapsedRealtimeNanos)
        assertEquals(88L, snapshot.processCpuTimeMillis)
        assertEquals(64L, snapshot.nativeHeapAllocatedBytes)
        assertEquals(9, snapshot.activeThreadCount)
        assertEquals(123L, snapshot.uidRxBytes)
        assertEquals(456L, snapshot.uidTxBytes)
    }

    private fun reader(rx: Long, tx: Long) = AndroidRuntimeTelemetryReader(
        now = { Instant.parse("2026-09-19T12:00:00Z") },
        elapsedRealtimeNanos = { 777L },
        processCpuTimeMillis = { 88L },
        heapMetrics = {
            AndroidRuntimeTelemetryReader.HeapMetrics(
                totalBytes = 512L,
                freeBytes = 256L,
                maxBytes = 1024L,
            )
        },
        nativeHeapAllocatedBytes = { 64L },
        activeThreadCount = { 9 },
        processUid = { 42 },
        uidRxBytes = { uid ->
            assertEquals(42, uid)
            rx
        },
        uidTxBytes = { uid ->
            assertEquals(42, uid)
            tx
        },
    )
}
