package app.lifeos.core.runtime.convergence

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.field.HypothesisId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GapSeverity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

@JvmInline
value class ConvergenceDecisionCheckpointId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid convergence checkpoint id" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid convergence checkpoint digest"
        }
    }
    override fun toString(): String = value

    companion object {
        const val PREFIX = "convergence-checkpoint:"
    }
}

data class ConvergenceSnapshotReference(
    val domainId: FieldDomainId,
    val snapshotId: FieldSnapshotId,
    val contentFingerprint: String,
) {
    init { require(contentFingerprint.isNotBlank()) }
}

data class ConvergenceDecisionCheckpoint(
    val id: ConvergenceDecisionCheckpointId,
    val sourceRequestId: String,
    val sourceFingerprint: String,
    val policyFingerprint: String,
    val workingSetFingerprint: String?,
    val snapshots: List<ConvergenceSnapshotReference>,
    val decision: ConvergenceDecision,
) {
    init {
        require(sourceRequestId.isNotBlank())
        require(sourceFingerprint.isNotBlank())
        require(policyFingerprint.isNotBlank())
        require(workingSetFingerprint == null || workingSetFingerprint.isNotBlank())
        require(snapshots.map { it.domainId }.distinct().size == snapshots.size)
        require(decision.sourceFingerprint == sourceFingerprint)
        require(id == expectedId()) { "Convergence checkpoint id/content mismatch" }
    }

    fun contentFingerprint(): String = computeContentFingerprint(
        sourceRequestId = sourceRequestId,
        sourceFingerprint = sourceFingerprint,
        policyFingerprint = policyFingerprint,
        workingSetFingerprint = workingSetFingerprint,
        snapshots = snapshots,
        decisionId = decision.id,
    )

    private fun expectedId(): ConvergenceDecisionCheckpointId =
        ConvergenceDecisionCheckpointId("${ConvergenceDecisionCheckpointId.PREFIX}${contentFingerprint()}")

    companion object {
        fun create(
            request: ConvergenceDecisionRequest,
            decision: ConvergenceDecision,
            policy: ConvergenceDecisionPolicy,
        ): ConvergenceDecisionCheckpoint {
            val snapshots = request.convergence.domainResults
                .sortedBy { it.state.domainId.value }
                .map { result ->
                    ConvergenceSnapshotReference(
                        domainId = result.state.domainId,
                        snapshotId = result.snapshot.id,
                        contentFingerprint = result.snapshot.contentFingerprint(),
                    )
                }
            val sourceFingerprint = decision.sourceFingerprint
            val policyFingerprint = policy.fingerprint()
            val fingerprint = computeContentFingerprint(
                sourceRequestId = request.source.id,
                sourceFingerprint = sourceFingerprint,
                policyFingerprint = policyFingerprint,
                workingSetFingerprint = request.workingSetFingerprint,
                snapshots = snapshots,
                decisionId = decision.id,
            )
            return ConvergenceDecisionCheckpoint(
                id = ConvergenceDecisionCheckpointId(
                    "${ConvergenceDecisionCheckpointId.PREFIX}$fingerprint"
                ),
                sourceRequestId = request.source.id,
                sourceFingerprint = sourceFingerprint,
                policyFingerprint = policyFingerprint,
                workingSetFingerprint = request.workingSetFingerprint,
                snapshots = snapshots,
                decision = decision,
            )
        }

        private fun computeContentFingerprint(
            sourceRequestId: String,
            sourceFingerprint: String,
            policyFingerprint: String,
            workingSetFingerprint: String?,
            snapshots: List<ConvergenceSnapshotReference>,
            decisionId: ConvergenceDecisionId,
        ): String = StableFieldIds.fingerprint(
            "convergence-decision-checkpoint/v1",
            sourceRequestId,
            sourceFingerprint,
            policyFingerprint,
            workingSetFingerprint.orEmpty(),
            decisionId.value,
            *snapshots.sortedBy { it.domainId.value }.flatMap { reference ->
                listOf(
                    reference.domainId.value,
                    reference.snapshotId.value,
                    reference.contentFingerprint,
                )
            }.toTypedArray(),
        )
    }
}

