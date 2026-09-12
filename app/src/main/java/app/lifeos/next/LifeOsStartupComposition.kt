package app.lifeos.next

/**
 * JVM-safe startup seam for the process composition owned by [LifeOsApplication].
 *
 * The hooks deliberately contain no Android types. Unit tests can therefore prove that process-wide
 * registries and recovery runtimes are installed before kernel execution is exposed, while the
 * Android application remains responsible for constructing the concrete dependencies.
 */
internal data class LifeOsStartupHooks(
    val installSharedResourceRuntime: () -> Unit,
    val installGoalExecutionRuntime: () -> Unit,
    val createKernel: () -> Unit,
    val installDeepSearchRuntime: () -> Unit,
    val startSelfHealingRuntime: () -> Unit,
    val installDurableGoalPlanRuntime: () -> Unit,
    val startKernel: () -> Unit,
)

internal object LifeOsStartupComposition {
    fun start(hooks: LifeOsStartupHooks) {
        hooks.installSharedResourceRuntime()
        hooks.installGoalExecutionRuntime()
        hooks.createKernel()
        hooks.installDeepSearchRuntime()
        hooks.startSelfHealingRuntime()
        hooks.installDurableGoalPlanRuntime()
        hooks.startKernel()
    }
}
