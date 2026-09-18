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
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One immutable encrypted file per generated tool; the legacy monolith is migration-only. */
class EncryptedGeneratedToolArtifactRepository(context: Context) : GeneratedToolArtifactRepository {
    private val directory = context.filesDir.resolve("generated-tool-artifact-vault")
    private val recordsDirectory = directory.resolve("records")
    private val legacyTarget = AtomicFile(directory.resolve(VAULT_FILE_NAME))
    private val key: SecretKey by lazy { loadOrCreateKey() }

    override suspend fun persist(artifact: GeneratedToolArtifact) = ioLocked {
        ensureMigrated()
        val target = targetFor(artifact.toolId)
        if (exists(target)) {
            val existing = readArtifact(target)
            require(existing == artifact) { "Generated-tool artifact for ${artifact.toolId} is immutable" }
            return@ioLocked
        }
        writeArtifact(target, artifact)
    }

    override suspend fun load(toolId: String): GeneratedToolArtifact? = ioLocked {
        require(toolId.isNotBlank()) { "Generated-tool artifact id must not be blank" }
        ensureMigrated()
        val target = targetFor(toolId)
        if (!exists(target)) null else readArtifact(target).also {
            require(it.toolId == toolId) { "Generated-tool artifact identity mismatch" }
        }
    }

    override suspend fun loadAll(): List<GeneratedToolArtifact> = ioLocked {
        ensureMigrated()
        recordFiles().map(::readArtifact).sortedBy { it.toolId }
    }

    private fun ensureMigrated() {
        ensureDirectory()
        if (recordFiles().isNotEmpty()) return
        val backup = directory.resolve("$VAULT_FILE_NAME.bak")
        if (!legacyTarget.baseFile.exists() && !backup.exists()) return
        readArtifacts(legacyTarget).forEach { artifact ->
            val target = targetFor(artifact.toolId)
            if (!exists(target)) writeArtifact(target, artifact)
        }
    }

    private fun readArtifact(target: AtomicFile): GeneratedToolArtifact {
        val values = readArtifacts(target)
        require(values.size == 1) { "Generated-tool artifact record must contain one artifact" }
        return values.single()
    }

    private fun readArtifacts(target: AtomicFile): List<GeneratedToolArtifact> {
        val container = target.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_CONTAINER_BYTES) {
                    "Generated-tool artifact file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return decrypt(container)
    }

    private fun writeArtifact(target: AtomicFile, artifact: GeneratedToolArtifact) {
        val container = encrypt(listOf(artifact))
        val stream = target.startWrite()
        try {
            stream.write(container)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    private fun encrypt(artifacts: List<GeneratedToolArtifact>): ByteArray {
        val plaintext = GeneratedToolArtifactCodec.encode(artifacts)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(plaintext)
        return ByteArrayOutputStream(encrypted.size + 64).also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(CONTAINER_VERSION)
                data.writeInt(GeneratedToolArtifactCodec.VERSION)
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.write(encrypted)
            }
        }.toByteArray().also { require(it.size <= MAX_CONTAINER_BYTES) }
    }

    private fun decrypt(container: ByteArray): List<GeneratedToolArtifact> {
        require(container.size <= MAX_CONTAINER_BYTES)
        return DataInputStream(ByteArrayInputStream(container)).use { data ->
            require(data.readInt() == CONTAINER_VERSION)
            require(data.readInt() == GeneratedToolArtifactCodec.VERSION)
            val ivSize = data.readInt()
            require(ivSize in 12..32)
            val iv = ByteArray(ivSize).also(data::readFully)
            val encrypted = data.readBytes()
            require(encrypted.isNotEmpty())
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            GeneratedToolArtifactCodec.decode(cipher.doFinal(encrypted))
        }
    }

    private fun targetFor(toolId: String): AtomicFile {
        require(toolId.isNotBlank())
        return AtomicFile(recordsDirectory.resolve(sha256(toolId) + RECORD_SUFFIX))
    }

    private fun recordFiles(): List<AtomicFile> {
        ensureDirectory()
        return recordsDirectory.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(RECORD_SUFFIX) }
            .sortedBy { it.name }
            .map(::AtomicFile)
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Generated-tool artifact vault unavailable" }
        check(recordsDirectory.isDirectory || recordsDirectory.mkdirs()) {
            "Generated-tool artifact record directory unavailable"
        }
    }

    private fun exists(target: AtomicFile): Boolean =
        target.baseFile.exists() || target.baseFile.resolveSibling("${target.baseFile.name}.bak").exists()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

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

    private suspend fun <T> ioLocked(block: () -> T): T = withContext(Dispatchers.IO) {
        processMutex.withLock { block() }
    }

    private companion object {
        val processMutex = Mutex()
        const val KEY_ALIAS = "lifeos.generated-tool-artifacts.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CONTAINER_VERSION = 1
        const val VAULT_FILE_NAME = "artifacts.tools"
        const val RECORD_SUFFIX = ".tool"
        const val MAX_PLAINTEXT_BYTES = 40 * 1024 * 1024
        const val MAX_CONTAINER_BYTES = MAX_PLAINTEXT_BYTES + 64 * 1024
    }
}
