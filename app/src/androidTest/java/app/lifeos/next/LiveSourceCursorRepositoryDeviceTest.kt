package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.EncryptedLiveSourceCursorRepository
import app.lifeos.core.data.LiveSourceCursorLoadResult
import app.lifeos.core.data.LiveSourceCursorState
import app.lifeos.core.data.LiveSourceCursorWriteResult
import app.lifeos.core.data.LiveSourceId
import app.lifeos.core.data.SourceCursor
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveSourceCursorRepositoryDeviceTest {
    @Test
    fun cursorSurvivesRepositoryReconstructionAndRejectsStaleWriter() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val sourceId = LiveSourceId("device-live-source-" + suffix)
        val fingerprint = app.lifeos.core.model.StableCognitiveIds.fingerprint(
            "device-live-source/v1",
            sourceId.value,
        )
        val at = Instant.now()
        val first = EncryptedLiveSourceCursorRepository(context)
        val initial = LiveSourceCursorState.initial(sourceId, fingerprint, at)

        assertTrue(first.compareAndSet(sourceId, null, initial) is LiveSourceCursorWriteResult.Saved)

        val bootstrapped = initial.bootstrap(
            nextCursor = SourceCursor("cursor-" + suffix),
            baselineFingerprint = app.lifeos.core.model.StableCognitiveIds.fingerprint(
                "device-live-source-baseline/v1",
                sourceId.value,
            ),
            at = at.plusNanos(1),
        )
        assertTrue(
            first.compareAndSet(sourceId, initial.revision, bootstrapped) is
                LiveSourceCursorWriteResult.Saved
        )

        val reconstructed = EncryptedLiveSourceCursorRepository(context)
        val loaded = reconstructed.load(sourceId) as LiveSourceCursorLoadResult.Loaded
        assertEquals(bootstrapped, loaded.state)

        val stale = initial.copy(
            revision = initial.revision + 1L,
            updatedAt = at.plusNanos(2),
        )
        val conflict = reconstructed.compareAndSet(sourceId, initial.revision, stale)
        assertTrue(conflict is LiveSourceCursorWriteResult.Conflict)
        assertEquals(
            bootstrapped.revision,
            (conflict as LiveSourceCursorWriteResult.Conflict).actualRevision,
        )
    }
}
