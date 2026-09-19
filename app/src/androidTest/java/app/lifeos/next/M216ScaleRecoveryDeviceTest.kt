package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexCursor
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M216ScaleRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: LifeOsApplication
        get() = instrumentation.targetContext.applicationContext as LifeOsApplication
    private val at = Instant.parse("2026-09-19T17:30:00Z")

    @Test
    fun seedBoundedScaleCorpusBeforeColdRestart() = runBlocking {
        val store = app.kernel.photonStore

        repeat(SCALE_PHOTONS) { index ->
            val photon = Photon(
                id = PhotonId(id(index)),
                revision = 1L,
                content = "m216-scale-payload-$index",
                provenance = Provenance(
                    source = "m216-scale-recovery",
                    actor = "scale-fixture",
                    createdAt = at.plusSeconds(index.toLong()),
                ),
                tags = setOf("m216-scale", "bucket:${index % 16}"),
            )
            val write = store.saveRevision(photon, null)
            assertTrue(
                write is PhotonRevisionWriteResult.Created ||
                    write is PhotonRevisionWriteResult.Idempotent
            )
        }

        val report = store.indexReport()
        assertTrue(report.livePhotonCount >= SCALE_PHOTONS)
        assertEquals(SCALE_PHOTONS, countScaleRefs(store))
        assertEquals(
            PhotonRevisionRef(PhotonId(id(SCALE_PHOTONS - 1)), 1L),
            store.latestRef(PhotonId(id(SCALE_PHOTONS - 1))),
        )
    }

    @Test
    fun recoverPagedScaleCorpusAfterColdRestart() = runBlocking {
        val reopened = app.kernel.photonStore
        val report = reopened.indexReport()
        assertTrue(report.livePhotonCount >= SCALE_PHOTONS)

        var cursor: PhotonIndexCursor? = null
        var seen = 0
        var first: PhotonRevisionRef? = null
        var last: PhotonRevisionRef? = null
        while (true) {
            val page = reopened.query(
                PhotonIndexQuery(
                    allTags = setOf("m216-scale"),
                    order = PhotonIndexOrder.IDENTITY,
                    after = cursor,
                    limit = PhotonIndexQuery.HARD_PAGE_LIMIT,
                )
            )
            if (page.isEmpty()) break
            if (first == null) first = page.first()
            last = page.last()
            seen += page.size
            if (page.size < PhotonIndexQuery.HARD_PAGE_LIMIT) break
            cursor = PhotonIndexCursor(PhotonIndexOrder.IDENTITY, page.last())
        }

        assertEquals(SCALE_PHOTONS, seen)
        assertEquals(PhotonRevisionRef(PhotonId(id(0)), 1L), first)
        assertEquals(PhotonRevisionRef(PhotonId(id(SCALE_PHOTONS - 1)), 1L), last)
        assertNotNull(reopened.load(requireNotNull(last)))
    }

    private suspend fun countScaleRefs(store: RevisionedPhotonRepository): Int {
        var cursor: PhotonIndexCursor? = null
        var count = 0
        while (true) {
            val page = store.query(
                PhotonIndexQuery(
                    allTags = setOf("m216-scale"),
                    order = PhotonIndexOrder.IDENTITY,
                    after = cursor,
                    limit = PhotonIndexQuery.HARD_PAGE_LIMIT,
                )
            )
            if (page.isEmpty()) return count
            count += page.size
            if (page.size < PhotonIndexQuery.HARD_PAGE_LIMIT) return count
            cursor = PhotonIndexCursor(PhotonIndexOrder.IDENTITY, page.last())
        }
    }

    private fun id(index: Int): String =
        "m216_scale_recovery_" + index.toString().padStart(5, '0')

    private companion object {
        const val SCALE_PHOTONS = 4_500
    }
}
