package app.lifeos.core.data.snapshot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.CognitiveSnapshot
import app.lifeos.core.runtime.CognitiveSnapshotCodec
import app.lifeos.core.runtime.CognitiveSnapshotRepository
import app.lifeos.core.runtime.SnapshotManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedCognitiveSnapshotRepository(
    context: Context,
    private val codec: CognitiveSnapshotCodec = CognitiveSnapshotVaultCodec,
) : CognitiveSnapshotRepository {
    private val file = AtomicFile(context.filesDir.resolve(FILE_NAME))
    private val key: SecretKey by lazy(::loadOrCreateKey)
    private val mutex = Mutex()

    override suspend fun loadAll(): List<Pair<CognitiveSnapshot, SnapshotManifest>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val base = file.baseFile
                val backup = base.resolveSibling("${base.name}.bak")
                if (!base.exists() && !backup.exists()) return@withLock emptyList()
                decodeContainer(readBounded())
            }
        }

    override suspend fun save(
        snapshot: CognitiveSnapshot,
        manifest: SnapshotManifest,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = runCatching { decodeContainer(readBounded()) }.getOrElse { emptyList() }
            val deduped = current.filterNot {
                it.first.eventSequence == snapshot.eventSequence &&
                    it.first.worldRevision == snapshot.worldRevision
            }
            val retained = (deduped + (snapshot to manifest))
                .sortedWith(
                    compareBy<Pair<CognitiveSnapshot, SnapshotManifest>>(
                        { it.first.eventSequence },
                        { it.first.worldRevision },
                    )
                )
                .takeLast(MAX_SNAPSHOTS)
            writeContainer(retained)
        }
    }

    private fun decodeContainer(
        container: ByteArray,
    ): List<Pair<CognitiveSnapshot, SnapshotManifest>> {
        val plaintext = decrypt(container)
        return DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
            require(input.readInt() == FORMAT_VERSION) { "Unsupported cognitive snapshot vault" }
            val count = input.readInt()
            require(count in 0..MAX_SNAPSHOTS)
            val values = ArrayList<Pair<CognitiveSnapshot, SnapshotManifest>>(count)
            repeat(count) {
                val size = input.readInt()
                require(size in 1..MAX_ENTRY_BYTES && size <= input.available())
                values += codec.decode(ByteArray(size).also(input::readFully))
            }
            require(input.available() == 0) { "Trailing cognitive snapshot vault bytes" }
            values
        }
    }

    private fun writeContainer(
        values: List<Pair<CognitiveSnapshot, SnapshotManifest>>,
    ) {
        val plaintext = ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(FORMAT_VERSION)
                stream.writeInt(values.size)
                values.forEach { (snapshot, manifest) ->
                    val encoded = codec.encode(snapshot, manifest)
                    require(encoded.size <= MAX_ENTRY_BYTES)
                    stream.writeInt(encoded.size)
                    stream.write(encoded)
                }
            }
            output.toByteArray()
        }
        require(plaintext.size <= MAX_PLAINTEXT_BYTES)
        val encrypted = encrypt(plaintext)
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun readBounded(): ByteArray = file.openRead().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_CONTAINER_BYTES) {
                "Cognitive snapshot vault too large"
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(CONTAINER_VERSION)
                stream.writeInt(cipher.iv.size)
                stream.write(cipher.iv)
                stream.writeInt(ciphertext.size)
                stream.write(ciphertext)
            }
            output.toByteArray()
        }.also { require(it.size <= MAX_CONTAINER_BYTES) }
    }

    private fun decrypt(container: ByteArray): ByteArray =
        DataInputStream(ByteArrayInputStream(container)).use { input ->
            require(input.readInt() == CONTAINER_VERSION)
            val ivSize = input.readInt()
            require(ivSize in 12..32)
            val iv = ByteArray(ivSize).also(input::readFully)
            val size = input.readInt()
            require(size in 1..MAX_CONTAINER_BYTES && size == input.available())
            val encrypted = ByteArray(size).also(input::readFully)
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                doFinal(encrypted)
            }.also { require(it.size <= MAX_PLAINTEXT_BYTES) }
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
        const val FILE_NAME = "cognitive-snapshots.v1"
        const val KEY_ALIAS = "lifeos.cognitive.snapshot.v1"
        const val FORMAT_VERSION = 1
        const val CONTAINER_VERSION = 1
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_SNAPSHOTS = 8
        const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
        const val MAX_PLAINTEXT_BYTES = MAX_SNAPSHOTS * MAX_ENTRY_BYTES + 4096
        const val MAX_CONTAINER_BYTES = MAX_PLAINTEXT_BYTES + 64 * 1024
    }
}

internal object CognitiveSnapshotVaultCodec : CognitiveSnapshotCodec {
    private const val VERSION = 1
    private const val MAX_TEXT_BYTES = 64 * 1024
    private const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    override fun encode(
        snapshot: CognitiveSnapshot,
        manifest: SnapshotManifest,
    ): ByteArray = ByteArrayOutputStream().let { output ->
        DataOutputStream(output).use { stream ->
            stream.writeInt(VERSION)
            stream.writeInt(snapshot.schemaVersion)
            stream.writeInt(snapshot.projectionVersion)
            stream.writeLong(snapshot.worldRevision)
            stream.writeLong(snapshot.eventSequence)
            stream.writeText(snapshot.worldRoot)
            stream.writeText(snapshot.dependencyIndexFingerprint)
            stream.writeText(snapshot.memoryIndexFingerprint)
            require(snapshot.payload.size <= MAX_PAYLOAD_BYTES)
            stream.writeInt(snapshot.payload.size)
            stream.write(snapshot.payload)

            stream.writeInt(manifest.schemaVersion)
            stream.writeInt(manifest.projectionVersion)
            stream.writeLong(manifest.worldRevision)
            stream.writeLong(manifest.eventSequence)
            stream.writeText(manifest.worldRoot)
            stream.writeText(manifest.dependencyIndexFingerprint)
            stream.writeText(manifest.memoryIndexFingerprint)
            stream.writeText(manifest.payloadSha256)
        }
        output.toByteArray()
    }

    override fun decode(bytes: ByteArray): Pair<CognitiveSnapshot, SnapshotManifest> =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION)
            val snapshot = CognitiveSnapshot(
                schemaVersion = input.readInt(),
                projectionVersion = input.readInt(),
                worldRevision = input.readLong(),
                eventSequence = input.readLong(),
                worldRoot = input.readText(),
                dependencyIndexFingerprint = input.readText(),
                memoryIndexFingerprint = input.readText(),
                payload = ByteArray(input.readInt().also {
                    require(it in 0..MAX_PAYLOAD_BYTES && it <= input.available())
                }).also(input::readFully),
            )
            val manifest = SnapshotManifest(
                schemaVersion = input.readInt(),
                projectionVersion = input.readInt(),
                worldRevision = input.readLong(),
                eventSequence = input.readLong(),
                worldRoot = input.readText(),
                dependencyIndexFingerprint = input.readText(),
                memoryIndexFingerprint = input.readText(),
                payloadSha256 = input.readText(),
            )
            require(input.available() == 0)
            snapshot to manifest
        }

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_TEXT_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        require(size in 1..MAX_TEXT_BYTES && size <= available())
        val bytes = ByteArray(size).also(::readFully)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
}
