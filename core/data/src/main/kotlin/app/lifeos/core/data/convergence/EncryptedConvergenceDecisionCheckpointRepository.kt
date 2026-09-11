package app.lifeos.core.data.convergence

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpoint
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointCodec
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointId
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointLoadReport
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointRepository
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointWriteResult
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

/** AES-GCM/Android-Keystore immutable V5 decision checkpoint vault. */
class EncryptedConvergenceDecisionCheckpointRepository(
    context: Context,
) : ConvergenceDecisionCheckpointRepository {
    private val directory = context.filesDir.resolve("convergence-decision-checkpoints")
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val mutex = Mutex()

    override suspend fun save(
        checkpoint: ConvergenceDecisionCheckpoint,
    ): ConvergenceDecisionCheckpointWriteResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val name = fileName(checkpoint.id)
            val base = directory.resolve(name)
            val backup = directory.resolve("$name.bak")
            if (base.exists() || backup.exists()) {
                val existing = readInternal(name)
                require(existing == checkpoint) { "Convergence checkpoint identity collision" }
                return@withLock ConvergenceDecisionCheckpointWriteResult.Duplicate(existing)
            }
            val plaintext = ConvergenceDecisionCheckpointCodec.encode(checkpoint)
            val encrypted = VaultCodec.encrypt(plaintext, key)
            val target = AtomicFile(base)
            val stream = target.startWrite()
            try {
                stream.write(encrypted)
                target.finishWrite(stream)
            } catch (error: Exception) {
                target.failWrite(stream)
                throw error
            }
            ConvergenceDecisionCheckpointWriteResult.Stored(checkpoint)
        }
    }

    override suspend fun load(
        id: ConvergenceDecisionCheckpointId,
    ): ConvergenceDecisionCheckpoint? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val name = fileName(id)
            val base = directory.resolve(name)
            val backup = directory.resolve("$name.bak")
            if (!base.exists() && !backup.exists()) return@withLock null
            readInternal(name).also { require(it.id == id) { "Convergence checkpoint id mismatch" } }
        }
    }

    override suspend fun loadReport(): ConvergenceDecisionCheckpointLoadReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val files = directory.listFiles()
                ?: throw IOException("Convergence checkpoint vault cannot be listed")
            val names = files
                .map { it.name.removeSuffix(".bak") }
                .filter { it.endsWith(FILE_SUFFIX) }
                .distinct()
                .sorted()
            val checkpoints = mutableListOf<ConvergenceDecisionCheckpoint>()
            val failures = mutableListOf<String>()
            names.forEach { name ->
                try {
                    checkpoints += readInternal(name)
                } catch (_: Exception) {
                    failures += name
                }
            }
            val byId = linkedMapOf<ConvergenceDecisionCheckpointId, ConvergenceDecisionCheckpoint>()
            checkpoints.sortedBy { it.id.value }.forEach { checkpoint ->
                val previous = byId.putIfAbsent(checkpoint.id, checkpoint)
                require(previous == null || previous == checkpoint) {
                    "Convergence checkpoint history contains identity collision"
                }
            }
            ConvergenceDecisionCheckpointLoadReport(
                checkpoints = byId.values.toList(),
                unreadableEntries = failures.distinct().sorted(),
            )
        }
    }

    private fun readInternal(name: String): ConvergenceDecisionCheckpoint {
        require(name.endsWith(FILE_SUFFIX)) { "Invalid convergence checkpoint file name" }
        val container = AtomicFile(directory.resolve(name)).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= VaultCodec.MAX_CONTAINER_BYTES) {
                    "Convergence checkpoint file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val plaintext = VaultCodec.decrypt(container, key)
        val checkpoint = ConvergenceDecisionCheckpointCodec.decode(plaintext)
        require(fileName(checkpoint.id) == name) {
            "Convergence checkpoint filename/content identity mismatch"
        }
        return checkpoint
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Convergence checkpoint vault unavailable" }
    }

    private fun fileName(id: ConvergenceDecisionCheckpointId): String =
        "${id.value.removePrefix(ConvergenceDecisionCheckpointId.PREFIX)}$FILE_SUFFIX"

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

    private object VaultCodec {
        private const val VERSION = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_CONTAINER_BYTES = ConvergenceDecisionCheckpointCodec.MAX_PAYLOAD_BYTES + 512

        fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
            require(plaintext.isNotEmpty() && plaintext.size <= ConvergenceDecisionCheckpointCodec.MAX_PAYLOAD_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
            val ciphertext = cipher.doFinal(plaintext)
            return ByteArrayOutputStream().let { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(VERSION)
                    data.writeInt(cipher.iv.size)
                    data.write(cipher.iv)
                    data.writeInt(ciphertext.size)
                    data.write(ciphertext)
                }
                output.toByteArray()
            }.also { require(it.size <= MAX_CONTAINER_BYTES) }
        }

        fun decrypt(container: ByteArray, key: SecretKey): ByteArray {
            require(container.isNotEmpty() && container.size <= MAX_CONTAINER_BYTES)
            val input = DataInputStream(ByteArrayInputStream(container))
            require(input.readInt() == VERSION) { "Unsupported convergence checkpoint vault version" }
            val ivLength = input.readInt()
            require(ivLength in 12..32) { "Invalid convergence checkpoint IV length" }
            val iv = ByteArray(ivLength).also(input::readFully)
            val ciphertextLength = input.readInt()
            require(ciphertextLength in 1..MAX_CONTAINER_BYTES && ciphertextLength == input.available()) {
                "Malformed convergence checkpoint ciphertext length"
            }
            val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            return cipher.doFinal(ciphertext).also {
                require(it.isNotEmpty() && it.size <= ConvergenceDecisionCheckpointCodec.MAX_PAYLOAD_BYTES)
            }
        }
    }

    private companion object {
        const val KEY_ALIAS = "lifeos.convergence.decision.checkpoint.v1"
        const val FILE_SUFFIX = ".cv5checkpoint"
    }
}
