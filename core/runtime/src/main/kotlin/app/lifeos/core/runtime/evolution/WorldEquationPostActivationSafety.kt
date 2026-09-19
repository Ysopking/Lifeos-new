package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.world.SelfStateWorldBand
import app.lifeos.core.runtime.world.WorldEquationActivationAuthority
import app.lifeos.core.runtime.world.WorldEquationHead
import java.time.Instant

data class WorldEquationPostActivationSafetyObservation private constructor(
    val id: String,
    val candidateEquationFingerprint: String,
    val assessmentId: String,
    val authorityFingerprint: String,
    val band: SelfStateWorldBand,
    val observedAt: Instant,
) {
    init {
        require(id.isNotBlank())
        require(candidateEquationFingerprint.isNotBlank())
        require(assessmentId.isNotBlank())
        require(authorityFingerprint.isNotBlank())
        require(id == expectedId())
    }

    fun fingerprint(): String = expectedId().removePrefix("world-equation-safety:")

    private fun expectedId(): String =
        "world-equation-safety:" + StableFieldIds.fingerprint(
            "world-equation-post-activation-safety-observation/v1",
            candidateEquationFingerprint,
            assessmentId,
            authorityFingerprint,
            band.name,
            observedAt.toString(),
        )

    companion object {
        fun create(
            candidateEquationFingerprint: String,
            assessmentId: String,
            authorityFingerprint: String,
            band: SelfStateWorldBand,
            observedAt: Instant,
        ): WorldEquationPostActivationSafetyObservation =
            WorldEquationPostActivationSafetyObservation(
                id = "world-equation-safety:" + StableFieldIds.fingerprint(
                    "world-equation-post-activation-safety-observation/v1",
                    candidateEquationFingerprint,
                    assessmentId,
                    authorityFingerprint,
                    band.name,
                    observedAt.toString(),
                ),
                candidateEquationFingerprint = candidateEquationFingerprint,
                assessmentId = assessmentId,
                authorityFingerprint = authorityFingerprint,
                band = band,
                observedAt = observedAt,
            )
    }
}

data class WorldEquationPostActivationSafetyPolicy(
    val version: String,
    val minimumObservations: Int,
    val consecutiveCriticalForRollback: Int,
    val consecutiveDegradedOrCriticalForRollback: Int,
) {
    init {
        require(version.isNotBlank())
        require(minimumObservations >= 2)
        require(consecutiveCriticalForRollback >= 2)
        require(consecutiveDegradedOrCriticalForRollback >= 2)
        require(consecutiveCriticalForRollback <= minimumObservations)
        require(consecutiveDegradedOrCriticalForRollback <= minimumObservations)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-post-activation-safety-policy/v1",
        version,
        minimumObservations.toString(),
        consecutiveCriticalForRollback.toString(),
        consecutiveDegradedOrCriticalForRollback.toString(),
    )

    fun requiresRollback(
        observations: List<WorldEquationPostActivationSafetyObservation>,
    ): Boolean {
        if (observations.size < minimumObservations) return false
        val critical = observations
            .takeLast(consecutiveCriticalForRollback)
            .all { it.band == SelfStateWorldBand.CRITICAL }
        val degradedOrCritical = observations
            .takeLast(consecutiveDegradedOrCriticalForRollback)
            .all {
                it.band == SelfStateWorldBand.DEGRADED ||
                    it.band == SelfStateWorldBand.CRITICAL
            }
        return critical || degradedOrCritical
    }

    companion object {
        val V1 = WorldEquationPostActivationSafetyPolicy(
            version = "world-equation-post-activation-safety-v1",
            minimumObservations = 3,
            consecutiveCriticalForRollback = 2,
            consecutiveDegradedOrCriticalForRollback = 3,
        )
    }
}

sealed interface WorldEquationPostActivationSafetyResult {
    data object NoActiveCandidate : WorldEquationPostActivationSafetyResult

    data class Monitored(
        val record: WorldEquationEvidenceRecord,
    ) : WorldEquationPostActivationSafetyResult

    data class RolledBack(
        val record: WorldEquationEvidenceRecord,
        val head: WorldEquationHead,
    ) : WorldEquationPostActivationSafetyResult
}

fun interface WorldEquationPostActivationSafetyObserver {
    suspend fun observe(
        assessmentId: String,
        authorityFingerprint: String,
        band: SelfStateWorldBand,
        observedAt: Instant,
    ): WorldEquationPostActivationSafetyResult
}

