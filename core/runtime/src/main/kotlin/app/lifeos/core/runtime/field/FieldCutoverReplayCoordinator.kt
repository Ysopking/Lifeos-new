package app.lifeos.core.runtime.field

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldSnapshot
import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.field.FieldSnapshotLoadReport
import app.lifeos.core.field.FieldSnapshotRepository
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonIndexCursor
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.FieldRegistry
import app.lifeos.core.runtime.InfluenceExecutor
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface FieldShadowAdmissionPolicy {
    suspend fun shouldProcess(photon: Photon): Boolean

    companion object {
        val ALL = FieldShadowAdmissionPolicy { true }
    }
}

/**
 * Live shadow admission is limited to selected domains, current hardware headroom and a deterministic
 * sample after the replay corpus reaches its minimum. Replay evidence, not LIVE sampling, controls
 * eligibility.
 */
class SelectedDomainFieldShadowAdmissionPolicy(
    private val states: FieldCutoverStateRepository,
    private val ledger: FieldShadowValidationLedger,
    private val validator: FieldShadowValidator,
    processors: List<ReplayableAuthoritativeFieldProcessor>,
    private val hardwareAllowsShadow: () -> Boolean = { true },
    private val minimumReplayCases: Int = 32,
    private val settledSampleModulo: Int = 8,
) : FieldShadowAdmissionPolicy {
    private val processors = processors.toList()

    init {
        require(minimumReplayCases > 0)
        require(settledSampleModulo > 0)
    }

    override suspend fun shouldProcess(photon: Photon): Boolean {
        val matches = processors.filter { it.accepts(photon) }
        require(matches.size <= 1) { "Multiple selected field domains accepted the same Photon" }
        val processor = matches.singleOrNull() ?: return false
        if (!hardwareAllowsShadow()) return false
        if (states.load(processor.domainId)?.mode == FieldCutoverMode.AUTHORITATIVE) return false

        val replayCount = validator.report(processor.domainId, ledger).replayCaseCount
        if (replayCount < minimumReplayCases) return true

        val fingerprint = runtimePhotonFingerprint(photon)
        val bucket = fingerprint.takeLast(8).toLong(16)
        return bucket % settledSampleModulo.toLong() == 0L
    }
}

class FieldCutoverReplayCoordinator(
    private val photons: RevisionedPhotonRepository,
    private val fields: FieldRegistry,
    private val executor: InfluenceExecutor,
    private val ledger: FieldShadowValidationLedger,
    private val validator: FieldShadowValidator,
    private val authority: FieldCutoverAuthority,
    processors: List<ReplayableAuthoritativeFieldProcessor>,
    private val replayAllowed: () -> Boolean = { true },
    private val maxCasesPerDomain: Int = 64,
) {
    private val processors = processors.sortedBy { it.domainId.value }

    init {
        require(maxCasesPerDomain > 0)
        require(this.processors.map { it.domainId }.distinct().size == this.processors.size)
    }

    suspend fun refresh(
        at: Instant,
        provenance: String = "deterministic-field-replay",
    ): List<FieldCutoverAssessment> {
        require(provenance.isNotBlank())
        if (!replayAllowed()) return processors.map { processor ->
            authority.assess(processor.domainId, at, "$provenance:resource-deferred")
        }
        return processors.map { processor ->
            replayDomain(processor)
            authority.assess(processor.domainId, at, provenance)
        }
    }

    private suspend fun replayDomain(processor: ReplayableAuthoritativeFieldProcessor) {
        queryRefs(processor).forEach { ref ->
            val photon = requireNotNull(photons.load(ref)) {
                "Field replay index references a missing Photon revision"
            }
            require(processor.accepts(photon))
            val legacy = executor.execute(
                photon = photon,
                fields = fields.activeFields(),
            )
            val replay = processor.replay(photon)
            val legacyState = if (legacy.failures.isEmpty()) TaskState.COMPLETED else TaskState.FAILED
            val sourceStatus = when {
                replay.execution.sourcePhotonId != photon.id -> ShadowSourceStatus.MISMATCH
                replay.execution.sourceRevision != photon.revision -> ShadowSourceStatus.MISMATCH
                replay.execution.sourceFingerprint != runtimePhotonFingerprint(photon) -> ShadowSourceStatus.MISMATCH
                else -> ShadowSourceStatus.PRESERVED
            }
            val replayCaseId = StableFieldIds.fingerprint(
                "field-cutover-replay-case/v1",
                processor.domainId.value,
                photon.id.value,
                photon.revision.toString(),
            )
            ledger.record(
                validator.validate(
                    FieldShadowValidationInput(
                        taskId = TaskId("field-replay-$replayCaseId"),
                        domainId = processor.domainId,
                        legacy = LegacyFieldObservation.capture(
                            finalState = legacyState,
                            influences = legacy.influences,
                        ),
                        universal = UniversalFieldObservation.capture(
                            shadow = replay.execution,
                            snapshot = replay.snapshot,
                        ),
                        sourceStatus = sourceStatus,
                        taskOwnershipStatus = ShadowTaskOwnershipStatus.PRESERVED,
                        origin = FieldShadowValidationOrigin.DETERMINISTIC_REPLAY,
                        replayCaseId = replayCaseId,
                    )
                )
            )
        }
    }

    private suspend fun queryRefs(
        processor: ReplayableAuthoritativeFieldProcessor,
    ): List<app.lifeos.core.model.PhotonRevisionRef> {
        val refs = mutableListOf<app.lifeos.core.model.PhotonRevisionRef>()
        var cursor: PhotonIndexCursor? = null
        while (refs.size < maxCasesPerDomain) {
            val remaining = maxCasesPerDomain - refs.size
            val limit = minOf(PhotonIndexQuery.HARD_PAGE_LIMIT, remaining)
            val page = photons.query(
                processor.replayQuery.copy(
                    latestOnly = true,
                    includeTombstoned = false,
                    order = PhotonIndexOrder.IDENTITY,
                    after = cursor,
                    limit = limit,
                )
            )
            refs += page
            if (page.size < limit) break
            cursor = PhotonIndexCursor(PhotonIndexOrder.IDENTITY, page.last())
        }
        return refs
    }
}

