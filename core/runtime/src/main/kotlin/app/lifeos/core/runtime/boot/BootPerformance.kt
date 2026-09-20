package app.lifeos.core.runtime.boot

enum class BootPhaseId(val stableId: String) {
    RUNTIME_BOOTSTRAP("runtime-bootstrap"),
    STORE_VERIFY("store-verify"),
    STATE_REHYDRATE("state-rehydrate"),
    PHOTON_REHYDRATE("photon-rehydrate"),
    MODULE_REHYDRATE("module-rehydrate"),
    THOUGHT_MATRIX_WARMUP("thought-matrix-warmup"),
    CAPABILITY_WARMUP("capability-warmup"),
    DELTA_DETECT("delta-detect"),
    VALIDATE("validate"),
}

data class BootPhaseTiming(
    val phase: BootPhaseId,
    val startedNanos: Long,
    val finishedNanos: Long,
) {
    init {
        require(finishedNanos >= startedNanos) {
            "Boot phase timing must not finish before it starts"
        }
    }

    val elapsedNanos: Long get() = finishedNanos - startedNanos
    val elapsedMillis: Long get() = elapsedNanos / 1_000_000L
}

data class BootPerformanceSnapshot(
    val timings: List<BootPhaseTiming>,
) {
    init {
        require(timings.map { it.phase }.distinct().size == timings.size) {
            "Boot performance snapshot may contain each phase only once"
        }
    }

    val wallClockNanos: Long
        get() {
            if (timings.isEmpty()) return 0L
            val first = timings.minOf { it.startedNanos }
            val last = timings.maxOf { it.finishedNanos }
            return last - first
        }

    val wallClockMillis: Long get() = wallClockNanos / 1_000_000L
}

class BootPerformanceRecorder(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private val samples = linkedMapOf<BootPhaseId, BootPhaseTiming>()

    suspend fun <T> measure(
        phase: BootPhaseId,
        block: suspend () -> T,
    ): T {
        val started = nanoTime()
        try {
            return block()
        } finally {
            val finished = nanoTime()
            val timing = BootPhaseTiming(
                phase = phase,
                startedNanos = started,
                finishedNanos = finished,
            )
            synchronized(lock) {
                check(phase !in samples) {
                    "Boot phase already measured: ${phase.stableId}"
                }
                samples[phase] = timing
            }
        }
    }

    fun snapshot(): BootPerformanceSnapshot = synchronized(lock) {
        BootPerformanceSnapshot(
            timings = samples.values
                .sortedWith(
                    compareBy<BootPhaseTiming> { it.startedNanos }
                        .thenBy { it.phase.stableId }
                )
        )
    }

    fun reset() {
        synchronized(lock) {
            samples.clear()
        }
    }
}
