package app.lifeos.next.kernel

import android.net.TrafficStats
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import app.lifeos.core.runtime.self.RuntimeTelemetrySnapshot
import java.time.Instant

/**
 * Permission-free local process telemetry. Reads only this process and this UID; no root access,
 * foreign-process inspection, raw performance counters or additional Android permissions.
 */
internal class AndroidRuntimeTelemetryReader(
    private val now: () -> Instant = Instant::now,
    private val elapsedRealtimeNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val processCpuTimeMillis: () -> Long = { Process.getElapsedCpuTime() },
    private val heapMetrics: () -> HeapMetrics = {
        Runtime.getRuntime().let { runtime ->
            HeapMetrics(
                totalBytes = runtime.totalMemory(),
                freeBytes = runtime.freeMemory(),
                maxBytes = runtime.maxMemory(),
            )
        }
    },
    private val nativeHeapAllocatedBytes: () -> Long = { Debug.getNativeHeapAllocatedSize() },
    private val activeThreadCount: () -> Int = { Thread.activeCount() },
    private val processUid: () -> Int = { Process.myUid() },
    private val uidRxBytes: (Int) -> Long = { uid -> TrafficStats.getUidRxBytes(uid) },
    private val uidTxBytes: (Int) -> Long = { uid -> TrafficStats.getUidTxBytes(uid) },
) {
    internal data class HeapMetrics(
        val totalBytes: Long,
        val freeBytes: Long,
        val maxBytes: Long,
    ) {
        init {
            require(totalBytes >= 0L)
            require(freeBytes >= 0L)
            require(maxBytes > 0L)
            require(freeBytes <= totalBytes)
            require(totalBytes <= maxBytes)
        }

        val usedBytes: Long
            get() = totalBytes - freeBytes
    }

    fun read(): RuntimeTelemetrySnapshot {
        val heap = heapMetrics()
        val uid = processUid()
        return RuntimeTelemetrySnapshot(
            observedAt = now(),
            elapsedRealtimeNanos = elapsedRealtimeNanos().coerceAtLeast(0L),
            processCpuTimeMillis = processCpuTimeMillis().coerceAtLeast(0L),
            heapUsedBytes = heap.usedBytes,
            heapMaxBytes = heap.maxBytes,
            nativeHeapAllocatedBytes = nativeHeapAllocatedBytes().coerceAtLeast(0L),
            activeThreadCount = activeThreadCount().coerceAtLeast(0),
            uidRxBytes = trafficOrUnknown(uidRxBytes(uid)),
            uidTxBytes = trafficOrUnknown(uidTxBytes(uid)),
        )
    }

    private fun trafficOrUnknown(value: Long): Long? =
        value.takeIf { it >= 0L }
}
