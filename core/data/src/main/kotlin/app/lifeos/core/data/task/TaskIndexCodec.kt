package app.lifeos.core.data.task

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskCodec
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskIndexReport
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class TaskIndexEntry(
    val id: TaskId,
    val type: TaskType,
    val state: TaskState,
    val priority: TaskPriority,
    val idempotencyKey: String,
    val createdAt: Instant,
    val scheduledAt: Instant?,
    val leaseExpiresAt: Instant?,
    val contentFingerprint: String,
) {
    init {
        require(idempotencyKey.isNotBlank()) { "Task index idempotency key must not be blank" }
        require(contentFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid task index content fingerprint"
        }
    }

    companion object {
        fun from(task: LifeTask): TaskIndexEntry = TaskIndexEntry(
            id = task.id,
            type = task.type,
            state = task.state,
            priority = task.priority,
            idempotencyKey = task.idempotencyKey,
            createdAt = task.createdAt,
            scheduledAt = task.scheduledAt,
            leaseExpiresAt = task.leaseExpiresAt,
            contentFingerprint = sha256(TaskCodec.encode(task)),
        )

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

internal class TaskIndexSnapshot(
    val formatVersion: Int = FORMAT_VERSION,
    entries: List<TaskIndexEntry> = emptyList(),
) {
    val entries: List<TaskIndexEntry> = entries.sortedBy { it.id.value }

    private val byId: Map<TaskId, TaskIndexEntry> = this.entries.associateBy { it.id }
    private val byIdempotencyKey: Map<String, TaskIndexEntry> =
        this.entries.associateBy { it.idempotencyKey }
    private val byState: Map<TaskState, List<TaskIndexEntry>> = this.entries.groupBy { it.state }
    private val activeByType: Map<TaskType, Int> = this.entries
        .asSequence()
        .filter { it.state !in TERMINAL_STATES }
        .groupingBy { it.type }
        .eachCount()

    init {
        require(formatVersion == FORMAT_VERSION) { "Unsupported task index format" }
        require(this.entries.size <= MAX_ENTRIES) { "Task index too large" }
        require(byId.size == this.entries.size) { "Duplicate task id in task index" }
        require(byIdempotencyKey.size == this.entries.size) {
            "Duplicate idempotency key in task index"
        }
    }

    fun containsId(id: TaskId): Boolean = id in byId

    fun byIdempotencyKey(key: String): TaskIndexEntry? = byIdempotencyKey[key]

    fun activeCount(types: Set<TaskType>): Int =
        types.sumOf { activeByType[it] ?: 0 }

    fun runnable(now: Instant, limit: Int): List<TaskIndexEntry> {
        require(limit > 0) { "Runnable task limit must be positive" }
        val queued = byState[TaskState.QUEUED].orEmpty()
        val retryReady = byState[TaskState.RETRY_WAIT].orEmpty().filter { entry ->
            entry.scheduledAt?.isAfter(now) != true
        }
        return (queued + retryReady)
            .sortedWith(
                compareByDescending<TaskIndexEntry> { it.priority.weight }
                    .thenBy { it.createdAt }
                    .thenBy { it.id.value }
            )
            .take(limit)
    }

    fun expiredLeases(now: Instant, limit: Int): List<TaskIndexEntry> {
        require(limit > 0) { "Expired lease limit must be positive" }
        return LEASED_STATES
            .asSequence()
            .flatMap { byState[it].orEmpty().asSequence() }
            .filter { it.leaseExpiresAt?.isAfter(now) == false }
            .sortedWith(
                compareBy<TaskIndexEntry> { it.leaseExpiresAt }
                    .thenBy { it.createdAt }
                    .thenBy { it.id.value }
            )
            .take(limit)
            .toList()
    }

    fun upsert(task: LifeTask): TaskIndexSnapshot {
        val next = TaskIndexEntry.from(task)
        val conflicting = byIdempotencyKey[next.idempotencyKey]
        require(conflicting == null || conflicting.id == next.id) {
            "Task idempotency key already belongs to ${conflicting?.id?.value}"
        }
        return TaskIndexSnapshot(
            entries = entries.filterNot { it.id == next.id } + next,
        )
    }

    fun report(unreadableEntries: List<String> = emptyList()): TaskIndexReport =
        TaskIndexReport(
            formatVersion = formatVersion,
            taskCount = entries.size,
            activeTaskCount = entries.count { it.state !in TERMINAL_STATES },
            idempotencyKeyCount = byIdempotencyKey.size,
            unreadableEntries = unreadableEntries.distinct().sorted(),
        )

    companion object {
        const val FORMAT_VERSION = 1
        const val MAX_ENTRIES = 250_000

        val TERMINAL_STATES = setOf(
            TaskState.COMPLETED,
            TaskState.SUPERSEDED,
            TaskState.FAILED,
            TaskState.CANCELLED,
        )
        val LEASED_STATES = setOf(
            TaskState.CLAIMED,
            TaskState.RUNNING,
            TaskState.CHECKPOINTED,
        )

        fun from(tasks: List<LifeTask>): TaskIndexSnapshot =
            TaskIndexSnapshot(entries = tasks.map(TaskIndexEntry::from))
    }
}

internal object TaskIndexCodec {
    const val MAX_PLAINTEXT_BYTES = 64 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 1024 * 1024

    fun encode(snapshot: TaskIndexSnapshot): ByteArray {
        val bytes = ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(snapshot.formatVersion)
                stream.writeInt(snapshot.entries.size)
                snapshot.entries.forEach { entry ->
                    stream.writeText(entry.id.value)
                    stream.writeInt(entry.type.ordinal)
                    stream.writeInt(entry.state.ordinal)
                    stream.writeInt(entry.priority.ordinal)
                    stream.writeText(entry.idempotencyKey)
                    stream.writeInstant(entry.createdAt)
                    stream.writeOptionalInstant(entry.scheduledAt)
                    stream.writeOptionalInstant(entry.leaseExpiresAt)
                    stream.writeText(entry.contentFingerprint)
                }
            }
            output.toByteArray()
        }
        require(bytes.isNotEmpty() && bytes.size <= MAX_PLAINTEXT_BYTES) {
            "Invalid task index payload size"
        }
        return bytes
    }

    fun decode(bytes: ByteArray): TaskIndexSnapshot {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PLAINTEXT_BYTES) {
            "Invalid task index payload size"
        }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            require(version == TaskIndexSnapshot.FORMAT_VERSION) {
                "Unsupported task index version"
            }
            val count = input.readInt()
            require(count in 0..TaskIndexSnapshot.MAX_ENTRIES) {
                "Invalid task index entry count"
            }
            val entries = ArrayList<TaskIndexEntry>(count)
            repeat(count) {
                entries += TaskIndexEntry(
                    id = TaskId(input.readText()),
                    type = input.readEnum(TaskType.values(), "task type"),
                    state = input.readEnum(TaskState.values(), "task state"),
                    priority = input.readEnum(TaskPriority.values(), "task priority"),
                    idempotencyKey = input.readText(),
                    createdAt = input.readInstant(),
                    scheduledAt = input.readOptionalInstant(),
                    leaseExpiresAt = input.readOptionalInstant(),
                    contentFingerprint = input.readText(),
                )
            }
            require(input.available() == 0) { "Trailing task index bytes" }
            TaskIndexSnapshot(formatVersion = version, entries = entries)
        }
    }

    private fun DataOutputStream.writeText(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size in 1..MAX_TEXT_BYTES) { "Task index text exceeds size limit" }
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        require(size in 1..MAX_TEXT_BYTES && size <= available()) {
            "Invalid task index text length"
        }
        val encoded = ByteArray(size).also(::readFully)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
    }

    private fun DataOutputStream.writeInstant(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataInputStream.readInstant(): Instant {
        val seconds = readLong()
        val nanos = readInt()
        require(nanos in 0..999_999_999) { "Invalid task index instant nanos" }
        return Instant.ofEpochSecond(seconds, nanos.toLong())
    }

    private fun DataOutputStream.writeOptionalInstant(value: Instant?) {
        writeBoolean(value != null)
        if (value != null) writeInstant(value)
    }

    private fun DataInputStream.readOptionalInstant(): Instant? =
        if (readBoolean()) readInstant() else null

    private fun <T> DataInputStream.readEnum(values: Array<T>, label: String): T {
        val ordinal = readInt()
        require(ordinal in values.indices) { "Invalid $label ordinal" }
        return values[ordinal]
    }
}

