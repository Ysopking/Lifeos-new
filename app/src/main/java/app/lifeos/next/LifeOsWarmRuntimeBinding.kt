package app.lifeos.next

import app.lifeos.core.runtime.boot.WarmRuntimeFeature
import app.lifeos.core.runtime.boot.WarmRuntimeFeatureStatus
import app.lifeos.core.runtime.boot.WarmRuntimeReadinessRegistry
import app.lifeos.next.kernel.LifeOsKernel
import app.lifeos.next.kernel.PrivateEscalationRuntime
import app.lifeos.next.kernel.PrivateSelfHealingRuntime
import app.lifeos.next.kernel.ProductivePerceptionContextRuntime

internal object LifeOsWarmRuntimeBinding {
    private val processWarmFeatures = setOf(
        WarmRuntimeFeature.PERSONAL_RUNTIME,
        WarmRuntimeFeature.DEEP_SEARCH,
        WarmRuntimeFeature.SELF_HEALING,
        WarmRuntimeFeature.DURABLE_GOALS,
        WarmRuntimeFeature.GENERATED_TOOLS,
        WarmRuntimeFeature.SENSORS,
    )

    fun beginBoot() {
        WarmRuntimeReadinessRegistry.beginBoot(processWarmFeatures)
    }

    fun observe(event: LifeOsStartupStageEvent) {
        val feature = featureFor(event.stage) ?: return
        when (event) {
            is LifeOsStartupStageEvent.Completed ->
                WarmRuntimeReadinessRegistry.markReady(feature)
            is LifeOsStartupStageEvent.Failed ->
                WarmRuntimeReadinessRegistry.markFailed(feature, event.message)
            is LifeOsStartupStageEvent.Started -> Unit
        }
    }

    suspend fun finish(
        kernel: LifeOsKernel,
        hooks: LifeOsStartupHooks,
        perception: ProductivePerceptionContextRuntime,
        selfHealing: PrivateSelfHealingRuntime?,
        escalation: PrivateEscalationRuntime?,
    ): ProcessRuntimeWarmInstallResult {
        kernel.startWarmBoot().join()
        resolveGeneratedToolsAfterKernelWarmup()
        val report = LifeOsStartupComposition.startWarm(hooks)
        if (
            kernel.bootstrapState.value.actionable &&
            LifeOsStartupStage.PERSONAL_RUNTIME_WARMUP in report.completedStages
        ) {
            WarmRuntimeReadinessRegistry.runWarmup(WarmRuntimeFeature.SENSORS) {
                perception.start()
            }
        } else {
            WarmRuntimeReadinessRegistry.markFailed(
                WarmRuntimeFeature.SENSORS,
                "personal-runtime-or-kernel-not-actionable",
            )
        }
        return ProcessRuntimeWarmInstallResult(
            selfHealingRuntime = selfHealing.takeIf {
                LifeOsStartupStage.SELF_HEALING in report.completedStages
            },
            escalationRuntime = escalation.takeIf {
                LifeOsStartupStage.SELF_HEALING in report.completedStages
            },
            startupReport = report,
        )
    }

    private fun resolveGeneratedToolsAfterKernelWarmup() {
        val current = WarmRuntimeReadinessRegistry.state(WarmRuntimeFeature.GENERATED_TOOLS)
        if (current.status == WarmRuntimeFeatureStatus.WARMING) {
            WarmRuntimeReadinessRegistry.markFailed(
                WarmRuntimeFeature.GENERATED_TOOLS,
                "generated-tool-rehydration-incomplete",
            )
        }
    }

    private fun featureFor(stage: LifeOsStartupStage): WarmRuntimeFeature? = when (stage) {
        LifeOsStartupStage.PERSONAL_RUNTIME_WARMUP -> WarmRuntimeFeature.PERSONAL_RUNTIME
        LifeOsStartupStage.DEEP_SEARCH -> WarmRuntimeFeature.DEEP_SEARCH
        LifeOsStartupStage.SELF_HEALING -> WarmRuntimeFeature.SELF_HEALING
        LifeOsStartupStage.DURABLE_GOALS -> WarmRuntimeFeature.DURABLE_GOALS
        LifeOsStartupStage.SHARED_RESOURCES,
        LifeOsStartupStage.GOAL_EXECUTION,
        LifeOsStartupStage.KERNEL_GRAPH,
        LifeOsStartupStage.KERNEL_BOOT,
        LifeOsStartupStage.COGNITIVE_STATE_READY,
        LifeOsStartupStage.RUNTIME_STARTED -> null
    }
}
