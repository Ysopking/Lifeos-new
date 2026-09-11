package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapDetector
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GeneratedToolArtifact
import app.lifeos.core.runtime.capability.GeneratedToolArtifactRepository
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.GeneratedToolTrialExecutionResult
import app.lifeos.core.runtime.capability.GeneratedToolTrialInvocation
import app.lifeos.core.runtime.capability.GeneratedToolTrialLedger
import app.lifeos.core.runtime.capability.GeneratedToolTrialResult
import app.lifeos.core.runtime.capability.GeneratedToolTrialRunner
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exact missing-capability subject. No baseline provider or APK artifact is invented. */
data class NovelCapabilityAdmissionSubject(
    val requirement: CapabilityRequirement,
    val toolId: String,
    val artifactId: String,
    val candidateRecordFingerprint: String,
    val sourceHash: String,
    val buildHash: String,
    val gapFingerprint: String,
) {
    init {
        require(toolId.isNotBlank())
        require(artifactId.isNotBlank())
        require(candidateRecordFingerprint.isNotBlank())
        require(GeneratedToolArtifact.isBoundedSourceHash(sourceHash))
        require(buildHash.matches(Regex("[0-9a-f]{64}")))
        require(gapFingerprint.isNotBlank())
    }

    val id: String = StableFieldIds.fingerprint(
        "novel-capability-admission-subject/v1",
        requirementFingerprint(requirement),
        toolId,
        artifactId,
        candidateRecordFingerprint,
        sourceHash,
        buildHash,
        gapFingerprint,
    )

    val activationAllowed: Boolean = false

    companion object {
        fun create(
            gap: CapabilityGap,
            record: GeneratedToolRecord,
            artifact: GeneratedToolArtifact,
        ): NovelCapabilityAdmissionSubject {
            require(gap.type == CapabilityGapType.CAPABILITY_MISSING) {
                "Novel capability admission requires CAPABILITY_MISSING"
            }
            require(gap.candidateProviderIds.isEmpty()) {
                "Novel capability admission cannot hide an existing provider"
            }
            require(record.state == GeneratedToolState.TRIAL) {
                "Novel capability candidate must remain TRIAL"
            }
            require(!artifact.activationAllowed)
            require(artifact.matches(record)) {
                "Novel capability artifact does not match candidate lifecycle"
            }
            require(GeneratedToolArtifact.isBoundedSourceHash(record.manifest.sourceHash)) {
                "Novel capability admission accepts only bounded generated-tool artifacts"
            }
            val requirement = gap.requirement
            require(requirement.capabilityId == record.manifest.sourceCapability) {
                "Novel capability requirement differs from candidate capability"
            }
            require(requirement.requiredInputs == record.manifest.requiredInputs) {
                "Novel capability input contract differs from candidate contract"
            }
            require(requirement.requiredOutputs == record.manifest.requiredOutputs) {
                "Novel capability output contract differs from candidate contract"
            }
            return NovelCapabilityAdmissionSubject(
                requirement = requirement,
                toolId = record.manifest.toolId,
                artifactId = artifact.id,
                candidateRecordFingerprint = record.evolutionFingerprint(),
                sourceHash = artifact.sourceHash,
                buildHash = artifact.buildHash,
                gapFingerprint = gapFingerprint(gap),
            )
        }
    }
}

data class NovelCapabilityAdmissionEvidence(
    val subjectId: String,
    val toolId: String,
    val artifactId: String,
    val candidateRecordFingerprint: String,
    val requirementFingerprint: String,
    val providerSnapshotFingerprint: String,
    val evaluatedAt: Instant,
) {
    init {
        require(subjectId.isNotBlank())
        require(toolId.isNotBlank())
        require(artifactId.isNotBlank())
        require(candidateRecordFingerprint.isNotBlank())
        require(requirementFingerprint.isNotBlank())
        require(providerSnapshotFingerprint.isNotBlank())
    }

    val id: String = StableFieldIds.fingerprint(
        "novel-capability-admission-evidence/v1",
        subjectId,
        toolId,
        artifactId,
        candidateRecordFingerprint,
        requirementFingerprint,
        providerSnapshotFingerprint,
        evaluatedAt.toString(),
    )

    val activationAllowed: Boolean = false
}

