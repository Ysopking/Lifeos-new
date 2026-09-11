package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

@JvmInline
value class ToolWorkshopJobId(val value: String) {
    init { require(value.startsWith(PREFIX)) { "Invalid ToolWorkshop job id" } }
    override fun toString(): String = value

    companion object {
        const val PREFIX = "tool-workshop:"

        fun create(
            sourcePhotonId: String,
            sourceRevision: Long,
            capabilityId: CapabilityId,
            severity: GapSeverity,
            gapType: CapabilityGapType,
            requiredInputs: Set<String>,
            requiredOutputs: Set<String>,
            candidateProviderIds: List<String>,
            policyVersion: String,
            workshopVersion: String,
        ): ToolWorkshopJobId {
            require(sourcePhotonId.isNotBlank())
            require(sourceRevision >= 0L)
            require(policyVersion.isNotBlank())
            require(workshopVersion.isNotBlank())
            return ToolWorkshopJobId(
                PREFIX + StableFieldIds.fingerprint(
                    "tool-workshop-job/v1",
                    sourcePhotonId,
                    sourceRevision.toString(),
                    capabilityId.value,
                    severity.name,
                    gapType.name,
                    policyVersion,
                    workshopVersion,
                    *requiredInputs.sorted().map { "input:$it" }.toTypedArray(),
                    *requiredOutputs.sorted().map { "output:$it" }.toTypedArray(),
                    *candidateProviderIds.distinct().sorted().map { "candidate:$it" }.toTypedArray(),
                )
            )
        }
    }
}

data class ToolWorkshopJobDefinition(
    val id: ToolWorkshopJobId,
    val sourceRequestId: String,
    val sourcePhotonId: String,
    val sourceRevision: Long,
    val capabilityId: CapabilityId,
    val severity: GapSeverity,
    val gapType: CapabilityGapType,
    val requiredInputs: Set<String>,
    val requiredOutputs: Set<String>,
    val candidateProviderIds: List<String>,
    val policyVersion: String,
    val workshopVersion: String,
    val createdAt: Instant,
) {
    init {
        require(sourceRequestId.isNotBlank())
        require(sourcePhotonId.isNotBlank())
        require(sourceRevision >= 0L)
        require(requiredInputs.none { it.isBlank() })
        require(requiredOutputs.none { it.isBlank() })
        require(candidateProviderIds.none { it.isBlank() })
        require(candidateProviderIds.distinct().size == candidateProviderIds.size)
        require(policyVersion.isNotBlank())
        require(workshopVersion.isNotBlank())
        require(id == identityId()) { "ToolWorkshop job id/content mismatch" }
    }

    val deterministicToolId: String = "generated-" + StableFieldIds.fingerprint(
        "tool-workshop-tool/v1",
        id.value,
    )

    fun sameIdentityAs(other: ToolWorkshopJobDefinition): Boolean =
        id == other.id &&
            sourcePhotonId == other.sourcePhotonId &&
            sourceRevision == other.sourceRevision &&
            capabilityId == other.capabilityId &&
            severity == other.severity &&
            gapType == other.gapType &&
            requiredInputs == other.requiredInputs &&
            requiredOutputs == other.requiredOutputs &&
            candidateProviderIds.sorted() == other.candidateProviderIds.sorted() &&
            policyVersion == other.policyVersion &&
            workshopVersion == other.workshopVersion

    private fun identityId(): ToolWorkshopJobId = ToolWorkshopJobId.create(
        sourcePhotonId = sourcePhotonId,
        sourceRevision = sourceRevision,
        capabilityId = capabilityId,
        severity = severity,
        gapType = gapType,
        requiredInputs = requiredInputs,
        requiredOutputs = requiredOutputs,
        candidateProviderIds = candidateProviderIds,
        policyVersion = policyVersion,
        workshopVersion = workshopVersion,
    )

    companion object {
        fun fromRequest(
            request: GeneratedToolRequest,
            sourceRevision: Long,
            policyVersion: String,
            workshopVersion: String,
            createdAt: Instant = request.requestedAt,
        ): ToolWorkshopJobDefinition {
            val id = ToolWorkshopJobId.create(
                sourcePhotonId = request.requestPhotonId.value,
                sourceRevision = sourceRevision,
                capabilityId = request.capabilityId,
                severity = request.severity,
                gapType = request.gapType,
                requiredInputs = request.requiredInputs,
                requiredOutputs = request.requiredOutputs,
                candidateProviderIds = request.candidateProviderIds,
                policyVersion = policyVersion,
                workshopVersion = workshopVersion,
            )
            return ToolWorkshopJobDefinition(
                id = id,
                sourceRequestId = request.id,
                sourcePhotonId = request.requestPhotonId.value,
                sourceRevision = sourceRevision,
                capabilityId = request.capabilityId,
                severity = request.severity,
                gapType = request.gapType,
                requiredInputs = request.requiredInputs,
                requiredOutputs = request.requiredOutputs,
                candidateProviderIds = request.candidateProviderIds.distinct().sorted(),
                policyVersion = policyVersion,
                workshopVersion = workshopVersion,
                createdAt = createdAt,
            )
        }
    }
}

