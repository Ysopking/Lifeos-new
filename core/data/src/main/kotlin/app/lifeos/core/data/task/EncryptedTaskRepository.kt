package app.lifeos.core.data.task

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.model.task.CreateTaskResult
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskCodec
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskStateMachine
import app.lifeos.core.model.worker.WorkerId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * File-backed encrypted TaskRepository for the private LIFEOS process.
 *
 * State-changing operations are serialized by a process-local Mutex. Worker-owned
 * execution transitions use explicit owner/lease CAS methods instead of the generic
 * transition path.
 */
class EncryptedTaskRepository(context: Context) : TaskRepository {
    private val directory = context.filesDir.resolve("task-vault")
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val mutex = Mutex()

    override suspend fun create(task: LifeTask): CreateTaskResult = ioLocked {
        require(task.state == TaskState.CREATED) { "New repository task must be CREATED" }
        val report = loadReportInternal()
        requireReadableVault(report)

        report.tasks.firstOrNull { it.idempotencyKey == task.idempotencyKey }?.let {
            return@ioLocked CreateTaskResult.Existing(it)
        }

        check(report.tasks.none { it.id == task.id }) { "Task ID already exists: ${task.id.value}" }
        writeTaskInternal(task)
        CreateTaskResult.Created(task)
    }

    override suspend fun get(id: TaskId): LifeTask? = ioLocked {
        readTaskIfPresentInternal(id)
    }

    override suspend fun findByIdempotencyKey(key: String): LifeTask? = ioLocked {
        require(key.isNotBlank()) { "Idempotency key must not be blank" }
        val report = loadReportInternal()
        requireReadableVault(report)
        report.tasks.firstOrNull { it.idempotencyKey == key }
    }

    override suspend fun listRunnable(now: Instant, limit: Int): List<LifeTask> = ioLocked {
        require(limit > 0) { "Runnable task limit must be positive" }
        val report = loadReportInternal()
        requireReadableVault(report)

        report.tasks
            .asSequence()
            .filter { task ->
                val scheduledAt = task.scheduledAt
                task.state == TaskState.QUEUED ||
                    (task.state == TaskState.RETRY_WAIT &&
                        (scheduledAt == null || !scheduledAt.isAfter(now)))
            }
            .sortedWith(
                compareByDescending<LifeTask> { it.priority.weight }
                    .thenBy { it.createdAt }
            )
            .take(limit)
            .toList()
    }

    override suspend fun listExpiredLeases(now: Instant, limit: Int): List<LifeTask> = ioLocked {
        require(limit > 0) { "Expired lease limit must be positive" }
        val report = loadReportInternal()
        requireReadableVault(report)

        report.tasks
            .asSequence()
            .filter { task ->
                task.state in LEASED_STATES && task.leaseExpiresAt?.isAfter(now) == false
            }
            .sortedWith(compareBy<LifeTask> { it.leaseExpiresAt }.thenBy { it.createdAt })
            .take(limit)
            .toList()
    }

    override suspend fun transition(
        id: TaskId,
        expected: TaskState,
        next: TaskState,
        at: Instant,
    ): LifeTask? = ioLocked {
        require(next != TaskState.CLAIMED) { "Use claim() for QUEUED -> CLAIMED" }
        require(expected !in LEASED_STATES) {
            "Use owner-safe execution operations for transitions from leased task states"
        }
        val current = readTaskIfPresentInternal(id) ?: return@ioLocked null
        if (current.state != expected) return@ioLocked null

        TaskStateMachine.requireTransition(expected, next)
        val updated = current.copy(
            state = next,
            updatedAt = at,
            claimedBy = null,
            leaseExpiresAt = null,
        )
        writeTaskInternal(updated)
        updated
    }

    override suspend fun claim(
        id: TaskId,
        workerId: WorkerId,
        acquiredAt: Instant,
        leaseUntil: Instant,
    ): LifeTask? = ioLocked {
        require(leaseUntil.isAfter(acquiredAt)) { "Task lease must expire after acquisition" }
        val current = readTaskIfPresentInternal(id) ?: return@ioLocked null
        if (current.state != TaskState.QUEUED) return@ioLocked null

        TaskStateMachine.requireTransition(TaskState.QUEUED, TaskState.CLAIMED)
        val claimed = current.copy(
            state = TaskState.CLAIMED,
            updatedAt = acquiredAt,
            claimedBy = workerId,
            leaseExpiresAt = leaseUntil,
        )
        writeTaskInternal(claimed)
        claimed
    }

