package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.runtime.evolution.RejectingWorldEquationPromotionAdmissionVerifier
import app.lifeos.core.runtime.evolution.WorldEquationPromotionAdmission
import app.lifeos.core.runtime.evolution.WorldEquationPromotionAdmissionVerifier
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
    private val specs: WorldEquationSpecRepository = InMemoryWorldEquationSpecRepository(),
    private val admissionVerifier: WorldEquationPromotionAdmissionVerifier =
        RejectingWorldEquationPromotionAdmissionVerifier,
) {
    private val mutex = Mutex()

    suspend fun activeVersion(): String = activeHead().activeEquationVersion

    suspend fun activeHead(): WorldEquationHead = mutex.withLock {
        activeHeadLocked()
    }

    suspend fun registerCandidate(spec: WorldEquationSpec) = mutex.withLock {
        persistAndRegister(spec)
    }

    suspend fun promote(
        candidate: WorldEquationSpec,
        admission: WorldEquationPromotionAdmission,
        expectedHeadFingerprint: String,
    ): WorldEquationHead = mutex.withLock {
        require(expectedHeadFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "World equation promotion requires an exact current head fingerprint"
        }
        require(candidate.version == admission.candidateVersion) {
            "World equation promotion admission targets another version"
        }
        require(candidate.fingerprint() == admission.candidateEquationFingerprint) {
            "World equation promotion admission targets a different artifact"
        }
        require(candidate.physicsFingerprint() == admission.candidatePhysicsFingerprint) {
            "World equation promotion admission targets different physics"
        }
        require(candidate.schemaFingerprint() == admission.equationSchemaFingerprint) {
            "World equation promotion admission targets a different schema"
        }
        persistAndRegister(candidate)

        repeat(MAX_CAS_ATTEMPTS) {
            val report = heads.loadReport()
            require(!report.corrupted) {
                "World equation head recovery required: ${report.message.orEmpty()}"
            }
            val current = report.head ?: seedBaseline()
            require(current.fingerprint == expectedHeadFingerprint) {
                "World equation promotion head changed since admission intent was prepared"
            }
            val currentSpec = requireNotNull(resolveRegistered(current.activeEquationVersion)) {
                "Current active world equation cannot be recovered"
            }
            admissionVerifier.verify(
                candidate = candidate,
                baseline = currentSpec,
                admission = admission,
            )
            require(currentSpec.fingerprint() == admission.baselineEquationFingerprint) {
                "World equation promotion baseline no longer matches active artifact"
            }
            require(currentSpec.physicsFingerprint() == admission.baselinePhysicsFingerprint) {
                "World equation promotion baseline no longer matches active physics"
            }
            require(currentSpec.schemaFingerprint() == admission.equationSchemaFingerprint) {
                "World equation promotion baseline no longer matches candidate schema"
            }
            if (current.activeEquationVersion == candidate.version) return@withLock current
            val next = WorldEquationHead.create(
                revision = Math.addExact(current.revision, 1L),
                activeEquationVersion = candidate.version,
                predecessorEquationVersion = current.activeEquationVersion,
                sourcePromotionId = admission.validation.promotionDecisionId,
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
            requireNotNull(resolveRegistered(predecessor)) {
                "World equation rollback predecessor cannot be recovered: $predecessor"
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

    private suspend fun activeHeadLocked(): WorldEquationHead {
        // Pin the compiled baseline into the immutable durable spec vault even when an older
        // deployment already seeded the equation head before the spec vault existed.
        persistAndRegister(baseline)
        val report = heads.loadReport()
        require(!report.corrupted) {
            "World equation head recovery required: ${report.message.orEmpty()}"
        }
        val head = report.head ?: seedBaseline()
        requireNotNull(resolveRegistered(head.activeEquationVersion)) {
            "Active world equation version cannot be recovered: ${head.activeEquationVersion}"
        }
        return head
    }

    private suspend fun seedBaseline(): WorldEquationHead {
        persistAndRegister(baseline)
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

    private suspend fun persistAndRegister(spec: WorldEquationSpec) {
        specs.putIfAbsent(spec)
        val durable = requireNotNull(specs.load(spec.version)) {
            "Durable world equation spec disappeared after persistence"
        }
        require(durable.fingerprint() == spec.fingerprint()) {
            "Durable world equation spec fingerprint mismatch"
        }
        equations.register(durable)
    }

    private suspend fun resolveRegistered(version: String): WorldEquationSpec? {
        equations.resolve(version)?.let { return it }
        val durable = specs.load(version) ?: return null
        equations.register(durable)
        return durable
    }

    private companion object {
        const val MAX_CAS_ATTEMPTS = 8
    }
}
