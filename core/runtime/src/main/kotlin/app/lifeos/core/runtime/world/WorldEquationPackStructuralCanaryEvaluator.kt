package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

data class WorldEquationPackStructuralCanaryObservation(
    val caseFingerprint: String,
    val planFingerprint: String,
    val admissionFingerprint: String,
    val candidatePackFingerprint: String,
    val metrics: WorldEquationPackRunMetrics,
    val fingerprint: String,
) {
    init {
        require(caseFingerprint.isNotBlank())
        require(planFingerprint.isNotBlank())
        require(admissionFingerprint.isNotBlank())
        require(candidatePackFingerprint.isNotBlank())
        require(fingerprint == expectedFingerprint())
    }

    val scope: WorldFormulaExecutionScope
        get() = WorldFormulaExecutionScope.STRUCTURAL_CANARY

    val productiveActivationAllowed: Boolean
        get() = false

    val productiveWorldMutationAllowed: Boolean
        get() = false

    val cognitiveSideEffectsAllowed: Boolean
        get() = false

    private fun expectedFingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-structural-canary-observation/v1",
        caseFingerprint,
        planFingerprint,
        admissionFingerprint,
        candidatePackFingerprint,
        metrics.fingerprint(),
    )

    companion object {
        fun create(
            case: WorldEquationPackShadowCase,
            plan: WorldEquationPackStructuralCanaryPlan,
            admission: WorldEquationPackStructuralCanaryAdmission,
            candidate: WorldEquationPack,
            metrics: WorldEquationPackRunMetrics,
        ): WorldEquationPackStructuralCanaryObservation {
            val fingerprint = StableFieldIds.fingerprint(
                "world-equation-pack-structural-canary-observation/v1",
                case.fingerprint(),
                plan.fingerprint,
                admission.fingerprint,
                candidate.fingerprint(),
                metrics.fingerprint(),
            )
            return WorldEquationPackStructuralCanaryObservation(
                caseFingerprint = case.fingerprint(),
                planFingerprint = plan.fingerprint,
                admissionFingerprint = admission.fingerprint,
                candidatePackFingerprint = candidate.fingerprint(),
                metrics = metrics,
                fingerprint = fingerprint,
            )
        }
    }
}

/**
 * Candidate-only canary sandbox. It materializes the structural candidate from a frozen case and
 * runs it under STRUCTURAL_CANARY policy using a process-local snapshot repository.
 *
 * This lane is deliberately non-productive: no cognitive snapshots, triggers, ProductiveWorldHead
 * mutation, promotion or activation are reachable.
 */