    override suspend fun startExecution(
        id: TaskId,
        workerId: WorkerId,
        startedAt: Instant,
    ): LifeTask? = ioLocked {
        val current = readTaskIfPresentInternal(id) ?: return@ioLocked null
        if (
            current.state != TaskState.CLAIMED ||
            current.claimedBy != workerId ||
            current.leaseExpiresAt?.isAfter(startedAt) != true ||
            current.attempt >= current.maxAttempts
        ) {
            return@ioLocked null
        }

        TaskStateMachine.requireTransition(TaskState.CLAIMED, TaskState.RUNNING)
        val running = current.copy(
            state = TaskState.RUNNING,
            attempt = current.attempt + 1,
            updatedAt = startedAt,
        )
        writeTaskInternal(running)
        running
    }

    override suspend fun finishExecution(
        id: TaskId,
        workerId: WorkerId,
        finalState: TaskState,
        finishedAt: Instant,
    ): LifeTask? = ioLocked {
        require(finalState in EXECUTION_TERMINAL_STATES) {
            "Execution can finish only as COMPLETED, SUPERSEDED, or FAILED"
        }
        val current = readTaskIfPresentInternal(id) ?: return@ioLocked null
        if (
            current.state !in EXECUTION_STATES ||
            current.claimedBy != workerId ||
            current.leaseExpiresAt?.isAfter(finishedAt) != true
        ) {
            return@ioLocked null
        }

        TaskStateMachine.requireTransition(current.state, finalState)
        val finished = current.copy(
            state = finalState,
            updatedAt = finishedAt,
            scheduledAt = null,
            claimedBy = null,
            leaseExpiresAt = null,
        )
        writeTaskInternal(finished)
        finished
    }

    override suspend fun interruptExecution(
        id: TaskId,
        workerId: WorkerId,
        interruptedAt: Instant,
    ): LifeTask? = ioLocked {
        val current = readTaskIfPresentInternal(id) ?: return@ioLocked null
        if (current.state !in EXECUTION_STATES || current.claimedBy != workerId) {
            return@ioLocked null
        }

        TaskStateMachine.requireTransition(current.state, TaskState.INTERRUPTED)
        val interrupted = current.copy(
            state = TaskState.INTERRUPTED,
            updatedAt = interruptedAt,
            scheduledAt = null,
            claimedBy = null,
            leaseExpiresAt = null,
        )
        writeTaskInternal(interrupted)
        interrupted
    }

    override suspend fun scheduleRetry(
        id: TaskId,
        workerId: WorkerId,
        retryAt: Instant,
        scheduledAt: Instant,
    ): LifeTask? = ioLocked {
        require(!retryAt.isBefore(scheduledAt)) { "Retry time must not precede scheduling time" }
        val current = readTaskIfPresentInternal(id) ?: return@ioLocked null
        if (
            current.state !in EXECUTION_STATES ||
            current.claimedBy != workerId ||
            current.leaseExpiresAt?.isAfter(scheduledAt) != true ||
            current.attempt >= current.maxAttempts
        ) {
            return@ioLocked null
        }

        TaskStateMachine.requireTransition(current.state, TaskState.RETRY_WAIT)
        val retry = current.copy(
            state = TaskState.RETRY_WAIT,
            updatedAt = scheduledAt,
            scheduledAt = retryAt,
            claimedBy = null,
            leaseExpiresAt = null,
        )
        writeTaskInternal(retry)
        retry
    }

    override suspend fun renewLease(
        id: TaskId,
        workerId: WorkerId,
        renewedAt: Instant,
        leaseUntil: Instant,
    ): LifeTask? = ioLocked {
        require(leaseUntil.isAfter(renewedAt)) { "Renewed task lease must expire after renewal" }
        val current = readTaskIfPresentInternal(id) ?: return@ioLocked null
        if (
            current.state !in LEASED_STATES ||
            current.claimedBy != workerId ||
            current.leaseExpiresAt?.isAfter(renewedAt) != true
        ) {
            return@ioLocked null
        }

        val renewed = current.copy(
            updatedAt = renewedAt,
            leaseExpiresAt = leaseUntil,
        )
        writeTaskInternal(renewed)
        renewed
    }

