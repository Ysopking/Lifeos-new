package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.EncryptedLiveSourceSnapshotRepository
import app.lifeos.core.data.LiveSourceId
import app.lifeos.core.data.LiveSourceSnapshotLoadResult
import app.lifeos.core.data.LiveSourceSnapshotState
import app.lifeos.core.data.LiveSourceSnapshotWriteResult
import app.lifeos.core.data.SourceInventoryItem
import app.lifeos.core.model.source.SourcePrivacyZone
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveSourceSnapshotRepositoryDeviceTest {
    @Test
    fun snapshotSurvivesRepositoryReconstructionAndRejectsStaleWriter() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val sourceId = LiveSourceId("device-live-snapshot-" + suffix)
        val identity = app.lifeos.core.model.StableCognitiveIds.fingerprint(
            "device-live-snapshot-identity/v1",
            sourceId.value,
        )
        val at = Instant.now()
        val first = EncryptedLiveSourceSnapshotRepository(context)
        val initial = LiveSourceSnapshotState.initial(
            sourceId = sourceId,
            connectorIdentityFingerprint = identity,
            items = listOf(
                SourceInventoryItem(
                    externalKey = "event-a-" + suffix,
                    fingerprint = "v1",
                    privacyZone = SourcePrivacyZone.PRIVATE,
                ),
            ),
            lastObservationRevision = 0L,
            at = at,
        )

        assertTrue(
            first.compareAndSet(sourceId, null, initial) is LiveSourceSnapshotWriteResult.Saved
        )

        val advanced = initial.replace(
            nextItems = listOf(
                SourceInventoryItem(
                    externalKey = "event-a-" + suffix,
                    fingerprint = "v2",
                    privacyZone = SourcePrivacyZone.SENSITIVE,
                ),
                SourceInventoryItem(
                    externalKey = "event-b-" + suffix,
                    fingerprint = "v1",
                    privacyZone = SourcePrivacyZone.PRIVATE,
                ),
            ),
            nextObservationRevision = 2L,
            at = at.plusNanos(1),
        )
        assertTrue(
            first.compareAndSet(sourceId, initial.revision, advanced) is
                LiveSourceSnapshotWriteResult.Saved
        )

        val reconstructed = EncryptedLiveSourceSnapshotRepository(context)
        val loaded = reconstructed.load(sourceId) as LiveSourceSnapshotLoadResult.Loaded
        assertEquals(advanced, loaded.state)

        val stale = initial.replace(
            nextItems = initial.items,
            nextObservationRevision = 0L,
            at = at.plusNanos(2),
        )
        val conflict = reconstructed.compareAndSet(sourceId, initial.revision, stale)
        assertTrue(conflict is LiveSourceSnapshotWriteResult.Conflict)
        assertEquals(
            advanced.revision,
            (conflict as LiveSourceSnapshotWriteResult.Conflict).actualRevision,
        )
    }
}
