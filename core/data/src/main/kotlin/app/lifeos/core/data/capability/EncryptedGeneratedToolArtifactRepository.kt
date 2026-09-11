package app.lifeos.core.data.capability

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.capability.GeneratedToolArtifact
import app.lifeos.core.runtime.capability.GeneratedToolArtifactCodec
import app.lifeos.core.runtime.capability.GeneratedToolArtifactRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encrypted atomic source of truth for bounded generated-tool executable artifacts. */
class EncryptedGeneratedToolArtifactRepository(context: Context) : GeneratedToolArtifactRepository {
    private val directory = context.filesDir.resolve("generated-tool-artifact-vault")
    private val target = AtomicFile(directory.resolve(VAULT_FILE_NAME))
    private val key: SecretKey by lazy { loadOrCreateKey() }

    override suspend fun persist(artifact: GeneratedToolArtifact) = ioLocked {
        val artifacts = readArtifactsLocked()
        val existing = artifacts.firstOrNull { it.toolId == artifact.toolId }
        if (existing != null) {
            require(existing == artifact) {
                "Generated-tool artifact for ${artifact.toolId} is immutable"
            }
            return@ioLocked
        }
        writeArtifactsLocked((artifacts + artifact).sortedBy { it.toolId })
    }

    override suspend fun load(toolId: String): GeneratedToolArtifact? = ioLocked {
        require(toolId.isNotBlank()) { "Generated-tool artifact id must not be blank" }
        readArtifactsLocked().firstOrNull { it.toolId == toolId }
    }

    override suspend fun loadAll(): List<GeneratedToolArtifact> = ioLocked {
        readArtifactsLocked()
    }

    private suspend fun <T> ioLocked(block: () -> T): T = withContext(Dispatchers.IO) {
        processMutex.withLock { block() }
    }

    private fun readArtifactsLocked(): List<GeneratedToolArtifact> {
        ensureDirectory()
        val backup = directory.resolve("$VAULT_FILE_NAME.bak")
        if (!target.baseFile.exists() && !backup.exists()) return emptyList()

        val container = target.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_CONTAINER_BYTES) {
                    "Generated-tool artifact vault file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return decrypt(container)
    }

    private fun writeArtifactsLocked(artifacts: List<GeneratedToolArtifact>) {
        ensureDirectory()
        val plaintext = GeneratedToolArtifactCodec.encode(artifacts)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) {
            "Generated-tool artifact vault payload too large"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(plaintext)
        val container = ByteArrayOutputStream(encrypted.size + 64).also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(CONTAINER_VERSION)
                data.writeInt(GeneratedToolArtifactCodec.VERSION)
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.write(encrypted)
            }
        }.toByteArray()
        require(container.size <= MAX_CONTAINER_BYTES) {
            "Generated-tool artifact vault container too large"
        }

        val stream = target.startWrite()
        try {
            stream.write(container)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    private fun decrypt(container: ByteArray): List<GeneratedToolArtifact> {
        require(container.size <= MAX_CONTAINER_BYTES) { "Generated-tool artifact vault file too large" }
        return DataInputStream(ByteArrayInputStream(container)).use { data ->
            require(data.readInt() == CONTAINER_VERSION) {
                "Unsupported generated-tool artifact container"
            }
            require(data.readInt() == GeneratedToolArtifactCodec.VERSION) {
                "Unsupported generated-tool artifact codec"
            }
            val ivSize = data.readInt()
            require(ivSize in 12..32) { "Invalid generated-tool artifact IV length" }
            val iv = ByteArray(ivSize).also(data::readFully)
            val encrypted = data.readBytes()
            require(encrypted.isNotEmpty()) { "Missing generated-tool artifact ciphertext" }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            GeneratedToolArtifactCodec.decode(cipher.doFinal(encrypted))
        }
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Generated-tool artifact vault unavailable"
        }
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
        val processMutex = Mutex()
        const val KEY_ALIAS = "lifeos.generated-tool-artifacts.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CONTAINER_VERSION = 1
        const val VAULT_FILE_NAME = "artifacts.tools"
        const val MAX_PLAINTEXT_BYTES = 40 * 1024 * 1024
        const val MAX_CONTAINER_BYTES = MAX_PLAINTEXT_BYTES + 64 * 1024
    }
}
