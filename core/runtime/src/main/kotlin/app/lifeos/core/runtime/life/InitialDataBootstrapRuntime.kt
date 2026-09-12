package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Availability truth reported by one concrete source adapter before a bootstrap pass. */
enum class InitialDataSourceStatus {
    AVAILABLE,
    UNAUTHORIZED,
    UNAVAILABLE,
}

/** One bounded page from a source. complete=true requires nextPosition=null. */
data class InitialDataSourcePage(
    val records: List<LifeSourceRecord>,
    val nextPosition: String?,
    val complete: Boolean,
) {
    init {
        require(complete == (nextPosition == null)) {
            "Initial-data page completion and next-position disagree"
        }
    }
}

/**
 * Adapter boundary for the first authorized import. Implementations must return stable records for
 * the same source state; the durable ingestor binds immutable evidence to adapter version + record id.
 */
interface InitialDataSourceAdapter {
    val descriptor: LifeSourceDescriptor

    suspend fun status(): InitialDataSourceStatus

    suspend fun readPage(
        afterPosition: String?,
        limit: Int,
    ): InitialDataSourcePage
}

enum class InitialDataBootstrapStatus {
    COMPLETE,
    PARTIAL,
}

data class InitialDataSourceResult(
    val descriptor: LifeSourceDescriptor,
    val status: InitialDataSourceStatus,
    val completed: Boolean,
    val durableRecordCount: Int,
    val checkpointRevision: Long,
    val checkpointPosition: String?,
)

data class InitialDataBootstrapSnapshot(
    val status: InitialDataBootstrapStatus,
    val sourceSetFingerprint: String,
    val sourceStateFingerprint: String,
    val sources: List<InitialDataSourceResult>,
    val reportPhotonId: PhotonId,
    val fingerprint: String,
)

/**
 * Crash-safe automatic first-read over every registered source.
 *
 * Source evidence is always committed by [DurableLifeSourceIngestor] before its cursor moves. A
 * process death therefore resumes from the last durable cursor and replays an interrupted page
 * idempotently. A sealed bootstrap report is keyed by source descriptors, availability states and
 * checkpoint states, so unchanged completed first-reads are not repeated on every process start.
 */
