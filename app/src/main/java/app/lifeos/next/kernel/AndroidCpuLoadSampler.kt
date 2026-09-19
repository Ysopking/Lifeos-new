package app.lifeos.next.kernel

import android.os.Process
import android.os.SystemClock

internal data class ProcessCpuObservation(
    val elapsedCpuMillis: Long,
    val wallClockMillis: Long,
    val logicalProcessorCount: Int,
    val loadFraction: Double,
) {
    init {
        require(elapsedCpuMillis >= 0L)
        require(wallClockMillis > 0L)
        require(logicalProcessorCount > 0)
        require(loadFraction.isFinite() && loadFraction in 0.0..1.0)
    }
}

/**
 * Permission-free process CPU sampler. Android's elapsed process CPU time is divided by elapsed
 * monotonic wall time and by the process-visible logical processor count.
 */
internal class AndroidCpuLoadSampler(
    private val elapsedCpuTimeMillis: () -> Long = Process::getElapsedCpuTime,
    private val wallClockMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private var previousElapsedCpuTime: Long? = null
    private var previousWallClock: Long? = null

    @Synchronized
    fun sample(logicalProcessorCount: Int): ProcessCpuObservation? {
        require(logicalProcessorCount > 0)
        val cpuNow = elapsedCpuTimeMillis()
        val wallNow = wallClockMillis()
        val cpuBefore = previousElapsedCpuTime
        val wallBefore = previousWallClock
        previousElapsedCpuTime = cpuNow
        previousWallClock = wallNow

        if (cpuBefore == null || wallBefore == null) return null
        val cpuDelta = cpuNow - cpuBefore
        val wallDelta = wallNow - wallBefore
        if (cpuDelta < 0L || wallDelta <= 0L) return null

        val denominator = wallDelta.toDouble() * logicalProcessorCount.toDouble()
        val load = if (denominator <= 0.0) 0.0 else (cpuDelta.toDouble() / denominator).coerceIn(0.0, 1.0)
        return ProcessCpuObservation(
            elapsedCpuMillis = cpuDelta,
            wallClockMillis = wallDelta,
            logicalProcessorCount = logicalProcessorCount,
            loadFraction = load,
        )
    }
}
