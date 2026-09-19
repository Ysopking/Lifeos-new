package app.lifeos.core.runtime.field

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldSnapshot
import app.lifeos.core.field.FieldSnapshotRepository
import app.lifeos.core.field.HypothesisState
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import app.lifeos.core.runtime.workers.DurableTaskExecutionObserver
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FieldShadowValidationOrigin {
    LIVE,
    DETERMINISTIC_REPLAY,
}

enum class ShadowSemanticState {
    RESOLVED,
    UNRESOLVED,
    FAILED,
    ABSENT,
}

enum class ShadowSourceStatus {
    PRESERVED,
    ADVANCED,
    MISSING,
    MISMATCH,
    UNAVAILABLE,
}

enum class ShadowTaskOwnershipStatus {
    PRESERVED,
    REGRESSION,
    INCONCLUSIVE,
}

enum class FieldShadowDifferenceClass {
    EQUIVALENT,
    EQUIVALENT_UNRESOLVED,
    CONFIDENCE_DRIFT,
    UNIVERSAL_UNRESOLVED,
    UNRESOLVED_COLLAPSED,
    SEMANTIC_DIVERGENCE,
    NO_LEGACY_EFFECT,
    LEGACY_FAILED,
    SHADOW_UNAVAILABLE,
    SNAPSHOT_MISSING,
    SOURCE_ADVANCED,
    SOURCE_INCOMPLETE,
    SOURCE_MUTATION,
    TASK_OWNERSHIP_REGRESSION,
}

enum class FieldCutoverRecommendation {
    NOT_SELECTED,
    INSUFFICIENT_REPLAY_CORPUS,
    BLOCKED,
    READY_FOR_SELECTED_DOMAIN,
}

data class LegacyFieldObservation(
    val finalState: TaskState,
    val semanticState: ShadowSemanticState,
    val influenceCount: Int,
    val influenceTypes: Set<String>,
    val averageConfidence: Double,
    val totalEnergyDelta: Double,
) {
    init {
        require(influenceCount >= 0)
        require(averageConfidence.isFinite() && averageConfidence in 0.0..1.0)
        require(totalEnergyDelta.isFinite())
        require(influenceTypes.none { it.isBlank() })
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "legacy-shadow-observation/v1",
        finalState.name,
        semanticState.name,
        influenceCount.toString(),
        java.lang.Double.toHexString(averageConfidence),
        java.lang.Double.toHexString(totalEnergyDelta),
        *influenceTypes.sorted().toTypedArray(),
    )

    companion object {
        fun capture(result: CognitiveTaskExecutionResult): LegacyFieldObservation = capture(
            finalState = result.finalState,
            influences = result.influences,
        )

        fun capture(
            finalState: TaskState,
            influences: List<FieldInfluence>,
        ): LegacyFieldObservation {
            val normalizedTypes = influences
                .map { it.type.trim().uppercase() }
                .filter { it.isNotBlank() }
                .toSortedSet()
            val unresolved = normalizedTypes.any { type ->
                UNRESOLVED_MARKERS.any(type::contains)
            }
            val semanticState = when {
                finalState == TaskState.FAILED || finalState == TaskState.CANCELLED -> ShadowSemanticState.FAILED
                influences.isEmpty() -> ShadowSemanticState.ABSENT
                unresolved -> ShadowSemanticState.UNRESOLVED
                else -> ShadowSemanticState.RESOLVED
            }
            val confidences = influences
                .map { it.confidence }
                .filter(Double::isFinite)
                .map { it.coerceIn(0.0, 1.0) }
            return LegacyFieldObservation(
                finalState = finalState,
                semanticState = semanticState,
                influenceCount = influences.size,
                influenceTypes = normalizedTypes,
                averageConfidence = confidences.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                totalEnergyDelta = influences
                    .map { it.deltaEnergy }
                    .filter(Double::isFinite)
                    .sum(),
            )
        }

        private val UNRESOLVED_MARKERS = setOf(
            "CONFLICT",
            "UNRESOLVED",
            "AMBIGUOUS",
            "COMPETING",
        )
    }
}

