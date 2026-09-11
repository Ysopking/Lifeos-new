package app.lifeos.core.runtime.resource

sealed interface SharedResourceBudgetDecision {
    data class Ready(
        val hardwarePlan: HardwareAdaptiveBudgetPlan,
        val allocation: WorldFormulaBudgetAllocationPlan,
    ) : SharedResourceBudgetDecision

    data class Blocked(val reason: String) : SharedResourceBudgetDecision {
        init { require(reason.isNotBlank()) }
    }
}

/**
 * Cross-module V16 contract. Implementations must derive the usable pool from current hardware and
 * persist a World Formula allocation snapshot before returning a domain allocation.
 */
fun interface SharedResourceBudgetGate {
    suspend fun allocate(
        hardQuota: ResourceBudgetQuota,
        demands: List<ResourceBudgetDemand>,
    ): SharedResourceBudgetDecision
}
