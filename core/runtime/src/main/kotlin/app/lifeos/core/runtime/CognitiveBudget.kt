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
)

data class CognitiveBudget(
    val maxParallelism: Int,
    val maxHotPhotons: Int,
    val recomputeBudgetMicros: Long,
    val enrichmentBudgetMicros: Long,
    val allowGlobalConvergence: Boolean,
    val allowBackgroundEnrichment: Boolean,
)

class CognitiveBudgetCompiler {
    fun compile(state: HardwareState, foregroundConversation: Boolean): CognitiveBudget {
        val constrained = state.memoryPressureMicros >= 800_000L || state.thermalPressureMicros >= 800_000L || state.batteryMicros <= 150_000L
        val parallelism = if (foregroundConversation) 1 else state.availableCores.coerceIn(1, if (constrained) 2 else 8)
        val hot = when {
            state.availableRamBytes < 512L * 1024 * 1024 -> 256
            state.availableRamBytes < 2L * 1024 * 1024 * 1024 -> 1_024
            else -> 4_096
        }
        return CognitiveBudget(
            maxParallelism = parallelism,
            maxHotPhotons = hot,
            recomputeBudgetMicros = if (foregroundConversation) 100_000L else if (constrained) 20_000L else 500_000L,
            enrichmentBudgetMicros = if (constrained) 0L else 1_000_000L,
            allowGlobalConvergence = !foregroundConversation && !constrained,
            allowBackgroundEnrichment = state.charging && !constrained,
        )
    }
}