data class UniversalFieldObservation(
    val shadowState: FieldShadowState,
    val semanticState: ShadowSemanticState,
    val convergenceStatus: ConvergenceStatus?,
    val snapshotPresent: Boolean,
    val winnerCount: Int,
    val topConfidence: Double,
) {
    init {
        require(winnerCount >= 0)
        require(topConfidence.isFinite() && topConfidence in 0.0..1.0)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "universal-shadow-observation/v1",
        shadowState.name,
        semanticState.name,
        convergenceStatus?.name.orEmpty(),
        snapshotPresent.toString(),
        winnerCount.toString(),
        java.lang.Double.toHexString(topConfidence),
    )

    companion object {
        fun capture(
            shadow: FieldShadowExecution,
            snapshot: FieldSnapshot?,
        ): UniversalFieldObservation {
            if (shadow.state != FieldShadowState.COMPLETED) {
                return UniversalFieldObservation(
                    shadowState = shadow.state,
                    semanticState = ShadowSemanticState.FAILED,
                    convergenceStatus = null,
                    snapshotPresent = false,
                    winnerCount = 0,
                    topConfidence = 0.0,
                )
            }
            if (snapshot == null) {
                return UniversalFieldObservation(
                    shadowState = shadow.state,
                    semanticState = ShadowSemanticState.ABSENT,
                    convergenceStatus = shadow.convergenceStatus,
                    snapshotPresent = false,
                    winnerCount = 0,
                    topConfidence = 0.0,
                )
            }

            val winners = snapshot.hypotheses.filter { it.state == HypothesisState.CONVERGED }
            val unresolved = snapshot.status != ConvergenceStatus.CONVERGED ||
                snapshot.hypotheses.any { it.state == HypothesisState.UNRESOLVED } ||
                winners.size != 1
            val semanticState = if (unresolved) {
                ShadowSemanticState.UNRESOLVED
            } else {
                ShadowSemanticState.RESOLVED
            }
            val top = (winners.singleOrNull()?.score?.total
                ?: snapshot.hypotheses.maxOfOrNull { it.score.total }
                ?: 0.0).coerceIn(0.0, 1.0)
            return UniversalFieldObservation(
                shadowState = shadow.state,
                semanticState = semanticState,
                convergenceStatus = snapshot.status,
                snapshotPresent = true,
                winnerCount = winners.size,
                topConfidence = top,
            )
        }
    }
}

data class FieldShadowValidationInput(
    val taskId: TaskId,
    val domainId: FieldDomainId,
    val legacy: LegacyFieldObservation,
    val universal: UniversalFieldObservation,
    val sourceStatus: ShadowSourceStatus,
    val taskOwnershipStatus: ShadowTaskOwnershipStatus,
    val origin: FieldShadowValidationOrigin,
    val replayCaseId: String? = null,
) {
    init {
        require(replayCaseId == null || replayCaseId.isNotBlank())
        require(origin != FieldShadowValidationOrigin.DETERMINISTIC_REPLAY || replayCaseId != null) {
            "Deterministic replay evidence requires a replay case id"
        }
    }
}

data class FieldShadowValidationEvidence(
    val id: String,
    val taskId: TaskId,
    val domainId: FieldDomainId,
    val origin: FieldShadowValidationOrigin,
    val replayCaseId: String?,
    val legacy: LegacyFieldObservation,
    val universal: UniversalFieldObservation,
    val sourceStatus: ShadowSourceStatus,
    val taskOwnershipStatus: ShadowTaskOwnershipStatus,
    val difference: FieldShadowDifferenceClass,
    val confidenceDelta: Double,
    val capturedAt: Instant,
) {
    init {
        require(id.isNotBlank())
        require(confidenceDelta.isFinite() && confidenceDelta in 0.0..1.0)
    }

    /** Excludes capture time so repeat replay cases can be checked for deterministic equality. */
    fun outcomeFingerprint(): String = StableFieldIds.fingerprint(
        "field-shadow-validation-outcome/v1",
        domainId.value,
        legacy.fingerprint(),
        universal.fingerprint(),
        sourceStatus.name,
        taskOwnershipStatus.name,
        difference.name,
        java.lang.Double.toHexString(confidenceDelta),
    )
}

data class FieldShadowValidationPolicy(
    val minimumReplayCases: Int = 32,
    val maximumConfidenceDelta: Double = 0.25,
    val selectedDomains: Set<FieldDomainId> = emptySet(),
    val maximumConfidenceDeltaByDomain: Map<FieldDomainId, Double> = emptyMap(),
) {
    init {
        require(minimumReplayCases > 0)
        require(maximumConfidenceDelta.isFinite() && maximumConfidenceDelta in 0.0..1.0)
        require(
            maximumConfidenceDeltaByDomain.values.all {
                it.isFinite() && it in 0.0..1.0
            }
        ) { "Domain confidence-delta limits must be finite and in 0..1" }
    }

    fun maximumConfidenceDelta(domainId: FieldDomainId): Double =
        maximumConfidenceDeltaByDomain[domainId] ?: maximumConfidenceDelta
}

