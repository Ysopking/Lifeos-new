package app.lifeos.next

/** Ordered process lifecycle stages exposed to the unified LIFEOS runtime topology. */
internal enum class LifeOsStartupStage {
    SHARED_RESOURCES,
    GOAL_EXECUTION,
    KERNEL_GRAPH,
    DEEP_SEARCH,
    SELF_HEALING,
    DURABLE_GOALS,
    RUNTIME_STARTED,
}

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
    val stageObserver: (LifeOsStartupStage) -> Unit = {},
)

internal object LifeOsStartupComposition {
    fun start(hooks: LifeOsStartupHooks) {
        runStage(LifeOsStartupStage.SHARED_RESOURCES, hooks.installSharedResourceRuntime, hooks.stageObserver)
        runStage(LifeOsStartupStage.GOAL_EXECUTION, hooks.installGoalExecutionRuntime, hooks.stageObserver)
        runStage(LifeOsStartupStage.KERNEL_GRAPH, hooks.createKernel, hooks.stageObserver)
        runStage(LifeOsStartupStage.DEEP_SEARCH, hooks.installDeepSearchRuntime, hooks.stageObserver)
        runStage(LifeOsStartupStage.SELF_HEALING, hooks.startSelfHealingRuntime, hooks.stageObserver)
        runStage(LifeOsStartupStage.DURABLE_GOALS, hooks.installDurableGoalPlanRuntime, hooks.stageObserver)
        runStage(LifeOsStartupStage.RUNTIME_STARTED, hooks.startKernel, hooks.stageObserver)
    }

    private fun runStage(
        stage: LifeOsStartupStage,
        action: () -> Unit,
        observer: (LifeOsStartupStage) -> Unit,
    ) {
        action()
        observer(stage)
    }
}
