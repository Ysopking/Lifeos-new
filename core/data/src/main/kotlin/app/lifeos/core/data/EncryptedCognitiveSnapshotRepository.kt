package app.lifeos.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.CognitiveSnapshot
import app.lifeos.core.runtime.CognitiveSnapshotRepository
import app.lifeos.core.runtime.SnapshotManifest
import app.lifeos.core.runtime.SnapshotVerifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encrypted bounded snapshot retention. Events remain authoritative history; snapshots only accelerate recovery. */
class EncryptedCognitiveSnapshotRepository(
    context: Context,
    private val verifier: SnapshotVerifier = SnapshotVerifier(),
) : CognitiveSnapshotRepository {
    private val directory = context.filesDir.resolve(DIRECTORY_NAME)
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val mutex = Mutex()

    override suspend fun save(snapshot: CognitiveSnapshot): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(directory.exists() || directory.mkdirs()) { "Unable to create cognitive snapshot directory" }
            val manifest = verifier.manifest(snapshot)
            writeSnapshot(snapshotFile(snapshot), snapshot, manifest)
            prune()
        }
    }

    override suspend fun candidates(limit: Int): List<Pair<CognitiveSnapshot, SnapshotManifest>> = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(limit > 0)
            if (!directory.exists()) return@withLock emptyList()
            snapshotFiles()
                .asSequence()
                .mapNotNull { file -> runCatching { readSnapshot(file) }.getOrNull() }
                .filter { (snapshot, manifest) -> verifier.verify(snapshot, manifest) }
                .take(limit)
                .toList()
        }
    }

    private fun snapshotFiles(): List<File> {
        val normalized = linkedSetOf<String>()
        directory.listFiles()?.forEach { file ->
            when {
                file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX) -> normalized += file.name
                file.name.startsWith(FILE_PREFIX) && file.name.endsWith("$FILE_SUFFIX.bak") -> normalized += file.name.removeSuffix(".bak")
            }
        }
        return normalized
            .map(directory::resolve)
            .sortedWith(compareByDescending<File> { snapshotSequence(it) }.thenByDescending { snapshotRevision(it) })
    }

    private fun snapshotFile(snapshot: CognitiveSnapshot): File = directory.resolve(
        "$FILE_PREFIX${snapshot.eventSequence.toString().padStart(20, '0')}-${snapshot.worldRevision.toString().padStart(20, '0')}$FILE_SUFFIX",
    )

    private fun snapshotSequence(file: File): Long = parseName(file).first
    private fun snapshotRevision(file: File): Long = parseName(file).second

    private fun parseName(file: File): Pair<Long, Long> {
        require(file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)) { "Invalid cognitive snapshot filename" }
        val parts = file.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).split('-')
        require(parts.size == 2) { "Invalid cognitive snapshot filename" }
        return parts[0].toLong() to parts[1].toLong()
    }

    private fun writeSnapshot(file: File, snapshot: CognitiveSnapshot, manifest: SnapshotManifest) {
        require(snapshot.payload.size <= MAX_PAYLOAD_BYTES) { "Cognitive snapshot payload too large" }
        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).use { out ->
            out.writeInt(REPOSITORY_SCHEMA_VERSION)
            out.writeInt(snapshot.schemaVersion)
            out.writeLong(snapshot.worldRevision)
            out.writeLong(snapshot.eventSequence)
            out.writeUTF(snapshot.worldRoot)
            out.writeInt(snapshot.payload.size)
            out.write(snapshot.payload)
            out.writeInt(manifest.schemaVersion)
            out.writeLong(manifest.worldRevision)
            out.writeLong(manifest.eventSequence)
            out.writeUTF(manifest.worldRoot)
            out.writeUTF(manifest.payloadSha256)
        }
        writeEncrypted(AtomicFile(file), plain.toByteArray())
    }

    private fun readSnapshot(file: File): Pair<CognitiveSnapshot, SnapshotManifest> {
        val plain = decrypt(AtomicFile(file))
        val input = DataInputStream(ByteArrayInputStream(plain))
        require(input.readInt() == REPOSITORY_SCHEMA_VERSION) { "Unsupported cognitive snapshot schema" }
        val snapshotSchema = input.readInt()
        val worldRevision = input.readLong()
        val eventSequence = input.readLong()
        val worldRoot = input.readUTF()
        val payloadSize = input.readInt().also { require(it in 0..MAX_PAYLOAD_BYTES) }
        val payload = ByteArray(payloadSize).also(input::readFully)
        val snapshot = CognitiveSnapshot(snapshotSchema, worldRevision, eventSequence, worldRoot, payload)
        val manifest = SnapshotManifest(
            schemaVersion = input.readInt(),
            worldRevision = input.readLong(),
            eventSequence = input.readLong(),
            worldRoot = input.readUTF(),
            payloadSha256 = input.readUTF(),
        )
        require(input.available() == 0) { "Trailing cognitive snapshot data" }
        require(snapshot.eventSequence == snapshotSequence(file) && snapshot.worldRevision == snapshotRevision(file)) {
            "Cognitive snapshot filename/content mismatch"
        }
        return snapshot to manifest
    }

    private fun writeEncrypted(file: AtomicFile, plain: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(plain)
        val container = ByteArrayOutputStream()
        DataOutputStream(container).use { out ->
            out.writeInt(CONTAINER_VERSION)
            out.writeInt(cipher.iv.size)
            out.write(cipher.iv)
            out.write(encrypted)
        }
        require(container.size() <= MAX_CONTAINER_BYTES) { "Cognitive snapshot container too large" }
        val stream = file.startWrite()
        try {
            stream.write(container.toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun decrypt(file: AtomicFile): ByteArray {
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size <= MAX_CONTAINER_BYTES) { "Cognitive snapshot container too large" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == CONTAINER_VERSION) { "Unsupported cognitive snapshot container" }
        val iv = ByteArray(input.readInt().also { require(it in 12..32) })
        input.readFully(iv)
        val encrypted = input.readBytes()
        require(encrypted.isNotEmpty()) { "Missing cognitive snapshot ciphertext" }
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            doFinal(encrypted)
        }
    }

    private fun prune() {
        snapshotFiles().drop(MAX_RETAINED_SNAPSHOTS).forEach { AtomicFile(it).delete() }
    }

    private fun loadOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "cognitive-snapshots.v1"
        const val FILE_PREFIX = "snapshot-"
        const val FILE_SUFFIX = ".bin"
        const val KEY_ALIAS = "lifeos.cognitive.snapshots.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CONTAINER_VERSION = 1
        const val REPOSITORY_SCHEMA_VERSION = 1
        const val MAX_RETAINED_SNAPSHOTS = 8
        const val MAX_PAYLOAD_BYTES = 32 * 1024 * 1024
        const val MAX_CONTAINER_BYTES = MAX_PAYLOAD_BYTES + 1024 * 1024
    }
}