/** Replays the live missing-capability fact before canary work; evidence itself has no authority. */
class NovelCapabilityAdmissionGate(
    private val capabilities: CapabilityRegistry,
    private val tools: GeneratedToolRegistry,
    private val artifacts: GeneratedToolArtifactRepository,
    private val now: () -> Instant = Instant::now,
) {
    private val gapDetector = CapabilityGapDetector(capabilities)

    suspend fun evaluate(subject: NovelCapabilityAdmissionSubject): NovelCapabilityAdmissionEvidence {
        val current = currentSnapshot(subject)
        return NovelCapabilityAdmissionEvidence(
            subjectId = subject.id,
            toolId = subject.toolId,
            artifactId = subject.artifactId,
            candidateRecordFingerprint = subject.candidateRecordFingerprint,
            requirementFingerprint = requirementFingerprint(subject.requirement),
            providerSnapshotFingerprint = current.providerSnapshotFingerprint,
            evaluatedAt = now(),
        )
    }

    suspend fun validate(
        evidence: NovelCapabilityAdmissionEvidence,
        subject: NovelCapabilityAdmissionSubject,
    ) {
        require(!evidence.activationAllowed && !subject.activationAllowed)
        val current = currentSnapshot(subject)
        require(evidence.subjectId == subject.id)
        require(evidence.toolId == subject.toolId)
        require(evidence.artifactId == subject.artifactId)
        require(evidence.candidateRecordFingerprint == subject.candidateRecordFingerprint)
        require(evidence.requirementFingerprint == requirementFingerprint(subject.requirement))
        require(evidence.providerSnapshotFingerprint == current.providerSnapshotFingerprint) {
            "Novel capability provider snapshot changed after admission"
        }
    }

    private suspend fun currentSnapshot(subject: NovelCapabilityAdmissionSubject): CurrentAdmissionSnapshot {
        val record = requireNotNull(tools.get(subject.toolId)) {
            "Unknown novel capability candidate ${subject.toolId}"
        }
        require(record.state == GeneratedToolState.TRIAL) {
            "Novel capability candidate must remain TRIAL"
        }
        require(record.evolutionFingerprint() == subject.candidateRecordFingerprint) {
            "Novel capability candidate record changed after subject creation"
        }
        val artifact = requireNotNull(artifacts.load(subject.toolId)) {
            "Novel capability artifact is missing"
        }
        require(artifact.id == subject.artifactId && artifact.matches(record)) {
            "Novel capability artifact changed after subject creation"
        }
        require(artifact.sourceHash == subject.sourceHash && artifact.buildHash == subject.buildHash)

        val gap = requireNotNull(gapDetector.detect(subject.requirement)) {
            "Novel capability gap disappeared before admission"
        }
        require(gap.type == CapabilityGapType.CAPABILITY_MISSING && gap.candidateProviderIds.isEmpty()) {
            "Novel capability admission requires the capability to remain completely missing"
        }
        require(gapFingerprint(gap) == subject.gapFingerprint) {
            "Novel capability gap changed after subject creation"
        }
        val providers = capabilities.providersFor(subject.requirement.capabilityId, includeUnavailable = true)
        require(providers.isEmpty()) {
            "Novel capability admission cannot proceed while any provider exists"
        }
        return CurrentAdmissionSnapshot(providerSnapshotFingerprint(providers))
    }

    private data class CurrentAdmissionSnapshot(val providerSnapshotFingerprint: String)
}

data class NovelCapabilityCanaryPolicy(
    val minimumCompletedOutcomes: Int = 5,
    val maxInvocations: Int = 5,
    val minimumSuccessRate: Double = 1.0,
    val minimumExpectedOutputRate: Double = 1.0,
) {
    init {
        require(minimumCompletedOutcomes > 0)
        require(maxInvocations >= minimumCompletedOutcomes)
        require(minimumSuccessRate in 0.0..1.0)
        require(minimumExpectedOutputRate in 0.0..1.0)
    }

    val id: String = StableFieldIds.fingerprint(
        "novel-capability-canary-policy/v1",
        minimumCompletedOutcomes.toString(),
        maxInvocations.toString(),
        minimumSuccessRate.toString(),
        minimumExpectedOutputRate.toString(),
    )
}

