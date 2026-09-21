package app.lifeos.next

import android.content.Context
import app.lifeos.core.data.LiveSourceAdapter
import app.lifeos.core.data.LiveSourceConnector
import app.lifeos.core.data.LiveSourceId
import app.lifeos.core.data.LiveSourcePriority
import app.lifeos.core.data.SnapshotToCursorLiveSourceAdapter
import app.lifeos.core.data.SourceChangeSet
import app.lifeos.core.data.SourceCursor
import app.lifeos.core.data.SourceDelta
import app.lifeos.core.data.SourceDeltaKind
import app.lifeos.core.data.SourceInventory
import app.lifeos.core.data.SourcePrivacyZone
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.life.InitialDataSourceAdapter
import app.lifeos.core.runtime.life.InitialDataSourceStatus
import app.lifeos.core.runtime.livedata.LiveDataAccountKey
import app.lifeos.core.runtime.livedata.LiveDataAccountObservation
import app.lifeos.core.runtime.livedata.LiveDataConnectorId
import app.lifeos.core.runtime.livedata.LiveDataDelta
import app.lifeos.core.runtime.livedata.LiveDataDeltaOperation
import app.lifeos.core.runtime.livedata.LiveDataPermission
import app.lifeos.core.runtime.livedata.LiveDataPermissionState
import app.lifeos.core.runtime.livedata.LiveDataStreamKind
import app.lifeos.next.fileingest.FileContentParserRegistry
import java.io.File
import java.time.Instant

/**
 * Incremental shared-storage connector backed by the durable Storage Intelligence journal.
 *
 * The connector never snapshots the complete filesystem into memory. New/changed/deleted paths are
 * replayed from a monotonically increasing SQLite revision and current file bytes are decoded only
 * for the bounded delta batch being published.
 */
internal class StorageProjectionDeferredException(
    message: String,
) : IllegalStateException(message)

