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

/**
 * Process-level bridge installed by the private APK before kernel construction. Core modules can use
 * the exact same broker instance without depending on Android or constructing parallel World Formula
 * repositories. If no gate is installed, legacy/local tests remain deterministic through null.
 */
object SharedResourceBudgetRuntimeRegistry {
    @Volatile
    private var installed: SharedResourceBudgetGate? = null

    fun install(gate: SharedResourceBudgetGate) {
        installed = gate
    }

    fun current(): SharedResourceBudgetGate? = installed
}
