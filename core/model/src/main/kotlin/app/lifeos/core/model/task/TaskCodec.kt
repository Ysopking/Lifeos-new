package app.lifeos.core.model.task

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.worker.WorkerId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

object TaskCodec {
    const val VERSION = 1
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_ITEMS = 10_000

    fun encode(task: LifeTask): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.text(task.id.value)
            out.text(task.type.name)
            out.text(task.state.name)
            out.text(task.priority.name)

            out.count(task.inputPhotonIds.size)
            task.inputPhotonIds.sortedBy { it.value }.forEach { out.text(it.value) }

            out.text(task.idempotencyKey)
            out.writeInt(task.attempt)
            out.writeInt(task.maxAttempts)
            out.instant(task.createdAt)
            out.instant(task.updatedAt)
            out.optionalInstant(task.scheduledAt)
            out.optionalText(task.claimedBy?.value)
            out.optionalInstant(task.leaseExpiresAt)
        }
        require(bytes.size() <= MAX_BYTES) { "Task exceeds size limit" }
    }.toByteArray()

    fun decode(bytes: ByteArray, version: Int = VERSION): LifeTask {
        require(version == VERSION) { "Unsupported task format" }
        require(bytes.size <= MAX_BYTES) { "Task exceeds size limit" }

        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val task = LifeTask(
                id = TaskId(input.text()),
                type = TaskType.valueOf(input.text()),
                state = TaskState.valueOf(input.text()),
                priority = TaskPriority.valueOf(input.text()),
                inputPhotonIds = buildSet {
                    repeat(input.count()) { add(PhotonId(input.text())) }
                },
                idempotencyKey = input.text(),
                attempt = input.readInt(),
                maxAttempts = input.readInt(),
                createdAt = input.instant(),
                updatedAt = input.instant(),
                scheduledAt = input.optionalInstant(),
                claimedBy = input.optionalText()?.let(::WorkerId),
                leaseExpiresAt = input.optionalInstant(),
            )
            require(input.available() == 0) { "Trailing task data" }
            task
        }
    }

    private fun DataOutputStream.text(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_BYTES) { "Task text exceeds size limit" }
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.text(): String {
        val size = readInt()
        require(size in 0..MAX_BYTES && size <= available()) { "Invalid task text length" }
        val encoded = ByteArray(size).also { readFully(it) }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
    }

    private fun DataOutputStream.optionalText(value: String?) {
        writeBoolean(value != null)
        if (value != null) text(value)
    }

    private fun DataInputStream.optionalText(): String? = if (readBoolean()) text() else null

    private fun DataOutputStream.instant(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataInputStream.instant(): Instant {
        val seconds = readLong()
        val nanos = readInt()
        require(nanos in 0..999_999_999) { "Invalid task instant nanos" }
        return Instant.ofEpochSecond(seconds, nanos.toLong())
    }

    private fun DataOutputStream.optionalInstant(value: Instant?) {
        writeBoolean(value != null)
        if (value != null) instant(value)
    }

    private fun DataInputStream.optionalInstant(): Instant? = if (readBoolean()) instant() else null

    private fun DataOutputStream.count(size: Int) {
        require(size in 0..MAX_ITEMS) { "Too many task items" }
        writeInt(size)
    }

    private fun DataInputStream.count(): Int = readInt().also {
        require(it in 0..MAX_ITEMS) { "Invalid task item count" }
    }
}