enum class ToolWorkshopJobState {
    REQUESTED,
    SPECIFIED,
    DESIGNED,
    IMPLEMENTED,
    BUILT,
    TESTED,
    SECURITY_VALIDATED,
    VERIFIED,
    TRIAL_READY,
    REJECTED,
    INTERRUPTED,
}

data class ToolWorkshopJobEvent(
    val revision: Long,
    val definition: ToolWorkshopJobDefinition,
    val state: ToolWorkshopJobState,
    val recordedAt: Instant,
    val stageFingerprint: String? = null,
    val detail: String? = null,
) {
    init {
        require(revision > 0L)
        require(stageFingerprint == null || stageFingerprint.isNotBlank())
        require(detail == null || detail.isNotBlank())
        if (state == ToolWorkshopJobState.REQUESTED) {
            require(stageFingerprint == null) { "REQUESTED cannot carry a stage fingerprint" }
        }
        if (state in STAGE_STATES) {
            require(!stageFingerprint.isNullOrBlank()) { "$state requires a durable stage fingerprint" }
        }
        if (state == ToolWorkshopJobState.REJECTED || state == ToolWorkshopJobState.INTERRUPTED) {
            require(!detail.isNullOrBlank()) { "$state requires a reason" }
        }
    }

    private companion object {
        val STAGE_STATES = setOf(
            ToolWorkshopJobState.SPECIFIED,
            ToolWorkshopJobState.DESIGNED,
            ToolWorkshopJobState.IMPLEMENTED,
            ToolWorkshopJobState.BUILT,
            ToolWorkshopJobState.TESTED,
            ToolWorkshopJobState.SECURITY_VALIDATED,
            ToolWorkshopJobState.VERIFIED,
            ToolWorkshopJobState.TRIAL_READY,
        )
    }
}

data class ToolWorkshopJobRepositoryLoadReport(
    val events: List<ToolWorkshopJobEvent>,
    val unreadableEntries: List<String> = emptyList(),
)

interface ToolWorkshopJobRepository {
    suspend fun loadReport(): ToolWorkshopJobRepositoryLoadReport
    suspend fun append(expectedRevision: Long, event: ToolWorkshopJobEvent): Boolean
}

data class ToolWorkshopJobSnapshot(
    val definition: ToolWorkshopJobDefinition,
    val state: ToolWorkshopJobState,
    val stageFingerprint: String? = null,
    val lastDetail: String? = null,
    val ledgerRevision: Long,
) {
    val terminal: Boolean
        get() = state == ToolWorkshopJobState.TRIAL_READY ||
            state == ToolWorkshopJobState.REJECTED ||
            state == ToolWorkshopJobState.INTERRUPTED

    val toolId: String get() = definition.deterministicToolId
}

