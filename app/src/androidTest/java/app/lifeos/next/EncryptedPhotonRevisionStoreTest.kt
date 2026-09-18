package app.lifeos.next

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lifeos.core.data.EncryptedPhotonStore
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
class EncryptedPhotonRevisionStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun before() = clearPhotonVault(context)
    @After fun after() = clearPhotonVault(context)

    @Test
    fun historicalRevisionsRemainAddressableWhileHeadAdvances() = runBlocking {
        val store = EncryptedPhotonStore(context)
        val id = PhotonId("b101_revision_history")
        val first = testPhoton(id, 1, "one")
        val second = testPhoton(id, 2, "two")

        assertIs<PhotonRevisionWriteResult.Created>(store.saveRevision(first, null))
        assertIs<PhotonRevisionWriteResult.Advanced>(store.saveRevision(second, 1))

        assertEquals(first, store.load(PhotonRevisionRef(id, 1)))
        assertEquals(second, store.load(PhotonRevisionRef(id, 2)))
        assertEquals(second, store.load(id))
        assertEquals(listOf(second), store.loadReport().photons)
    }
}
