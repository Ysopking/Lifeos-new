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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class PhotonIndexMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun before() = clearPhotonVault(context)
    @After fun after() = clearPhotonVault(context)

    @Test
    fun legacyHeadMigratesToRevisionVaultAndPublishesEncryptedIndex() = runBlocking {
        val id = PhotonId("b101_migration")
        val legacy = testPhoton(id, 1, "legacy")
        writeLegacyPhoton(context, legacy)

        val store = EncryptedPhotonStore(context)
        assertEquals(legacy, store.load(id))
        assertEquals(legacy, store.load(PhotonRevisionRef(id, 1)))

        val vault = context.filesDir.resolve("photon-vault")
        assertFalse(vault.resolve("${id.value}.photon").exists())
        assertTrue(vault.resolve("revisions/${id.value}/1.photon").exists())
        assertTrue(vault.resolve("photon-index.v1").exists())
    }
}
