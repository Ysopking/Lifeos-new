package app.lifeos.core.runtime.resource

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.runtime.world.ResourceAllocationWorldEquationProfile
import app.lifeos.core.runtime.world.WorldFormulaCoordinator
import app.lifeos.core.runtime.world.WorldFormulaExecutionState
import app.lifeos.core.runtime.world.WorldFormulaStatus
import kotlin.math.floor

enum class ResourceBudgetDomain {
    GOAL_EXECUTION,
    DEEP_SEARCH,
    COGNITION,
    TOOL_WORKSHOP,
    EVOLUTION,
    HOT_SWAP,
    SELF_HEALING,
    COLLABORATIVE_ARTIFACTS,
    BACKGROUND_LEARNING,
    BACKGROUND,
}

/**
 * Demand presented to the World Formula broker. Values are typed decision inputs, not permissions.
 * Hard quotas, V14 owner policy and downstream reservations remain authoritative.
 */
data class ResourceBudgetDemand(
    val domain: ResourceBudgetDomain,
    val requested: ResourceBudgetUsage,
    val goalRelevance: Double,
    val priority: Double,
    val expectedUtility: Double,
    val confidence: Double = 1.0,
) {
    init {
        require(!requested.isZero()) { "Resource budget demand must request non-zero work" }
        require(goalRelevance.isFinite() && goalRelevance in 0.0..1.0)
        require(priority.isFinite() && priority in 0.0..1.0)
        require(expectedUtility.isFinite() && expectedUtility in 0.0..1.0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "resource-budget-demand/v1",
        domain.name,
        requested.elapsedMillis.toString(),
        requested.workUnits.toString(),
        requested.memoryBytes.toString(),
        requested.ioBytes.toString(),
        requested.networkBytes.toString(),
        requested.candidates.toString(),
        java.lang.Double.toHexString(goalRelevance),
        java.lang.Double.toHexString(priority),
        java.lang.Double.toHexString(expectedUtility),
        java.lang.Double.toHexString(confidence),
    )
}

data class ResourceBudgetDomainAllocation(
    val domain: ResourceBudgetDomain,
    val demandFingerprint: String,
    val worldWeight: Double,
    val allocated: ResourceBudgetUsage,
) {
    init {
        require(demandFingerprint.isNotBlank())
        require(worldWeight.isFinite() && worldWeight in 0.0..1.0)
    }
}

data class WorldFormulaBudgetAllocationPlan(
    val pool: ResourceBudgetQuota,
    val allocations: List<ResourceBudgetDomainAllocation>,
    val unallocated: ResourceBudgetUsage,
    val worldSnapshotId: String,
    val hardwareSnapshotFingerprint: String,
) {
    init {
        require(allocations.map { it.domain }.distinct().size == allocations.size)
        require(worldSnapshotId.isNotBlank())
        require(hardwareSnapshotFingerprint.isNotBlank())
        val allocatedTotal = allocations.fold(ResourceBudgetUsage()) { acc, item -> acc + item.allocated }
        require(pool.allows(allocatedTotal + unallocated)) {
            "World Formula budget plan exceeds the supplied resource pool"
        }
        require(allocatedTotal + unallocated == pool.asUsage()) {
            "World Formula budget plan must exactly partition the supplied resource pool"
        }
    }

    fun allocation(domain: ResourceBudgetDomain): ResourceBudgetDomainAllocation? =
        allocations.firstOrNull { it.domain == domain }
}

sealed interface WorldFormulaBudgetBrokerDecision {
    data class Ready(val plan: WorldFormulaBudgetAllocationPlan) : WorldFormulaBudgetBrokerDecision
    data class Blocked(val reason: String) : WorldFormulaBudgetBrokerDecision {
        init { require(reason.isNotBlank()) }
    }
}

/**
 * V16 shared BudgetBroker. The World Formula chooses relative attraction of competing workloads;
 * deterministic weighted water-filling then partitions each hard resource dimension. The broker can
 * never create capacity and never bypass V14/V16 reservation enforcement.
 */
class WorldFormulaBudgetBroker(
    private val worldFormula: WorldFormulaCoordinator,
    private val profile: ResourceAllocationWorldEquationProfile = ResourceAllocationWorldEquationProfile(),
) {
    suspend fun allocate(
        pool: ResourceBudgetQuota,
        hardware: HardwareStateSnapshot,
        demands: List<ResourceBudgetDemand>,
    ): WorldFormulaBudgetBrokerDecision {
        if (demands.isEmpty()) {
            return WorldFormulaBudgetBrokerDecision.Ready(
                WorldFormulaBudgetAllocationPlan(
                    pool = pool,
                    allocations = emptyList(),
                    unallocated = pool.asUsage(),
                    worldSnapshotId = StableFieldIds.fingerprint(
                        "resource-budget-empty-allocation/v1",
                        hardware.fingerprint(),
                    ),
                    hardwareSnapshotFingerprint = hardware.fingerprint(),
                )
            )
        }
        require(demands.map { it.domain }.distinct().size == demands.size) {
            "Budget broker accepts one demand per resource domain"
        }
        if (hardware.shouldSuspendHeavyWork()) {
            return WorldFormulaBudgetBrokerDecision.Blocked("hardware-state-requires-suspension")
        }

        val prepared = profile.request(hardware, demands)
        val execution = worldFormula.evaluate(prepared.request)
        if (
            execution.state != WorldFormulaExecutionState.COMPLETED ||
            execution.status != WorldFormulaStatus.CONVERGED ||
            !execution.persisted
        ) {
            return WorldFormulaBudgetBrokerDecision.Blocked(
                "resource-allocation-world-formula-not-converged-and-persisted",
            )
        }
        val snapshot = requireNotNull(execution.snapshot)
        val weights = demands.sortedBy { it.domain.name }.associate { demand ->
            val laneInput = prepared.laneInputs.getValue(demand.domain.name)
            val laneState = snapshot.finalState[laneInput.toNode().id]
                ?: error("World Formula allocation lane missing from final state")
            val salience = laneState[WorldSignalDimension.ANALYTIC_SALIENCE]
            val readiness = laneState[WorldSignalDimension.CAPABILITY_READINESS]
            val salienceValue = (salience?.value ?: 0.0) * (salience?.confidence ?: 0.0)
            val readinessValue = (readiness?.value ?: 0.0) * (readiness?.confidence ?: 0.0)
            val weight = (salienceValue * readinessValue).coerceIn(0.0, 1.0)
            demand.domain to weight
        }
        if (weights.values.all { it <= 0.0 }) {
            return WorldFormulaBudgetBrokerDecision.Blocked("world-formula-produced-zero-resource-attraction")
        }

        val requestedByDomain = demands.associate { it.domain to it.requested }
        val elapsed = distributeDimension(
            pool.elapsedMillis,
            requestedByDomain.mapValues { it.value.elapsedMillis },
            weights,
        )
        val work = distributeDimension(
            pool.workUnits,
            requestedByDomain.mapValues { it.value.workUnits },
            weights,
        )
        val memory = distributeDimension(
            pool.memoryBytes,
            requestedByDomain.mapValues { it.value.memoryBytes },
            weights,
        )
        val io = distributeDimension(
            pool.ioBytes,
            requestedByDomain.mapValues { it.value.ioBytes },
            weights,
        )
        val network = distributeDimension(
            pool.networkBytes,
            requestedByDomain.mapValues { it.value.networkBytes },
            weights,
        )
        val candidates = distributeDimension(
            pool.candidates,
            requestedByDomain.mapValues { it.value.candidates },
            weights,
        )

        val allocations = demands.sortedBy { it.domain.name }.map { demand ->
            ResourceBudgetDomainAllocation(
                domain = demand.domain,
                demandFingerprint = demand.fingerprint(),
                worldWeight = weights.getValue(demand.domain),
                allocated = ResourceBudgetUsage(
                    elapsedMillis = elapsed.allocations.getValue(demand.domain),
                    workUnits = work.allocations.getValue(demand.domain),
                    memoryBytes = memory.allocations.getValue(demand.domain),
                    ioBytes = io.allocations.getValue(demand.domain),
                    networkBytes = network.allocations.getValue(demand.domain),
                    candidates = candidates.allocations.getValue(demand.domain),
                ),
            )
        }
        val unallocated = ResourceBudgetUsage(
            elapsedMillis = elapsed.unallocated,
            workUnits = work.unallocated,
            memoryBytes = memory.unallocated,
            ioBytes = io.unallocated,
            networkBytes = network.unallocated,
            candidates = candidates.unallocated,
        )
        return WorldFormulaBudgetBrokerDecision.Ready(
            WorldFormulaBudgetAllocationPlan(
                pool = pool,
                allocations = allocations,
                unallocated = unallocated,
                worldSnapshotId = snapshot.id,
                hardwareSnapshotFingerprint = hardware.fingerprint(),
            )
        )
    }

    private data class DimensionDistribution(
        val allocations: Map<ResourceBudgetDomain, Long>,
        val unallocated: Long,
    )

    private fun distributeDimension(
        capacity: Long,
        requested: Map<ResourceBudgetDomain, Long>,
        weights: Map<ResourceBudgetDomain, Double>,
    ): DimensionDistribution {
        require(capacity >= 0L)
        require(requested.values.all { it >= 0L })
        val domains = requested.keys.sortedBy { it.name }
        val allocations = domains.associateWith { 0L }.toMutableMap()
        var remaining = capacity
        var active = domains.filter { requested.getValue(it) > 0L && weights.getValue(it) > 0.0 }

        while (remaining > 0L && active.isNotEmpty()) {
            val totalWeight = active.sumOf { weights.getValue(it) }
            if (totalWeight <= 0.0) break
            var progressed = false
            val roundRemaining = remaining
            active.forEach { domain ->
                if (remaining <= 0L) return@forEach
                val need = requested.getValue(domain) - allocations.getValue(domain)
                if (need <= 0L) return@forEach
                val proportional = floor(roundRemaining.toDouble() * weights.getValue(domain) / totalWeight)
                    .toLong()
                    .coerceAtLeast(0L)
                val grant = minOf(need, proportional, remaining)
                if (grant > 0L) {
                    allocations[domain] = Math.addExact(allocations.getValue(domain), grant)
                    remaining -= grant
                    progressed = true
                }
            }
            active = active.filter { allocations.getValue(it) < requested.getValue(it) }
            if (!progressed && remaining > 0L && active.isNotEmpty()) {
                val next = active.maxWithOrNull(
                    compareBy<ResourceBudgetDomain> { weights.getValue(it) }
                        .thenByDescending { it.name }
                ) ?: break
                allocations[next] = Math.addExact(allocations.getValue(next), 1L)
                remaining -= 1L
                active = active.filter { allocations.getValue(it) < requested.getValue(it) }
            }
        }
        return DimensionDistribution(allocations.toMap(), remaining)
    }
}

private fun ResourceBudgetQuota.asUsage(): ResourceBudgetUsage = ResourceBudgetUsage(
    elapsedMillis = elapsedMillis,
    workUnits = workUnits,
    memoryBytes = memoryBytes,
    ioBytes = ioBytes,
    networkBytes = networkBytes,
    candidates = candidates,
)