data class FieldShadowValidationReport(
    val domainId: FieldDomainId,
    val recommendation: FieldCutoverRecommendation,
    val replayCaseCount: Int,
    val equivalentCaseCount: Int,
    val nondeterministicReplayCases: Set<String>,
    val sourceMutationRegressions: Int,
    val taskOwnershipRegressions: Int,
    val unresolvedCollapsedRegressions: Int,
    val semanticDivergences: Int,
    val blockerClasses: Set<FieldShadowDifferenceClass>,
) {
    val passed: Boolean
        get() = recommendation == FieldCutoverRecommendation.READY_FOR_SELECTED_DOMAIN
}

/**
 * Pure semantic comparator and fail-closed cutover recommender.
 *
 * It never switches runtime strategy. A READY recommendation is only evidence that a separately
 * selected domain passed the configured deterministic replay gate.
 */
class FieldShadowValidator(
    private val policy: FieldShadowValidationPolicy = FieldShadowValidationPolicy(),
    private val now: () -> Instant = Instant::now,
) {
    fun validate(input: FieldShadowValidationInput): FieldShadowValidationEvidence {
        val confidenceDelta = kotlin.math.abs(
            input.legacy.averageConfidence - input.universal.topConfidence,
        ).coerceIn(0.0, 1.0)
        val difference = classify(input, confidenceDelta)
        val id = StableFieldIds.fingerprint(
            "field-shadow-validation/v1",
            input.taskId.value,
            input.domainId.value,
            input.origin.name,
            input.replayCaseId.orEmpty(),
            input.legacy.fingerprint(),
            input.universal.fingerprint(),
            input.sourceStatus.name,
            input.taskOwnershipStatus.name,
            difference.name,
            java.lang.Double.toHexString(confidenceDelta),
        )
        return FieldShadowValidationEvidence(
            id = id,
            taskId = input.taskId,
            domainId = input.domainId,
            origin = input.origin,
            replayCaseId = input.replayCaseId,
            legacy = input.legacy,
            universal = input.universal,
            sourceStatus = input.sourceStatus,
            taskOwnershipStatus = input.taskOwnershipStatus,
            difference = difference,
            confidenceDelta = confidenceDelta,
            capturedAt = now(),
        )
    }

    suspend fun report(
        domainId: FieldDomainId,
        ledger: FieldShadowValidationLedger,
    ): FieldShadowValidationReport = report(domainId, ledger.forDomain(domainId))

    fun report(
        domainId: FieldDomainId,
        evidence: List<FieldShadowValidationEvidence>,
    ): FieldShadowValidationReport {
        val replay = evidence
            .filter { it.domainId == domainId && it.origin == FieldShadowValidationOrigin.DETERMINISTIC_REPLAY }
            .filter { it.replayCaseId != null }
        val byCase = replay.groupBy { requireNotNull(it.replayCaseId) }
        val nondeterministic = byCase
            .filterValues { caseEvidence -> caseEvidence.map { it.outcomeFingerprint() }.distinct().size > 1 }
            .keys
            .toSortedSet()
        val canonicalCases = byCase
            .toSortedMap()
            .values
            .map { caseEvidence -> caseEvidence.sortedBy { it.id }.first() }
        val blockers = canonicalCases
            .map { it.difference }
            .filter(::isBlockingDifference)
            .toSortedSet(compareBy { it.name })
        val sourceRegressions = canonicalCases.count { it.difference == FieldShadowDifferenceClass.SOURCE_MUTATION }
        val ownershipRegressions = canonicalCases.count {
            it.difference == FieldShadowDifferenceClass.TASK_OWNERSHIP_REGRESSION
        }
        val unresolvedCollapsed = canonicalCases.count {
            it.difference == FieldShadowDifferenceClass.UNRESOLVED_COLLAPSED
        }
        val semanticDivergences = canonicalCases.count { isSemanticDivergence(it.difference) }
        val equivalent = canonicalCases.count {
            it.difference == FieldShadowDifferenceClass.EQUIVALENT ||
                it.difference == FieldShadowDifferenceClass.EQUIVALENT_UNRESOLVED
        }
        val recommendation = when {
            domainId !in policy.selectedDomains -> FieldCutoverRecommendation.NOT_SELECTED
            canonicalCases.size < policy.minimumReplayCases -> FieldCutoverRecommendation.INSUFFICIENT_REPLAY_CORPUS
            nondeterministic.isNotEmpty() -> FieldCutoverRecommendation.BLOCKED
            blockers.isNotEmpty() -> FieldCutoverRecommendation.BLOCKED
            equivalent != canonicalCases.size -> FieldCutoverRecommendation.BLOCKED
            else -> FieldCutoverRecommendation.READY_FOR_SELECTED_DOMAIN
        }
        return FieldShadowValidationReport(
            domainId = domainId,
            recommendation = recommendation,
            replayCaseCount = canonicalCases.size,
            equivalentCaseCount = equivalent,
            nondeterministicReplayCases = nondeterministic,
            sourceMutationRegressions = sourceRegressions,
            taskOwnershipRegressions = ownershipRegressions,
            unresolvedCollapsedRegressions = unresolvedCollapsed,
            semanticDivergences = semanticDivergences,
            blockerClasses = blockers,
        )
    }

    private fun classify(
        input: FieldShadowValidationInput,
        confidenceDelta: Double,
    ): FieldShadowDifferenceClass {
        if (input.taskOwnershipStatus == ShadowTaskOwnershipStatus.REGRESSION) {
            return FieldShadowDifferenceClass.TASK_OWNERSHIP_REGRESSION
        }
        when (input.sourceStatus) {
            ShadowSourceStatus.MISMATCH -> return FieldShadowDifferenceClass.SOURCE_MUTATION
            ShadowSourceStatus.ADVANCED -> return FieldShadowDifferenceClass.SOURCE_ADVANCED
            ShadowSourceStatus.MISSING,
            ShadowSourceStatus.UNAVAILABLE,
            -> return FieldShadowDifferenceClass.SOURCE_INCOMPLETE
            ShadowSourceStatus.PRESERVED -> Unit
        }
        if (input.universal.shadowState != FieldShadowState.COMPLETED) {
            return FieldShadowDifferenceClass.SHADOW_UNAVAILABLE
        }
        if (!input.universal.snapshotPresent) {
            return FieldShadowDifferenceClass.SNAPSHOT_MISSING
        }
        return when (input.legacy.semanticState) {
            ShadowSemanticState.FAILED -> FieldShadowDifferenceClass.LEGACY_FAILED
            ShadowSemanticState.ABSENT -> FieldShadowDifferenceClass.NO_LEGACY_EFFECT
            ShadowSemanticState.UNRESOLVED -> when (input.universal.semanticState) {
                ShadowSemanticState.UNRESOLVED -> FieldShadowDifferenceClass.EQUIVALENT_UNRESOLVED
                ShadowSemanticState.RESOLVED -> FieldShadowDifferenceClass.UNRESOLVED_COLLAPSED
                else -> FieldShadowDifferenceClass.SEMANTIC_DIVERGENCE
            }
            ShadowSemanticState.RESOLVED -> when (input.universal.semanticState) {
                ShadowSemanticState.RESOLVED -> if (
                    confidenceDelta <= policy.maximumConfidenceDelta(input.domainId)
                ) {
                    FieldShadowDifferenceClass.EQUIVALENT
                } else {
                    FieldShadowDifferenceClass.CONFIDENCE_DRIFT
                }
                ShadowSemanticState.UNRESOLVED -> FieldShadowDifferenceClass.UNIVERSAL_UNRESOLVED
                else -> FieldShadowDifferenceClass.SEMANTIC_DIVERGENCE
            }
        }
    }

    private fun isBlockingDifference(difference: FieldShadowDifferenceClass): Boolean = when (difference) {
        FieldShadowDifferenceClass.EQUIVALENT,
        FieldShadowDifferenceClass.EQUIVALENT_UNRESOLVED,
        -> false
        else -> true
    }

    private fun isSemanticDivergence(difference: FieldShadowDifferenceClass): Boolean = when (difference) {
        FieldShadowDifferenceClass.CONFIDENCE_DRIFT,
        FieldShadowDifferenceClass.UNIVERSAL_UNRESOLVED,
        FieldShadowDifferenceClass.UNRESOLVED_COLLAPSED,
        FieldShadowDifferenceClass.SEMANTIC_DIVERGENCE,
        FieldShadowDifferenceClass.NO_LEGACY_EFFECT,
        FieldShadowDifferenceClass.LEGACY_FAILED,
        FieldShadowDifferenceClass.SHADOW_UNAVAILABLE,
        FieldShadowDifferenceClass.SNAPSHOT_MISSING,
        -> true
        else -> false
    }
}

