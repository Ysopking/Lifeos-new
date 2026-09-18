package app.lifeos.core.data.cognition

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.cognition.CognitionJournalIndexRepository
import app.lifeos.core.runtime.cognition.CognitionJournalIndexSnapshot
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedCognitionJournalIndexRepository(
    context: Context,
) : CognitionJournalIndexRepository {
    private val file = AtomicFile(context.filesDir.resolve(FILE_NAME))
    private val key: SecretKey by lazy(::loadOrCreateKey)
    private val mutex = Mutex()

    @Volatile
    private var cacheInitialized = false

    @Volatile
    private var cached: CognitionJournalIndexSnapshot? = null

    override suspend fun load(): CognitionJournalIndexSnapshot? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (cacheInitialized) return@withLock cached

                val base = file.baseFile
                val backup = base.resolveSibling("${base.name}.bak")
                if (!base.exists() && !backup.exists()) {
                    cacheInitialized = true
                    cached = null
                    return@withLock null
                }

                val container = file.openRead().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(
                            output.size() + count <= CognitionJournalIndexVaultCodec.MAX_CONTAINER_BYTES
                        ) { "Cognition journal index file too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val snapshot = CognitionJournalIndexCodec.decode(
                    CognitionJournalIndexVaultCodec.decrypt(container, key)
                )
                cached = snapshot
                cacheInitialized = true
                snapshot
            }
        }

    override suspend fun save(snapshot: CognitionJournalIndexSnapshot) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val plaintext = CognitionJournalIndexCodec.encode(snapshot)
                val encrypted = CognitionJournalIndexVaultCodec.encrypt(plaintext, key)
                val stream = file.startWrite()
                try {
                    stream.write(encrypted)
                    file.finishWrite(stream)
                } catch (error: Exception) {
                    file.failWrite(stream)
                    throw error
                }
                cached = snapshot.canonical()
                cacheInitialized = true
            }
        }

    private fun loadOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val FILE_NAME = "cognition-journal-index.v1"
        const val KEY_ALIAS = "lifeos.cognition.journal.index.v1"
    }
}
