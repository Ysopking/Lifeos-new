package app.lifeos.core.data.world

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.world.WorldFormulaSnapshot
import app.lifeos.core.runtime.world.WorldFormulaSnapshotCodec
import app.lifeos.core.runtime.world.WorldFormulaSnapshotLoadReport
import app.lifeos.core.runtime.world.WorldFormulaSnapshotRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedWorldFormulaSnapshotRepository(context: Context) : WorldFormulaSnapshotRepository {
    private val directory = context.filesDir.resolve("world-formula-snapshot-vault")
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val mutex = Mutex()

    override suspend fun save(snapshot: WorldFormulaSnapshot): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val encoded = WorldFormulaSnapshotCodec.encode(snapshot)
            val encrypted = WorldFormulaVaultCodec.encrypt(encoded, key)
            val target = AtomicFile(directory.resolve(fileName(snapshot.id)))
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

    override suspend fun load(id: String): WorldFormulaSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val name = fileName(id)
            val base = directory.resolve(name)
            val backup = directory.resolve("$name.bak")
            if (!base.exists() && !backup.exists()) return@withLock null
            readSnapshotInternal(name).also { snapshot ->
                require(snapshot.id == id) { "World formula snapshot identity mismatch" }
            }
        }
    }

    @Deprecated(
        message = "WorldFormula snapshots have no global chronological head; use ProductiveWorldHead or a domain-specific authority",
        level = DeprecationLevel.WARNING,
    )
    override suspend fun loadLatest(): WorldFormulaSnapshot? {
        val report = loadReport()
        return report.snapshots.maxWithOrNull(
            compareBy<WorldFormulaSnapshot> { it.finalState.generation }
                .thenBy { it.runId }
                .thenBy { it.id }
        )
    }

    override suspend fun loadReport(): WorldFormulaSnapshotLoadReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val files = directory.listFiles() ?: throw IOException("World formula snapshot vault cannot be listed")
            val names = files
                .map { it.name.removeSuffix(".bak") }
                .filter { it.endsWith(FILE_SUFFIX) }
                .distinct()
                .sorted()
            val snapshots = mutableListOf<WorldFormulaSnapshot>()
            val failures = mutableListOf<String>()
            names.forEach { name ->
                try {
                    snapshots += readSnapshotInternal(name)
                } catch (_: Exception) {
                    failures += name
                }
            }
            WorldFormulaSnapshotLoadReport(
                snapshots = snapshots
                    .distinctBy { it.id }
                    .sortedWith(
                        compareBy<WorldFormulaSnapshot> { it.finalState.generation }
                            .thenBy { it.runId }
                            .thenBy { it.id }
                    ),
                unreadableEntries = failures.distinct().sorted(),
            )
        }
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val name = fileName(id)
            val target = AtomicFile(directory.resolve(name))
            target.delete()
            check(!target.baseFile.exists() && !directory.resolve("$name.bak").exists()) {
                "World formula snapshot delete was not durable"
            }
        }
    }

    private fun readSnapshotInternal(name: String): WorldFormulaSnapshot {
        require(name.endsWith(FILE_SUFFIX)) { "Invalid world formula snapshot file name" }
        val container = AtomicFile(directory.resolve(name)).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= WorldFormulaVaultCodec.MAX_CONTAINER_BYTES) {
                    "World formula snapshot file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val plaintext = WorldFormulaVaultCodec.decrypt(container, key)
        val snapshot = WorldFormulaSnapshotCodec.decode(plaintext)
        require(fileName(snapshot.id) == name) { "World formula snapshot file/content identity mismatch" }
        return snapshot
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "World formula snapshot vault unavailable" }
    }

    private fun fileName(id: String): String {
        require(id.startsWith(SNAPSHOT_PREFIX)) { "Invalid world formula snapshot id prefix" }
        val digest = id.removePrefix(SNAPSHOT_PREFIX)
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid world formula snapshot id digest" }
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
        const val KEY_ALIAS = "lifeos.world.formula.snapshot.v1"
        const val SNAPSHOT_PREFIX = "world-snapshot:"
        const val FILE_SUFFIX = ".wsnapshot"
    }
}
