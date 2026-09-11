package app.lifeos.core.data.thought

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.field.FieldThoughtGraphProjectionCodec
import app.lifeos.core.runtime.field.FieldThoughtGraphProjectionEnvelope
import app.lifeos.core.runtime.field.FieldThoughtGraphProjectionId
import app.lifeos.core.runtime.field.FieldThoughtGraphProjectionLoadReport
import app.lifeos.core.runtime.field.FieldThoughtGraphProjectionOutboxRepository
import app.lifeos.core.runtime.field.FieldThoughtGraphProjectionWriteResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encrypted immutable outbox for crash-safe FieldSnapshot -> ThoughtGraph projection. */
class EncryptedFieldThoughtGraphProjectionOutboxRepository(
    context: Context,
) : FieldThoughtGraphProjectionOutboxRepository {
    private val directory = context.filesDir.resolve("field-thought-graph-projection-outbox")
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val mutex = Mutex()

    override suspend fun save(
        envelope: FieldThoughtGraphProjectionEnvelope,
    ): FieldThoughtGraphProjectionWriteResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val name = fileName(envelope.id)
            val base = directory.resolve(name)
            val backup = directory.resolve("$name.bak")
            if (base.exists() || backup.exists()) {
                val existing = readEnvelopeInternal(name)
                require(existing == envelope) { "Field thought-graph projection identity collision" }
                return@withLock FieldThoughtGraphProjectionWriteResult.Duplicate(existing)
            }

            val plaintext = FieldThoughtGraphProjectionCodec.encode(envelope)
            val encrypted = ProjectionVaultCodec.encrypt(plaintext, key)
            val target = AtomicFile(base)
            val stream = target.startWrite()
            try {
                stream.write(encrypted)
                target.finishWrite(stream)
            } catch (error: Exception) {
                target.failWrite(stream)
                throw error
            }
            FieldThoughtGraphProjectionWriteResult.Stored(envelope)
        }
    }

    override suspend fun load(
        id: FieldThoughtGraphProjectionId,
    ): FieldThoughtGraphProjectionEnvelope? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val name = fileName(id)
            val base = directory.resolve(name)
            val backup = directory.resolve("$name.bak")
            if (!base.exists() && !backup.exists()) return@withLock null
            readEnvelopeInternal(name).also { envelope ->
                require(envelope.id == id) { "Field thought-graph projection identity mismatch" }
            }
        }
    }

    override suspend fun loadReport(): FieldThoughtGraphProjectionLoadReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val files = directory.listFiles()
                ?: throw IOException("Field thought-graph projection outbox cannot be listed")
            val names = files
                .map { it.name.removeSuffix(".bak") }
                .filter { it.endsWith(FILE_SUFFIX) }
                .distinct()
                .sorted()
            val envelopes = mutableListOf<FieldThoughtGraphProjectionEnvelope>()
            val failures = mutableListOf<String>()
            names.forEach { name ->
                try {
                    envelopes += readEnvelopeInternal(name)
                } catch (_: Exception) {
                    failures += name
                }
            }
            FieldThoughtGraphProjectionLoadReport(
                envelopes = envelopes.distinctBy { it.id }.sortedBy { it.id.value },
                unreadableEntries = failures.distinct().sorted(),
            )
        }
    }

    private fun readEnvelopeInternal(name: String): FieldThoughtGraphProjectionEnvelope {
        require(name.endsWith(FILE_SUFFIX)) { "Invalid field thought-graph projection file name" }
        val container = AtomicFile(directory.resolve(name)).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= ProjectionVaultCodec.MAX_CONTAINER_BYTES) {
                    "Field thought-graph projection file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val plaintext = ProjectionVaultCodec.decrypt(container, key)
        val envelope = FieldThoughtGraphProjectionCodec.decode(plaintext)
        require(fileName(envelope.id) == name) {
            "Field thought-graph projection file/content identity mismatch"
        }
        return envelope
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Field thought-graph projection outbox unavailable"
        }
    }

    private fun fileName(id: FieldThoughtGraphProjectionId): String {
        val value = id.value
        require(value.startsWith(ID_PREFIX)) { "Invalid field thought-graph projection id prefix" }
        val digest = value.removePrefix(ID_PREFIX)
        require(digest.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid field thought-graph projection id digest"
        }
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

    private object ProjectionVaultCodec {
        private const val VERSION = 1
        const val MAX_CONTAINER_BYTES = FieldThoughtGraphProjectionCodec.MAX_PAYLOAD_BYTES + 512
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
            require(plaintext.isNotEmpty() && plaintext.size <= FieldThoughtGraphProjectionCodec.MAX_PAYLOAD_BYTES) {
                "Invalid field thought-graph projection plaintext size"
            }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
            val ciphertext = cipher.doFinal(plaintext)
            return ByteArrayOutputStream().let { output ->
                DataOutputStream(output).use { stream ->
                    stream.writeInt(VERSION)
                    stream.writeInt(cipher.iv.size)
                    stream.write(cipher.iv)
                    stream.writeInt(ciphertext.size)
                    stream.write(ciphertext)
                }
                output.toByteArray()
            }.also {
                require(it.size <= MAX_CONTAINER_BYTES) { "Encrypted field projection too large" }
            }
        }

        fun decrypt(container: ByteArray, key: SecretKey): ByteArray {
            require(container.isNotEmpty() && container.size <= MAX_CONTAINER_BYTES) {
                "Invalid field thought-graph projection container size"
            }
            val input = DataInputStream(ByteArrayInputStream(container))
            require(input.readInt() == VERSION) { "Unsupported field projection vault version" }
            val ivLength = input.readInt()
            require(ivLength in 12..32) { "Invalid field projection IV length" }
            val iv = ByteArray(ivLength).also(input::readFully)
            val ciphertextLength = input.readInt()
            require(ciphertextLength in 1..MAX_CONTAINER_BYTES && ciphertextLength == input.available()) {
                "Malformed field projection ciphertext length"
            }
            val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            return cipher.doFinal(ciphertext).also {
                require(it.isNotEmpty() && it.size <= FieldThoughtGraphProjectionCodec.MAX_PAYLOAD_BYTES) {
                    "Invalid decrypted field projection size"
                }
            }
        }
    }

    private companion object {
        const val KEY_ALIAS = "lifeos.field.thought.graph.projection.v1"
        const val ID_PREFIX = "field-thought-graph-projection:"
        const val FILE_SUFFIX = ".fgprojection"
    }
}
