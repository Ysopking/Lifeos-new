package app.lifeos.core.data.informationasset

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.informationasset.InformationAssetCodec
import app.lifeos.core.runtime.informationasset.InformationAssetHistoryLoadReport
import app.lifeos.core.runtime.informationasset.InformationAssetId
import app.lifeos.core.runtime.informationasset.InformationAssetRepository
import app.lifeos.core.runtime.informationasset.InformationAssetRevision
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionId
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionLoadReport
import app.lifeos.core.runtime.informationasset.InformationAssetSaveResult
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Encrypted append-only storage for exact InformationAsset revisions.
 * Existing revisions are never replaced with different content, including when the existing file
 * is unreadable. Parallel history leaves are surfaced as ambiguous instead of inventing recency.
 */
class EncryptedInformationAssetRepository(context: Context) : InformationAssetRepository {
    private val root = context.filesDir.resolve("information-asset-vault")
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val mutex = Mutex()

    override suspend fun save(revision: InformationAssetRevision): InformationAssetSaveResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureRoot()
                val directory = assetDirectory(revision.request.id).also(::ensureDirectory)
                val name = fileName(revision.manifest.id)
                val base = directory.resolve(name)
                val backup = directory.resolve("$name.bak")
                val encoded = InformationAssetCodec.encode(revision)

                if (base.exists() || backup.exists()) {
                    val existing = readRevisionInternal(revision.request.id, name)
                    check(InformationAssetCodec.encode(existing).contentEquals(encoded)) {
                        "InformationAsset revision collision: existing content differs"
                    }
                    return@withLock InformationAssetSaveResult.ALREADY_PRESENT
                }

                val encrypted = InformationAssetVaultCodec.encrypt(encoded, key)
                val target = AtomicFile(base)
                val stream = target.startWrite()
                try {
                    stream.write(encrypted)
                    target.finishWrite(stream)
                } catch (error: Exception) {
                    target.failWrite(stream)
                    throw error
                }
                val persisted = readRevisionInternal(revision.request.id, name)
                check(InformationAssetCodec.encode(persisted).contentEquals(encoded)) {
                    "InformationAsset revision did not round-trip after durable write"
                }
                InformationAssetSaveResult.STORED
            }
        }

    override suspend fun loadRevision(
        assetId: InformationAssetId,
        revisionId: InformationAssetRevisionId,
    ): InformationAssetRevisionLoadReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureRoot()
            val directory = assetDirectory(assetId)
            val name = fileName(revisionId)
            if (!directory.exists()) return@withLock InformationAssetRevisionLoadReport(null)
            val base = directory.resolve(name)
            val backup = directory.resolve("$name.bak")
            if (!base.exists() && !backup.exists()) return@withLock InformationAssetRevisionLoadReport(null)
            try {
                val revision = readRevisionInternal(assetId, name)
                InformationAssetRevisionLoadReport(revision)
            } catch (_: Exception) {
                InformationAssetRevisionLoadReport(null, listOf(relativeEntry(assetId, name)))
            }
        }
    }

    override suspend fun loadLatest(assetId: InformationAssetId): InformationAssetRevisionLoadReport {
        val history = loadHistory(assetId)
        if (history.revisions.isEmpty()) {
            return InformationAssetRevisionLoadReport(null, history.unreadableEntries)
        }
        val referencedParents = history.revisions.mapNotNullTo(mutableSetOf()) {
            it.manifest.parent?.revisionId
        }
        val leaves = history.revisions.filter { it.manifest.id !in referencedParents }
        if (leaves.size != 1) {
            return InformationAssetRevisionLoadReport(
                revision = null,
                unreadableEntries = (history.unreadableEntries +
                    "ambiguous-latest:${assetId.value}:${leaves.map { it.manifest.id.value }.sorted().joinToString(",")}")
                    .distinct()
                    .sorted(),
            )
        }
        return InformationAssetRevisionLoadReport(leaves.single(), history.unreadableEntries)
    }

    override suspend fun loadHistory(assetId: InformationAssetId): InformationAssetHistoryLoadReport =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureRoot()
                val directory = assetDirectory(assetId)
                if (!directory.exists()) return@withLock InformationAssetHistoryLoadReport(emptyList())
                check(directory.isDirectory) { "InformationAsset path is not a directory" }
                val names = directory.listFiles()
                    ?.map { it.name.removeSuffix(".bak") }
                    ?.filter { it.endsWith(FILE_SUFFIX) }
                    ?.distinct()
                    ?.sorted()
                    .orEmpty()
                val revisions = mutableListOf<InformationAssetRevision>()
                val failures = mutableListOf<String>()
                names.forEach { name ->
                    try {
                        revisions += readRevisionInternal(assetId, name)
                    } catch (_: Exception) {
                        failures += relativeEntry(assetId, name)
                    }
                }
                InformationAssetHistoryLoadReport(
                    revisions = revisions
                        .distinctBy { it.manifest.id }
                        .sortedBy { it.manifest.id.value },
                    unreadableEntries = failures.distinct().sorted(),
                )
            }
        }

    private fun readRevisionInternal(assetId: InformationAssetId, name: String): InformationAssetRevision {
        require(name.endsWith(FILE_SUFFIX)) { "Invalid InformationAsset revision file name" }
        val directory = assetDirectory(assetId)
        val container = AtomicFile(directory.resolve(name)).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= InformationAssetVaultCodec.MAX_CONTAINER_BYTES) {
                    "InformationAsset vault entry too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val plaintext = InformationAssetVaultCodec.decrypt(container, key)
        val revision = InformationAssetCodec.decode(plaintext)
        require(revision.request.id == assetId) { "InformationAsset file/content asset identity mismatch" }
        require(fileName(revision.manifest.id) == name) {
            "InformationAsset file/content revision identity mismatch"
        }
        return revision
    }

    private fun ensureRoot() = ensureDirectory(root)

    private fun ensureDirectory(directory: java.io.File) {
        check(directory.isDirectory || directory.mkdirs()) { "InformationAsset vault unavailable" }
    }

    private fun assetDirectory(assetId: InformationAssetId): java.io.File {
        val value = assetId.value
        require(value.startsWith(ASSET_PREFIX)) { "Invalid InformationAsset id prefix" }
        val digest = value.removePrefix(ASSET_PREFIX)
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid InformationAsset id digest" }
        return root.resolve(digest)
    }

    private fun fileName(revisionId: InformationAssetRevisionId): String =
        "${revisionId.value}$FILE_SUFFIX"

    private fun relativeEntry(assetId: InformationAssetId, name: String): String =
        "${assetId.value}/$name"

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
        const val KEY_ALIAS = "lifeos.information.asset.v1"
        const val ASSET_PREFIX = "information-asset-"
        const val FILE_SUFFIX = ".iasset"
    }
}