sealed interface ConvergenceDecisionCheckpointWriteResult {
    val checkpoint: ConvergenceDecisionCheckpoint

    data class Stored(override val checkpoint: ConvergenceDecisionCheckpoint) : ConvergenceDecisionCheckpointWriteResult
    data class Duplicate(override val checkpoint: ConvergenceDecisionCheckpoint) : ConvergenceDecisionCheckpointWriteResult
}

data class ConvergenceDecisionCheckpointLoadReport(
    val checkpoints: List<ConvergenceDecisionCheckpoint>,
    val unreadableEntries: List<String>,
) {
    val isCorrupted: Boolean get() = unreadableEntries.isNotEmpty()
}

interface ConvergenceDecisionCheckpointRepository {
    suspend fun save(checkpoint: ConvergenceDecisionCheckpoint): ConvergenceDecisionCheckpointWriteResult
    suspend fun load(id: ConvergenceDecisionCheckpointId): ConvergenceDecisionCheckpoint?
    suspend fun loadReport(): ConvergenceDecisionCheckpointLoadReport
}

/** Persistence-before-exposure wrapper for V5 decisions. */
class DurableConvergenceDecisionCoordinator(
    private val repository: ConvergenceDecisionCheckpointRepository,
    private val policy: ConvergenceDecisionPolicy = ConvergenceDecisionPolicy(),
    private val engine: ConvergenceDecisionEngine = ConvergenceDecisionEngine(policy),
) {
    suspend fun decide(request: ConvergenceDecisionRequest): ConvergenceDecisionCheckpoint {
        val decision = engine.decide(request)
        val checkpoint = ConvergenceDecisionCheckpoint.create(request, decision, policy)
        val persisted = repository.save(checkpoint).checkpoint
        require(persisted == checkpoint) { "Persisted convergence checkpoint differs from decision" }
        return persisted
    }

    suspend fun loadVerified(): List<ConvergenceDecisionCheckpoint> {
        val report = repository.loadReport()
        check(!report.isCorrupted) {
            "Convergence checkpoint history is corrupted: ${report.unreadableEntries.joinToString(",")}" 
        }
        return report.checkpoints.sortedBy { it.id.value }
    }
}

object ConvergenceDecisionCheckpointCodec {
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
    private const val MAGIC = 0x4c4f5335 // LOS5
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 16 * 1024
    private const val MAX_SNAPSHOTS = 64
    private const val MAX_HYPOTHESES = 1024
    private const val MAX_CANDIDATES = 2048
    private const val MAX_EVIDENCE_REQUESTS = 512
    private const val MAX_CAPABILITY_GAPS = 256
    private const val MAX_REASONS = 512
    private const val MAX_SET_ITEMS = 256

