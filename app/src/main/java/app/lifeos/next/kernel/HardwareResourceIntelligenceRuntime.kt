package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.world.EncryptedWorldFormulaSnapshotRepository
import app.lifeos.core.runtime.resource.HardwareAdaptiveBudgetPlan
import app.lifeos.core.runtime.resource.HardwareAdaptiveResourceOptimizer
import app.lifeos.core.runtime.resource.HardwareBudgetMode
import app.lifeos.core.runtime.resource.HardwareStateSnapshot
import app.lifeos.core.runtime.resource.HardwareWorkPriority
import app.lifeos.core.runtime.resource.ResourceBudgetDemand
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.resource.SharedResourceBudgetDecision
import app.lifeos.core.runtime.resource.SharedResourceBudgetGate
import app.lifeos.core.runtime.resource.WorldFormulaBudgetBroker
import app.lifeos.core.runtime.resource.WorldFormulaBudgetBrokerDecision
import app.lifeos.core.runtime.world.HardwareWorldEquationProfile
import app.lifeos.core.runtime.world.InMemoryWorldEquationRegistry
import app.lifeos.core.runtime.world.ResourceAllocationWorldEquationProfile
import app.lifeos.core.runtime.world.WorldFormulaCoordinator
import app.lifeos.core.runtime.world.WorldFormulaExecutionPolicy
import app.lifeos.core.runtime.world.WorldFormulaExecution
import app.lifeos.core.runtime.world.WorldFormulaExecutionState
import app.lifeos.core.runtime.world.WorldFormulaStatus
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import java.time.Duration
import java.time.Clock

data class HardwareEpoch(
    val thermalBucket: String,
    val memoryPressureBucket: Int,
    val batteryBucket: Int,
    val charging: Boolean?,
    val availableCoreBucket: Int,
) {
    companion object {
        fun from(snapshot: HardwareStateSnapshot): HardwareEpoch = HardwareEpoch(
            thermalBucket = snapshot.thermalState.name,
            memoryPressureBucket = when (snapshot.memoryHeadroom()) {
                null -> -1
                in 0.75..1.0 -> 3
                in 0.40..<0.75 -> 2
                in 0.20..<0.40 -> 1
                else -> 0
            },
            batteryBucket = when (val battery = snapshot.batteryFraction) {
                null -> -1
                in 0.75..1.0 -> 4
                in 0.50..<0.75 -> 3
                in 0.25..<0.50 -> 2
                in 0.10..<0.25 -> 1
                else -> 0
            },
            charging = snapshot.charging,
            availableCoreBucket = when (snapshot.availableProcessors) {
                1 -> 1
                in 2..3 -> 2
                in 4..7 -> 4
                else -> 8
            },
        )
    }
}

data class CachedHardwareWorld(
    val epoch: HardwareEpoch,
    val hardware: HardwareStateSnapshot,
    val world: WorldFormulaExecution,
    val evaluatedAt: Instant,
)

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
 * APK-level V16 bridge.
 *
 * HardwareWorldEquationProfile is a validity/convergence gate. HardwareAdaptiveResourceOptimizer
 * remains authoritative for the measured hardware envelope. ResourceAllocationWorldEquationProfile
 * supplies the actual cross-domain WorldFormula weighting inside that envelope. Hard owner/system
 * quotas remain absolute.
 */
class HardwareResourceIntelligenceRuntime internal constructor(
    context: Context,
    private val reader: AndroidHardwareStateReader = AndroidHardwareStateReader(context),
    private val optimizer: HardwareAdaptiveResourceOptimizer = HardwareAdaptiveResourceOptimizer(),
    private val profile: HardwareWorldEquationProfile = HardwareWorldEquationProfile(),
    private val allocationProfile: ResourceAllocationWorldEquationProfile = ResourceAllocationWorldEquationProfile(),
    private val clock: Clock = Clock.systemUTC(),
    private val hardwareEpochTtl: Duration = Duration.ofSeconds(15),
) : HardwareExecutionBudgetGate, SharedResourceBudgetGate {
    private val worldFormula = WorldFormulaCoordinator(
        equations = InMemoryWorldEquationRegistry(
            listOf(profile.spec, allocationProfile.spec)
        ),
        snapshots = EncryptedWorldFormulaSnapshotRepository(context.applicationContext),
        executionPolicy = WorldFormulaExecutionPolicy.RESOURCE,
    )
    private val budgetBroker = WorldFormulaBudgetBroker(worldFormula, allocationProfile)
    private val cacheMutex = Mutex()

    @Volatile
    private var cachedHardwareWorld: CachedHardwareWorld? = null

    suspend fun evaluate(
        hardQuota: ResourceBudgetQuota,
        requested: ResourceBudgetUsage,
        priority: HardwareWorkPriority = HardwareWorkPriority.NORMAL,
    ): HardwareResourceDecision {
        val cached = currentHardwareWorld()
        val hardware = cached.hardware
        val world = cached.world
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
        val cached = currentHardwareWorld()
        val hardware = cached.hardware
        val hardwareWorld = cached.world
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
        if (hardwarePlan.mode == HardwareBudgetMode.SUSPENDED) {
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
    fun currentHardwareSnapshot(): HardwareStateSnapshot =
        cachedHardwareWorld?.hardware ?: reader.read()

    private suspend fun currentHardwareWorld(): CachedHardwareWorld {
        val observed = reader.read()
        val epoch = HardwareEpoch.from(observed)
        val now = clock.instant()
        cachedHardwareWorld?.let { cached ->
            if (cached.epoch == epoch && Duration.between(cached.evaluatedAt, now) <= hardwareEpochTtl) {
                return cached
            }
        }
        return cacheMutex.withLock {
            cachedHardwareWorld?.let { cached ->
                if (cached.epoch == epoch && Duration.between(cached.evaluatedAt, now) <= hardwareEpochTtl) {
                    return@withLock cached
                }
            }
            CachedHardwareWorld(
                epoch = epoch,
                hardware = observed,
                world = worldFormula.evaluate(profile.request(observed)),
                evaluatedAt = now,
            ).also { cachedHardwareWorld = it }
        }
    }

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
