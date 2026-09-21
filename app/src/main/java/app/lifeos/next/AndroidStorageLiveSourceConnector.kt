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
                externalKey = encodeExternalKey(
                    change.volumeId,
                    change.relativePath,
                ),
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
    ): LiveDataDelta {
        require(delta.sourceId == sourceId)
        val observedAt = now()

        if (delta.kind == SourceDeltaKind.DELETED) {
            return deleteDelta(delta, observedAt)
        }

        val (volumeId, relativePath) =
            decodeExternalKey(delta.externalKey)
        val entry =
            inventory.load(volumeId, relativePath)
                ?: return deleteDelta(delta, observedAt)

        val file = File(entry.absolutePath)
        if (!file.isFile || !file.canRead()) {
            return deleteDelta(delta, observedAt)
        }

        val classification =
            AndroidFileMetadataClassifier.classify(
                relativePath = entry.relativePath,
                displayName = file.name,
                mimeType = null,
            )

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

    private fun encodeExternalKey(
        volumeId: String,
        relativePath: String,
    ): String =
        volumeId.length.toString() +
            ":" +
            volumeId +
            relativePath

    private fun decodeExternalKey(
        value: String,
    ): Pair<String, String> {
        val separator = value.indexOf(':')
        require(separator > 0) {
            "Invalid storage external key"
        }
        val volumeLength =
            value.substring(0, separator).toIntOrNull()
        require(volumeLength != null && volumeLength > 0) {
            "Invalid storage external-key volume length"
        }
        val volumeStart = separator + 1
        val pathStart = volumeStart + volumeLength
        require(pathStart < value.length) {
            "Invalid storage external-key payload"
        }
        return value.substring(volumeStart, pathStart) to
            value.substring(pathStart)
    }

    private fun safe(value: String): String =
        value.replace('\n', ' ')
            .replace('\r', ' ')
            .trim()

    private companion object {
        const val INITIAL_CURSOR = "0"
        const val MAX_CHANGE_BATCH = 16_384
        const val MAX_EXTRACTED_CONTENT_BYTES =
            448 * 1024
        const val MAX_LIVE_PAYLOAD_BYTES =
            512 * 1024
    }
}
