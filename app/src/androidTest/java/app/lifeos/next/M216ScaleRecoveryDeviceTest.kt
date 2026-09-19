package app.lifeos.next

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lifeos.core.data.EncryptedPhotonStore
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexCursor
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M216ScaleRecoveryDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val at = Instant.parse("2026-09-19T17:30:00Z")

    @Test
    fun seedBoundedScaleCorpusBeforeColdRestart() = runBlocking {
        clearPhotonVault(context)
        val store = EncryptedPhotonStore(context)

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
            assertTrue(store.saveRevision(photon, null) is PhotonRevisionWriteResult.Created)
        }

        val report = store.indexReport()
        assertEquals(SCALE_PHOTONS, report.livePhotonCount)
        assertEquals(SCALE_PHOTONS, report.latestRefs.size)
        assertEquals(
            PhotonRevisionRef(PhotonId(id(SCALE_PHOTONS - 1)), 1L),
            store.latestRef(PhotonId(id(SCALE_PHOTONS - 1))),
        )
    }

    @Test
    fun recoverPagedScaleCorpusAfterColdRestart() = runBlocking {
        val reopened = EncryptedPhotonStore(context)
        val report = reopened.indexReport()
        assertEquals(SCALE_PHOTONS, report.livePhotonCount)
        assertEquals(SCALE_PHOTONS, report.latestRefs.size)

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
        clearPhotonVault(context)
    }

    private fun id(index: Int): String =
        "m216_scale_recovery_" + index.toString().padStart(5, '0')

    private companion object {
        const val SCALE_PHOTONS = 4_500
    }
}