    fun encode(checkpoint: ConvergenceDecisionCheckpoint): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeString(checkpoint.id.value)
            data.writeString(checkpoint.sourceRequestId)
            data.writeString(checkpoint.sourceFingerprint)
            data.writeString(checkpoint.policyFingerprint)
            data.writeNullableString(checkpoint.workingSetFingerprint)
            data.writeCount(checkpoint.snapshots.size, MAX_SNAPSHOTS)
            checkpoint.snapshots.sortedBy { it.domainId.value }.forEach { reference ->
                data.writeString(reference.domainId.value)
                data.writeString(reference.snapshotId.value)
                data.writeString(reference.contentFingerprint)
            }
            data.writeDecision(checkpoint.decision)
        }
        return output.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_PAYLOAD_BYTES) { "Convergence checkpoint payload too large" }
        }
    }

    fun decode(payload: ByteArray): ConvergenceDecisionCheckpoint {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) { "Invalid convergence checkpoint payload size" }
        val input = DataInputStream(ByteArrayInputStream(payload))
        require(input.readInt() == MAGIC) { "Unsupported convergence checkpoint magic" }
        require(input.readInt() == VERSION) { "Unsupported convergence checkpoint version" }
        val id = ConvergenceDecisionCheckpointId(input.readString())
        val sourceRequestId = input.readString()
        val sourceFingerprint = input.readString()
        val policyFingerprint = input.readString()
        val workingSetFingerprint = input.readNullableString()
        val snapshots = List(input.readCount(MAX_SNAPSHOTS)) {
            ConvergenceSnapshotReference(
                domainId = FieldDomainId(input.readString()),
                snapshotId = FieldSnapshotId(input.readString()),
                contentFingerprint = input.readString(),
            )
        }
        val decision = input.readDecision()
        require(input.available() == 0) { "Trailing convergence checkpoint bytes" }
        return ConvergenceDecisionCheckpoint(
            id = id,
            sourceRequestId = sourceRequestId,
            sourceFingerprint = sourceFingerprint,
            policyFingerprint = policyFingerprint,
            workingSetFingerprint = workingSetFingerprint,
            snapshots = snapshots,
            decision = decision,
        )
    }

    private fun DataOutputStream.writeDecision(decision: ConvergenceDecision) {
        writeString(decision.id.value)
        writeString(decision.state.name)
        writeString(decision.sourceFingerprint)
        writeCount(decision.selectedHypothesisIds.size, MAX_HYPOTHESES)
        decision.selectedHypothesisIds.sortedBy { it.value }.forEach { writeString(it.value) }

        writeCount(decision.candidates.size, MAX_CANDIDATES)
        decision.candidates.forEach { candidate ->
            writeString(candidate.domainId.value)
            writeString(candidate.hypothesisId.value)
            writeDouble(candidate.totalScore)
            writeDouble(candidate.evidenceScore)
            writeDouble(candidate.contradiction)
            writeDouble(candidate.marginToRunnerUp)
            writeDouble(candidate.conflictSeverity)
            writeInt(candidate.freshSupportingEvidence)
            writeDouble(candidate.confidenceBand.lower)
            writeDouble(candidate.confidenceBand.point)
            writeDouble(candidate.confidenceBand.upper)
        }

        writeCount(decision.evidenceRequests.size, MAX_EVIDENCE_REQUESTS)
        decision.evidenceRequests.forEach { request ->
            writeString(request.id.value)
            writeString(request.kind.name)
            writeString(request.domainId.value)
            writeCount(request.hypothesisIds.size, MAX_HYPOTHESES)
            request.hypothesisIds.forEach { writeString(it.value) }
            writeString(request.semanticKey)
            writeString(request.reason)
            writeString(request.sourceFingerprint)
        }

        writeCount(decision.capabilityGaps.size, MAX_CAPABILITY_GAPS)
        decision.capabilityGaps.forEach(::writeCapabilityGap)

        writeBoolean(decision.escalation != null)
        decision.escalation?.let { escalation ->
            writeString(escalation.target.name)
            writeString(escalation.reason)
            writeCount(escalation.hypothesisIds.size, MAX_HYPOTHESES)
            escalation.hypothesisIds.forEach { writeString(it.value) }
            writeCount(escalation.evidenceRequestIds.size, MAX_EVIDENCE_REQUESTS)
            escalation.evidenceRequestIds.forEach { writeString(it.value) }
            writeCount(escalation.capabilityIds.size, MAX_SET_ITEMS)
            escalation.capabilityIds.forEach(::writeString)
            writeString(escalation.sourceFingerprint)
        }

        writeCount(decision.reasons.size, MAX_REASONS)
        decision.reasons.forEach(::writeString)
    }

    private fun DataInputStream.readDecision(): ConvergenceDecision {
        val id = ConvergenceDecisionId(readString())
        val state = ConvergenceDecisionState.valueOf(readString())
        val sourceFingerprint = readString()
        val selected = List(readCount(MAX_HYPOTHESES)) { HypothesisId(readString()) }
        val candidates = List(readCount(MAX_CANDIDATES)) {
            ConvergenceCandidateAssessment(
                domainId = FieldDomainId(readString()),
                hypothesisId = HypothesisId(readString()),
                totalScore = readDouble(),
                evidenceScore = readDouble(),
                contradiction = readDouble(),
                marginToRunnerUp = readDouble(),
                conflictSeverity = readDouble(),
                freshSupportingEvidence = readInt().also { require(it >= 0) },
                confidenceBand = ConvergenceConfidenceBand(
                    lower = readDouble(),
                    point = readDouble(),
                    upper = readDouble(),
                ),
            )
        }
        val evidenceRequests = List(readCount(MAX_EVIDENCE_REQUESTS)) {
            ConvergenceEvidenceRequest(
                id = EvidenceRequestId(readString()),
                kind = ConvergenceEvidenceGapKind.valueOf(readString()),
                domainId = FieldDomainId(readString()),
                hypothesisIds = List(readCount(MAX_HYPOTHESES)) { HypothesisId(readString()) },
                semanticKey = readString(),
                reason = readString(),
                sourceFingerprint = readString(),
            )
        }
        val capabilityGaps = List(readCount(MAX_CAPABILITY_GAPS)) { readCapabilityGap() }
        val escalation = if (readBoolean()) {
            ConvergenceEscalationRequest(
                target = ConvergenceEscalationTarget.valueOf(readString()),
                reason = readString(),
                hypothesisIds = List(readCount(MAX_HYPOTHESES)) { HypothesisId(readString()) },
                evidenceRequestIds = List(readCount(MAX_EVIDENCE_REQUESTS)) { EvidenceRequestId(readString()) },
                capabilityIds = List(readCount(MAX_SET_ITEMS)) { readString() },
                sourceFingerprint = readString(),
            )
        } else {
            null
        }
        val reasons = List(readCount(MAX_REASONS)) { readString() }
        return ConvergenceDecision(
            id = id,
            state = state,
            selectedHypothesisIds = selected,
            candidates = candidates,
            evidenceRequests = evidenceRequests,
            capabilityGaps = capabilityGaps,
            escalation = escalation,
            reasons = reasons,
            sourceFingerprint = sourceFingerprint,
        )
    }

    private fun DataOutputStream.writeCapabilityGap(gap: CapabilityGap) {
        writeString(gap.requirement.capabilityId.value)
        writeString(gap.requirement.severity.name)
        writeString(gap.type.name)
        writeCount(gap.requirement.requiredInputs.size, MAX_SET_ITEMS)
        gap.requirement.requiredInputs.sorted().forEach(::writeString)
        writeCount(gap.requirement.requiredOutputs.size, MAX_SET_ITEMS)
        gap.requirement.requiredOutputs.sorted().forEach(::writeString)
        writeCount(gap.candidateProviderIds.size, MAX_SET_ITEMS)
        gap.candidateProviderIds.sorted().forEach(::writeString)
    }

    private fun DataInputStream.readCapabilityGap(): CapabilityGap = CapabilityGap(
        requirement = CapabilityRequirement(
            capabilityId = CapabilityId(readString()),
            severity = GapSeverity.valueOf(readString()),
            requiredInputs = List(readCount(MAX_SET_ITEMS)) { readString() }.toSet(),
            requiredOutputs = List(readCount(MAX_SET_ITEMS)) { readString() }.toSet(),
        ),
        type = CapabilityGapType.valueOf(readString()),
        candidateProviderIds = List(readCount(MAX_SET_ITEMS)) { readString() },
    )

    private fun DataOutputStream.writeCount(value: Int, max: Int) {
        require(value in 0..max) { "Invalid convergence checkpoint collection size" }
        writeInt(value)
    }

    private fun DataInputStream.readCount(max: Int): Int = readInt().also {
        require(it in 0..max) { "Invalid convergence checkpoint collection size" }
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readString() else null

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Convergence checkpoint string too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) { "Invalid convergence checkpoint string length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
