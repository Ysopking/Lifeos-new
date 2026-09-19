package app.lifeos.next

import android.content.Context
import app.lifeos.core.data.LiveSourceAdapter
import app.lifeos.core.data.LiveSourceConnector
import app.lifeos.core.data.LiveSourceId
import app.lifeos.core.data.LiveSourcePriority
import app.lifeos.core.data.SourceChangeSet
import app.lifeos.core.data.SourceCursor
import app.lifeos.core.data.SourceDelta
import app.lifeos.core.data.SourceDeltaKind
import app.lifeos.core.data.SourceInventory
import app.lifeos.core.data.SourceInventoryItem
import app.lifeos.core.model.source.SourcePrivacyZone
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.life.InitialDataSourceAdapter
import app.lifeos.core.runtime.life.InitialDataSourceStatus
import app.lifeos.core.runtime.life.LifeSourceRecord
import app.lifeos.core.runtime.livedata.LiveDataAccountKey
import app.lifeos.core.runtime.livedata.LiveDataAccountObservation
import app.lifeos.core.runtime.livedata.LiveDataCapability
import app.lifeos.core.runtime.livedata.LiveDataConnectorId
import app.lifeos.core.runtime.livedata.LiveDataDelta
import app.lifeos.core.runtime.livedata.LiveDataDeltaOperation
import app.lifeos.core.runtime.livedata.LiveDataPermission
import app.lifeos.core.runtime.livedata.LiveDataPermissionState
import app.lifeos.core.runtime.livedata.LiveDataStreamKind
import app.lifeos.core.runtime.livedata.canonicalLiveDataMetadata
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

internal object AndroidLiveSourceConnectors {
    fun create(
        context: Context,
        now: () -> Instant = Instant::now,
    ): List<LiveSourceConnector> = listOf(
        AndroidInitialSourceLiveConnector(
            source = androidCalendarInitialDataSource(context),
            sourceId = LiveSourceId("android-calendar"),
            connectorId = LiveDataConnectorId("android-calendar"),
            accountKey = LiveDataAccountKey("device-local-calendar"),
            streamKind = LiveDataStreamKind.CALENDAR,
            connectorVersion = "android-calendar-live/v1",
            priority = LiveSourcePriority.HIGH,
            privacyZone = SourcePrivacyZone.SENSITIVE,
            now = now,
        ),
        AndroidInitialSourceLiveConnector(
            source = AndroidSharedFilesInitialDataSource(context),
            sourceId = LiveSourceId(AndroidSharedFilesInitialDataSource.SOURCE_ID),
            connectorId = LiveDataConnectorId("android-files"),
            accountKey = LiveDataAccountKey("device-shared-files"),
            streamKind = LiveDataStreamKind.FILE,
            connectorVersion = "android-shared-files-live/v1",
            priority = LiveSourcePriority.NORMAL,
            privacyZone = SourcePrivacyZone.PRIVATE,
            now = now,
        ),
    )
}

/**
 * Snapshot-only bridge over the existing productive Android source readers.
 *
 * It never persists payloads. The latest scan payload is retained only in a process-local immutable
 * cache long enough to project the diff into canonical LiveData Photons. Restart truth is owned by
 * the encrypted LiveSourceSnapshotRepository.
 */
