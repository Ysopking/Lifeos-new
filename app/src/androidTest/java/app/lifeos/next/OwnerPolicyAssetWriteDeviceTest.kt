package app.lifeos.next

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.EncryptedBinaryAssetStore
import app.lifeos.core.data.policy.EncryptedOwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyGrant
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device regression for V14 FILE_WRITE enforcement at the encrypted binary asset boundary. */
@RunWith(AndroidJUnit4::class)
class OwnerPolicyAssetWriteDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun assetWriteIsAllowedThenFailsClosedAfterRevokeAcrossFreshStore() = runBlocking {
        val root = instrumentation.targetContext.cacheDir.resolve(
            "owner-policy-asset-${System.nanoTime()}"
        )
        check(root.mkdirs()) { "Unable to create isolated owner-policy asset test root" }
        val context = isolatedFilesContext(root)
        val repository = EncryptedOwnerPolicyRepository(context)
        val ledger = OwnerPolicyLedger(repository)
        val grant = OwnerPolicyGrant.create(
            actorId = OwnerActorId(EncryptedBinaryAssetStore.OWNER_ACTOR_ID),
            effect = OwnerEffectType.FILE_WRITE,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.EXACT,
                EncryptedBinaryAssetStore.OWNER_RESOURCE,
            ),
            scope = EncryptedBinaryAssetStore.OWNER_SCOPE,
            validFrom = Instant.EPOCH,
        )
        val bytes = byteArrayOf(1, 3, 3, 7)

        try {
            val blockedBeforeGrant = runCatching {
                EncryptedBinaryAssetStore(context).save(bytes, "application/octet-stream")
            }.exceptionOrNull()
            assertOwnerPolicyBlocked(blockedBeforeGrant)
            assertEquals(0, assetFileCount(root))

            ledger.grant(grant)
            val authorizedStore = EncryptedBinaryAssetStore(context)
            val ref = authorizedStore.save(bytes, "application/octet-stream")
            assertArrayEquals(bytes, authorizedStore.load(ref))
            assertEquals(1, assetFileCount(root))

            ledger.revoke(grant.id)

            // A fresh store models restart/reconstruction. It must reload durable policy and must
            // not reuse the authority that existed before revoke.
            val blockedAfterRestart = runCatching {
                EncryptedBinaryAssetStore(context).save(byteArrayOf(9, 9), "application/octet-stream")
            }.exceptionOrNull()
            assertOwnerPolicyBlocked(blockedAfterRestart)
            assertEquals(1, assetFileCount(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun isolatedFilesContext(root: File): Context =
        object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }

    private fun assetFileCount(root: File): Int =
        root.resolve("asset-vault").listFiles()
            ?.count { file -> file.name.endsWith(".asset") }
            ?: 0

    private fun assertOwnerPolicyBlocked(error: Throwable?) {
        assertNotNull("Expected fail-closed owner-policy rejection", error)
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().startsWith("owner-policy-blocked:"))
    }
}