data class NovelCapabilityCanaryReservationRequest(
    val admissionEvidenceId: String,
    val toolId: String,
    val candidateRecordFingerprint: String,
    val invocationId: String,
    val inputFingerprint: String,
    val expectedOutputFingerprint: String,
    val maxInvocations: Int,
    val reservedAt: Instant,
) {
    init {
        require(admissionEvidenceId.isNotBlank())
        require(toolId.isNotBlank())
        require(candidateRecordFingerprint.isNotBlank())
        require(invocationId.isNotBlank())
        require(inputFingerprint.isNotBlank())
        require(expectedOutputFingerprint.isNotBlank())
        require(maxInvocations > 0)
    }
}

data class NovelCapabilityCanaryReservation(
    val admissionEvidenceId: String,
    val toolId: String,
    val candidateRecordFingerprint: String,
    val invocationId: String,
    val inputFingerprint: String,
    val expectedOutputFingerprint: String,
    val sequence: Int,
    val reservedAt: Instant,
) {
    init {
        require(admissionEvidenceId.isNotBlank())
        require(toolId.isNotBlank())
        require(candidateRecordFingerprint.isNotBlank())
        require(invocationId.isNotBlank())
        require(inputFingerprint.isNotBlank())
        require(expectedOutputFingerprint.isNotBlank())
        require(sequence > 0)
    }

    val id: String = StableFieldIds.fingerprint(
        "novel-capability-canary-reservation/v1",
        admissionEvidenceId,
        toolId,
        candidateRecordFingerprint,
        invocationId,
        inputFingerprint,
        expectedOutputFingerprint,
        sequence.toString(),
        reservedAt.toString(),
    )
}

enum class NovelCapabilityCanaryStopReason {
    SAFETY_VIOLATION,
    ADMISSION_INVALIDATED,
}

data class NovelCapabilityCanaryKillSwitchEvidence(
    val admissionEvidenceId: String,
    val toolId: String,
    val reason: NovelCapabilityCanaryStopReason,
    val triggerEvidenceId: String,
    val trippedAt: Instant,
) {
    init {
        require(admissionEvidenceId.isNotBlank())
        require(toolId.isNotBlank())
        require(triggerEvidenceId.isNotBlank())
    }

    val id: String = StableFieldIds.fingerprint(
        "novel-capability-canary-kill-switch/v1",
        admissionEvidenceId,
        toolId,
        reason.name,
        triggerEvidenceId,
        trippedAt.toString(),
    )

    val activationAllowed: Boolean = false
}

data class NovelCapabilityCanaryOutcome(
    val admissionEvidenceId: String,
    val reservationId: String,
    val toolId: String,
    val candidateRecordFingerprint: String,
    val invocationId: String,
    val trialResultFingerprint: String,
    val success: Boolean,
    val producedExpectedOutput: Boolean,
    val safetyViolation: Boolean,
    val latencyMs: Long,
    val recordedAt: Instant,
) {
    init {
        require(admissionEvidenceId.isNotBlank())
        require(reservationId.isNotBlank())
        require(toolId.isNotBlank())
        require(candidateRecordFingerprint.isNotBlank())
        require(invocationId.isNotBlank())
        require(trialResultFingerprint.isNotBlank())
        require(latencyMs >= 0)
    }

    val id: String = StableFieldIds.fingerprint(
        "novel-capability-canary-outcome/v1",
        admissionEvidenceId,
        reservationId,
        toolId,
        candidateRecordFingerprint,
        invocationId,
        trialResultFingerprint,
        success.toString(),
        producedExpectedOutput.toString(),
        safetyViolation.toString(),
        latencyMs.toString(),
        recordedAt.toString(),
    )
}

sealed interface NovelCapabilityCanaryReserveResult {
    data class Reserved(val reservation: NovelCapabilityCanaryReservation, val duplicate: Boolean) :
        NovelCapabilityCanaryReserveResult
    data class Exhausted(val usedInvocations: Int) : NovelCapabilityCanaryReserveResult
    data class Stopped(val evidence: NovelCapabilityCanaryKillSwitchEvidence) : NovelCapabilityCanaryReserveResult
}