class InitialDataBootstrapRuntime(
    private val photons: PhotonRepository,
    private val memory: DurableLifeMemoryRuntime,
    sources: Collection<InitialDataSourceAdapter>,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private val sources = sources.sortedWith(
        compareBy<InitialDataSourceAdapter> { it.descriptor.sourceId }
            .thenBy { it.descriptor.adapterVersion }
    )
    private val checkpoints = PhotonBackedLifeSourceCheckpointStore(photons)
    private val ingestor = DurableLifeSourceIngestor(photons, checkpoints)

    init {
        require(pageSize in 1..MAX_PAGE_SIZE) { "Initial-data page size is outside the bounded range" }
        require(this.sources.map { it.descriptor.sourceId }.distinct().size == this.sources.size) {
            "Only one active adapter may own an initial-data source id"
        }
    }

    suspend fun run(): InitialDataBootstrapSnapshot = mutex.withLock {
        val states = linkedMapOf<InitialDataSourceAdapter, InitialDataSourceStatus>()
        for (source in sources) states[source] = source.status()

        val sourceSetFingerprint = StableCognitiveIds.fingerprint(
            "initial-data-source-set/v1",
            *sources.flatMap { source ->
                listOf(source.descriptor.sourceId, source.descriptor.adapterVersion, source.descriptor.fingerprint)
            }.toTypedArray(),
        )
        val stateFingerprint = StableCognitiveIds.fingerprint(
            "initial-data-source-state/v1",
            sourceSetFingerprint,
            *sources.flatMap { source ->
                val checkpoint = checkpoints.load(source.descriptor)
                listOf(
                    source.descriptor.fingerprint,
                    states.getValue(source).name,
                    checkpoint.revision.toString(),
                    checkpoint.position.orEmpty(),
                    checkpoint.lastBatchFingerprint.orEmpty(),
                )
            }.toTypedArray(),
        )
        val preexistingReportId = reportId(sourceSetFingerprint, stateFingerprint)
        photons.load(preexistingReportId)?.let { existing ->
            validateReport(existing, sourceSetFingerprint, stateFingerprint)
            return@withLock snapshot(
                sourceSetFingerprint = sourceSetFingerprint,
                sourceStateFingerprint = stateFingerprint,
                states = states,
                reportPhotonId = preexistingReportId,
            )
        }

        val gapIds = linkedSetOf<PhotonId>()
        for (source in sources) {
            val descriptor = source.descriptor
            when (states.getValue(source)) {
                InitialDataSourceStatus.UNAUTHORIZED -> {
                    val before = checkpoints.load(descriptor)
                    val commit = ingestor.ingest(
                        descriptor = descriptor,
                        records = emptyList(),
                        nextPosition = before.position,
                        authorized = false,
                        committedAt = now(),
                    )
                    commit.permissionGap?.id?.let(gapIds::add)
                }

                InitialDataSourceStatus.UNAVAILABLE -> {
                    gapIds += ingestor.recordUnavailable(descriptor, now()).id
                }

                InitialDataSourceStatus.AVAILABLE -> ingestAvailable(source)
            }
        }

        memory.rebuild(now())

        val finalStateFingerprint = StableCognitiveIds.fingerprint(
            "initial-data-source-state/v1",
            sourceSetFingerprint,
            *sources.flatMap { source ->
                val checkpoint = checkpoints.load(source.descriptor)
                listOf(
                    source.descriptor.fingerprint,
                    states.getValue(source).name,
                    checkpoint.revision.toString(),
                    checkpoint.position.orEmpty(),
                    checkpoint.lastBatchFingerprint.orEmpty(),
                )
            }.toTypedArray(),
        )
        val finalReportId = reportId(sourceSetFingerprint, finalStateFingerprint)
        val provisional = snapshot(
            sourceSetFingerprint = sourceSetFingerprint,
            sourceStateFingerprint = finalStateFingerprint,
            states = states,
            reportPhotonId = finalReportId,
        )
        val report = reportPhoton(provisional, gapIds, now())
        saveIdempotent(report)
        provisional
    }

    private suspend fun ingestAvailable(source: InitialDataSourceAdapter) {
        val descriptor = source.descriptor
        var checkpoint = checkpoints.load(descriptor)
        if (checkpoint.isComplete()) return
        var position = checkpoint.position
        var pages = 0
        while (true) {
            check(++pages <= MAX_PAGES_PER_RUN) { "Initial-data source exceeded bounded page count" }
            val page = source.readPage(position, pageSize)
            require(page.records.all { it.sourceId == descriptor.sourceId }) {
                "Initial-data adapter emitted a record for another source"
            }
            if (!page.complete) {
                require(!page.nextPosition.isNullOrBlank() && page.nextPosition != position) {
                    "Initial-data adapter did not advance its cursor"
                }
            }
            val commit = ingestor.ingest(
                descriptor = descriptor,
                records = page.records,
                nextPosition = page.nextPosition,
                authorized = true,
                committedAt = now(),
            )
            checkpoint = commit.checkpoint
            position = checkpoint.position
            if (page.complete) break
        }
    }

    private suspend fun snapshot(
        sourceSetFingerprint: String,
        sourceStateFingerprint: String,
        states: Map<InitialDataSourceAdapter, InitialDataSourceStatus>,
        reportPhotonId: PhotonId,
    ): InitialDataBootstrapSnapshot {
        val all = photons.loadAll()
        val results = sources.map { source ->
            val descriptor = source.descriptor
            val checkpoint = checkpoints.load(descriptor)
            InitialDataSourceResult(
                descriptor = descriptor,
                status = states.getValue(source),
                completed = states.getValue(source) == InitialDataSourceStatus.AVAILABLE && checkpoint.isComplete(),
                durableRecordCount = all.count { photon ->
                    "life-source-evidence" in photon.tags &&
                        "source:${descriptor.sourceId}" in photon.tags &&
                        "source-adapter:${descriptor.adapterVersion}" in photon.tags
                },
                checkpointRevision = checkpoint.revision,
                checkpointPosition = checkpoint.position,
            )
        }
        val overall = if (results.all { it.status == InitialDataSourceStatus.AVAILABLE && it.completed }) {
            InitialDataBootstrapStatus.COMPLETE
        } else {
            InitialDataBootstrapStatus.PARTIAL
        }
        val fingerprint = StableCognitiveIds.fingerprint(
            "initial-data-bootstrap-snapshot/v1",
            overall.name,
            sourceSetFingerprint,
            sourceStateFingerprint,
            *results.flatMap { result ->
                listOf(
                    result.descriptor.fingerprint,
                    result.status.name,
                    result.completed.toString(),
                    result.durableRecordCount.toString(),
                    result.checkpointRevision.toString(),
                    result.checkpointPosition.orEmpty(),
                )
            }.toTypedArray(),
        )
        return InitialDataBootstrapSnapshot(
            status = overall,
            sourceSetFingerprint = sourceSetFingerprint,
            sourceStateFingerprint = sourceStateFingerprint,
            sources = results,
            reportPhotonId = reportPhotonId,
            fingerprint = fingerprint,
        )
    }

    private fun reportPhoton(
        snapshot: InitialDataBootstrapSnapshot,
        gapIds: Set<PhotonId>,
        createdAt: Instant,
    ): Photon = Photon(
        id = snapshot.reportPhotonId,
        content = buildString {
            appendLine("schema=1")
            appendLine("status=${snapshot.status.name}")
            appendLine("source_set=${snapshot.sourceSetFingerprint}")
            appendLine("source_state=${snapshot.sourceStateFingerprint}")
            appendLine("sources=${snapshot.sources.size}")
            appendLine("available=${snapshot.sources.count { it.status == InitialDataSourceStatus.AVAILABLE }}")
            appendLine("unauthorized=${snapshot.sources.count { it.status == InitialDataSourceStatus.UNAUTHORIZED }}")
            appendLine("unavailable=${snapshot.sources.count { it.status == InitialDataSourceStatus.UNAVAILABLE }}")
            append("records=${snapshot.sources.sumOf { it.durableRecordCount }}")
        },
        mimeType = MIME_TYPE,
        semanticMass = 0.0,
        energy = 0.0,
        confidence = 1.0,
        provenance = Provenance(
            source = "initial-data-bootstrap",
            actor = "lifeos",
            createdAt = createdAt,
            parentIds = gapIds,
        ),
        tags = setOf(
            "life-memory-management",
            "initial-data-bootstrap",
            "initial-data-bootstrap:${snapshot.status.name.lowercase()}",
            "source-set:${snapshot.sourceSetFingerprint}",
            "source-state:${snapshot.sourceStateFingerprint}",
        ),
    )

    private fun validateReport(
        report: Photon,
        sourceSetFingerprint: String,
        sourceStateFingerprint: String,
    ) {
        require(report.mimeType == MIME_TYPE && "initial-data-bootstrap" in report.tags) {
            "Initial-data bootstrap report identity resolved to another record"
        }
        require("source-set:$sourceSetFingerprint" in report.tags) { "Initial-data source-set mismatch" }
        require("source-state:$sourceStateFingerprint" in report.tags) { "Initial-data source-state mismatch" }
    }

    private suspend fun saveIdempotent(photon: Photon) {
        val existing = photons.load(photon.id)
        if (existing == null) photons.save(photon)
        else check(existing == photon) { "Conflicting initial-data bootstrap report identity: ${photon.id.value}" }
    }

    private fun reportId(sourceSetFingerprint: String, sourceStateFingerprint: String): PhotonId = PhotonId(
        "initial-data-bootstrap-" + StableCognitiveIds.fingerprint(
            "initial-data-bootstrap-id/v1",
            sourceSetFingerprint,
            sourceStateFingerprint,
        )
    )

    private fun DurableLifeSourceCheckpoint.isComplete(): Boolean =
        revision > 0L && position == null && lastBatchFingerprint != null

    private companion object {
        const val MIME_TYPE = "application/vnd.lifeos.initial-data-bootstrap+text"
        const val DEFAULT_PAGE_SIZE = 200
        const val MAX_PAGE_SIZE = 2_000
        const val MAX_PAGES_PER_RUN = 100_000
    }
}
