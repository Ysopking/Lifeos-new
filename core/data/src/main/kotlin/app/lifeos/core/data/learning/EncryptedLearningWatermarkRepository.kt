package app.lifeos.core.data.learning

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.learning.LearningWatermarkCodec
import app.lifeos.core.runtime.learning.LearningWatermarkLoadResult
import app.lifeos.core.runtime.learning.LearningWatermarkRepository
import app.lifeos.core.runtime.learning.LearningWatermarkState
import app.lifeos.core.runtime.learning.LearningWatermarkWriteResult
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedLearningWatermarkRepository(context: Context) : LearningWatermarkRepository {
    private val file = AtomicFile(context.filesDir.resolve(FILE_NAME))
    private val key: SecretKey by lazy(::loadOrCreateKey)
    private val mutex = Mutex()

    override suspend fun load(): LearningWatermarkLoadResult = withContext(Dispatchers.IO) {
        mutex.withLock { loadInternal() }
    }

    override suspend fun compareAndSet(
        expectedRevision: Long?,
        next: LearningWatermarkState,
    ): LearningWatermarkWriteResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            when (val current = loadInternal()) {
                LearningWatermarkLoadResult.Missing -> {
                    if (expectedRevision != null) {
                        return@withLock LearningWatermarkWriteResult.Conflict(actualRevision = null)
                    }
                    require(next.revision == 1L) {
                        "First persisted learning watermark revision must be 1"
                    }
                }
                is LearningWatermarkLoadResult.Loaded -> {
                    if (current.state.revision != expectedRevision) {
                        return@withLock LearningWatermarkWriteResult.Conflict(current.state.revision)
                    }
                    require(next.revision == Math.addExact(current.state.revision, 1)) {
                        "Learning watermark revision must advance exactly once"
                    }
                }
                is LearningWatermarkLoadResult.Unreadable -> {
                    return@withLock LearningWatermarkWriteResult.UnreadableExisting(current.message)
                }
            }

            val plaintext = LearningWatermarkCodec.encode(next).toByteArray(StandardCharsets.UTF_8)
            val encrypted = LearningWatermarkVaultCodec.encrypt(plaintext, key)
            val stream = file.startWrite()
            try {
                stream.write(encrypted)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
            LearningWatermarkWriteResult.Saved(next)
        }
    }

    private fun loadInternal(): LearningWatermarkLoadResult {
        val base = file.baseFile
        val backup = base.resolveSibling("${base.name}.bak")
        if (!base.exists() && !backup.exists()) return LearningWatermarkLoadResult.Missing

        return try {
            val container = file.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= LearningWatermarkVaultCodec.MAX_CONTAINER_BYTES) {
                        "Learning watermark file too large"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val plaintext = LearningWatermarkVaultCodec.decrypt(container, key)
            val encoded = String(plaintext, StandardCharsets.UTF_8)
            LearningWatermarkLoadResult.Loaded(LearningWatermarkCodec.decode(encoded))
        } catch (error: Exception) {
            LearningWatermarkLoadResult.Unreadable(
                error.message ?: error::class.simpleName ?: "Learning watermark state unreadable",
            )
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
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val FILE_NAME = "continuous-learning-watermarks.state"
        const val KEY_ALIAS = "lifeos.learning.watermark.v1"
    }
}