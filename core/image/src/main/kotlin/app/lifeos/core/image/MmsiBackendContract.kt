package app.lifeos.core.image

/**
 * Contract shared by the reference implementation and future native SIMD/Vulkan backends.
 * Runtime selection must never change the mathematical meaning of a request.
 */
interface MmsiBackendExecutor {
    val backend: MmsiBackend
    fun process(request: MmsiImageEngine.Request): MmsiPipelineResult
}

class CpuReferenceMmsiBackend(
    private val engine: MmsiImageEngine = MmsiImageEngine(),
) : MmsiBackendExecutor {
    override val backend: MmsiBackend = MmsiBackend.CPU_REFERENCE
    override fun process(request: MmsiImageEngine.Request): MmsiPipelineResult = engine.process(request)
}

data class MmsiExecutionMetrics(
    val elapsedMs: Double,
    val estimatedPeakRamBytes: Long,
    val backend: MmsiBackend,
) {
    init {
        require(elapsedMs >= 0.0)
        require(estimatedPeakRamBytes >= 0L)
    }

    fun satisfies(budget: MmsiPerformanceBudget): Boolean =
        elapsedMs <= budget.totalLatencyMs && estimatedPeakRamBytes <= budget.peakRamBytes
}
