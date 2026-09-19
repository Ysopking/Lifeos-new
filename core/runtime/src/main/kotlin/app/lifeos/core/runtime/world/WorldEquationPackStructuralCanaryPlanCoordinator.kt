package app.lifeos.core.runtime.world

/**
 * Recovery-safe persistence boundary for non-executing structural canary plans.
 *
 * It cannot execute a plan or mutate any productive world authority.
 */
class WorldEquationPackStructuralCanaryPlanCoordinator(
    private val repository: WorldEquationPackStructuralCanaryPlanRepository,
    private val planner: WorldEquationPackStructuralCanaryPlanner =
        WorldEquationPackStructuralCanaryPlanner(),
) {
    suspend fun createAndPersist(
        validation: WorldEquationPackStructuralValidationBundle,
        recovery: WorldEquationPackStructuralValidationRecoveryReport,
        maximumCases: Int,
        maximumConsecutiveFailures: Int,
    ): WorldEquationPackStructuralCanaryPlan {
        val plan = planner.create(
            validation = validation,
            recovery = recovery,
            maximumCases = maximumCases,
            maximumConsecutiveFailures = maximumConsecutiveFailures,
        )
        repository.putIfAbsent(plan)
        val durable = requireNotNull(repository.load(plan.fingerprint)) {
            "Structural canary plan disappeared after persistence"
        }
        require(durable == plan) {
            "Durable structural canary plan does not match planned content"
        }
        return durable
    }

    suspend fun recover(
        planFingerprint: String,
    ): WorldEquationPackStructuralCanaryPlan? {
        require(planFingerprint.isNotBlank())
        return repository.load(planFingerprint)
    }
}