class ToolWorkshopJobLedger(
    private val repository: ToolWorkshopJobRepository,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun create(definition: ToolWorkshopJobDefinition): ToolWorkshopJobSnapshot {
        snapshot(definition.id)?.let { existing ->
            require(existing.definition.sameIdentityAs(definition)) {
                "Equivalent ToolWorkshop job id resolved to different identity"
            }
            return existing
        }
        append(
            ToolWorkshopJobEvent(
                revision = nextRevision(),
                definition = definition,
                state = ToolWorkshopJobState.REQUESTED,
                recordedAt = now(),
            )
        )
        return requireNotNull(snapshot(definition.id))
    }

    suspend fun advance(
        snapshot: ToolWorkshopJobSnapshot,
        target: ToolWorkshopJobState,
        stageFingerprint: String,
        detail: String? = null,
    ): ToolWorkshopJobSnapshot {
        require(target in ORDERED_STAGES.drop(1)) { "Use reject/interrupt for terminal failure states" }
        if (snapshot.state == target) {
            require(snapshot.stageFingerprint == stageFingerprint) {
                "ToolWorkshop stage replay changed its durable output fingerprint"
            }
            return snapshot
        }
        require(!snapshot.terminal) { "Terminal ToolWorkshop job cannot advance" }
        val expected = nextState(snapshot.state)
        require(target == expected) {
            "ToolWorkshop job must advance from ${snapshot.state} to $expected, not $target"
        }
        append(
            ToolWorkshopJobEvent(
                revision = nextRevision(),
                definition = snapshot.definition,
                state = target,
                recordedAt = now(),
                stageFingerprint = stageFingerprint,
                detail = detail,
            )
        )
        return requireNotNull(snapshot(snapshot.definition.id))
    }

    suspend fun reject(snapshot: ToolWorkshopJobSnapshot, reason: String): ToolWorkshopJobSnapshot =
        terminate(snapshot, ToolWorkshopJobState.REJECTED, reason)

    suspend fun interrupt(snapshot: ToolWorkshopJobSnapshot, reason: String): ToolWorkshopJobSnapshot =
        terminate(snapshot, ToolWorkshopJobState.INTERRUPTED, reason)

    suspend fun snapshot(id: ToolWorkshopJobId): ToolWorkshopJobSnapshot? {
        val report = loadStrict()
        return replay(report.events.filter { it.definition.id == id })
    }

    suspend fun all(): List<ToolWorkshopJobSnapshot> {
        val report = loadStrict()
        return report.events.groupBy { it.definition.id }
            .values
            .mapNotNull(::replay)
            .sortedBy { it.definition.id.value }
    }

    suspend fun active(): List<ToolWorkshopJobSnapshot> = all().filterNot { it.terminal }

    private suspend fun terminate(
        snapshot: ToolWorkshopJobSnapshot,
        target: ToolWorkshopJobState,
        reason: String,
    ): ToolWorkshopJobSnapshot {
        require(reason.isNotBlank())
        if (snapshot.state == target) {
            require(snapshot.lastDetail == reason) { "Terminal ToolWorkshop replay changed its reason" }
            return snapshot
        }
        require(!snapshot.terminal) { "Terminal ToolWorkshop job cannot transition" }
        append(
            ToolWorkshopJobEvent(
                revision = nextRevision(),
                definition = snapshot.definition,
                state = target,
                recordedAt = now(),
                detail = reason,
            )
        )
        return requireNotNull(snapshot(snapshot.definition.id))
    }

    private fun nextState(state: ToolWorkshopJobState): ToolWorkshopJobState {
        val index = ORDERED_STAGES.indexOf(state)
        require(index >= 0 && index < ORDERED_STAGES.lastIndex) {
            "ToolWorkshop state $state has no forward stage"
        }
        return ORDERED_STAGES[index + 1]
    }

    private suspend fun append(eventTemplate: ToolWorkshopJobEvent) {
        repeat(MAX_CAS_ATTEMPTS) {
            val report = loadStrict()
            val current = report.events.lastOrNull()?.revision ?: 0L
            val event = eventTemplate.copy(revision = current + 1L)
            if (repository.append(current, event)) return
        }
        error("ToolWorkshop job ledger CAS retries exhausted")
    }

    private suspend fun nextRevision(): Long {
        val report = loadStrict()
        return (report.events.lastOrNull()?.revision ?: 0L) + 1L
    }

    private suspend fun loadStrict(): ToolWorkshopJobRepositoryLoadReport {
        val report = repository.loadReport()
        check(report.unreadableEntries.isEmpty()) {
            "ToolWorkshop job ledger is unreadable: ${report.unreadableEntries.joinToString(",")}" 
        }
        require(report.events.map { it.revision } == (1L..report.events.size.toLong()).toList()) {
            "ToolWorkshop job revisions must be contiguous"
        }
        return report
    }

    private fun replay(events: List<ToolWorkshopJobEvent>): ToolWorkshopJobSnapshot? {
        if (events.isEmpty()) return null
        val first = events.first()
        require(first.state == ToolWorkshopJobState.REQUESTED) {
            "ToolWorkshop job must begin with REQUESTED"
        }
        require(events.all { it.definition == first.definition }) {
            "ToolWorkshop job definition changed during replay"
        }

        var state = ToolWorkshopJobState.REQUESTED
        var stageFingerprint: String? = null
        var detail: String? = first.detail
        events.drop(1).forEach { event ->
            when (event.state) {
                ToolWorkshopJobState.REQUESTED -> error("ToolWorkshop job cannot request twice")
                ToolWorkshopJobState.REJECTED,
                ToolWorkshopJobState.INTERRUPTED -> {
                    require(state !in TERMINAL_STATES)
                    state = event.state
                }
                else -> {
                    require(state !in TERMINAL_STATES)
                    require(event.state == nextState(state)) {
                        "Invalid ToolWorkshop replay transition $state -> ${event.state}"
                    }
                    state = event.state
                    stageFingerprint = event.stageFingerprint
                }
            }
            detail = event.detail ?: detail
        }
        return ToolWorkshopJobSnapshot(
            definition = first.definition,
            state = state,
            stageFingerprint = stageFingerprint,
            lastDetail = detail,
            ledgerRevision = events.last().revision,
        )
    }

    private companion object {
        const val MAX_CAS_ATTEMPTS = 32
        val ORDERED_STAGES = listOf(
            ToolWorkshopJobState.REQUESTED,
            ToolWorkshopJobState.SPECIFIED,
            ToolWorkshopJobState.DESIGNED,
            ToolWorkshopJobState.IMPLEMENTED,
            ToolWorkshopJobState.BUILT,
            ToolWorkshopJobState.TESTED,
            ToolWorkshopJobState.SECURITY_VALIDATED,
            ToolWorkshopJobState.VERIFIED,
            ToolWorkshopJobState.TRIAL_READY,
        )
        val TERMINAL_STATES = setOf(
            ToolWorkshopJobState.TRIAL_READY,
            ToolWorkshopJobState.REJECTED,
            ToolWorkshopJobState.INTERRUPTED,
        )
    }
}
