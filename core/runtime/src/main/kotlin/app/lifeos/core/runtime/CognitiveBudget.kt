package app.lifeos.core.runtime

data class HardwareState(
    val availableCores: Int,
    val availableRamBytes: Long,
    val memoryPressureMicros: Long,
    val batteryMicros: Long,
    val charging: Boolean,
    val thermalPressureMicros: Long,
    val storageAvailableBytes: Long,
    val acceleratorAvailable: Boolean,
    val idle: Boolean = false,
) {
    init {
        require(availableCores > 0)
        require(availableRamBytes >= 0L)
        require(memoryPressureMicros in 0L..MICROS)
        require(batteryMicros in 0L..MICROS)
        require(thermalPressureMicros in 0L..MICROS)
        require(storageAvailableBytes >= 0L)
    }
}

data class CognitiveBudget(
    val maxParallelism: Int,
    val maxHotPhotons: Int,
    val recomputeBudgetMicros: Long,
    val enrichmentBudgetMicros: Long,
    val allowGlobalConvergence: Boolean,
    val allowBackgroundEnrichment: Boolean,
)

enum class CognitiveWorkload {
    CHAT,
    FIELD,
    INGEST,
    SEARCH,
    CREATIVE,
    GC,
    EVOLUTION,
}

/**
 * Hardware compiles into compute budgets only. It must never alter evidence, confidence, owner
 * policy, authority or the semantic result of a completed deterministic calculation.
 */
class CognitiveBudgetCompiler {
    /** Compatibility entrypoint retained for existing callers. */
    fun compile(state: HardwareState, foregroundConversation: Boolean): CognitiveBudget =
        compile(
            state = state,
            workload = if (foregroundConversation) CognitiveWorkload.CHAT else CognitiveWorkload.FIELD,
        )

    fun compile(
        state: HardwareState,
        workload: CognitiveWorkload,
    ): CognitiveBudget {
        val thermalHot = state.thermalPressureMicros >= 800_000L
        val memoryHot = state.memoryPressureMicros >= 800_000L
        val batteryLow = !state.charging && state.batteryMicros <= 150_000L
        val constrained = thermalHot || memoryHot || batteryLow

        val maxParallelism = when (workload) {
            CognitiveWorkload.CHAT -> 1
            CognitiveWorkload.FIELD -> state.availableCores.coerceIn(1, if (constrained) 2 else 8)
            CognitiveWorkload.INGEST -> state.availableCores.coerceIn(1, if (constrained) 1 else 4)
            CognitiveWorkload.SEARCH -> state.availableCores.coerceIn(1, if (constrained) 1 else 4)
            CognitiveWorkload.CREATIVE -> state.availableCores.coerceIn(1, if (constrained) 2 else 6)
            CognitiveWorkload.GC -> state.availableCores.coerceIn(1, if (constrained) 1 else 2)
            CognitiveWorkload.EVOLUTION -> state.availableCores.coerceIn(1, if (constrained) 1 else 4)
        }

        val maxHotPhotons = when {
            state.availableRamBytes < 512L * MIB -> 256
            state.availableRamBytes < 2L * GIB -> 1_024
            state.availableRamBytes < 6L * GIB -> 4_096
            else -> 8_192
        }

        val backgroundWindow = state.charging && state.idle && !thermalHot && !memoryHot
        val deepComputeAllowed = !constrained && when (workload) {
            CognitiveWorkload.CHAT -> false
            CognitiveWorkload.FIELD -> true
            CognitiveWorkload.INGEST -> backgroundWindow
            CognitiveWorkload.SEARCH -> !batteryLow
            CognitiveWorkload.CREATIVE -> !thermalHot
            CognitiveWorkload.GC -> backgroundWindow
            CognitiveWorkload.EVOLUTION -> backgroundWindow
        }

        val recomputeBudgetMicros = when (workload) {
            CognitiveWorkload.CHAT -> 100_000L
            CognitiveWorkload.FIELD -> if (constrained) 40_000L else 500_000L
            CognitiveWorkload.INGEST -> if (deepComputeAllowed) 350_000L else 40_000L
            CognitiveWorkload.SEARCH -> if (deepComputeAllowed) 600_000L else 100_000L
            CognitiveWorkload.CREATIVE -> if (deepComputeAllowed) 700_000L else 150_000L
            CognitiveWorkload.GC -> if (deepComputeAllowed) 300_000L else 20_000L
            CognitiveWorkload.EVOLUTION -> if (deepComputeAllowed) 800_000L else 20_000L
        }

        val enrichmentBudgetMicros = when (workload) {
            CognitiveWorkload.CHAT -> 0L
            CognitiveWorkload.FIELD -> if (constrained) 0L else 250_000L
            CognitiveWorkload.INGEST -> if (deepComputeAllowed) 1_000_000L else 0L
            CognitiveWorkload.SEARCH -> if (deepComputeAllowed) 750_000L else 100_000L
            CognitiveWorkload.CREATIVE -> if (deepComputeAllowed) 1_000_000L else 200_000L
            CognitiveWorkload.GC -> 0L
            CognitiveWorkload.EVOLUTION -> if (deepComputeAllowed) 1_000_000L else 0L
        }

        return CognitiveBudget(
            maxParallelism = maxParallelism,
            maxHotPhotons = maxHotPhotons,
            recomputeBudgetMicros = recomputeBudgetMicros,
            enrichmentBudgetMicros = enrichmentBudgetMicros,
            allowGlobalConvergence = workload == CognitiveWorkload.FIELD && deepComputeAllowed,
            allowBackgroundEnrichment = when (workload) {
                CognitiveWorkload.INGEST,
                CognitiveWorkload.GC,
                CognitiveWorkload.EVOLUTION -> backgroundWindow
                CognitiveWorkload.FIELD,
                CognitiveWorkload.SEARCH,
                CognitiveWorkload.CREATIVE -> deepComputeAllowed
                CognitiveWorkload.CHAT -> false
            },
        )
    }

    private companion object {
        const val MICROS = 1_000_000L
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * MIB
    }
}