internal class AndroidStorageLiveSourceConnector(
    private val inventory: StorageChangeJournal,
    private val statusSource: InitialDataSourceAdapter,
    private val parsers: FileContentParserRegistry =
        FileContentParserRegistry(),
    private val now: () -> Instant = Instant::now,
) : LiveSourceConnector,
    SnapshotToCursorLiveSourceAdapter {

    constructor(
        context: Context,
        now: () -> Instant = Instant::now,
    ) : this(
        inventory = AndroidStorageInventoryStore(context),
        statusSource = AndroidSharedFilesInitialDataSource(context),
        parsers = FileContentParserRegistry(),
        now = now,
    )

    override val sourceId: LiveSourceId =
        LiveSourceId(AndroidSharedFilesInitialDataSource.SOURCE_ID)

    override val adapter: LiveSourceAdapter
        get() = this

    override val streamKind: LiveDataStreamKind =
        LiveDataStreamKind.FILE

    // Deliberately stable so durable v1 snapshot state can be promoted in place to a cursor.
    override val connectorVersion: String =
        "android-shared-files-live/v1"

    override val priority: LiveSourcePriority =
        LiveSourcePriority.NORMAL

    private val connectorId =
        LiveDataConnectorId("android-files")

    private val accountKey =
        LiveDataAccountKey("device-shared-files")

    override suspend fun accountObservation(): LiveDataAccountObservation {
        val status = statusSource.status()
        val permissionState = when (status) {
            InitialDataSourceStatus.AVAILABLE ->
                LiveDataPermissionState.GRANTED

            InitialDataSourceStatus.UNAUTHORIZED ->
                LiveDataPermissionState.DENIED

            InitialDataSourceStatus.UNAVAILABLE ->
                LiveDataPermissionState.UNAVAILABLE
        }

        return LiveDataAccountObservation(
            connectorId = connectorId,
            accountKey = accountKey,
            capabilities =
                if (status == InitialDataSourceStatus.UNAVAILABLE) {
                    emptySet()
                } else {
                    setOf(streamKind.requiredCapability)
                },
            permissions =
                LiveDataPermission.entries.associateWith { permission ->
                    if (permission == streamKind.requiredPermission) {
                        permissionState
                    } else {
                        LiveDataPermissionState.UNAVAILABLE
                    }
                },
            observedAt = now(),
        )
    }

    override suspend fun inventory(): SourceInventory =
        SourceInventory(
            items = emptyList(),
            cursor = SourceCursor(INITIAL_CURSOR),
        )

    override suspend fun migrationCursor(): SourceCursor =
        SourceCursor(INITIAL_CURSOR)

    override suspend fun changesAfter(
        cursor: SourceCursor,
    ): SourceChangeSet {
        val revision = cursor.value.toLongOrNull()
            ?: error("Invalid storage live cursor: " + cursor.value)
        require(revision >= 0L)

        val changes = inventory.loadChangesAfter(
            revisionExclusive = revision,
            limit = MAX_CHANGE_BATCH,
        )

        val deltas = changes.map { change ->
            SourceDelta(
                deltaId = "storage-change:" + change.revision,
                sourceId = sourceId,
                externalKey = externalKey(change),
                kind = change.kind,
                previousFingerprint = change.previousFingerprint,
                newFingerprint = change.newFingerprint,
                observationRevision = change.revision,
                privacyZone = SourcePrivacyZone.PRIVATE,
            )
        }

        return SourceChangeSet(
            deltas = deltas,
            nextCursor = SourceCursor(
                (changes.lastOrNull()?.revision ?: revision)
                    .toString()
            ),
        )
    }

    override suspend fun project(
        delta: SourceDelta,
    ): LiveDataDelta? {
        require(delta.sourceId == sourceId)
        val observedAt = now()

        val changeRevision = delta.deltaId
            .removePrefix(CHANGE_ID_PREFIX)
            .toLongOrNull()
            ?: return null
        val change = inventory.loadChange(changeRevision)
            ?: throw StorageProjectionDeferredException(
                "storage-change-missing:$changeRevision"
            )
        if (
            change.revision != delta.observationRevision ||
            externalKey(change) != delta.externalKey ||
            change.kind != delta.kind ||
            change.previousFingerprint != delta.previousFingerprint ||
            change.newFingerprint != delta.newFingerprint
        ) {
            throw StorageProjectionDeferredException(
                "storage-change-mismatch:$changeRevision"
            )
        }

        if (delta.kind == SourceDeltaKind.DELETED) {
            return deleteDelta(delta, observedAt)
        }

        val entry =
            inventory.load(change.volumeId, change.relativePath)
                ?: throw StorageProjectionDeferredException(
                    "storage-entry-missing:$changeRevision"
                )

        val file = File(entry.absolutePath)
        if (!file.isFile || !file.canRead()) {
            throw StorageProjectionDeferredException(
                "storage-file-not-readable:$changeRevision"
            )
        }
        if (
            file.length().coerceAtLeast(0L) != entry.sizeBytes ||
            file.lastModified().coerceAtLeast(0L) != entry.modifiedAtMillis
        ) {
            throw StorageProjectionDeferredException(
                "storage-file-state-changed:$changeRevision"
            )
        }
        if (
            delta.newFingerprint != null &&
            delta.newFingerprint != entry.metadataStateFingerprint
        ) {
            throw StorageProjectionDeferredException(
                "storage-journal-state-stale:$changeRevision"
            )
        }

        val classification =
            AndroidFileMetadataClassifier.classify(
                relativePath = entry.relativePath,
                displayName = file.name,
                mimeType = null,
            )

        if (
            classification.category != entry.category ||
            classification.suspectedEncrypted != entry.suspectedEncrypted
        ) {
            throw StorageProjectionDeferredException(
                "storage-classification-stale:$changeRevision"
            )
        }

        val extraction = parsers.extract(
            file = file,
            classification = classification,
            maxOutputBytes = MAX_EXTRACTED_CONTENT_BYTES,
        )

        val payload = buildString {
            appendLine("schema=2")
            appendLine("relative_path=" + safe(entry.relativePath))
            appendLine("name=" + safe(file.name))
            appendLine("extension=" + safe(file.extension.lowercase()))
            appendLine("size_bytes=" + entry.sizeBytes)
            appendLine("modified_ms=" + entry.modifiedAtMillis)
            appendLine("category=" + entry.category.name)
            appendLine("whatsapp=" + classification.whatsapp)
            appendLine("suspected_encrypted=" + entry.suspectedEncrypted)
            appendLine("content_fingerprint=" + entry.contentFingerprint.orEmpty())
            appendLine("decode_state=" + extraction.state.name)
            appendLine("parser=" + extraction.parserId.orEmpty())
            appendLine("parser_version=" + extraction.parserVersion.orEmpty())
            append("content=")
            extraction.text?.let(::append)
        }

        check(payload.toByteArray(Charsets.UTF_8).size <= MAX_LIVE_PAYLOAD_BYTES) {
            "Storage live payload exceeded bounded size"
        }

        val version = StableCognitiveIds.fingerprint(
            "android-storage-live-payload/v1",
            delta.newFingerprint.orEmpty(),
            entry.contentFingerprint.orEmpty(),
            extraction.parserVersion.orEmpty(),
            extraction.state.name,
            extraction.text.orEmpty(),
        )
        val occurredAt = if (entry.modifiedAtMillis > 0L) {
            minOf(
                Instant.ofEpochMilli(entry.modifiedAtMillis),
                observedAt,
            )
        } else {
            observedAt
        }

        return LiveDataDelta(
            connectorId = connectorId,
            accountKey = accountKey,
            kind = streamKind,
            externalId = delta.externalKey,
            externalVersion = version,
            operation = LiveDataDeltaOperation.UPSERT,
            occurredAt = occurredAt,
            observedAt = observedAt,
            payload = payload,
            mimeType =
                "application/vnd.lifeos.file-content+text",
        )
    }

    private fun deleteDelta(
        delta: SourceDelta,
        observedAt: Instant,
    ): LiveDataDelta =
        LiveDataDelta(
            connectorId = connectorId,
            accountKey = accountKey,
            kind = streamKind,
            externalId = delta.externalKey,
            externalVersion = delta.deltaId,
            operation = LiveDataDeltaOperation.DELETE,
            occurredAt = observedAt,
            observedAt = observedAt,
            payload = null,
        )

    private fun externalKey(
        change: StorageChangeEntry,
    ): String =
        "storage-" + StableCognitiveIds.fingerprint(
            "android-storage-live-external/v1",
            change.volumeId,
            change.relativePath,
        )

    private fun safe(value: String): String =
        value.replace('\n', ' ')
            .replace('\r', ' ')
            .trim()

    private companion object {
        const val INITIAL_CURSOR = "0"
        const val CHANGE_ID_PREFIX = "storage-change:"
        const val MAX_CHANGE_BATCH = 16_384
        const val MAX_EXTRACTED_CONTENT_BYTES =
            448 * 1024
        const val MAX_LIVE_PAYLOAD_BYTES =
            512 * 1024
    }
}
