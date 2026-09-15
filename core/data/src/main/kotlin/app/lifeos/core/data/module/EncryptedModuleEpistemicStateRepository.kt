package app.lifeos.core.data.module

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.module.ModuleEpistemicLoadResult
import app.lifeos.core.runtime.module.ModuleEpistemicState
import app.lifeos.core.runtime.module.ModuleEpistemicStateCodec
import app.lifeos.core.runtime.module.ModuleEpistemicStateRepository
import app.lifeos.core.runtime.module.ModuleEpistemicWriteResult
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * AndroidKeyStore/AES-GCM backed compare-and-set store for one current module epistemic state per
 * stable module id. The persisted state's predecessor fingerprint preserves the revision chain;
 * outcome and InformationAsset ledgers remain the authorities for the underlying evidence history.
 */
class EncryptedModuleEpistemicStateRepository(
    context: Context,
) : ModuleEpistemicStateRepository {
    private val directory = context.filesDir.resolve(DIRECTORY_NAME).apply { mkdirs() }
    private val key: SecretKey by lazy(::loadOrCreateKey)
    private val mutex = Mutex()

    override suspend fun load(moduleId: String): ModuleEpistemicLoadResult = withContext(Dispatchers.IO) {
        require(moduleId.isNotBlank()) { "Module id must not be blank" }
        mutex.withLock { loadInternal(moduleId) }
    }

    override suspend fun compareAndSet(
        moduleId: String,
        expectedRevision: Long?,
        next: ModuleEpistemicState,
    ): ModuleEpistemicWriteResult = withContext(Dispatchers.IO) {
        require(moduleId.isNotBlank()) { "Module id must not be blank" }
        require(next.identity.moduleId == moduleId) { "Module epistemic state id mismatch" }
        mutex.withLock {
            when (val current = loadInternal(moduleId)) {
                ModuleEpistemicLoadResult.Missing -> {
                    if (expectedRevision != null) {
                        return@withLock ModuleEpistemicWriteResult.Conflict(actualRevision = null)
                    }
                    require(next.revision == 1L) { "First persisted module epistemic revision must be 1" }
                    require(next.previousStateFingerprint == null) {
                        "First persisted module epistemic state cannot bind a predecessor"
                    }
                }
                is ModuleEpistemicLoadResult.Loaded -> {
                    if (current.state.revision != expectedRevision) {
                        return@withLock ModuleEpistemicWriteResult.Conflict(current.state.revision)
                    }
                    require(next.revision == Math.addExact(current.state.revision, 1L)) {
                        "Module epistemic revision must advance exactly once"
                    }
                    require(next.previousStateFingerprint == current.state.stateFingerprint) {
                        "Module epistemic successor must bind the exact predecessor fingerprint"
                    }
                    require(next.identity.moduleId == current.state.identity.moduleId) {
                        "Module epistemic successor changed stable module id"
                    }
                }
                is ModuleEpistemicLoadResult.Unreadable -> {
                    return@withLock ModuleEpistemicWriteResult.UnreadableExisting(current.message)
                }
            }

            val plaintext = ModuleEpistemicStateCodec.encode(next)
            val encrypted = ModuleEpistemicVaultCodec.encrypt(plaintext, key)
            val file = fileFor(moduleId)
            val stream = file.startWrite()
            try {
                stream.write(encrypted)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
            ModuleEpistemicWriteResult.Saved(next)
        }
    }

    private fun loadInternal(moduleId: String): ModuleEpistemicLoadResult {
        val file = fileFor(moduleId)
        val base = file.baseFile
        val backup = base.resolveSibling("${base.name}.bak")
        if (!base.exists() && !backup.exists()) return ModuleEpistemicLoadResult.Missing

        return try {
            val container = file.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= ModuleEpistemicVaultCodec.MAX_CONTAINER_BYTES) {
                        "Module epistemic state file too large"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val plaintext = ModuleEpistemicVaultCodec.decrypt(container, key)
            val state = ModuleEpistemicStateCodec.decode(plaintext)
            require(state.identity.moduleId == moduleId) {
                "Persisted module epistemic state belongs to a different module"
            }
            ModuleEpistemicLoadResult.Loaded(state)
        } catch (error: Exception) {
            ModuleEpistemicLoadResult.Unreadable(
                error.message ?: error::class.simpleName ?: "Module epistemic state unreadable",
            )
        }
    }

    private fun fileFor(moduleId: String): AtomicFile = AtomicFile(
        directory.resolve("${StableFieldIds.fingerprint("module-epistemic-file/v1", moduleId)}.state")
    )

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
        const val DIRECTORY_NAME = "module-epistemic-state"
        const val KEY_ALIAS = "lifeos.module.epistemic.v1"
    }
}
