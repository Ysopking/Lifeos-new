package app.lifeos.core.runtime.boot

/** One ordered boot-time runtime-state restore step. */
fun interface RuntimeStateRehydrationStep {
    suspend fun rehydrate()
}

/**
 * Runs the existing runtime restore first and then additional state restore steps in strict order.
 * Any failure is propagated immediately so BootCoordinator fails before the runtime supervisor can
 * be started by the app composition root.
 */
class ChainedStateRehydrator(
    private val primary: StateRehydrator,
    private val additionalSteps: List<RuntimeStateRehydrationStep>,
) : StateRehydrator {
    override suspend fun rehydrate(): RehydratedRuntimeState {
        val restored = primary.rehydrate()
        for (step in additionalSteps) {
            step.rehydrate()
        }
        return restored
    }
}
