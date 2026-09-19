package app.lifeos.core.runtime.world

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Durable activation authority for versioned LIFEOS WorldFormula physics.
 *
 * It can only activate an equation version that is already present in the trusted equation registry.
 * Registration/build/validation of new physics remains outside this class and must pass the existing
 * extension/evolution gates first. The active head is therefore a durable selector, not a generator.
 */
class WorldEquationVersionAuthority(
    private val equations: WorldEquationRegistry,
    private val heads: WorldEquationHeadRepository,
    private val baselineVersion: String,
) {
    private val mutex = Mutex()

    init {
        require(baselineVersion.isNotBlank())
    }

    suspend fun active(): WorldEquationHead = mutex.withLock {
        loadOrCreateBaselineLocked()
    }

    suspend fun activate(
        equationVersion: String,
        promotionEvidenceId: String,
    ): WorldEquationHead = mutex.withLock {
        require(equationVersion.isNotBlank())
        require(promotionEvidenceId.isNotBlank())
        requireNotNull(equations.resolve(equationVersion)) {
            "World equation version is not registered: $equationVersion"
        }

        repeat(MAX_CAS_ATTEMPTS) {
            val current = loadOrCreateBaselineLocked()
            if (current.activeEquationVersion == equationVersion) return@withLock current

            val next = WorldEquationHead.create(
                revision = Math.addExact(current.revision, 1L),
                activeEquationVersion = equationVersion,
                predecessorEquationVersion = current.activeEquationVersion,
                sourcePromotionId = promotionEvidenceId,
            )
            if (heads.compareAndSet(current.revision, next)) {
                return@withLock next
            }
        }
        error("World equation head CAS did not converge")
    }

    suspend fun rollback(
        expectedActiveVersion: String,
        rollbackEvidenceId: String,
    ): WorldEquationHead = mutex.withLock {
        require(expectedActiveVersion.isNotBlank())
        require(rollbackEvidenceId.isNotBlank())

        repeat(MAX_CAS_ATTEMPTS) {
            val current = loadOrCreateBaselineLocked()
            require(current.activeEquationVersion == expectedActiveVersion) {
                "World equation rollback expected $expectedActiveVersion but found ${current.activeEquationVersion}"
            }
            val predecessor = requireNotNull(current.predecessorEquationVersion) {
                "World equation head has no predecessor to roll back to"
            }
            requireNotNull(equations.resolve(predecessor)) {
                "World equation rollback predecessor is no longer registered: $predecessor"
            }

            val next = WorldEquationHead.create(
                revision = Math.addExact(current.revision, 1L),
                activeEquationVersion = predecessor,
                predecessorEquationVersion = current.activeEquationVersion,
                sourcePromotionId = rollbackEvidenceId,
            )
            if (heads.compareAndSet(current.revision, next)) {
                return@withLock next
            }
        }
        error("World equation rollback CAS did not converge")
    }

    private suspend fun loadOrCreateBaselineLocked(): WorldEquationHead {
        val report = heads.loadReport()
        require(!report.corrupted) {
            "World equation head is corrupted: ${report.message.orEmpty()}"
        }
        report.head?.let { head ->
            requireNotNull(equations.resolve(head.activeEquationVersion)) {
                "Active world equation is not registered: ${head.activeEquationVersion}"
            }
            head.predecessorEquationVersion?.let { predecessor ->
                requireNotNull(equations.resolve(predecessor)) {
                    "World equation predecessor is not registered: $predecessor"
                }
            }
            return head
        }

        requireNotNull(equations.resolve(baselineVersion)) {
            "Baseline world equation is not registered: $baselineVersion"
        }
        val baseline = WorldEquationHead.create(
            revision = 1L,
            activeEquationVersion = baselineVersion,
            predecessorEquationVersion = null,
            sourcePromotionId = null,
        )
        if (heads.compareAndSet(null, baseline)) return baseline

        val raced = requireNotNull(heads.load()) {
            "World equation head creation raced but no durable head exists"
        }
        requireNotNull(equations.resolve(raced.activeEquationVersion)) {
            "Raced world equation head is not registered: ${raced.activeEquationVersion}"
        }
        return raced
    }

    private companion object {
        const val MAX_CAS_ATTEMPTS = 8
    }
}
