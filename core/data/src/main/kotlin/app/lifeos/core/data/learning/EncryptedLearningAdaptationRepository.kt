package app.lifeos.core.data.learning

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.learning.LearningAdaptation
import app.lifeos.core.runtime.learning.LearningAdaptationCodec
import app.lifeos.core.runtime.learning.LearningAdaptationId
import app.lifeos.core.runtime.learning.LearningAdaptationLoadReport
import app.lifeos.core.runtime.learning.LearningAdaptationRepository
import app.lifeos.core.runtime.learning.LearningAdaptationWriteResult
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

/** AES-GCM/Android-Keystore append-only vault for V6 learning adaptations. */
class EncryptedLearningAdaptationRepository(
    context: Context,
) : LearningAdaptationRepository {
    private val directory = context.filesDir.resolve(DIRECTORY_NAME)
    private val key: SecretKey by lazy(::loadOrCreateKey)
    private val mutex = Mutex()

    override suspend fun save(event: LearningAdaptation): LearningAdaptationWriteResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectory()
                val name = fileName(event.id)
                val base = directory.resolve(name)
                val backup = directory.resolve("$name.bak")
                if (base.exists() || backup.exists()) {
                    val existing = readInternal(name)
                    require(existing == event) { "Learning adaptation identity collision" }
                    return@withLock LearningAdaptationWriteResult.Duplicate(existing)
                }

                val encrypted = AdaptationVaultCodec.encrypt(
                    LearningAdaptationCodec.encode(event),
                    key,
                )
                val target = AtomicFile(base)
                val stream = target.startWrite()
                try {
                    stream.write(encrypted)
                    target.finishWrite(stream)
                } catch (error: Exception) {
                    target.failWrite(stream)
                    throw error
                }
                LearningAdaptationWriteResult.Stored(event)
            }
        }

    override suspend fun load(id: LearningAdaptationId): LearningAdaptation? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectory()
                val name = fileName(id)
                val base = directory.resolve(name)
                val backup = directory.resolve("$name.bak")
                if (!base.exists() && !backup.exists()) return@withLock null
                readInternal(name).also { event ->
                    require(event.id == id) { "Learning adaptation identity mismatch" }
                }
            }
        }

    override suspend fun loadReport(): LearningAdaptationLoadReport =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectory()
                val files = directory.listFiles()
                    ?: throw IOException("Learning adaptation ledger cannot be listed")
                val names = files
                    .map { it.name.removeSuffix(".bak") }
                    .filter { it.endsWith(FILE_SUFFIX) }
                    .distinct()
                    .sorted()
                val events = mutableListOf<LearningAdaptation>()
                val failures = mutableListOf<String>()
                names.forEach { name ->
                    try {
                        events += readInternal(name)
                    } catch (_: Exception) {
                        failures += name
                    }
                }
                LearningAdaptationLoadReport(
                    events = events.distinctBy { it.id }.sortedBy { it.id.value },
                    unreadableEntries = failures.distinct().sorted(),
                )
            }
        }

    private fun readInternal(name: String): LearningAdaptation {
        require(name.endsWith(FILE_SUFFIX)) { "Invalid learning adaptation file name" }
        val container = AtomicFile(directory.resolve(name)).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= AdaptationVaultCodec.MAX_CONTAINER_BYTES) {
                    "Learning adaptation file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val event = LearningAdaptationCodec.decode(AdaptationVaultCodec.decrypt(container, key))
        require(fileName(event.id) == name) {
            "Learning adaptation file/content identity mismatch"
        }
        return event
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Learning adaptation ledger unavailable"
        }
    }

    private fun fileName(id: LearningAdaptationId): String {
        val digest = id.value.removePrefix(LearningAdaptationId.PREFIX)
        require(id.value.startsWith(LearningAdaptationId.PREFIX)) {
            "Invalid learning adaptation id prefix"
        }
        require(digest.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid learning adaptation id digest"
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

    private object AdaptationVaultCodec {
        private const val VERSION = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_CONTAINER_BYTES = LearningAdaptationCodec.MAX_PAYLOAD_BYTES + 512

        fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
            require(plaintext.isNotEmpty() && plaintext.size <= LearningAdaptationCodec.MAX_PAYLOAD_BYTES) {
                "Invalid learning adaptation plaintext size"
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
                require(it.size <= MAX_CONTAINER_BYTES) { "Encrypted learning adaptation too large" }
            }
        }

        fun decrypt(container: ByteArray, key: SecretKey): ByteArray {
            require(container.isNotEmpty() && container.size <= MAX_CONTAINER_BYTES) {
                "Invalid learning adaptation container size"
            }
            val input = DataInputStream(ByteArrayInputStream(container))
            require(input.readInt() == VERSION) { "Unsupported learning adaptation vault version" }
            val ivLength = input.readInt()
            require(ivLength in 12..32) { "Invalid learning adaptation IV length" }
            val iv = ByteArray(ivLength).also(input::readFully)
            val ciphertextLength = input.readInt()
            require(ciphertextLength in 1..MAX_CONTAINER_BYTES && ciphertextLength == input.available()) {
                "Malformed learning adaptation ciphertext length"
            }
            val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            return cipher.doFinal(ciphertext).also {
                require(it.isNotEmpty() && it.size <= LearningAdaptationCodec.MAX_PAYLOAD_BYTES) {
                    "Invalid decrypted learning adaptation size"
                }
            }
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "learning-adaptation-ledger"
        const val FILE_SUFFIX = ".ladapt"
        const val KEY_ALIAS = "lifeos.learning.adaptation.v1"
    }
}
