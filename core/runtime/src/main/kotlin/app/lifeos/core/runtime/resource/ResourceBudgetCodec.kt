package app.lifeos.core.runtime.resource

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

/** Deterministic bounded codec for one durable V16 resource-budget account. */
object ResourceBudgetAccountCodec {
    private const val MAGIC = 0x52424131 // RBA1
    private const val VERSION = 1
    private const val MAX_RESERVATIONS = 20_000
    private const val MAX_STRING_BYTES = 32 * 1024
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun encode(account: ResourceBudgetAccount): ByteArray = ByteArrayOutputStream().let { output ->
        DataOutputStream(output).use { stream ->
            stream.writeInt(MAGIC)
            stream.writeInt(VERSION)
            writeString(stream, account.id.value)
            stream.writeLong(account.revision)
            writeQuota(stream, account.quota)
            writeUsage(stream, account.consumed)
            require(account.reservations.size <= MAX_RESERVATIONS) { "Too many resource budget reservations" }
            stream.writeInt(account.reservations.size)
            account.reservations.forEach { writeReservation(stream, it) }
        }
        output.toByteArray()
    }.also { require(it.size <= MAX_PAYLOAD_BYTES) { "Resource budget payload too large" } }

    fun decode(bytes: ByteArray): ResourceBudgetAccount {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES) { "Invalid resource budget payload size" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid resource budget payload magic" }
        require(input.readInt() == VERSION) { "Unsupported resource budget payload version" }
        val id = ResourceBudgetAccountId(readString(input))
        val revision = input.readLong()
        val quota = readQuota(input)
        val consumed = readUsage(input)
        val count = input.readInt()
        require(count in 0..MAX_RESERVATIONS) { "Invalid resource budget reservation count" }
        val reservations = List(count) { readReservation(input) }
        require(input.available() == 0) { "Trailing resource budget payload bytes" }
        return ResourceBudgetAccount(
            id = id,
            revision = revision,
            quota = quota,
            consumed = consumed,
            reservations = reservations,
        )
    }

    private fun writeReservation(output: DataOutputStream, reservation: ResourceBudgetReservation) {
        writeString(output, reservation.id.value)
        writeString(output, reservation.accountId.value)
        writeString(output, reservation.idempotencyKey)
        writeUsage(output, reservation.reserved)
        writeString(output, reservation.createdAt.toString())
        writeString(output, reservation.state.name)
        output.writeBoolean(reservation.settledUsage != null)
        reservation.settledUsage?.let { writeUsage(output, it) }
        output.writeBoolean(reservation.settledAt != null)
        reservation.settledAt?.let { writeString(output, it.toString()) }
    }

    private fun readReservation(input: DataInputStream): ResourceBudgetReservation = ResourceBudgetReservation(
        id = ResourceBudgetReservationId(readString(input)),
        accountId = ResourceBudgetAccountId(readString(input)),
        idempotencyKey = readString(input),
        reserved = readUsage(input),
        createdAt = Instant.parse(readString(input)),
        state = enumValueOf<ResourceBudgetReservationState>(readString(input)),
        settledUsage = if (input.readBoolean()) readUsage(input) else null,
        settledAt = if (input.readBoolean()) Instant.parse(readString(input)) else null,
    )

    private fun writeQuota(output: DataOutputStream, quota: ResourceBudgetQuota) {
        output.writeLong(quota.elapsedMillis)
        output.writeLong(quota.workUnits)
        output.writeLong(quota.memoryBytes)
        output.writeLong(quota.ioBytes)
        output.writeLong(quota.networkBytes)
        output.writeLong(quota.candidates)
    }

    private fun readQuota(input: DataInputStream): ResourceBudgetQuota = ResourceBudgetQuota(
        elapsedMillis = input.readLong(),
        workUnits = input.readLong(),
        memoryBytes = input.readLong(),
        ioBytes = input.readLong(),
        networkBytes = input.readLong(),
        candidates = input.readLong(),
    )

    private fun writeUsage(output: DataOutputStream, usage: ResourceBudgetUsage) {
        output.writeLong(usage.elapsedMillis)
        output.writeLong(usage.workUnits)
        output.writeLong(usage.memoryBytes)
        output.writeLong(usage.ioBytes)
        output.writeLong(usage.networkBytes)
        output.writeLong(usage.candidates)
    }

    private fun readUsage(input: DataInputStream): ResourceBudgetUsage = ResourceBudgetUsage(
        elapsedMillis = input.readLong(),
        workUnits = input.readLong(),
        memoryBytes = input.readLong(),
        ioBytes = input.readLong(),
        networkBytes = input.readLong(),
        candidates = input.readLong(),
    )

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Resource budget string too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available()) {
            "Invalid resource budget string length"
        }
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }
}
