package app.lifeos.core.data.thought

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.ThoughtMatrixDurableState
import app.lifeos.core.runtime.ThoughtMatrixDurableStateCodec
import app.lifeos.core.runtime.ThoughtMatrixStateRepository
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedThoughtMatrixStateRepository(context: Context) : ThoughtMatrixStateRepository {
    private val directory = context.filesDir.resolve("thought-matrix-state-vault")
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val mutex = Mutex()

    override suspend fun save(state: ThoughtMatrixDurableState): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val plaintext = ThoughtMatrixDurableStateCodec.encode(state)
            val encrypted = ThoughtMatrixStateVaultCodec.encrypt(plaintext, key)
            val target = AtomicFile(directory.resolve(FILE_NAME))
            val stream = target.startWrite()
            try {
                stream.write(encrypted)
                target.finishWrite(stream)
            } catch (error: Exception) {
                target.failWrite(stream)
                throw error
            }
        }
    }

    override suspend fun load(): ThoughtMatrixDurableState? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val base = directory.resolve(FILE_NAME)
            val backup = directory.resolve("$FILE_NAME.bak")
            if (!base.exists() && !backup.exists()) return@withLock null

            val container = AtomicFile(base).openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= ThoughtMatrixStateVaultCodec.MAX_CONTAINER_BYTES) {
                        "ThoughtMatrix state file too large"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val plaintext = ThoughtMatrixStateVaultCodec.decrypt(container, key)
            ThoughtMatrixDurableStateCodec.decode(plaintext)
        }
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "ThoughtMatrix state vault unavailable" }
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
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "lifeos.thought.matrix.state.v1"
        const val FILE_NAME = "latest.tmatrix"
    }
}