sealed interface NovelCapabilityCanaryOutcomeWriteResult {
    data class Recorded(
        val outcome: NovelCapabilityCanaryOutcome,
        val killSwitch: NovelCapabilityCanaryKillSwitchEvidence?,
    ) : NovelCapabilityCanaryOutcomeWriteResult

    data class Duplicate(
        val outcome: NovelCapabilityCanaryOutcome,
        val killSwitch: NovelCapabilityCanaryKillSwitchEvidence?,
    ) : NovelCapabilityCanaryOutcomeWriteResult

    data class Conflict(val existingOutcomeId: String) : NovelCapabilityCanaryOutcomeWriteResult
}

interface NovelCapabilityCanaryStore {
    suspend fun reserveNovel(request: NovelCapabilityCanaryReservationRequest): NovelCapabilityCanaryReserveResult
    suspend fun novelReservation(admissionEvidenceId: String, invocationId: String): NovelCapabilityCanaryReservation?
    suspend fun novelReservations(admissionEvidenceId: String): List<NovelCapabilityCanaryReservation>
    suspend fun recordNovelOutcome(outcome: NovelCapabilityCanaryOutcome): NovelCapabilityCanaryOutcomeWriteResult
    suspend fun novelOutcome(admissionEvidenceId: String, invocationId: String): NovelCapabilityCanaryOutcome?
    suspend fun novelOutcomes(admissionEvidenceId: String): List<NovelCapabilityCanaryOutcome>
    suspend fun tripNovel(evidence: NovelCapabilityCanaryKillSwitchEvidence): NovelCapabilityCanaryKillSwitchEvidence
    suspend fun novelKillSwitch(admissionEvidenceId: String): NovelCapabilityCanaryKillSwitchEvidence?
}

internal class InMemoryNovelCapabilityCanaryStore : NovelCapabilityCanaryStore {
    private data class Bucket(
        val reservations: LinkedHashMap<String, NovelCapabilityCanaryReservation> = linkedMapOf(),
        val outcomes: LinkedHashMap<String, NovelCapabilityCanaryOutcome> = linkedMapOf(),
        var killSwitch: NovelCapabilityCanaryKillSwitchEvidence? = null,
    )

    private val mutex = Mutex()
    private val buckets = mutableMapOf<String, Bucket>()

    override suspend fun reserveNovel(request: NovelCapabilityCanaryReservationRequest): NovelCapabilityCanaryReserveResult =
        mutex.withLock {
            val bucket = buckets.getOrPut(request.admissionEvidenceId) { Bucket() }
            bucket.killSwitch?.let { return@withLock NovelCapabilityCanaryReserveResult.Stopped(it) }
            bucket.reservations[request.invocationId]?.let { existing ->
                require(existing.toolId == request.toolId)
                require(existing.candidateRecordFingerprint == request.candidateRecordFingerprint)
                require(existing.inputFingerprint == request.inputFingerprint)
                require(existing.expectedOutputFingerprint == request.expectedOutputFingerprint)
                return@withLock NovelCapabilityCanaryReserveResult.Reserved(existing, duplicate = true)
            }
            if (bucket.reservations.size >= request.maxInvocations) {
                return@withLock NovelCapabilityCanaryReserveResult.Exhausted(bucket.reservations.size)
            }
            val reservation = NovelCapabilityCanaryReservation(
                admissionEvidenceId = request.admissionEvidenceId,
                toolId = request.toolId,
                candidateRecordFingerprint = request.candidateRecordFingerprint,
                invocationId = request.invocationId,
                inputFingerprint = request.inputFingerprint,
                expectedOutputFingerprint = request.expectedOutputFingerprint,
                sequence = bucket.reservations.size + 1,
                reservedAt = request.reservedAt,
            )
            bucket.reservations[request.invocationId] = reservation
            NovelCapabilityCanaryReserveResult.Reserved(reservation, duplicate = false)
        }

    override suspend fun novelReservation(admissionEvidenceId: String, invocationId: String): NovelCapabilityCanaryReservation? =
        mutex.withLock { buckets[admissionEvidenceId]?.reservations?.get(invocationId) }

    override suspend fun novelReservations(admissionEvidenceId: String): List<NovelCapabilityCanaryReservation> =
        mutex.withLock { buckets[admissionEvidenceId]?.reservations?.values?.sortedBy { it.sequence }.orEmpty() }