class EphemeralFieldSnapshotRepository : FieldSnapshotRepository {
    private val mutex = Mutex()
    private val values = linkedMapOf<FieldSnapshotId, FieldSnapshot>()

    override suspend fun save(snapshot: FieldSnapshot) = mutex.withLock {
        values[snapshot.id]?.let { existing ->
            require(existing == snapshot) { "Replay snapshot id collision" }
        }
        values[snapshot.id] = snapshot
    }

    override suspend fun load(id: FieldSnapshotId): FieldSnapshot? =
        mutex.withLock { values[id] }

    override suspend fun loadLatest(domainId: FieldDomainId): FieldSnapshot? =
        mutex.withLock {
            values.values
                .filter { it.domainId == domainId }
                .maxByOrNull { it.id.value }
        }

    override suspend fun loadReport(domainId: FieldDomainId?): FieldSnapshotLoadReport =
        mutex.withLock {
            FieldSnapshotLoadReport(
                snapshots = values.values
                    .filter { domainId == null || it.domainId == domainId }
                    .sortedBy { it.id.value },
                unreadableEntries = emptyList(),
            )
        }

    override suspend fun delete(id: FieldSnapshotId) {
        mutex.withLock { values.remove(id) }
    }
}

class ReplayableUniversalFieldProcessor(
    override val domainId: FieldDomainId,
    override val replayQuery: PhotonIndexQuery,
    private val acceptsPhoton: (Photon) -> Boolean,
    private val productive: FieldShadowProcessor,
    private val replayProcessor: FieldShadowProcessor,
    private val replaySnapshots: FieldSnapshotRepository,
) : ReplayableAuthoritativeFieldProcessor {
    init {
        require(domainId != DefaultPhotonFieldRequestFactory.DOMAIN_ID)
    }

    override fun accepts(photon: Photon): Boolean = acceptsPhoton(photon)

    override suspend fun process(photon: Photon): FieldShadowExecution {
        require(accepts(photon))
        return productive.process(photon).also(::requireDomain)
    }

    override suspend fun replay(photon: Photon): FieldReplayExecution {
        require(accepts(photon))
        val execution = replayProcessor.process(photon).also(::requireDomain)
        val snapshot = execution.snapshotId?.let { replaySnapshots.load(it) }
        return FieldReplayExecution(execution, snapshot)
    }

    private fun requireDomain(execution: FieldShadowExecution) {
        require(execution.domainId == domainId) {
            "Selected universal field processor returned a different domain"
        }
    }
}