internal class AndroidInitialSourceLiveConnector(
    private val source: InitialDataSourceAdapter,
    override val sourceId: LiveSourceId,
    private val connectorId: LiveDataConnectorId,
    private val accountKey: LiveDataAccountKey,
    override val streamKind: LiveDataStreamKind,
    override val connectorVersion: String,
    override val priority: LiveSourcePriority,
    private val privacyZone: SourcePrivacyZone,
    private val now: () -> Instant = Instant::now,
) : LiveSourceConnector, LiveSourceAdapter {
    private data class CurrentRecord(
        val externalKey: String,
        val fingerprint: String,
        val payload: String,
        val mimeType: String,
        val sourceObservedAt: Instant,
        val metadata: app.lifeos.core.model.source.CanonicalSourceMetadata?,
    )

    private val currentRecords = AtomicReference<Map<String, CurrentRecord>>(emptyMap())

    override val adapter: LiveSourceAdapter
        get() = this

    init {
        require(source.descriptor.sourceId == sourceId.value) {
            "Live source id must preserve the underlying Android source identity"
        }
    }

    override suspend fun accountObservation(): LiveDataAccountObservation {
        val status = source.status()
        val requiredPermission = streamKind.requiredPermission
        val permissionState = when (status) {
            InitialDataSourceStatus.AVAILABLE -> LiveDataPermissionState.GRANTED
            InitialDataSourceStatus.UNAUTHORIZED -> LiveDataPermissionState.DENIED
            InitialDataSourceStatus.UNAVAILABLE -> LiveDataPermissionState.UNAVAILABLE
        }
        return LiveDataAccountObservation(
            connectorId = connectorId,
            accountKey = accountKey,
            capabilities = if (status == InitialDataSourceStatus.UNAVAILABLE) {
                emptySet()
            } else {
                setOf(streamKind.requiredCapability)
            },
            permissions = LiveDataPermission.entries.associateWith { permission ->
                if (permission == requiredPermission) {
                    permissionState
                } else {
                    LiveDataPermissionState.UNAVAILABLE
                }
            },
            observedAt = now(),
        )
    }

    override suspend fun inventory(): SourceInventory {
        val records = collectAllRecords(source)
        val projected = records.map { record ->
            record.toCurrentRecord(sourceId)
        }
        require(projected.map { it.externalKey }.distinct().size == projected.size) {
            "Android live source emitted duplicate external identities"
        }
        currentRecords.set(projected.associateBy { it.externalKey })
        return SourceInventory(
            items = projected.map { record ->
                SourceInventoryItem(
                    externalKey = record.externalKey,
                    fingerprint = record.fingerprint,
                    privacyZone = privacyZone,
                )
            },
            cursor = null,
        )
    }

    override suspend fun changesAfter(cursor: SourceCursor): SourceChangeSet {
        error("Android Calendar/Files live sources are snapshot-diff providers and own no incremental cursor")
    }

    override suspend fun project(delta: SourceDelta): LiveDataDelta {
        require(delta.sourceId == sourceId)
        val observedAt = now()
        return if (delta.kind == SourceDeltaKind.DELETED) {
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
                metadata = canonicalLiveDataMetadata(
                    connectorId = connectorId,
                    accountKey = accountKey,
                    kind = streamKind,
                    externalId = delta.externalKey,
                    externalVersion = delta.deltaId,
                    occurredAt = observedAt,
                    observedAt = observedAt,
                    mimeType = streamKind.defaultMimeType,
                    privacyZone = delta.privacyZone,
                ),
            )
        } else {
            val record = requireNotNull(currentRecords.get()[delta.externalKey]) {
                "Current Android source payload missing for " + delta.externalKey
            }
            require(record.fingerprint == delta.newFingerprint) {
                "Current Android source payload does not match diff fingerprint"
            }
            val occurredAt = minOf(record.sourceObservedAt, observedAt)
            val canonicalMetadata = record.metadata
                ?.takeIf {
                    it.externalObject.provider.providerId == connectorId.value &&
                        it.externalObject.account.accountId == accountKey.value &&
                        it.externalObject.externalId == delta.externalKey &&
                        it.externalObject.externalVersion == record.fingerprint
                }
                ?.let { metadata ->
                    metadata.copy(
                        privacyZone = SourcePrivacyZone.mostRestrictive(
                            listOf(metadata.privacyZone, delta.privacyZone)
                        ),
                        timestamps = metadata.timestamps.copy(
                            occurredAt = occurredAt,
                            observedAt = observedAt,
                            importedAt = observedAt,
                        ),
                    )
                }
                ?: canonicalLiveDataMetadata(
                    connectorId = connectorId,
                    accountKey = accountKey,
                    kind = streamKind,
                    externalId = delta.externalKey,
                    externalVersion = record.fingerprint,
                    occurredAt = occurredAt,
                    observedAt = observedAt,
                    mimeType = record.mimeType,
                    privacyZone = delta.privacyZone,
                )
            LiveDataDelta(
                connectorId = connectorId,
                accountKey = accountKey,
                kind = streamKind,
                externalId = delta.externalKey,
                externalVersion = record.fingerprint,
                operation = LiveDataDeltaOperation.UPSERT,
                occurredAt = occurredAt,
                observedAt = observedAt,
                payload = record.payload,
                mimeType = record.mimeType,
                metadata = canonicalMetadata,
            )
        }
    }

    private fun LifeSourceRecord.toCurrentRecord(expectedSourceId: LiveSourceId): CurrentRecord {
        require(sourceId == expectedSourceId.value) {
            "Android source record belongs to another source"
        }
        val parsed = parseVersionedRecordId(recordId)
        val externalKey = parsed?.first ?: recordId
        val fingerprint = parsed?.second ?: StableCognitiveIds.fingerprint(
            "android-live-source-record/v1",
            expectedSourceId.value,
            recordId,
            payload,
            mimeType,
            *tags.sorted().toTypedArray(),
        )
        return CurrentRecord(
            externalKey = externalKey,
            fingerprint = fingerprint,
            payload = payload,
            mimeType = mimeType,
            sourceObservedAt = observedAt,
            metadata = metadata,
        )
    }

    private fun parseVersionedRecordId(recordId: String): Pair<String, String>? {
        val fingerprint = recordId.substringAfterLast('-', "")
        if (!fingerprint.matches(Regex("[0-9a-f]{64}"))) return null
        val externalKey = recordId.removeSuffix("-" + fingerprint)
        if (externalKey.isBlank()) return null
        return externalKey to fingerprint
    }

    private suspend fun collectAllRecords(
        source: InitialDataSourceAdapter,
    ): List<LifeSourceRecord> {
        val records = mutableListOf<LifeSourceRecord>()
        var position: String? = null
        var pages = 0
        while (true) {
            check(++pages <= MAX_PAGES) { "Android live source exceeded bounded page count" }
            val remaining = MAX_INVENTORY_ITEMS - records.size
            check(remaining > 0) { "Android live source inventory exceeded bounded capacity" }
            val page = source.readPage(
                afterPosition = position,
                limit = minOf(PAGE_SIZE, remaining),
            )
            require(page.records.all { it.sourceId == source.descriptor.sourceId })
            records += page.records
            if (page.complete) break
            check(records.size < MAX_INVENTORY_ITEMS) {
                "Android live source inventory exceeded bounded capacity"
            }
            val next = requireNotNull(page.nextPosition)
            require(next != position) { "Android live source pager did not advance" }
            position = next
        }
        return records
    }

    private companion object {
        const val PAGE_SIZE = 2_000
        const val MAX_INVENTORY_ITEMS = 16_384
        const val MAX_PAGES = 10_000
    }
}