    override suspend fun interruptExpiredLease(
        id: TaskId,
        expectedState: TaskState,
        expectedWorkerId: WorkerId,
        expectedLeaseExpiresAt: Instant,
        at: Instant,
    ): LifeTask? = ioLocked {
        require(expectedState in LEASED_STATES) { "Expected state must hold a worker lease" }
        val current = readTaskIfPresentInternal(id) ?: return@ioLocked null
        if (
            current.state != expectedState ||
            current.claimedBy != expectedWorkerId ||
            current.leaseExpiresAt != expectedLeaseExpiresAt ||
            current.leaseExpiresAt?.isAfter(at) != false
        ) {
            return@ioLocked null
        }

        TaskStateMachine.requireTransition(expectedState, TaskState.INTERRUPTED)
        val interrupted = current.copy(
            state = TaskState.INTERRUPTED,
            updatedAt = at,
            scheduledAt = null,
            claimedBy = null,
            leaseExpiresAt = null,
        )
        writeTaskInternal(interrupted)
        interrupted
    }

    private suspend fun <T> ioLocked(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private fun readTaskIfPresentInternal(id: TaskId): LifeTask? {
        ensureDirectory()
        val file = taskFile(id)
        val backup = directory.resolve("${file.name}.bak")
        if (!file.exists() && !backup.exists()) return null
        return readTaskInternal(file.name)
    }

    private fun loadReportInternal(): VaultReport {
        ensureDirectory()
        val files = directory.listFiles() ?: throw IOException("Task vault cannot be listed")
        val names = files
            .map { it.name.removeSuffix(".bak") }
            .filter { it.endsWith(TASK_SUFFIX) }
            .distinct()
            .sorted()

        val tasks = mutableListOf<LifeTask>()
        val unreadable = mutableListOf<String>()
        for (name in names) {
            try {
                tasks += readTaskInternal(name)
            } catch (error: Exception) {
                unreadable += name
            }
        }
        return VaultReport(tasks, unreadable)
    }

    private fun requireReadableVault(report: VaultReport) {
        check(report.unreadableFiles.isEmpty()) {
            "Task vault contains unreadable tasks: ${report.unreadableFiles.size}"
        }
    }

    private fun readTaskInternal(name: String): LifeTask {
        require(name.endsWith(TASK_SUFFIX)) { "Invalid task file name" }
        val bytes = AtomicFile(directory.resolve(name)).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_FILE_BYTES) { "Task file too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

        val task = decrypt(bytes)
        require(name == "${safeId(task.id)}$TASK_SUFFIX") { "Task identity mismatch" }
        return task
    }

    private fun writeTaskInternal(task: LifeTask) {
        ensureDirectory()
        val cleartext = TaskCodec.encode(task)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(cleartext)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(CONTAINER_VERSION)
            stream.writeInt(TaskCodec.VERSION)
            stream.writeInt(cipher.iv.size)
            stream.write(cipher.iv)
            stream.write(encrypted)
        }
        require(output.size() <= MAX_FILE_BYTES) { "Task file too large" }

        val target = AtomicFile(taskFile(task.id))
        val stream = target.startWrite()
        try {
            stream.write(output.toByteArray())
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    private fun decrypt(container: ByteArray): LifeTask {
        require(container.size <= MAX_FILE_BYTES) { "Task file too large" }
        return DataInputStream(ByteArrayInputStream(container)).use { input ->
            val containerVersion = input.readInt()
            require(containerVersion == CONTAINER_VERSION) { "Unsupported task container" }
            val codecVersion = input.readInt()
            require(codecVersion in 1..TaskCodec.VERSION) { "Unsupported task codec" }
            val ivSize = input.readInt()
            require(ivSize in 12..32) { "Invalid task IV length" }
            val iv = ByteArray(ivSize).also(input::readFully)
            val encrypted = input.readBytes()
            require(encrypted.isNotEmpty()) { "Missing task ciphertext" }

            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            TaskCodec.decode(cipher.doFinal(encrypted), codecVersion)
        }
    }

    private fun taskFile(id: TaskId) = directory.resolve("${safeId(id)}$TASK_SUFFIX")

    private fun safeId(id: TaskId): String = id.value.also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid task ID" }
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Task vault unavailable" }
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
        val tasks: List<LifeTask>,
        val unreadableFiles: List<String>,
    )

    private companion object {
        val LEASED_STATES = setOf(TaskState.CLAIMED, TaskState.RUNNING, TaskState.CHECKPOINTED)
        val EXECUTION_STATES = setOf(TaskState.RUNNING, TaskState.CHECKPOINTED)
        val EXECUTION_TERMINAL_STATES = setOf(
            TaskState.COMPLETED,
            TaskState.SUPERSEDED,
            TaskState.FAILED,
        )
        const val KEY_ALIAS = "lifeos.task.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CONTAINER_VERSION = 1
        const val TASK_SUFFIX = ".task"
        const val MAX_FILE_BYTES = 1024 * 1024 + 256
    }
}
