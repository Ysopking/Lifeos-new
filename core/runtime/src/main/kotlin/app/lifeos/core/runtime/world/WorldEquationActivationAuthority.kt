package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldEquationSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The only productive selector for versioned cognitive world physics.
 *
 * Candidate creation/evaluation remains outside this class. Activation requires an already
 * registered immutable equation spec plus an explicit promotion id and advances the durable head
 * through CAS. BootEngine cycles read only the active head and freeze it for their whole lifetime.
 */
class WorldEquationActivationAuthority(
    private val equations: InMemoryWorldEquationRegistry,
    private val heads: WorldEquationHeadRepository,
    private val baseline: WorldEquationSpec,
) {
    private val mutex = Mutex()

    suspend fun activeVersion(): String = mutex.withLock {
        val report = heads.loadReport()
        require(!report.corrupted) {
            "World equation head recovery required: ${report.message.orEmpty()}"
        }
        val head = report.head ?: seedBaseline()
        requireNotNull(equations.resolve(head.activeEquationVersion)) {
            "Active world equation version is not registered: ${head.activeEquationVersion}"
        }
        head.activeEquationVersion
    }

    suspend fun registerCandidate(spec: WorldEquationSpec) = mutex.withLock {
        equations.register(spec)
    }

    suspend fun activate(
        version: String,
        promotionId: String,
    ): WorldEquationHead = mutex.withLock {
        require(version.isNotBlank())
        require(promotionId.isNotBlank())
        requireNotNull(equations.resolve(version)) {
            "Cannot activate unregistered world equation version: $version"
        }

        repeat(MAX_CAS_ATTEMPTS) {
            val report = heads.loadReport()
            require(!report.corrupted) {
                "World equation head recovery required: ${report.message.orEmpty()}"
            }
            val current = report.head ?: seedBaseline()
            if (current.activeEquationVersion == version) return@withLock current
            val next = WorldEquationHead.create(
                revision = Math.addExact(current.revision, 1L),
                activeEquationVersion = version,
                predecessorEquationVersion = current.activeEquationVersion,
                sourcePromotionId = promotionId,
            )
            if (heads.compareAndSet(current.revision, next)) {
                return@withLock next
            }
        }
        error("World equation head CAS did not converge")
    }

    suspend fun rollbackToPredecessor(
        expectedCurrentVersion: String,
        rollbackDecisionId: String,
    ): WorldEquationHead = mutex.withLock {
        require(expectedCurrentVersion.isNotBlank())
        require(rollbackDecisionId.isNotBlank())

        repeat(MAX_CAS_ATTEMPTS) {
            val report = heads.loadReport()
            require(!report.corrupted) {
                "World equation head recovery required: ${report.message.orEmpty()}"
            }
            val current = report.head ?: seedBaseline()
            require(current.activeEquationVersion == expectedCurrentVersion) {
                "World equation rollback current version mismatch"
            }
            val predecessor = requireNotNull(current.predecessorEquationVersion) {
                "World equation rollback requires a predecessor version"
            }
            requireNotNull(equations.resolve(predecessor)) {
                "World equation rollback predecessor is not registered: $predecessor"
            }
            val next = WorldEquationHead.create(
                revision = Math.addExact(current.revision, 1L),
                activeEquationVersion = predecessor,
                predecessorEquationVersion = current.activeEquationVersion,
                sourcePromotionId = "rollback:$rollbackDecisionId",
            )
            if (heads.compareAndSet(current.revision, next)) {
                return@withLock next
            }
        }
        error("World equation rollback CAS did not converge")
    }

    private suspend fun seedBaseline(): WorldEquationHead {
        equations.register(baseline)
        val initial = WorldEquationHead.create(
            revision = 1L,
            activeEquationVersion = baseline.version,
            predecessorEquationVersion = null,
            sourcePromotionId = "bootstrap-baseline",
        )
        if (heads.compareAndSet(null, initial)) return initial
        return requireNotNull(heads.load()) {
            "World equation baseline lost during concurrent initialization"
        }
    }

    private companion object {
        const val MAX_CAS_ATTEMPTS = 8
    }
}
