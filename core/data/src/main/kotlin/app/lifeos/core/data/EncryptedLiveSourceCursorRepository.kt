package app.lifeos.core.data

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedLiveSourceCursorRepository(
    context: Context,
) : LiveSourceCursorRepository {
    private val root = context.filesDir.resolve(ROOT_DIRECTORY)
    private val records = root.resolve(RECORDS_DIRECTORY)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun load(
        sourceId: LiveSourceId,
    ): LiveSourceCursorLoadResult = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            val file = recordFile(sourceId)
            if (!exists(file)) return@withLock LiveSourceCursorLoadResult.Missing
            try {
                LiveSourceCursorLoadResult.Loaded(readState(file, sourceId))
            } catch (error: Exception) {
                LiveSourceCursorLoadResult.Unreadable(
                    error.message ?: error::class.simpleName ?: "Live source cursor unreadable"
                )
            }
        }
    }

    override suspend fun compareAndSet(
        sourceId: LiveSourceId,
        expectedRevision: Long?,
        next: LiveSourceCursorState,
    ): LiveSourceCursorWriteResult = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            require(next.sourceId == sourceId) {
                "Live source cursor payload belongs to another source"
            }
            val file = recordFile(sourceId)
            val current = if (!exists(file)) {
                LiveSourceCursorLoadResult.Missing
            } else {
                try {
                    LiveSourceCursorLoadResult.Loaded(readState(file, sourceId))
                } catch (error: Exception) {
                    LiveSourceCursorLoadResult.Unreadable(
                        error.message ?: error::class.simpleName ?: "Live source cursor unreadable"
                    )
                }
            }

            when (current) {
                LiveSourceCursorLoadResult.Missing -> {
                    if (expectedRevision != null) {
                        return@withLock LiveSourceCursorWriteResult.Conflict(null)
                    }
                    require(next.revision == 1L) {
                        "First live source cursor revision must be 1"
                    }
                }
                is LiveSourceCursorLoadResult.Loaded -> {
                    val state = current.state
                    if (state.revision != expectedRevision) {
                        return@withLock LiveSourceCursorWriteResult.Conflict(state.revision)
                    }
                    require(next.revision == Math.addExact(state.revision, 1L)) {
                        "Live source cursor revision must advance exactly once"
                    }
                    require(next.sourceId == state.sourceId)
                    require(next.connectorIdentityFingerprint == state.connectorIdentityFingerprint) {
                        "Live source connector identity cannot mutate inside one cursor record"
                    }
                    require(next.lastObservationRevision >= state.lastObservationRevision) {
                        "Live source observation revision cannot move backwards"
                    }
                    require(!state.bootstrapped || next.bootstrapped) {
                        "Live source cursor cannot become unbootstrapped"
                    }
                }
                is LiveSourceCursorLoadResult.Unreadable -> {
                    return@withLock LiveSourceCursorWriteResult.UnreadableExisting(current.message)
                }
            }

            writeState(file, next)
            val persisted = readState(file, sourceId)
            require(persisted == next) {
                "Live source cursor write did not round-trip exactly"
            }
            LiveSourceCursorWriteResult.Saved(persisted)
        }
    }

    private fun writeState(
        file: File,
        state: LiveSourceCursorState,
    ) {
        val plaintext = LiveSourceCursorStateCodec.encode(state)
        val encrypted = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = MAX_STATE_BYTES,
            associatedData = associatedData(file),
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, encrypted)
    }

    private fun readState(
        file: File,
        expectedSourceId: LiveSourceId,
    ): LiveSourceCursorState {
        require(file == recordFile(expectedSourceId)) {
            "Live source cursor physical path does not match requested source"
        }
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(file, MAX_STATE_BYTES),
            key = key,
            maxPlaintextBytes = MAX_STATE_BYTES,
            associatedData = associatedData(file),
        )
        val state = LiveSourceCursorStateCodec.decode(plaintext)
        require(state.sourceId == expectedSourceId) {
            "Live source cursor payload source does not match physical record path"
        }
        require(file == recordFile(state.sourceId)) {
            "Live source cursor payload/path binding mismatch"
        }
        return state
    }

    private fun recordFile(sourceId: LiveSourceId): File =
        records.resolve(sha256(sourceId.value) + RECORD_SUFFIX)

    private fun associatedData(file: File): ByteArray {
        require(file.parentFile == records)
        return (ROOT_DIRECTORY + "/" + RECORDS_DIRECTORY + "/" + file.name)
            .toByteArray(Charsets.UTF_8)
    }

    private fun ensureDirectories() {
        check(root.isDirectory || root.mkdirs()) {
            "Live source cursor vault unavailable"
        }
        check(records.isDirectory || records.mkdirs()) {
            "Live source cursor records directory unavailable"
        }
    }

    private fun exists(file: File): Boolean =
        file.exists() || File(file.path + ".bak").exists()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val ROOT_DIRECTORY = "live-source-cursor-vault"
        const val RECORDS_DIRECTORY = "records"
        const val RECORD_SUFFIX = ".lscursor"
        const val KEY_ALIAS = "lifeos.live.source.cursor.v1"
        const val MAX_STATE_BYTES = 64 * 1024
        val processMutex = Mutex()
    }
}

internal object LiveSourceCursorStateCodec {
    private const val MAGIC = 0x4C534352
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 16 * 1024
    const val MAX_PAYLOAD_BYTES = 64 * 1024

    fun encode(state: LiveSourceCursorState): ByteArray =
        ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeLong(state.revision)
                writeString(data, state.sourceId.value)
                writeString(data, state.connectorIdentityFingerprint)
                writeNullableString(data, state.cursor?.value)
                data.writeBoolean(state.bootstrapped)
                writeNullableString(data, state.baselineFingerprint)
                data.writeLong(state.lastObservationRevision)
                writeString(data, state.updatedAt.toString())
            }
            output.toByteArray()
        }.also {
            require(it.isNotEmpty() && it.size <= MAX_PAYLOAD_BYTES)
        }

    fun decode(bytes: ByteArray): LiveSourceCursorState {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid live source cursor magic" }
        require(input.readInt() == VERSION) { "Unsupported live source cursor version" }
        val state = LiveSourceCursorState(
            revision = input.readLong(),
            sourceId = LiveSourceId(readString(input)),
            connectorIdentityFingerprint = readString(input),
            cursor = readNullableString(input)?.let(::SourceCursor),
            bootstrapped = input.readBoolean(),
            baselineFingerprint = readNullableString(input),
            lastObservationRevision = input.readLong(),
            updatedAt = Instant.parse(readString(input)),
        )
        require(input.available() == 0) { "Trailing live source cursor payload bytes" }
        return state
    }

    private fun writeNullableString(output: DataOutputStream, value: String?) {
        output.writeBoolean(value != null)
        if (value != null) writeString(output, value)
    }

    private fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Live source cursor string too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..MAX_STRING_BYTES && size <= input.available()) {
            "Invalid live source cursor string length"
        }
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }
}
