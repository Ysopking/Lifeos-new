package app.lifeos.next

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lifeos.core.data.EncryptedPhotonStore
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexCursor
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotonIndexQueryScaleDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun before() = clearPhotonVault(context)
    @After fun after() = clearPhotonVault(context)

    @Test
    fun indexedCandidateQueriesRemainSemanticallyStableAcrossReopen() = runBlocking {
        val store = EncryptedPhotonStore(context)
        repeat(1_000) { index ->
            val id = PhotonId("o201_scale_${index.toString().padStart(4, '0')}")
            val tags = buildSet {
                add("bucket:${index % 10}")
                if (index % 2 == 0) add("even")
            }
            val first = testPhoton(id, 1, "v1-$index").copy(
                mimeType = if (index % 3 == 0) "application/json" else "text/plain",
                phase = if (index % 5 == 0) PhotonPhase.REFLECTING else PhotonPhase.ACTIVE,
                tags = tags,
            )
            assertTrue(store.saveRevision(first, null) is PhotonRevisionWriteResult.Created)
            if (index % 4 == 0) {
                val second = first.copy(
                    revision = 2,
                    content = "v2-$index",
                )
                assertTrue(store.saveRevision(second, 1) is PhotonRevisionWriteResult.Advanced)
            }
        }

        val query = PhotonIndexQuery(
            phases = setOf(PhotonPhase.ACTIVE),
            mimeTypes = setOf("text/plain"),
            allTags = setOf("even", "bucket:2"),
            latestOnly = true,
            order = PhotonIndexOrder.IDENTITY,
            limit = 256,
        )
        val firstPage = store.query(query)
        assertTrue(firstPage.isNotEmpty())
        assertTrue(firstPage.size <= 256)
        firstPage.forEach { ref ->
            val photon = requireNotNull(store.load(ref))
            assertEquals(PhotonPhase.ACTIVE, photon.phase)
            assertEquals("text/plain", photon.mimeType)
            assertTrue("even" in photon.tags)
            assertTrue("bucket:2" in photon.tags)
            assertEquals(store.latestRef(photon.id), ref)
        }

        val paged = mutableListOf<PhotonRevisionRef>()
        var cursor: PhotonIndexCursor? = null
        while (true) {
            val page = store.query(
                query.copy(
                    after = cursor,
                    limit = 17,
                )
            )
            paged += page
            if (page.size < 17) break
            cursor = PhotonIndexCursor(PhotonIndexOrder.IDENTITY, page.last())
        }
        assertEquals(firstPage, paged)

        val reopened = EncryptedPhotonStore(context)
        assertEquals(firstPage, reopened.query(query))
        assertEquals(
            PhotonRevisionRef(PhotonId("o201_scale_0000"), 2),
            reopened.latestRef(PhotonId("o201_scale_0000")),
        )
    }
}