    override suspend fun recordNovelOutcome(outcome: NovelCapabilityCanaryOutcome): NovelCapabilityCanaryOutcomeWriteResult =
        mutex.withLock {
            val bucket = buckets.getOrPut(outcome.admissionEvidenceId) { Bucket() }
            bucket.outcomes[outcome.invocationId]?.let { existing ->
                return@withLock if (existing == outcome) {
                    NovelCapabilityCanaryOutcomeWriteResult.Duplicate(existing, bucket.killSwitch)
                } else {
                    NovelCapabilityCanaryOutcomeWriteResult.Conflict(existing.id)
                }
            }
            require(bucket.killSwitch == null) { "Cannot record novel canary outcome after stop" }
            val reservation = requireNotNull(bucket.reservations[outcome.invocationId]) {
                "Novel canary outcome requires a reservation"
            }
            require(reservation.id == outcome.reservationId)
            require(reservation.toolId == outcome.toolId)
            require(reservation.candidateRecordFingerprint == outcome.candidateRecordFingerprint)
            bucket.outcomes[outcome.invocationId] = outcome
            val stop = if (outcome.safetyViolation) {
                NovelCapabilityCanaryKillSwitchEvidence(
                    admissionEvidenceId = outcome.admissionEvidenceId,
                    toolId = outcome.toolId,
                    reason = NovelCapabilityCanaryStopReason.SAFETY_VIOLATION,
                    triggerEvidenceId = outcome.id,
                    trippedAt = outcome.recordedAt,
                ).also { bucket.killSwitch = it }
            } else null
            NovelCapabilityCanaryOutcomeWriteResult.Recorded(outcome, stop)
        }

    override suspend fun novelOutcome(admissionEvidenceId: String, invocationId: String): NovelCapabilityCanaryOutcome? =
        mutex.withLock { buckets[admissionEvidenceId]?.outcomes?.get(invocationId) }

    override suspend fun novelOutcomes(admissionEvidenceId: String): List<NovelCapabilityCanaryOutcome> =
        mutex.withLock { buckets[admissionEvidenceId]?.outcomes?.values?.sortedBy { it.invocationId }.orEmpty() }

    override suspend fun tripNovel(evidence: NovelCapabilityCanaryKillSwitchEvidence): NovelCapabilityCanaryKillSwitchEvidence =
        mutex.withLock {
            val bucket = buckets.getOrPut(evidence.admissionEvidenceId) { Bucket() }
            bucket.killSwitch?.let { existing ->
                require(existing.toolId == evidence.toolId)
                return@withLock existing
            }
            bucket.killSwitch = evidence
            evidence
        }

    override suspend fun novelKillSwitch(admissionEvidenceId: String): NovelCapabilityCanaryKillSwitchEvidence? =
        mutex.withLock { buckets[admissionEvidenceId]?.killSwitch }
}

sealed interface NovelCapabilityCanaryExecutionResult {
    data class Executed(
        val outcome: NovelCapabilityCanaryOutcome,
        val duplicate: Boolean,
        val execution: GeneratedToolTrialExecutionResult?,
        val killSwitch: NovelCapabilityCanaryKillSwitchEvidence?,
    ) : NovelCapabilityCanaryExecutionResult

    data class Blocked(
        val reasons: List<String>,
        val killSwitch: NovelCapabilityCanaryKillSwitchEvidence?,
    ) : NovelCapabilityCanaryExecutionResult

    data class Exhausted(val usedInvocations: Int) : NovelCapabilityCanaryExecutionResult
}

