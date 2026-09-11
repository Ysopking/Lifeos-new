package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.world.EncryptedWorldFormulaSnapshotRepository
import app.lifeos.core.runtime.resource.HardwareAdaptiveBudgetPlan
import app.lifeos.core.runtime.resource.HardwareAdaptiveResourceOptimizer
import app.lifeos.core.runtime.resource.HardwareStateSnapshot
import app.lifeos.core.runtime.resource.HardwareWorkPriority
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.world.HardwareWorldEquationProfile
import app.lifeos.core.runtime.world.InMemoryWorldEquationRegistry
import app.lifeos.core.runtime.world.WorldFormulaCoordinator
import app.lifeos.core.runtime.world.WorldFormulaExecution
import app.lifeos.core.runtime.world.WorldFormulaExecutionState
import app.lifeos.core.runtime.world.WorldFormulaStatus

sealed interface HardwareExecutionBudgetDecision {
    data class Ready(val plan: HardwareAdaptiveBudgetPlan) : HardwareExecutionBudgetDecision
    data class Blocked(val reason: String) : HardwareExecutionBudgetDecision {
        init { require(reason.isNotBlank()) }
    }
}

fun interface HardwareExecutionBudgetGate {
    suspend fun plan(
        hardQuota: ResourceBudgetQuota,
        requested: ResourceBudgetUsage,
        priority: HardwareWorkPriority,
    ): HardwareExecutionBudgetDecision
}

/**
 * APK-level V16 bridge. A fresh local hardware observation is projected through the World Formula
 * and persisted before it may influence a resource recommendation. The World Formula stays
 * informational: hard owner/system quotas remain absolute and this bridge can only reduce them.
 */
class HardwareResourceIntelligenceRuntime internal constructor(
    context: Context,
    private val reader: AndroidHardwareStateReader = AndroidHardwareStateReader(context),
    private val optimizer: HardwareAdaptiveResourceOptimizer = HardwareAdaptiveResourceOptimizer(),
    private val profile: HardwareWorldEquationProfile = HardwareWorldEquationProfile(),
) : HardwareExecutionBudgetGate {
    private val worldFormula = WorldFormulaCoordinator(
        equations = InMemoryWorldEquationRegistry(listOf(profile.spec)),
        snapshots = EncryptedWorldFormulaSnapshotRepository(context.applicationContext),
    )

    suspend fun evaluate(
        hardQuota: ResourceBudgetQuota,
        requested: ResourceBudgetUsage,
        priority: HardwareWorkPriority = HardwareWorkPriority.NORMAL,
    ): HardwareResourceDecision {
        val hardware = reader.read()
        val world = worldFormula.evaluate(profile.request(hardware))
        if (
            world.state != WorldFormulaExecutionState.COMPLETED ||
            world.status != WorldFormulaStatus.CONVERGED ||
            !world.persisted
        ) {
            return HardwareResourceDecision.Blocked(
                hardware = hardware,
                world = world,
                reason = "hardware-world-formula-not-converged-and-persisted",
            )
        }

        return HardwareResourceDecision.Ready(
            hardware = hardware,
            world = world,
            plan = optimizer.plan(
                hardQuota = hardQuota,
                requested = requested,
                hardware = hardware,
                priority = priority,
            ),
        )
    }

    override suspend fun plan(
        hardQuota: ResourceBudgetQuota,
        requested: ResourceBudgetUsage,
        priority: HardwareWorkPriority,
    ): HardwareExecutionBudgetDecision = when (
        val decision = evaluate(hardQuota, requested, priority)
    ) {
        is HardwareResourceDecision.Ready -> HardwareExecutionBudgetDecision.Ready(decision.plan)
        is HardwareResourceDecision.Blocked -> HardwareExecutionBudgetDecision.Blocked(decision.reason)
    }

    /** Read-only diagnostics path; it does not reserve or spend a resource budget. */
    fun currentHardwareSnapshot(): HardwareStateSnapshot = reader.read()
}

sealed interface HardwareResourceDecision {
    val hardware: HardwareStateSnapshot
    val world: WorldFormulaExecution

    data class Ready(
        override val hardware: HardwareStateSnapshot,
        override val world: WorldFormulaExecution,
        val plan: HardwareAdaptiveBudgetPlan,
    ) : HardwareResourceDecision

    data class Blocked(
        override val hardware: HardwareStateSnapshot,
        override val world: WorldFormulaExecution,
        val reason: String,
    ) : HardwareResourceDecision {
        init { require(reason.isNotBlank()) }
    }
}
