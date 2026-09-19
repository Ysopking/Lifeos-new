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

class EncryptedLiveSourceSnapshotRepository(
    context: Context,
) : LiveSourceSnapshotRepository {
    private val root = context.filesDir.resolve(ROOT_DIRECTORY)
    private val records = root.resolve(RECORDS_DIRECTORY)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun load(
        sourceId: LiveSourceId,
    ): LiveSourceSnapshotLoadResult = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            val file = recordFile(sourceId)
            if (!exists(file)) return@withLock LiveSourceSnapshotLoadResult.Missing
            try {
                LiveSourceSnapshotLoadResult.Loaded(readState(file, sourceId))
            } catch (error: Exception) {
                LiveSourceSnapshotLoadResult.Unreadable(
                    error.message ?: error::class.simpleName ?: "Live source snapshot unreadable"
                )
            }
        }
    }

    override suspend fun compareAndSet(
        sourceId: LiveSourceId,
        expectedRevision: Long?,
        next: LiveSourceSnapshotState,
    ): LiveSourceSnapshotWriteResult = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            require(next.sourceId == sourceId) {
                "Live source snapshot payload belongs to another source"
            }
            val file = recordFile(sourceId)
            val current = if (!exists(file)) {
                LiveSourceSnapshotLoadResult.Missing
            } else {
                try {
                    LiveSourceSnapshotLoadResult.Loaded(readState(file, sourceId))
                } catch (error: Exception) {
                    LiveSourceSnapshotLoadResult.Unreadable(
                        error.message ?: error::class.simpleName ?: "Live source snapshot unreadable"
                    )
                }
            }

            when (current) {
                LiveSourceSnapshotLoadResult.Missing -> {
                    if (expectedRevision != null) {
                        return@withLock LiveSourceSnapshotWriteResult.Conflict(null)
                    }
                    require(next.revision == 1L) {
                        "First live source snapshot revision must be 1"
                    }
                }
                is LiveSourceSnapshotLoadResult.Loaded -> {
                    val state = current.state
                    if (state.revision != expectedRevision) {
                        return@withLock LiveSourceSnapshotWriteResult.Conflict(state.revision)
                    }
                    require(next.revision == Math.addExact(state.revision, 1L)) {
                        "Live source snapshot revision must advance exactly once"
                    }
                    require(next.sourceId == state.sourceId)
                    require(next.connectorIdentityFingerprint == state.connectorIdentityFingerprint) {
                        "Live source connector identity cannot mutate inside one snapshot record"
                    }
                    require(next.lastObservationRevision >= state.lastObservationRevision) {
                        "Live source snapshot observation revision cannot move backwards"
                    }
                }
                is LiveSourceSnapshotLoadResult.Unreadable -> {
                    return@withLock LiveSourceSnapshotWriteResult.UnreadableExisting(current.message)
                }
            }

            writeState(file, next)
            val persisted = readState(file, sourceId)
            require(persisted == next) {
                "Live source snapshot write did not round-trip exactly"
            }
            LiveSourceSnapshotWriteResult.Saved(persisted)
        }
    }

    private fun writeState(
        file: File,
        state: LiveSourceSnapshotState,
    ) {
        val plaintext = LiveSourceSnapshotStateCodec.encode(state)
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
    ): LiveSourceSnapshotState {
        require(file == recordFile(expectedSourceId)) {
            "Live source snapshot physical path does not match requested source"
        }
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(file, MAX_STATE_BYTES),
            key = key,
            maxPlaintextBytes = MAX_STATE_BYTES,
            associatedData = associatedData(file),
        )
        val state = LiveSourceSnapshotStateCodec.decode(plaintext)
        require(state.sourceId == expectedSourceId) {
            "Live source snapshot payload source does not match physical record path"
        }
        require(file == recordFile(state.sourceId)) {
            "Live source snapshot payload/path binding mismatch"
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
            "Live source snapshot vault unavailable"
        }
        check(records.isDirectory || records.mkdirs()) {
            "Live source snapshot records directory unavailable"
        }
    }

    private fun exists(file: File): Boolean =
        file.exists() || File(file.path + ".bak").exists()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val ROOT_DIRECTORY = "live-source-snapshot-vault"
        const val RECORDS_DIRECTORY = "records"
        const val RECORD_SUFFIX = ".lssnapshot"
        const val KEY_ALIAS = "lifeos.live.source.snapshot.v1"
        const val MAX_STATE_BYTES = 2 * 1024 * 1024
        val processMutex = Mutex()
    }
}

internal object LiveSourceSnapshotStateCodec {
    private const val MAGIC = 0x4C535350
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 16 * 1024
    private const val MAX_ITEMS = 16_384
    const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024

    fun encode(state: LiveSourceSnapshotState): ByteArray =
        ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeLong(state.revision)
                writeString(data, state.sourceId.value)
                writeString(data, state.connectorIdentityFingerprint)
                writeString(data, state.inventoryFingerprint)
                data.writeLong(state.lastObservationRevision)
                writeString(data, state.updatedAt.toString())
                data.writeInt(state.items.size)
                state.items.forEach { item ->
                    writeString(data, item.externalKey)
                    writeNullableString(data, item.fingerprint)
                    writeString(data, item.privacyZone.name)
                }
            }
            output.toByteArray()
        }.also {
            require(it.isNotEmpty() && it.size <= MAX_PAYLOAD_BYTES)
        }

    fun decode(bytes: ByteArray): LiveSourceSnapshotState {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid live source snapshot magic" }
        require(input.readInt() == VERSION) { "Unsupported live source snapshot version" }
        val revision = input.readLong()
        val sourceId = LiveSourceId(readString(input))
        val connectorIdentityFingerprint = readString(input)
        val inventoryFingerprint = readString(input)
        val lastObservationRevision = input.readLong()
        val updatedAt = Instant.parse(readString(input))
        val itemCount = input.readInt()
        require(itemCount in 0..MAX_ITEMS) { "Invalid live source snapshot item count" }
        val items = buildList(itemCount) {
            repeat(itemCount) {
                val externalKey = readString(input)
                val fingerprint = readNullableString(input)
                val privacyZone = SourcePrivacyZone.valueOf(readString(input))
                add(SourceInventoryItem(externalKey, fingerprint, privacyZone))
            }
        }
        require(input.available() == 0) { "Trailing live source snapshot payload bytes" }
        return LiveSourceSnapshotState(
            revision = revision,
            sourceId = sourceId,
            connectorIdentityFingerprint = connectorIdentityFingerprint,
            inventoryFingerprint = inventoryFingerprint,
            items = items,
            lastObservationRevision = lastObservationRevision,
            updatedAt = updatedAt,
        )
    }

    private fun writeNullableString(output: DataOutputStream, value: String?) {
        output.writeBoolean(value != null)
        if (value != null) writeString(output, value)
    }

    private fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Live source snapshot string too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..MAX_STRING_BYTES && size <= input.available()) {
            "Invalid live source snapshot string length"
        }
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }
}