interface FieldShadowValidationLedger {
    suspend fun record(evidence: FieldShadowValidationEvidence): Boolean
    suspend fun latest(limit: Int = 64): List<FieldShadowValidationEvidence>
    suspend fun forDomain(domainId: FieldDomainId): List<FieldShadowValidationEvidence>
}

/** Bounded, idempotent evidence ledger; validation telemetry can never grow without limit. */
class BoundedFieldShadowValidationLedger(
    private val capacity: Int = 256,
) : FieldShadowValidationLedger {
    private val mutex = Mutex()
    private val values = linkedMapOf<String, FieldShadowValidationEvidence>()

    init { require(capacity > 0) }

    override suspend fun record(evidence: FieldShadowValidationEvidence): Boolean = mutex.withLock {
        if (evidence.id in values) return@withLock false
        values[evidence.id] = evidence
        while (values.size > capacity) {
            val oldest = values.keys.firstOrNull() ?: break
            values.remove(oldest)
        }
        true
    }

    override suspend fun latest(limit: Int): List<FieldShadowValidationEvidence> = mutex.withLock {
        require(limit > 0)
        values.values.toList().asReversed().take(limit)
    }

    override suspend fun forDomain(domainId: FieldDomainId): List<FieldShadowValidationEvidence> = mutex.withLock {
        values.values.filter { it.domainId == domainId }
    }
}