class WorldEquationPackStructuralCanaryEvaluator(
    private val materializer: WorldEquationPackCaseMaterializer =
        WorldEquationPackCaseMaterializer(),
) {
    suspend fun evaluate(
        baseline: WorldEquationPack,
        candidate: WorldEquationPackCandidate,
        plan: WorldEquationPackStructuralCanaryPlan,
        admission: WorldEquationPackStructuralCanaryAdmission,
        case: WorldEquationPackShadowCase,
    ): WorldEquationPackStructuralCanaryObservation {
        require(candidate.baselinePackFingerprint == baseline.fingerprint()) {
            "Structural canary candidate targets another baseline"
        }
        require(candidate.changeKind == WorldEquationPackChangeKind.STRUCTURAL) {
            "Structural canary sandbox rejects parameter-only candidates"
        }
        require(plan.baselinePackFingerprint == baseline.fingerprint())
        require(plan.candidatePackFingerprint == candidate.candidate.fingerprint())
        require(admission.planFingerprint == plan.fingerprint)
        require(admission.baselinePackFingerprint == plan.baselinePackFingerprint)
        require(admission.candidatePackFingerprint == plan.candidatePackFingerprint)
        require(admission.validationBundleFingerprint == plan.validationBundleFingerprint)
        require(admission.isolatedExecutionAllowed)
        require(!admission.productiveActivationAllowed)
        require(!admission.productiveWorldMutationAllowed)

        val materialized = materializer.materialize(candidate.candidate, case)
        val metrics = evaluateMaterialized(candidate.candidate, materialized)
        return WorldEquationPackStructuralCanaryObservation.create(
            case = case,
            plan = plan,
            admission = admission,
            candidate = candidate.candidate,
            metrics = metrics,
        )
    }

    private suspend fun evaluateMaterialized(
        pack: WorldEquationPack,
        materialized: WorldEquationPackMaterializedCase,
    ): WorldEquationPackRunMetrics {
        val request = materialized.request ?: return invalidMetrics(
            materialized.materializationFingerprint
        )
        val snapshots = CanarySnapshotRepository()
        val coordinator = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(pack.equation)),
            snapshots = snapshots,
            executionPolicy = WorldFormulaExecutionPolicy.STRUCTURAL_CANARY,
        )
        val execution = coordinator.evaluate(request)
        require(execution.scope == WorldFormulaExecutionScope.STRUCTURAL_CANARY)
        require(!execution.productiveCommitAllowed)
        require(execution.state != WorldFormulaExecutionState.PERSISTENCE_FAILED) {
            "Structural canary sandbox repository persistence failed"
        }
        if (execution.state == WorldFormulaExecutionState.INVALID) {
            return invalidMetrics(materialized.materializationFingerprint)
        }

        val snapshot = requireNotNull(execution.snapshot)
        val active = snapshot.iterations
            .flatMap { it.contributions }
            .filter { abs(it.signedDelta) > ACTIVE_EPSILON }
            .mapTo(linkedSetOf()) { it.coefficientId }

        return WorldEquationPackRunMetrics(
            status = snapshot.status,
            iterationCount = snapshot.iterations.size,
            conflictCount = snapshot.conflicts.size,
            anomalyCount = snapshot.anomalies.size,
            terminalDelta = snapshot.iterations.lastOrNull()?.maxDelta ?: 0.0,
            activeCoefficientIds = active,
            materializationFingerprint = materialized.materializationFingerprint,
            graphFingerprint = snapshot.graphFingerprint,
            finalStateFingerprint = snapshot.finalState.fingerprint(),
        )
    }

    private fun invalidMetrics(
        materializationFingerprint: String,
    ): WorldEquationPackRunMetrics =
        WorldEquationPackRunMetrics(
            status = WorldFormulaStatus.INVALID_EQUATION,
            iterationCount = 0,
            conflictCount = 0,
            anomalyCount = 1,
            terminalDelta = 1.0,
            activeCoefficientIds = emptySet(),
            materializationFingerprint = materializationFingerprint,
            graphFingerprint = null,
            finalStateFingerprint = null,
        )

    private class CanarySnapshotRepository : WorldFormulaSnapshotRepository {
        private val mutex = Mutex()
        private val byId = linkedMapOf<String, WorldFormulaSnapshot>()

        override suspend fun save(snapshot: WorldFormulaSnapshot) = mutex.withLock {
            val existing = byId[snapshot.id]
            require(existing == null || existing == snapshot) {
                "Structural canary snapshot id collision"
            }
            byId[snapshot.id] = snapshot
            while (byId.size > MAX_SNAPSHOTS) {
                byId.remove(byId.keys.first())
            }
        }

        override suspend fun load(id: String): WorldFormulaSnapshot? = mutex.withLock {
            byId[id]
        }

        @Suppress("DEPRECATION")
        override suspend fun loadLatest(): WorldFormulaSnapshot? = mutex.withLock {
            byId.values.lastOrNull()
        }

        override suspend fun loadReport(): WorldFormulaSnapshotLoadReport = mutex.withLock {
            WorldFormulaSnapshotLoadReport(
                snapshots = byId.values.toList(),
                unreadableEntries = emptyList(),
            )
        }

        override suspend fun delete(id: String) {
            mutex.withLock { byId.remove(id) }
        }
    }

    private companion object {
        const val MAX_SNAPSHOTS = 4
        const val ACTIVE_EPSILON = 1e-12
    }
}