/** Executes non-productive novel canaries only through the bounded TRIAL runner. */
class NovelCapabilityCanaryCoordinator(
    private val admissionGate: NovelCapabilityAdmissionGate,
    private val trialRunner: GeneratedToolTrialRunner,
    private val trialLedger: GeneratedToolTrialLedger,
    private val store: NovelCapabilityCanaryStore,
    private val policy: NovelCapabilityCanaryPolicy = NovelCapabilityCanaryPolicy(),
    private val now: () -> Instant = Instant::now,
) {
    suspend fun execute(
        subject: NovelCapabilityAdmissionSubject,
        admission: NovelCapabilityAdmissionEvidence,
        invocation: GeneratedToolTrialInvocation,
    ): NovelCapabilityCanaryExecutionResult {
        require(invocation.toolId == subject.toolId)
        require(invocation.requestedPermissions.isEmpty()) {
            "Novel canary forbids requested permissions and productive effects"
        }
        admissionGate.validate(admission, subject)
        store.novelKillSwitch(admission.id)?.let {
            return NovelCapabilityCanaryExecutionResult.Blocked(listOf("canary-stopped:${it.reason.name}"), it)
        }
        store.novelOutcome(admission.id, invocation.invocationId)?.let { existing ->
            return NovelCapabilityCanaryExecutionResult.Executed(
                outcome = existing,
                duplicate = true,
                execution = null,
                killSwitch = store.novelKillSwitch(admission.id),
            )
        }

        val reserve = store.reserveNovel(
            NovelCapabilityCanaryReservationRequest(
                admissionEvidenceId = admission.id,
                toolId = subject.toolId,
                candidateRecordFingerprint = subject.candidateRecordFingerprint,
                invocationId = invocation.invocationId,
                inputFingerprint = StableFieldIds.fingerprint("novel-canary-input/v1", invocation.input),
                expectedOutputFingerprint = StableFieldIds.fingerprint("novel-canary-expected/v1", invocation.expectedOutput),
                maxInvocations = policy.maxInvocations,
                reservedAt = now(),
            )
        )
        val reservation = when (reserve) {
            is NovelCapabilityCanaryReserveResult.Exhausted ->
                return NovelCapabilityCanaryExecutionResult.Exhausted(reserve.usedInvocations)
            is NovelCapabilityCanaryReserveResult.Stopped ->
                return NovelCapabilityCanaryExecutionResult.Blocked(
                    listOf("canary-stopped:${reserve.evidence.reason.name}"),
                    reserve.evidence,
                )
            is NovelCapabilityCanaryReserveResult.Reserved -> reserve.reservation
        }

        try {
            admissionGate.validate(admission, subject)
        } catch (failure: IllegalArgumentException) {
            val stop = store.tripNovel(
                NovelCapabilityCanaryKillSwitchEvidence(
                    admissionEvidenceId = admission.id,
                    toolId = subject.toolId,
                    reason = NovelCapabilityCanaryStopReason.ADMISSION_INVALIDATED,
                    triggerEvidenceId = reservation.id,
                    trippedAt = now(),
                )
            )
            return NovelCapabilityCanaryExecutionResult.Blocked(
                listOf("admission-invalidated:${failure.message.orEmpty()}"),
                stop,
            )
        }

        val existingTrial = trialLedger.evidence(subject.toolId).results
            .firstOrNull { it.invocationId == invocation.invocationId }
        val execution = if (existingTrial == null) trialRunner.execute(invocation) else null
        val trial = trialLedger.evidence(subject.toolId).results
            .firstOrNull { it.invocationId == invocation.invocationId }

        if (trial == null) {
            val stop = store.tripNovel(
                NovelCapabilityCanaryKillSwitchEvidence(
                    admissionEvidenceId = admission.id,
                    toolId = subject.toolId,
                    reason = NovelCapabilityCanaryStopReason.ADMISSION_INVALIDATED,
                    triggerEvidenceId = reservation.id,
                    trippedAt = now(),
                )
            )
            val reasons = when (execution) {
                is GeneratedToolTrialExecutionResult.Blocked -> execution.reasons
                is GeneratedToolTrialExecutionResult.Failed -> listOf(execution.reason)
                else -> listOf("trial-result-missing")
            }
            return NovelCapabilityCanaryExecutionResult.Blocked(reasons.distinct().sorted(), stop)
        }

        val outcome = trial.toNovelOutcome(admission, subject, reservation)
        return when (val write = store.recordNovelOutcome(outcome)) {
            is NovelCapabilityCanaryOutcomeWriteResult.Conflict ->
                error("Conflicting novel canary outcome already recorded: ${write.existingOutcomeId}")
            is NovelCapabilityCanaryOutcomeWriteResult.Duplicate ->
                NovelCapabilityCanaryExecutionResult.Executed(write.outcome, true, execution, write.killSwitch)
            is NovelCapabilityCanaryOutcomeWriteResult.Recorded ->
                NovelCapabilityCanaryExecutionResult.Executed(write.outcome, false, execution, write.killSwitch)
        }
    }

    private fun GeneratedToolTrialResult.toNovelOutcome(
        admission: NovelCapabilityAdmissionEvidence,
        subject: NovelCapabilityAdmissionSubject,
        reservation: NovelCapabilityCanaryReservation,
    ): NovelCapabilityCanaryOutcome = NovelCapabilityCanaryOutcome(
        admissionEvidenceId = admission.id,
        reservationId = reservation.id,
        toolId = subject.toolId,
        candidateRecordFingerprint = subject.candidateRecordFingerprint,
        invocationId = invocationId,
        trialResultFingerprint = fingerprint(),
        success = success,
        producedExpectedOutput = producedExpectedOutput,
        safetyViolation = safetyViolation,
        latencyMs = latencyMs,
        recordedAt = recordedAt,
    )
}

