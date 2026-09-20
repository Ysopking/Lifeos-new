package app.lifeos.core.runtime.boot

enum class BootPhaseId(
    val stableId: String,
) {
    RUNTIME_BOOTSTRAP("runtime-bootstrap"),
    STORE_VERIFY("store-verify"),
    RUNTIME_REHYDRATE("runtime-rehydrate"),
    PHOTON_REHYDRATE("photon-rehydrate"),
    MODULE_REHYDRATE("module-rehydrate"),
    THOUGHT_MATRIX_WARMUP("thought-matrix-warmup"),
    CAPABILITY_WARMUP("capability-warmup"),
    DELTA_DETECT("delta-detect"),
    VALIDATE("validate"),
}

data class BootPhaseTiming(
    val phase: String,
    val startedNanos: Long,
    val finishedNanos: Long,
) {
    init {
        require(phase.isNotBlank()) { "Boot phase must not be blank" }
        require(finishedNanos >= startedNanos) {
            "Boot phase must not finish before it starts"
        }
    }

    val elapsedNanos: Long
        get() = finishedNanos - startedNanos

    val elapsedMillis: Long
        get() = elapsedNanos / 1_000_000L
}

data class BootPerformanceSnapshot(
    val timings: List<BootPhaseTiming>,
) {
    init {
        require(timings.map { it.phase }.distinct().size == timings.size) {
            "Boot phase timings must contain at most one sample per phase"
        }
    }

    val totalMeasuredNanos: Long
        get() = timings.sumOf(BootPhaseTiming::elapsedNanos)

    val totalMeasuredMillis: Long
        get() = totalMeasuredNanos / 1_000_000L

    fun timing(id: BootPhaseId): BootPhaseTiming? =
        timings.firstOrNull { it.phase == id.stableId }

    companion object {
        val EMPTY = BootPerformanceSnapshot(emptyList())
    }
}

class BootPerformanceRecorder(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private val samples = linkedMapOf<String, BootPhaseTiming>()

    fun reset() {
        synchronized(lock) {
            samples.clear()
        }
    }

    suspend fun <T> measure(
        id: BootPhaseId,
        block: suspend () -> T,
    ): T {
        val start = nanoTime()
        try {
            return block()
        } finally {
            val end = nanoTime()
            val timing = BootPhaseTiming(
                phase = id.stableId,
                startedNanos = start,
                finishedNanos = end,
            )
            synchronized(lock) {
                check(id.stableId !in samples) {
                    "Boot phase measured more than once: ${id.stableId}"
                }
                samples[id.stableId] = timing
            }
        }
    }

    fun snapshot(): BootPerformanceSnapshot = synchronized(lock) {
        BootPerformanceSnapshot(samples.values.toList())
    }
}
