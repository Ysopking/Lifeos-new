package app.lifeos.core.runtime.world

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WorldEquationPackStructuralPromotionReviewLoadReport(
    val bundles: List<WorldEquationPackStructuralPromotionReviewBundle>,
    val unreadableEntries: List<String>,
) {
    init {
        require(bundles.map { it.fingerprint }.distinct().size == bundles.size)
        require(unreadableEntries.none { it.isBlank() })
    }

    val corrupted: Boolean
        get() = unreadableEntries.isNotEmpty()
}

interface WorldEquationPackStructuralPromotionReviewRepository {
    suspend fun putIfAbsent(bundle: WorldEquationPackStructuralPromotionReviewBundle)
    suspend fun load(bundleFingerprint: String): WorldEquationPackStructuralPromotionReviewBundle?
    suspend fun loadReport(): WorldEquationPackStructuralPromotionReviewLoadReport
}

class InMemoryWorldEquationPackStructuralPromotionReviewRepository(
    initial: List<WorldEquationPackStructuralPromotionReviewBundle> = emptyList(),
) : WorldEquationPackStructuralPromotionReviewRepository {
    private val mutex = Mutex()
    private val byFingerprint =
        linkedMapOf<String, WorldEquationPackStructuralPromotionReviewBundle>()

    init {
        initial.forEach { bundle ->
            require(bundle.fingerprint !in byFingerprint)
            byFingerprint[bundle.fingerprint] = bundle
        }
    }

    override suspend fun putIfAbsent(
        bundle: WorldEquationPackStructuralPromotionReviewBundle,
    ) = mutex.withLock {
        val existing = byFingerprint[bundle.fingerprint]
        require(existing == null || existing == bundle) {
            "Structural promotion review fingerprint collision"
        }
        if (existing == null) {
            byFingerprint[bundle.fingerprint] = bundle
        }
    }

    override suspend fun load(
        bundleFingerprint: String,
    ): WorldEquationPackStructuralPromotionReviewBundle? = mutex.withLock {
        require(bundleFingerprint.isNotBlank())
        byFingerprint[bundleFingerprint]
    }

    override suspend fun loadReport(): WorldEquationPackStructuralPromotionReviewLoadReport =
        mutex.withLock {
            WorldEquationPackStructuralPromotionReviewLoadReport(
                bundles = byFingerprint.values.sortedBy { it.fingerprint },
                unreadableEntries = emptyList(),
            )
        }
}

class WorldEquationPackStructuralPromotionReviewCoordinator(
    private val repository: WorldEquationPackStructuralPromotionReviewRepository,
    private val gate: WorldEquationPackStructuralPromotionReviewGate =
        WorldEquationPackStructuralPromotionReviewGate(),
) {
    suspend fun buildAndPersist(
        validation: WorldEquationPackStructuralValidationBundle,
        plan: WorldEquationPackStructuralCanaryPlan,
        record: WorldEquationPackStructuralCanaryEvidenceRecord,
    ): WorldEquationPackStructuralPromotionReviewBundle {
        val bundle = gate.build(validation, plan, record)
        repository.putIfAbsent(bundle)
        val durable = requireNotNull(repository.load(bundle.fingerprint)) {
            "Structural promotion review bundle disappeared after persistence"
        }
        require(durable == bundle) {
            "Durable structural promotion review bundle mismatch"
        }
        return durable
    }

    suspend fun recover(
        bundleFingerprint: String,
    ): WorldEquationPackStructuralPromotionReviewBundle? =
        repository.load(bundleFingerprint)
}