enum class NovelCapabilityCanaryReadinessDecision {
    READY_FOR_REVIEW,
    INSUFFICIENT_EVIDENCE,
    NOT_READY,
    STOPPED,
    ADMISSION_STALE,
}

data class NovelCapabilityCanaryReadinessEvidence(
    val admissionEvidenceId: String,
    val subjectId: String,
    val toolId: String,
    val candidateRecordFingerprint: String,
    val artifactId: String,
    val policyId: String,
    val reservedInvocations: Int,
    val completedOutcomes: Int,
    val successes: Int,
    val expectedOutputs: Int,
    val safetyViolations: Int,
    val averageLatencyMs: Double,
    val decision: NovelCapabilityCanaryReadinessDecision,
    val reasons: List<String>,
    val outcomeEvidenceIds: List<String>,
    val killSwitchEvidenceId: String? = null,
) {
    init {
        require(admissionEvidenceId.isNotBlank())
        require(subjectId.isNotBlank())
        require(toolId.isNotBlank())
        require(candidateRecordFingerprint.isNotBlank())
        require(artifactId.isNotBlank())
        require(policyId.isNotBlank())
        require(reservedInvocations >= 0 && completedOutcomes >= 0)
        require(successes in 0..completedOutcomes)
        require(expectedOutputs in 0..completedOutcomes)
        require(safetyViolations in 0..completedOutcomes)
        require(averageLatencyMs >= 0.0)
        require(reasons.isNotEmpty())
        require(killSwitchEvidenceId == null || killSwitchEvidenceId.isNotBlank())
    }

    val id: String = StableFieldIds.fingerprint(
        "novel-capability-canary-readiness-evidence/v1",
        admissionEvidenceId,
        subjectId,
        toolId,
        candidateRecordFingerprint,
        artifactId,
        policyId,
        reservedInvocations.toString(),
        completedOutcomes.toString(),
        successes.toString(),
        expectedOutputs.toString(),
        safetyViolations.toString(),
        averageLatencyMs.toString(),
        decision.name,
        killSwitchEvidenceId.orEmpty(),
        *reasons.sorted().map { "reason:$it" }.toTypedArray(),
        *outcomeEvidenceIds.sorted().map { "outcome:$it" }.toTypedArray(),
    )

    val activationAllowed: Boolean = false
}

