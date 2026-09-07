package app.lifeos.core.data.checkpoint

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.model.checkpoint.CheckpointCodec
import app.lifeos.core.model.checkpoint.CheckpointId
import app.lifeos.core.model.checkpoint.CheckpointRepository
import app.lifeos.core.model.checkpoint.SaveCheckpointResult
import app.lifeos.core.model.checkpoint.TaskCheckpoint
import app.lifeos.core.model.task.TaskId
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

/**
 * File-backed encrypted checkpoint storage for durable LIFEOS task recovery.
 *
 * Checkpoint writes are serialized inside the app process. A task/sequence pair
 * identifies one logical checkpoint; duplicate saves resolve to the existing
 * checkpoint instead of creating competing recovery anchors.
 */
class EncryptedCheckpointRepository(context: Context) : CheckpointRepository {
    private val directory = context.filesDir.resolve("checkpoint-vault")
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val mutex = Mutex()

    override suspend fun save(checkpoint: TaskCheckpoint): SaveCheckpointResult = ioLocked {
        val report = loadReportInternal()
        requireReadableVault(report)

        report.checkpoints.firstOrNull { it.id == checkpoint.id }?.let { existing ->
            return@ioLocked SaveCheckpointResult.Existing(existing)
        }

        report.checkpoints.firstOrNull {
            it.taskId == checkpoint.taskId && it.sequence == checkpoint.sequence
        }?.let { existing ->
            return@ioLocked SaveCheckpointResult.Existing(existing)
        }

        writeCheckpointInternal(checkpoint)
        SaveCheckpointResult.Created(checkpoint)
    }

    override suspend fun get(id: CheckpointId): TaskCheckpoint? = ioLocked {
        ensureDirectory()
        val file = checkpointFile(id)
        val backup = directory.resolve("${file.name}.bak")
        if (!file.exists() && !backup.exists()) return@ioLocked null
        readCheckpointInternal(file.name)
    }

    override suspend fun latest(taskId: TaskId): TaskCheckpoint? = ioLocked {
        val report = loadReportInternal()
        requireReadableVault(report)
        report.checkpoints
            .asSequence()
            .filter { it.taskId == taskId }
            .maxWithOrNull(compareBy<TaskCheckpoint> { it.sequence }.thenBy { it.createdAt })
    }

    override suspend fun list(taskId: TaskId, limit: Int): List<TaskCheckpoint> = ioLocked {
        require(limit > 0) { "Checkpoint list limit must be positive" }
        val report = loadReportInternal()
        requireReadableVault(report)
        report.checkpoints
            .asSequence()
            .filter { it.taskId == taskId }
            .sortedWith(
                compareByDescending<TaskCheckpoint> { it.sequence }
                    .thenByDescending { it.createdAt }
            )
            .take(limit)
            .toList()
    }

    override suspend fun delete(id: CheckpointId): Boolean = ioLocked {
        ensureDirectory()
        val file = checkpointFile(id)
        val backup = directory.resolve("${file.name}.bak")
        val existed = file.exists() || backup.exists()
        if (existed) AtomicFile(file).delete()
        existed
    }

    private suspend fun <T> ioLocked(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private fun loadReportInternal(): VaultReport {
        ensureDirectory()
        val files = directory.listFiles() ?: throw IOException("Checkpoint vault cannot be listed")
        val names = files
            .map { it.name.removeSuffix(".bak") }
            .filter { it.endsWith(CHECKPOINT_SUFFIX) }
            .distinct()
            .sorted()

        val checkpoints = mutableListOf<TaskCheckpoint>()
        val unreadable = mutableListOf<String>()
        for (name in names) {
            try {
                checkpoints += readCheckpointInternal(name)
            } catch (error: Exception) {
                unreadable += name
            }
        }
        return VaultReport(checkpoints, unreadable)
    }

    private fun requireReadableVault(report: VaultReport) {
        check(report.unreadableFiles.isEmpty()) {
            "Checkpoint vault contains unreadable checkpoints: ${report.unreadableFiles.size}"
        }
    }

    private fun readCheckpointInternal(name: String): TaskCheckpoint {
        require(name.endsWith(CHECKPOINT_SUFFIX)) { "Invalid checkpoint file name" }
        val bytes = AtomicFile(directory.resolve(name)).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_FILE_BYTES) { "Checkpoint file too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

        val checkpoint = decrypt(bytes)
        require(name == "${safeId(checkpoint.id)}$CHECKPOINT_SUFFIX") {
            "Checkpoint identity mismatch"
        }
        return checkpoint
    }

    private fun writeCheckpointInternal(checkpoint: TaskCheckpoint) {
        ensureDirectory()
        val cleartext = CheckpointCodec.encode(checkpoint)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(cleartext)

        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(CONTAINER_VERSION)
            stream.writeInt(CheckpointCodec.VERSION)
            stream.writeInt(cipher.iv.size)
            stream.write(cipher.iv)
            stream.write(encrypted)
        }
        require(output.size() <= MAX_FILE_BYTES) { "Checkpoint file too large" }

        val target = AtomicFile(checkpointFile(checkpoint.id))
        val stream = target.startWrite()
        try {
            stream.write(output.toByteArray())
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    private fun decrypt(container: ByteArray): TaskCheckpoint {
        require(container.size <= MAX_FILE_BYTES) { "Checkpoint file too large" }
        return DataInputStream(ByteArrayInputStream(container)).use { input ->
            val containerVersion = input.readInt()
            require(containerVersion == CONTAINER_VERSION) { "Unsupported checkpoint container" }

            val codecVersion = input.readInt()
            require(codecVersion == CheckpointCodec.VERSION) { "Unsupported checkpoint codec" }

            val ivSize = input.readInt()
            require(ivSize in 12..32) { "Invalid checkpoint IV length" }
            val iv = ByteArray(ivSize).also(input::readFully)
            val encrypted = input.readBytes()
            require(encrypted.isNotEmpty()) { "Missing checkpoint ciphertext" }

            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            CheckpointCodec.decode(cipher.doFinal(encrypted), codecVersion)
        }
    }

    private fun checkpointFile(id: CheckpointId) =
        directory.resolve("${safeId(id)}$CHECKPOINT_SUFFIX")

    private fun safeId(id: CheckpointId): String = id.value.also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid checkpoint ID" }
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Checkpoint vault unavailable" }
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

    private data class VaultReport(
        val checkpoints: List<TaskCheckpoint>,
        val unreadableFiles: List<String>,
    )

    private companion object {
        const val KEY_ALIAS = "lifeos.checkpoint.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CONTAINER_VERSION = 1
        const val CHECKPOINT_SUFFIX = ".checkpoint"
        const val MAX_FILE_BYTES = TaskCheckpoint.MAX_PAYLOAD_BYTES + 4096
    }
}