/**
 * Best-effort observer that joins authoritative legacy outcomes with persisted universal snapshots.
 * It is intended to be installed only after the primary durable runtime observer.
 */
class FieldShadowValidationObserver(
    private val photons: PhotonRepository,
    private val snapshots: FieldSnapshotRepository,
    private val ledger: FieldShadowValidationLedger,
    private val validator: FieldShadowValidator = FieldShadowValidator(),
) : DurableTaskExecutionObserver {
    override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
        val shadow = result.fieldShadow ?: return
        val domainId = shadow.domainId ?: return
        val sourceLoad = loadSource(result, shadow)
        val snapshot = if (shadow.state == FieldShadowState.COMPLETED) {
            try {
                shadow.snapshotId?.let { snapshots.load(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val input = FieldShadowValidationInput(
            taskId = result.taskId,
            domainId = domainId,
            legacy = LegacyFieldObservation.capture(result),
            universal = UniversalFieldObservation.capture(shadow, snapshot),
            sourceStatus = sourceLoad,
            taskOwnershipStatus = if (result.finalState in TERMINAL_STATES) {
                ShadowTaskOwnershipStatus.PRESERVED
            } else {
                ShadowTaskOwnershipStatus.INCONCLUSIVE
            },
            origin = FieldShadowValidationOrigin.LIVE,
        )
        ledger.record(validator.validate(input))
    }

    override suspend fun onDispatchFailure(task: LifeTask, error: Exception) {
        // There is no committed universal snapshot to compare here. Ownership regressions belong in
        // deterministic replay evidence where the expected terminal state is explicitly known.
    }

    private suspend fun loadSource(
        result: CognitiveTaskExecutionResult,
        shadow: FieldShadowExecution,
    ): ShadowSourceStatus {
        val shadowId = shadow.sourcePhotonId ?: return ShadowSourceStatus.UNAVAILABLE
        val shadowRevision = shadow.sourceRevision ?: return ShadowSourceStatus.UNAVAILABLE
        val shadowFingerprint = shadow.sourceFingerprint ?: return ShadowSourceStatus.UNAVAILABLE
        val resultId = result.photonId ?: return ShadowSourceStatus.UNAVAILABLE
        if (resultId != shadowId) return ShadowSourceStatus.MISMATCH
        val current: Photon = try {
            photons.load(resultId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ShadowSourceStatus.UNAVAILABLE
        } ?: return ShadowSourceStatus.MISSING
        return when {
            current.revision > shadowRevision -> ShadowSourceStatus.ADVANCED
            current.revision < shadowRevision -> ShadowSourceStatus.MISMATCH
            runtimePhotonFingerprint(current) != shadowFingerprint -> ShadowSourceStatus.MISMATCH
            else -> ShadowSourceStatus.PRESERVED
        }
    }

    private companion object {
        val TERMINAL_STATES = setOf(
            TaskState.COMPLETED,
            TaskState.SUPERSEDED,
            TaskState.FAILED,
            TaskState.CANCELLED,
        )
    }
}
