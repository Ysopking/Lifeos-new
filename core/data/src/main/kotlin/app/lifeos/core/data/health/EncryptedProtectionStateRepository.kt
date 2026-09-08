package app.lifeos.core.data.health

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.model.health.ProtectionStateLoadResult
import app.lifeos.core.model.health.ProtectionStateWriteResult
import app.lifeos.core.model.health.RuntimeProtectionState
import app.lifeos.core.model.health.RuntimeProtectionStateCodec
import app.lifeos.core.model.health.RuntimeProtectionStateRepository
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedProtectionStateRepository(context: Context) : RuntimeProtectionStateRepository {
    private val file = AtomicFile(context.filesDir.resolve(FILE_NAME))
    private val key: SecretKey by lazy(::loadOrCreateKey)
    private val mutex = Mutex()

    override suspend fun load(): ProtectionStateLoadResult = withContext(Dispatchers.IO) {
        mutex.withLock { loadInternal() }
    }

    override suspend fun compareAndSet(
        expectedRevision: Long?,
        next: RuntimeProtectionState,
    ): ProtectionStateWriteResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            when (val current = loadInternal()) {
                ProtectionStateLoadResult.Missing -> {
                    if (expectedRevision != null) {
                        return@withLock ProtectionStateWriteResult.Conflict(actualRevision = null)
                    }
                    require(next.revision == 1L) {
                        "First persisted protection revision must be 1"
                    }
                }
                is ProtectionStateLoadResult.Loaded -> {
                    if (current.state.revision != expectedRevision) {
                        return@withLock ProtectionStateWriteResult.Conflict(current.state.revision)
                    }
                    require(next.revision == Math.addExact(current.state.revision, 1)) {
                        "Protection revision must advance exactly once"
                    }
                    require(next.generation >= current.state.generation) {
                        "Protection generation cannot move backwards"
                    }
                }
                is ProtectionStateLoadResult.Unreadable -> {
                    return@withLock ProtectionStateWriteResult.UnreadableExisting(current.message)
                }
            }

            val plaintext = RuntimeProtectionStateCodec.encode(next)
            val encrypted = ProtectionStateVaultCodec.encrypt(plaintext, key)
            val stream = file.startWrite()
            try {
                stream.write(encrypted)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
            ProtectionStateWriteResult.Saved(next)
        }
    }

    private fun loadInternal(): ProtectionStateLoadResult {
        val base = file.baseFile
        val backup = base.resolveSibling("${base.name}.bak")
        if (!base.exists() && !backup.exists()) return ProtectionStateLoadResult.Missing
        return try {
            val container = file.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= ProtectionStateVaultCodec.MAX_CONTAINER_BYTES) {
                        "Protection state file too large"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val plaintext = ProtectionStateVaultCodec.decrypt(container, key)
            ProtectionStateLoadResult.Loaded(RuntimeProtectionStateCodec.decode(plaintext))
        } catch (error: Exception) {
            ProtectionStateLoadResult.Unreadable(
                error.message ?: error::class.simpleName ?: "Protection state unreadable",
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
        const val FILE_NAME = "runtime-protection.state"
        const val KEY_ALIAS = "lifeos.runtime.protection.v1"
    }
}
