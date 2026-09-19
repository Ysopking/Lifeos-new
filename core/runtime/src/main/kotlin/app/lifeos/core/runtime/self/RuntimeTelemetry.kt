package app.lifeos.core.runtime.self

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

data class RuntimeTrafficDelta(
    val elapsedRealtimeNanos: Long,
    val rxBytes: Long?,
    val txBytes: Long?,
) {
    init {
        require(elapsedRealtimeNanos >= 0L)
        require(rxBytes == null || rxBytes >= 0L)
        require(txBytes == null || txBytes >= 0L)
    }
}

data class RuntimeTelemetrySnapshot(
    val observedAt: Instant,
    val elapsedRealtimeNanos: Long,
    val processCpuTimeMillis: Long,
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
    val nativeHeapAllocatedBytes: Long,
    val activeThreadCount: Int,
    val uidRxBytes: Long?,
    val uidTxBytes: Long?,
) {
    init {
        require(elapsedRealtimeNanos >= 0L)
        require(processCpuTimeMillis >= 0L)
        require(heapUsedBytes >= 0L)
        require(heapMaxBytes > 0L)
        require(heapUsedBytes <= heapMaxBytes) {
            "Heap used bytes must not exceed the process heap maximum"
        }
        require(nativeHeapAllocatedBytes >= 0L)
        require(activeThreadCount >= 0)
        require(uidRxBytes == null || uidRxBytes >= 0L)
        require(uidTxBytes == null || uidTxBytes >= 0L)
    }

    fun heapHeadroom(): Double =
        (1.0 - heapUsedBytes.toDouble() / heapMaxBytes.toDouble()).coerceIn(0.0, 1.0)

    fun trafficDelta(previous: RuntimeTelemetrySnapshot): RuntimeTrafficDelta? {
        if (elapsedRealtimeNanos < previous.elapsedRealtimeNanos) return null
        val rx = monotonicDelta(uidRxBytes, previous.uidRxBytes)
        val tx = monotonicDelta(uidTxBytes, previous.uidTxBytes)
        return RuntimeTrafficDelta(
            elapsedRealtimeNanos = elapsedRealtimeNanos - previous.elapsedRealtimeNanos,
            rxBytes = rx,
            txBytes = tx,
        )
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "lifeos-runtime-telemetry/v1",
        observedAt.toString(),
        elapsedRealtimeNanos.toString(),
        processCpuTimeMillis.toString(),
        heapUsedBytes.toString(),
        heapMaxBytes.toString(),
        nativeHeapAllocatedBytes.toString(),
        activeThreadCount.toString(),
        uidRxBytes?.toString().orEmpty(),
        uidTxBytes?.toString().orEmpty(),
    )

    private fun monotonicDelta(current: Long?, previous: Long?): Long? {
        if (current == null || previous == null || current < previous) return null
        return current - previous
    }
}
