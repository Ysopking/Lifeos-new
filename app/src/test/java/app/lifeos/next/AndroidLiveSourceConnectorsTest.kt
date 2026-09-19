package app.lifeos.next

import app.lifeos.core.data.LiveSourceId
import app.lifeos.core.data.LiveSourcePriority
import app.lifeos.core.data.SourceDelta
import app.lifeos.core.data.SourceDeltaKind
import app.lifeos.core.model.source.SourcePrivacyZone
import app.lifeos.core.runtime.life.InitialDataSourceAdapter
import app.lifeos.core.runtime.life.InitialDataSourcePage
import app.lifeos.core.runtime.life.InitialDataSourceStatus
import app.lifeos.core.runtime.life.LifeSourceDescriptor
import app.lifeos.core.runtime.life.LifeSourceRecord
import app.lifeos.core.runtime.livedata.LiveDataAccountKey
import app.lifeos.core.runtime.livedata.LiveDataConnectorId
import app.lifeos.core.runtime.livedata.LiveDataDeltaOperation
import app.lifeos.core.runtime.livedata.LiveDataPermission
import app.lifeos.core.runtime.livedata.LiveDataPermissionState
import app.lifeos.core.runtime.livedata.LiveDataStreamKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AndroidLiveSourceConnectorsTest {
    private val at = Instant.parse("2026-09-19T04:30:00Z")

    @Test
    fun calendarSnapshotUsesStableExternalIdentityAndCurrentPayload() = runBlocking {
        val state = "a".repeat(64)
        val source = FakeInitialSource(
            descriptor = LifeSourceDescriptor("android-calendar", "android-calendar/v1"),
            status = InitialDataSourceStatus.AVAILABLE,
            records = listOf(
                LifeSourceRecord(
                    sourceId = "android-calendar",
                    recordId = "event-42-$state",
                    observedAt = at.minusSeconds(30),
                    payload = "title=care meeting",
                    mimeType = "application/vnd.lifeos.android-calendar-event+text",
                    tags = setOf("event", "calendar-event"),
                ),
            ),
        )
        val connector = AndroidInitialSourceLiveConnector(
            source = source,
            sourceId = LiveSourceId("android-calendar"),
            connectorId = LiveDataConnectorId("android-calendar"),
            accountKey = LiveDataAccountKey("device-local-calendar"),
            streamKind = LiveDataStreamKind.CALENDAR,
            connectorVersion = "android-calendar-live/v1",
            priority = LiveSourcePriority.HIGH,
            privacyZone = SourcePrivacyZone.SENSITIVE,
            now = { at },
        )

        val account = connector.accountObservation()
        assertEquals(
            LiveDataPermissionState.GRANTED,
            account.permissions[LiveDataPermission.READ_CALENDAR],
        )
        assertTrue(LiveDataStreamKind.CALENDAR.requiredCapability in account.capabilities)

        val inventory = connector.inventory()
        assertNull(inventory.cursor)
        assertEquals("event-42", inventory.items.single().externalKey)
        assertEquals(state, inventory.items.single().fingerprint)
        assertEquals(SourcePrivacyZone.SENSITIVE, inventory.items.single().privacyZone)

        val projected = connector.project(
            SourceDelta(
                deltaId = "delta-created",
                sourceId = LiveSourceId("android-calendar"),
                externalKey = "event-42",
                kind = SourceDeltaKind.CREATED,
                previousFingerprint = null,
                newFingerprint = state,
                observationRevision = 1L,
                privacyZone = SourcePrivacyZone.SENSITIVE,
            )
        )
        assertEquals(LiveDataDeltaOperation.UPSERT, projected.operation)
        assertEquals("event-42", projected.externalId)
        assertEquals(state, projected.externalVersion)
        assertEquals("title=care meeting", projected.payload)
    }

    @Test
    fun unauthorizedFilesFailPermissionClosedBeforeInventory() = runBlocking {
        val source = FakeInitialSource(
            descriptor = LifeSourceDescriptor("android-shared-files", "android-shared-files/v2"),
            status = InitialDataSourceStatus.UNAUTHORIZED,
            records = emptyList(),
        )
        val connector = AndroidInitialSourceLiveConnector(
            source = source,
            sourceId = LiveSourceId("android-shared-files"),
            connectorId = LiveDataConnectorId("android-files"),
            accountKey = LiveDataAccountKey("device-shared-files"),
            streamKind = LiveDataStreamKind.FILE,
            connectorVersion = "android-shared-files-live/v1",
            priority = LiveSourcePriority.NORMAL,
            privacyZone = SourcePrivacyZone.PRIVATE,
            now = { at },
        )

        val account = connector.accountObservation()
        assertEquals(
            LiveDataPermissionState.DENIED,
            account.permissions[LiveDataPermission.READ_FILES],
        )
        assertTrue(LiveDataStreamKind.FILE.requiredCapability in account.capabilities)
        assertEquals(0, source.readCalls)
    }

    @Test
    fun deletionProjectsWithoutRetainingDeletedPayload() = runBlocking {
        val source = FakeInitialSource(
            descriptor = LifeSourceDescriptor("android-shared-files", "android-shared-files/v2"),
            status = InitialDataSourceStatus.AVAILABLE,
            records = emptyList(),
        )
        val connector = AndroidInitialSourceLiveConnector(
            source = source,
            sourceId = LiveSourceId("android-shared-files"),
            connectorId = LiveDataConnectorId("android-files"),
            accountKey = LiveDataAccountKey("device-shared-files"),
            streamKind = LiveDataStreamKind.FILE,
            connectorVersion = "android-shared-files-live/v1",
            priority = LiveSourcePriority.NORMAL,
            privacyZone = SourcePrivacyZone.PRIVATE,
            now = { at },
        )

        connector.inventory()
        val projected = connector.project(
            SourceDelta(
                deltaId = "delete-version",
                sourceId = LiveSourceId("android-shared-files"),
                externalKey = "file-deadbeef",
                kind = SourceDeltaKind.DELETED,
                previousFingerprint = "b".repeat(64),
                newFingerprint = null,
                observationRevision = 1L,
                privacyZone = SourcePrivacyZone.PRIVATE,
            )
        )

        assertEquals(LiveDataDeltaOperation.DELETE, projected.operation)
        assertEquals("delete-version", projected.externalVersion)
        assertNull(projected.payload)
    }

    private class FakeInitialSource(
        override val descriptor: LifeSourceDescriptor,
        private val status: InitialDataSourceStatus,
        private val records: List<LifeSourceRecord>,
    ) : InitialDataSourceAdapter {
        var readCalls: Int = 0
            private set

        override suspend fun status(): InitialDataSourceStatus = status

        override suspend fun readPage(
            afterPosition: String?,
            limit: Int,
        ): InitialDataSourcePage {
            readCalls += 1
            require(afterPosition == null)
            return InitialDataSourcePage(records.take(limit), null, complete = true)
        }
    }
}
