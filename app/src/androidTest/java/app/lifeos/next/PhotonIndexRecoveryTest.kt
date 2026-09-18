package app.lifeos.next

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lifeos.core.data.EncryptedPhotonStore
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class PhotonIndexRecoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun before() = clearPhotonVault(context)
    @After fun after() = clearPhotonVault(context)

    @Test
    fun destroyedIndexRebuildsOnlyFromEncryptedRevisionPayloads() = runBlocking {
        val id = PhotonId("b101_index_recovery")
        val first = testPhoton(id, 1, "one")
        val second = testPhoton(id, 2, "two")
        EncryptedPhotonStore(context).apply {
            saveRevision(first, null)
            saveRevision(second, 1)
        }

        context.filesDir.resolve("photon-vault/photon-index.v1").writeBytes(byteArrayOf(1, 2, 3, 4))

        val recovered = EncryptedPhotonStore(context)
        assertEquals(second, recovered.load(id))
        assertEquals(first, recovered.load(PhotonRevisionRef(id, 1)))
        assertEquals(second, recovered.load(PhotonRevisionRef(id, 2)))
        assertTrue(recovered.indexReport().unreadableRevisionFiles.isEmpty())
    }
}
