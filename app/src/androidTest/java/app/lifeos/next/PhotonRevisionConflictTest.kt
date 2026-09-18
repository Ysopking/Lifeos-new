package app.lifeos.next

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lifeos.core.data.EncryptedPhotonStore
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionWriteResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class PhotonRevisionConflictTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun before() = clearPhotonVault(context)
    @After fun after() = clearPhotonVault(context)

    @Test
    fun sameRevisionIsIdempotentButConflictingOrStaleWritesAreRejected() = runBlocking {
        val store = EncryptedPhotonStore(context)
        val id = PhotonId("b101_conflict")
        val first = testPhoton(id, 1, "one")
        val second = testPhoton(id, 2, "two")

        assertTrue(store.saveRevision(first, null) is PhotonRevisionWriteResult.Created)
        assertTrue(store.saveRevision(first, null) is PhotonRevisionWriteResult.Idempotent)
        assertTrue(
            store.saveRevision(first.copy(content = "different"), null) is
                PhotonRevisionWriteResult.Conflict
        )
        assertTrue(store.saveRevision(second, 1) is PhotonRevisionWriteResult.Advanced)
        assertTrue(store.saveRevision(first, null) is PhotonRevisionWriteResult.Conflict)

        assertEquals(second, store.load(id))
    }
}
