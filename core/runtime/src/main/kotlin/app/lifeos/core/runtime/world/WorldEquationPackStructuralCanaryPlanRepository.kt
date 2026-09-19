package app.lifeos.core.runtime.world

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WorldEquationPackStructuralCanaryPlanLoadReport(
    val plans: List<WorldEquationPackStructuralCanaryPlan>,
    val unreadableEntries: List<String>,
) {
    init {
        require(plans.map { it.fingerprint }.distinct().size == plans.size)
        require(unreadableEntries.none { it.isBlank() })
    }

    val corrupted: Boolean
        get() = unreadableEntries.isNotEmpty()
}

interface WorldEquationPackStructuralCanaryPlanRepository {
    suspend fun putIfAbsent(plan: WorldEquationPackStructuralCanaryPlan)
    suspend fun load(planFingerprint: String): WorldEquationPackStructuralCanaryPlan?
    suspend fun loadReport(): WorldEquationPackStructuralCanaryPlanLoadReport
}

class InMemoryWorldEquationPackStructuralCanaryPlanRepository(
    initial: List<WorldEquationPackStructuralCanaryPlan> = emptyList(),
) : WorldEquationPackStructuralCanaryPlanRepository {
    private val mutex = Mutex()
    private val byFingerprint = linkedMapOf<String, WorldEquationPackStructuralCanaryPlan>()

    init {
        initial.forEach { plan ->
            require(plan.fingerprint !in byFingerprint)
            byFingerprint[plan.fingerprint] = plan
        }
    }

    override suspend fun putIfAbsent(
        plan: WorldEquationPackStructuralCanaryPlan,
    ) = mutex.withLock {
        val existing = byFingerprint[plan.fingerprint]
        require(existing == null || existing == plan) {
            "Structural canary plan fingerprint collision"
        }
        if (existing == null) {
            byFingerprint[plan.fingerprint] = plan
        }
    }

    override suspend fun load(
        planFingerprint: String,
    ): WorldEquationPackStructuralCanaryPlan? = mutex.withLock {
        require(planFingerprint.isNotBlank())
        byFingerprint[planFingerprint]
    }

    override suspend fun loadReport(): WorldEquationPackStructuralCanaryPlanLoadReport =
        mutex.withLock {
            WorldEquationPackStructuralCanaryPlanLoadReport(
                plans = byFingerprint.values.sortedBy { it.fingerprint },
                unreadableEntries = emptyList(),
            )
        }
}