internal object TaskIndexVaultCodec {
    private const val VERSION = 1
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val MAX_CONTAINER_BYTES = TaskIndexCodec.MAX_PLAINTEXT_BYTES + 64 * 1024

    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        require(plaintext.isNotEmpty() && plaintext.size <= TaskIndexCodec.MAX_PLAINTEXT_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
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
            require(it.size <= MAX_CONTAINER_BYTES) { "Encrypted task index too large" }
        }
    }

    fun decrypt(container: ByteArray, key: SecretKey): ByteArray {
        require(container.isNotEmpty() && container.size <= MAX_CONTAINER_BYTES) {
            "Invalid task index container size"
        }
        val input = DataInputStream(ByteArrayInputStream(container))
        require(input.readInt() == VERSION) { "Unsupported task index vault version" }
        val ivLength = input.readInt()
        require(ivLength in 12..32) { "Invalid task index IV length" }
        val iv = ByteArray(ivLength).also(input::readFully)
        val ciphertextLength = input.readInt()
        require(
            ciphertextLength in 1..MAX_CONTAINER_BYTES &&
                ciphertextLength == input.available()
        ) {
            "Malformed task index ciphertext length"
        }
        val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(ciphertext).also {
            require(it.isNotEmpty() && it.size <= TaskIndexCodec.MAX_PLAINTEXT_BYTES) {
                "Invalid decrypted task index size"
            }
        }
    }
}
