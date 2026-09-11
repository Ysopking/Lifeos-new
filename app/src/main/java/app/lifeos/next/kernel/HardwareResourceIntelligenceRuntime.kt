package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.world.EncryptedWorldFormulaSnapshotRepository
import app.lifeos.core.runtime.resource.HardwareAdaptiveBudgetPlan
import app.lifeos.core.runtime.resource.HardwareAdaptiveResourceOptimizer
import app.lifeos.core.runtime.resource.HardwareStateSnapshot
import app.lifeos.core.runtime.resource.HardwareWorkPriority
import app.lifeos.core.runtime.resource.ResourceBudgetDemand
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.resource.WorldFormulaBudgetAllocationPlan
import app.lifeos.core.runtime.resource.WorldFormulaBudgetBroker
import app.lifeos.core.runtime.resource.WorldFormulaBudgetBrokerDecision
import app.lifeos.core.runtime.world.HardwareWorldEquationProfile
import app.lifeos.core.runtime.world.InMemoryWorldEquationRegistry
import app.lifeos.core.runtime.world.ResourceAllocationWorldEquationProfile
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

sealed interface SharedResourceBudgetDecision {
    data class Ready(
        val hardwarePlan: HardwareAdaptiveBudgetPlan,
        val allocation: WorldFormulaBudgetAllocationPlan,
    ) : SharedResourceBudgetDecision

    data class Blocked(val reason: String) : SharedResourceBudgetDecision {
        init { require(reason.isNotBlank()) }
    }
}

fun interface SharedResourceBudgetGate {
    suspend fun allocate(
        hardQuota: ResourceBudgetQuota,
        demands: List<ResourceBudgetDemand>,
    ): SharedResourceBudgetDecision
}

/**
 * APK-level V16 bridge. A fresh local hardware observation is projected through the World Formula
 * and persisted before it may influence a resource recommendation. Cross-domain distribution then
 * runs through a second persisted World Formula snapshot. Hard owner/system quotas remain absolute;
 * both stages can only shrink or partition the permitted envelope.
 */
class HardwareResourceIntelligenceRuntime internal constructor(
    context: Context,
    private val reader: AndroidHardwareStateReader = AndroidHardwareStateReader(context),
    private val optimizer: HardwareAdaptiveResourceOptimizer = HardwareAdaptiveResourceOptimizer(),
    private val profile: HardwareWorldEquationProfile = HardwareWorldEquationProfile(),
    private val allocationProfile: ResourceAllocationWorldEquationProfile = ResourceAllocationWorldEquationProfile(),
) : HardwareExecutionBudgetGate, SharedResourceBudgetGate {
    private val worldFormula = WorldFormulaCoordinator(
        equations = InMemoryWorldEquationRegistry(
            listOf(profile.spec, allocationProfile.spec)
        ),
        snapshots = EncryptedWorldFormulaSnapshotRepository(context.applicationContext),
    )
    private val budgetBroker = WorldFormulaBudgetBroker(worldFormula, allocationProfile)

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

    override suspend fun allocate(
        hardQuota: ResourceBudgetQuota,
        demands: List<ResourceBudgetDemand>,
    ): SharedResourceBudgetDecision {
        val hardware = reader.read()
        val hardwareWorld = worldFormula.evaluate(profile.request(hardware))
        if (
            hardwareWorld.state != WorldFormulaExecutionState.COMPLETED ||
            hardwareWorld.status != WorldFormulaStatus.CONVERGED ||
            !hardwareWorld.persisted
        ) {
            return SharedResourceBudgetDecision.Blocked(
                "hardware-world-formula-not-converged-and-persisted"
            )
        }

        val aggregateRequested = demands.fold(ResourceBudgetUsage()) { acc, demand ->
            acc + demand.requested
        }
        val hardwarePlan = optimizer.plan(
            hardQuota = hardQuota,
            requested = aggregateRequested,
            hardware = hardware,
            priority = aggregatePriority(demands),
        )
        if (hardwarePlan.mode == app.lifeos.core.runtime.resource.HardwareBudgetMode.SUSPENDED) {
            return SharedResourceBudgetDecision.Blocked(
                hardwarePlan.reasons.joinToString("|").ifBlank { "hardware-state-requires-suspension" }
            )
        }

        return when (
            val allocation = budgetBroker.allocate(
                pool = hardwarePlan.effectiveQuota,
                hardware = hardware,
                demands = demands,
            )
        ) {
            is WorldFormulaBudgetBrokerDecision.Blocked ->
                SharedResourceBudgetDecision.Blocked(allocation.reason)
            is WorldFormulaBudgetBrokerDecision.Ready ->
                SharedResourceBudgetDecision.Ready(
                    hardwarePlan = hardwarePlan,
                    allocation = allocation.plan,
                )
        }
    }

    /** Read-only diagnostics path; it does not reserve or spend a resource budget. */
    fun currentHardwareSnapshot(): HardwareStateSnapshot = reader.read()

    private fun aggregatePriority(demands: List<ResourceBudgetDemand>): HardwareWorkPriority {
        val priority = demands.maxOfOrNull { it.priority } ?: 0.0
        return when {
            priority >= 0.90 -> HardwareWorkPriority.CRITICAL
            priority >= 0.70 -> HardwareWorkPriority.HIGH
            priority < 0.30 -> HardwareWorkPriority.LOW
            else -> HardwareWorkPriority.NORMAL
        }
    }
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