class WorldEquationPostActivationSafetyMonitor(
    private val evidence: WorldEquationEvidenceRepository,
    private val evidenceCoordinator: WorldEquationEvidenceCoordinator,
    private val authority: WorldEquationActivationAuthority,
    private val policy: WorldEquationPostActivationSafetyPolicy =
        WorldEquationPostActivationSafetyPolicy.V1,
) : WorldEquationPostActivationSafetyObserver {
    override suspend fun observe(
        assessmentId: String,
        authorityFingerprint: String,
        band: SelfStateWorldBand,
        observedAt: Instant,
    ): WorldEquationPostActivationSafetyResult {
        val activeHead = authority.activeHead()
        val report = evidence.loadReport()
        require(!report.corrupted) {
            "WorldEquation safety evidence recovery required"
        }
        val activeRecords = report.records.filter {
            it.state == WorldEquationLifecycleState.ACTIVE &&
                it.evidence.candidateVersion == activeHead.activeEquationVersion
        }
        require(activeRecords.size <= 1) {
            "Multiple ACTIVE WorldEquation evidence records target the same active version"
        }
        val current = activeRecords.singleOrNull()
            ?: return WorldEquationPostActivationSafetyResult.NoActiveCandidate
        require(current.activationHeadFingerprint == activeHead.fingerprint) {
            "ACTIVE WorldEquation evidence is bound to another activation head"
        }

        val observation = WorldEquationPostActivationSafetyObservation.create(
            candidateEquationFingerprint = current.candidateEquationFingerprint,
            assessmentId = assessmentId,
            authorityFingerprint = authorityFingerprint,
            band = band,
            observedAt = observedAt,
        )
        val recorded = evidenceCoordinator.recordPostActivationSafetyObservation(
            candidateEquationFingerprint = current.candidateEquationFingerprint,
            observation = observation,
        )
        if (!policy.requiresRollback(recorded.postActivationSafetyObservations)) {
            return WorldEquationPostActivationSafetyResult.Monitored(recorded)
        }

        val rollbackDecisionId = rollbackDecisionId(recorded)
        evidenceCoordinator.quarantine(
            candidateEquationFingerprint = recorded.candidateEquationFingerprint,
            verdictId = rollbackDecisionId,
        )
        val rolledBackHead = authority.rollbackToPredecessor(
            expectedCurrentVersion = recorded.evidence.candidateVersion,
            rollbackDecisionId = rollbackDecisionId,
        )
        val rolledBackRecord = evidenceCoordinator.markRolledBack(
            candidateEquationFingerprint = recorded.candidateEquationFingerprint,
            rollbackDecisionId = rollbackDecisionId,
        )
        return WorldEquationPostActivationSafetyResult.RolledBack(
            record = rolledBackRecord,
            head = rolledBackHead,
        )
    }

    suspend fun reconcile() {
        var report = evidence.loadReport()
        require(!report.corrupted) {
            "WorldEquation safety evidence recovery required"
        }

        val activeHead = authority.activeHead()
        val promotedButUnsealed = report.records.filter {
            it.state == WorldEquationLifecycleState.PROMOTABLE &&
                it.evidence.candidateVersion == activeHead.activeEquationVersion
        }
        require(promotedButUnsealed.size <= 1) {
            "Multiple PROMOTABLE evidence records match the active WorldEquation head"
        }
        promotedButUnsealed.singleOrNull()?.let { record ->
            evidenceCoordinator.markActive(
                candidateEquationFingerprint = record.candidateEquationFingerprint,
                activationHeadFingerprint = activeHead.fingerprint,
            )
            report = evidence.loadReport()
            require(!report.corrupted) {
                "WorldEquation safety evidence recovery required"
            }
        }

        report.records
            .filter { it.state == WorldEquationLifecycleState.QUARANTINED }
            .forEach { record ->
                val decisionId = requireNotNull(record.rollbackDecisionId) {
                    "Quarantined WorldEquation evidence lacks rollback decision"
                }
                val head = authority.activeHead()
                when (head.activeEquationVersion) {
                    record.evidence.candidateVersion -> {
                        authority.rollbackToPredecessor(
                            expectedCurrentVersion = record.evidence.candidateVersion,
                            rollbackDecisionId = decisionId,
                        )
                        evidenceCoordinator.markRolledBack(
                            record.candidateEquationFingerprint,
                            decisionId,
                        )
                    }
                    record.evidence.baselineVersion -> {
                        evidenceCoordinator.markRolledBack(
                            record.candidateEquationFingerprint,
                            decisionId,
                        )
                    }
                    else -> error(
                        "Quarantined WorldEquation evidence cannot reconcile against active version " +
                            head.activeEquationVersion
                    )
                }
            }
    }

    private fun rollbackDecisionId(
        record: WorldEquationEvidenceRecord,
    ): String = "world-equation-rollback:" + StableFieldIds.fingerprint(
        "world-equation-post-activation-rollback/v1",
        record.candidateEquationFingerprint,
        policy.fingerprint(),
        *record.postActivationSafetyObservations.map { it.fingerprint() }.toTypedArray(),
    )
}

object WorldEquationPostActivationSafetyRuntimeRegistry {
    @Volatile
    private var installed: WorldEquationPostActivationSafetyObserver? = null

    fun install(observer: WorldEquationPostActivationSafetyObserver) {
        installed = observer
    }

    fun current(): WorldEquationPostActivationSafetyObserver? = installed
}