class NovelCapabilityCanaryReadinessGate(
    private val admissionGate: NovelCapabilityAdmissionGate,
    private val trialLedger: GeneratedToolTrialLedger,
    private val store: NovelCapabilityCanaryStore,
    private val policy: NovelCapabilityCanaryPolicy = NovelCapabilityCanaryPolicy(),
) {
    suspend fun evaluate(
        subject: NovelCapabilityAdmissionSubject,
        admission: NovelCapabilityAdmissionEvidence,
    ): NovelCapabilityCanaryReadinessEvidence {
        val reservations = store.novelReservations(admission.id)
        val outcomes = store.novelOutcomes(admission.id)
        val stop = store.novelKillSwitch(admission.id)
        val admissionStale = if (stop == null) {
            try {
                admissionGate.validate(admission, subject)
                false
            } catch (_: IllegalArgumentException) {
                true
            }
        } else false

        val trialByInvocation = trialLedger.evidence(subject.toolId).results.associateBy { it.invocationId }
        val trialLedgerExact = outcomes.all { outcome ->
            trialByInvocation[outcome.invocationId]?.fingerprint() == outcome.trialResultFingerprint
        }
        val successes = outcomes.count { it.success }
        val expected = outcomes.count { it.producedExpectedOutput }
        val safety = outcomes.count { it.safetyViolation }
        val averageLatency = outcomes.map { it.latencyMs.toDouble() }.takeIf { it.isNotEmpty() }?.average() ?: 0.0

        val reasons = mutableListOf<String>()
        val decision = when {
            stop != null -> {
                reasons += "canary-stopped:${stop.reason.name}"
                NovelCapabilityCanaryReadinessDecision.STOPPED
            }
            admissionStale -> {
                reasons += "admission-stale"
                NovelCapabilityCanaryReadinessDecision.ADMISSION_STALE
            }
            !trialLedgerExact -> {
                reasons += "trial-ledger-mismatch"
                NovelCapabilityCanaryReadinessDecision.NOT_READY
            }
            reservations.size != outcomes.size -> {
                reasons += "pending-outcomes:${reservations.size - outcomes.size}"
                NovelCapabilityCanaryReadinessDecision.INSUFFICIENT_EVIDENCE
            }
            outcomes.size < policy.minimumCompletedOutcomes -> {
                reasons += "insufficient-outcomes:${outcomes.size}<${policy.minimumCompletedOutcomes}"
                NovelCapabilityCanaryReadinessDecision.INSUFFICIENT_EVIDENCE
            }
            safety > 0 -> {
                reasons += "safety-violations:$safety"
                NovelCapabilityCanaryReadinessDecision.NOT_READY
            }
            successes.toDouble() / outcomes.size < policy.minimumSuccessRate -> {
                reasons += "success-rate:${successes.toDouble() / outcomes.size}<${policy.minimumSuccessRate}"
                NovelCapabilityCanaryReadinessDecision.NOT_READY
            }
            expected.toDouble() / outcomes.size < policy.minimumExpectedOutputRate -> {
                reasons += "expected-output-rate:${expected.toDouble() / outcomes.size}<${policy.minimumExpectedOutputRate}"
                NovelCapabilityCanaryReadinessDecision.NOT_READY
            }
            else -> {
                reasons += "ready-for-independent-promotion-review"
                NovelCapabilityCanaryReadinessDecision.READY_FOR_REVIEW
            }
        }

        return NovelCapabilityCanaryReadinessEvidence(
            admissionEvidenceId = admission.id,
            subjectId = subject.id,
            toolId = subject.toolId,
            candidateRecordFingerprint = subject.candidateRecordFingerprint,
            artifactId = subject.artifactId,
            policyId = policy.id,
            reservedInvocations = reservations.size,
            completedOutcomes = outcomes.size,
            successes = successes,
            expectedOutputs = expected,
            safetyViolations = safety,
            averageLatencyMs = averageLatency,
            decision = decision,
            reasons = reasons,
            outcomeEvidenceIds = outcomes.map { it.id }.sorted(),
            killSwitchEvidenceId = stop?.id,
        )
    }
}

internal fun requirementFingerprint(requirement: CapabilityRequirement): String = StableFieldIds.fingerprint(
    "novel-capability-requirement/v1",
    requirement.capabilityId.value,
    requirement.severity.name,
    *requirement.requiredInputs.sorted().map { "input:$it" }.toTypedArray(),
    *requirement.requiredOutputs.sorted().map { "output:$it" }.toTypedArray(),
)

internal fun gapFingerprint(gap: CapabilityGap): String = StableFieldIds.fingerprint(
    "novel-capability-gap/v1",
    requirementFingerprint(gap.requirement),
    gap.type.name,
    *gap.candidateProviderIds.sorted().map { "provider:$it" }.toTypedArray(),
)

internal fun providerSnapshotFingerprint(providers: List<CapabilityDescriptor>): String = StableFieldIds.fingerprint(
    "novel-capability-provider-snapshot/v1",
    providers.size.toString(),
    *providers.sortedWith(compareBy<CapabilityDescriptor>({ it.capabilityId.value }, { it.providerId }))
        .map { it.evolutionFingerprint() }
        .toTypedArray(),
)
