package app.lifeos.core.data.field

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldSnapshot
import app.lifeos.core.field.FieldSnapshotCodec
import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.field.FieldSnapshotLoadReport
import app.lifeos.core.field.FieldSnapshotRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedFieldSnapshotRepository(context: Context) : FieldSnapshotRepository {
    private val directory = context.filesDir.resolve("field-snapshot-vault")
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val mutex = Mutex()

    override suspend fun save(snapshot: FieldSnapshot): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val encoded = FieldSnapshotCodec.encode(snapshot).toByteArray(StandardCharsets.UTF_8)
            val encrypted = FieldSnapshotVaultCodec.encrypt(encoded, key)
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

    override suspend fun load(id: FieldSnapshotId): FieldSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val name = fileName(id)
            val base = directory.resolve(name)
            val backup = directory.resolve("$name.bak")
            if (!base.exists() && !backup.exists()) return@withLock null
            readSnapshotInternal(name).also { snapshot ->
                require(snapshot.id == id) { "Field snapshot identity mismatch" }
            }
        }
    }

    override suspend fun loadLatest(domainId: FieldDomainId): FieldSnapshot? {
        val report = loadReport(domainId)
        return report.snapshots.maxWithOrNull(
            compareBy<FieldSnapshot> { it.state.iteration.index }
                .thenBy { it.runId.value }
                .thenBy { it.id.value },
        )
    }

    override suspend fun loadReport(domainId: FieldDomainId?): FieldSnapshotLoadReport =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectory()
                val files = directory.listFiles() ?: throw IOException("Field snapshot vault cannot be listed")
                val names = files
                    .map { it.name.removeSuffix(".bak") }
                    .filter { it.endsWith(FILE_SUFFIX) }
                    .distinct()
                    .sorted()
                val snapshots = mutableListOf<FieldSnapshot>()
                val failures = mutableListOf<String>()
                names.forEach { name ->
                    try {
                        val snapshot = readSnapshotInternal(name)
                        if (domainId == null || snapshot.domainId == domainId) snapshots += snapshot
                    } catch (_: Exception) {
                        failures += name
                    }
                }
                FieldSnapshotLoadReport(
                    snapshots = snapshots
                        .distinctBy { it.id }
                        .sortedWith(
                            compareBy<FieldSnapshot> { it.domainId.value }
                                .thenBy { it.state.iteration.index }
                                .thenBy { it.runId.value }
                                .thenBy { it.id.value },
                        ),
                    unreadableEntries = failures.distinct().sorted(),
                )
            }
        }

    override suspend fun delete(id: FieldSnapshotId): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val name = fileName(id)
            val target = AtomicFile(directory.resolve(name))
            target.delete()
            check(!target.baseFile.exists() && !directory.resolve("$name.bak").exists()) {
                "Field snapshot delete was not durable"
            }
        }
    }

    private fun readSnapshotInternal(name: String): FieldSnapshot {
        require(name.endsWith(FILE_SUFFIX)) { "Invalid field snapshot file name" }
        val container = AtomicFile(directory.resolve(name)).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= FieldSnapshotVaultCodec.MAX_CONTAINER_BYTES) {
                    "Field snapshot file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val plaintext = FieldSnapshotVaultCodec.decrypt(container, key)
        val snapshot = FieldSnapshotCodec.decode(plaintext.toString(StandardCharsets.UTF_8))
        require(fileName(snapshot.id) == name) { "Field snapshot file/content identity mismatch" }
        return snapshot
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Field snapshot vault unavailable" }
    }

    private fun fileName(id: FieldSnapshotId): String {
        val value = id.value
        require(value.startsWith(SNAPSHOT_PREFIX)) { "Invalid field snapshot id prefix" }
        val digest = value.removePrefix(SNAPSHOT_PREFIX)
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid field snapshot id digest" }
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
        const val KEY_ALIAS = "lifeos.field.snapshot.v1"
        const val SNAPSHOT_PREFIX = "snapshot:"
        const val FILE_SUFFIX = ".fsnapshot"
    }
}
