package app.lifeos.core.data.cognition

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.runtime.cognition.CognitionCoverageRepository
import app.lifeos.core.runtime.cognition.CognitionCoverageSnapshot
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

class EncryptedCognitionCoverageRepository(
    context: Context,
) : CognitionCoverageRepository {
    private val file = AtomicFile(context.filesDir.resolve(FILE_NAME))
    private val key: SecretKey by lazy(::loadOrCreateKey)
    private val mutex = Mutex()

    @Volatile
    private var cacheInitialized = false

    @Volatile
    private var cached: CognitionCoverageSnapshot? = null

    override suspend fun load(): CognitionCoverageSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (cacheInitialized) return@withLock cached
            val base = file.baseFile
            val backup = base.resolveSibling("${base.name}.bak")
            if (!base.exists() && !backup.exists()) {
                cacheInitialized = true
                return@withLock null
            }
            val container = file.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_CONTAINER_BYTES) {
                        "Cognition coverage file too large"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val decoded = decode(decrypt(container))
            cached = decoded
            cacheInitialized = true
            decoded
        }
    }

    override suspend fun save(snapshot: CognitionCoverageSnapshot) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val encrypted = encrypt(encode(snapshot))
            val stream = file.startWrite()
            try {
                stream.write(encrypted)
                file.finishWrite(stream)
                cached = snapshot
                cacheInitialized = true
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
    }

    private fun encode(snapshot: CognitionCoverageSnapshot): ByteArray =
        ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(snapshot.formatVersion)
                val refs = snapshot.canonical()
                stream.writeInt(refs.size)
                refs.forEach { ref ->
                    stream.writeText(ref.photonId.value)
                    stream.writeLong(ref.revision)
                }
            }
            output.toByteArray()
        }.also {
            require(it.isNotEmpty() && it.size <= MAX_PLAINTEXT_BYTES)
        }

    private fun decode(bytes: ByteArray): CognitionCoverageSnapshot =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == CognitionCoverageSnapshot.FORMAT_VERSION)
            val count = input.readInt()
            require(count in 0..CognitionCoverageSnapshot.MAX_ENTRIES)
            val refs = LinkedHashSet<PhotonRevisionRef>(count)
            repeat(count) {
                val ref = PhotonRevisionRef(
                    photonId = PhotonId(input.readText()),
                    revision = input.readLong(),
                )
                require(refs.add(ref)) { "Duplicate cognition coverage ref" }
            }
            require(input.available() == 0) { "Trailing cognition coverage bytes" }
            CognitionCoverageSnapshot(covered = refs)
        }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(VAULT_VERSION)
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
            require(input.readInt() == VAULT_VERSION)
            val ivSize = input.readInt()
            require(ivSize in 12..32)
            val iv = ByteArray(ivSize).also(input::readFully)
            val cipherSize = input.readInt()
            require(cipherSize in 1..MAX_CONTAINER_BYTES && cipherSize == input.available())
            val ciphertext = ByteArray(cipherSize).also(input::readFully)
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                doFinal(ciphertext)
            }.also { require(it.isNotEmpty() && it.size <= MAX_PLAINTEXT_BYTES) }
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
        const val FILE_NAME = "cognition-coverage.v1"
        const val KEY_ALIAS = "lifeos.cognition.coverage.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VAULT_VERSION = 1
        const val MAX_TEXT_BYTES = 16 * 1024
        const val MAX_PLAINTEXT_BYTES = 64 * 1024 * 1024
        const val MAX_CONTAINER_BYTES = MAX_PLAINTEXT_BYTES + 64 * 1024
    }
}
