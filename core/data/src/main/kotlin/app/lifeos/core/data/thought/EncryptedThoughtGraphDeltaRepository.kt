package app.lifeos.core.data.thought

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.thought.ThoughtGraphDelta
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaCodec
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaId
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaLoadReport
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaRepository
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaWriteResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encrypted, immutable, one-file-per-delta append-only vault for V3 ThoughtGraph history. */
class EncryptedThoughtGraphDeltaRepository(context: Context) : ThoughtGraphDeltaRepository {
    private val directory = context.filesDir.resolve("thought-graph-delta-vault")
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val mutex = Mutex()

    override suspend fun save(delta: ThoughtGraphDelta): ThoughtGraphDeltaWriteResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectory()
                val name = fileName(delta.id)
                val base = directory.resolve(name)
                val backup = directory.resolve("$name.bak")
                if (base.exists() || backup.exists()) {
                    val existing = readDeltaInternal(name)
                    require(sameIdentityContent(existing, delta)) {
                        "Thought graph delta identity collision"
                    }
                    return@withLock ThoughtGraphDeltaWriteResult.Duplicate(existing)
                }

                val plaintext = ThoughtGraphDeltaCodec.encode(delta)
                val encrypted = ThoughtGraphDeltaVaultCodec.encrypt(plaintext, key)
                val target = AtomicFile(base)
                val stream = target.startWrite()
                try {
                    stream.write(encrypted)
                    target.finishWrite(stream)
                } catch (error: Exception) {
                    target.failWrite(stream)
                    throw error
                }
                ThoughtGraphDeltaWriteResult.Stored(delta)
            }
        }

    override suspend fun load(id: ThoughtGraphDeltaId): ThoughtGraphDelta? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectory()
                val name = fileName(id)
                val base = directory.resolve(name)
                val backup = directory.resolve("$name.bak")
                if (!base.exists() && !backup.exists()) return@withLock null
                readDeltaInternal(name).also { delta ->
                    require(delta.id == id) { "Thought graph delta identity mismatch" }
                }
            }
        }

    override suspend fun loadReport(): ThoughtGraphDeltaLoadReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val files = directory.listFiles()
                ?: throw IOException("Thought graph delta vault cannot be listed")
            val names = files
                .map { it.name.removeSuffix(".bak") }
                .filter { it.endsWith(FILE_SUFFIX) }
                .distinct()
                .sorted()
            val deltas = mutableListOf<ThoughtGraphDelta>()
            val failures = mutableListOf<String>()
            names.forEach { name ->
                try {
                    deltas += readDeltaInternal(name)
                } catch (_: Exception) {
                    failures += name
                }
            }
            ThoughtGraphDeltaLoadReport(
                deltas = deltas
                    .distinctBy { it.id }
                    .sortedBy { it.id.value },
                unreadableEntries = failures.distinct().sorted(),
            )
        }
    }

    private fun readDeltaInternal(name: String): ThoughtGraphDelta {
        require(name.endsWith(FILE_SUFFIX)) { "Invalid thought graph delta file name" }
        val container = AtomicFile(directory.resolve(name)).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= ThoughtGraphDeltaVaultCodec.MAX_CONTAINER_BYTES) {
                    "Thought graph delta file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val plaintext = ThoughtGraphDeltaVaultCodec.decrypt(container, key)
        val delta = ThoughtGraphDeltaCodec.decode(plaintext)
        require(fileName(delta.id) == name) { "Thought graph delta file/content identity mismatch" }
        return delta
    }

    private fun sameIdentityContent(first: ThoughtGraphDelta, second: ThoughtGraphDelta): Boolean =
        first.id == second.id &&
            first.sourceKey == second.sourceKey &&
            first.sourceRevision == second.sourceRevision &&
            first.nodeVersions == second.nodeVersions &&
            first.edgeVersions == second.edgeVersions

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Thought graph delta vault unavailable" }
    }

    private fun fileName(id: ThoughtGraphDeltaId): String {
        val value = id.value
        require(value.startsWith(DELTA_PREFIX)) { "Invalid thought graph delta id prefix" }
        val digest = value.removePrefix(DELTA_PREFIX)
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid thought graph delta id digest" }
        return "$digest$FILE_SUFFIX"
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
        const val KEY_ALIAS = "lifeos.thought.graph.delta.v1"
        const val DELTA_PREFIX = "thought-graph-delta:"
        const val FILE_SUFFIX = ".tgdelta"
    }
}
