package app.lifeos.core.data

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedLiveSourceCursorRepository(context: Context) : LiveSourceCursorRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val file = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy { EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS) }

    override suspend fun load(sourceId: LiveSourceId): LiveSourceCursorState? = ioLocked {
        readAllLocked()[sourceId]
    }

    override suspend fun compareAndSet(
        expectedStateRevision: Long,
        state: LiveSourceCursorState,
    ): Boolean = ioLocked {
        require(expectedStateRevision >= 0L)
        val all = readAllLocked().toMutableMap()
        val current = all[state.sourceId]
        val currentRevision = current?.stateRevision ?: 0L
        if (currentRevision != expectedStateRevision) return@ioLocked false
        require(state.stateRevision == expectedStateRevision + 1L) {
            "LiveSource cursor state revision must advance exactly once"
        }
        if (current != null) {
            require(state.lastObservationRevision >= current.lastObservationRevision)
            require(state.lastObservedAtEpochMillis >= current.lastObservedAtEpochMillis)
        }
        all[state.sourceId] = state
        writeAllLocked(all)
        true
    }

    private fun readAllLocked(): Map<LiveSourceId, LiveSourceCursorState> {
        ensureDirectory()
        if (!exists(file)) return emptyMap()
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(file, MAX_PLAINTEXT_BYTES),
            key = key,
            maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
        )
        return DataInputStream(ByteArrayInputStream(plaintext)).use { data ->
            require(data.readInt() == VERSION) { "Unsupported LiveSource cursor store version" }
            val count = data.readInt()
            require(count in 0..MAX_SOURCES)
            buildMap {
                repeat(count) {
                    val sourceId = LiveSourceId(data.readText())
                    val state = LiveSourceCursorState(
                        sourceId = sourceId,
                        stateRevision = data.readLong(),
                        cursor = SourceCursor(data.readText()),
                        lastObservationRevision = data.readLong(),
                        lastObservedAtEpochMillis = data.readLong(),
                        inventoryFingerprint = data.readText(),
                    )
                    require(put(sourceId, state) == null) { "Duplicate LiveSource cursor id" }
                }
                require(data.available() == 0) { "Trailing LiveSource cursor store bytes" }
            }
        }
    }

    private fun writeAllLocked(states: Map<LiveSourceId, LiveSourceCursorState>) {
        require(states.size <= MAX_SOURCES)
        ensureDirectory()
        val plaintext = ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(VERSION)
                data.writeInt(states.size)
                states.entries.sortedBy { it.key.value }.forEach { (sourceId, state) ->
                    data.writeText(sourceId.value)
                    data.writeLong(state.stateRevision)
                    data.writeText(state.cursor.value)
                    data.writeLong(state.lastObservationRevision)
                    data.writeLong(state.lastObservedAtEpochMillis)
                    data.writeText(state.inventoryFingerprint)
                }
            }
            output.toByteArray()
        }
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, container)
    }

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_TEXT_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val length = readInt()
        require(length in 1..MAX_TEXT_BYTES)
        return ByteArray(length).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "LiveSource cursor directory unavailable" }
    }

    private fun exists(target: File): Boolean =
        target.exists() || File("${target.path}.bak").exists()

    private suspend fun <T> ioLocked(block: () -> T): T = withContext(Dispatchers.IO) {
        processMutex.withLock { block() }
    }

    private companion object {
        const val ROOT_DIRECTORY = "live-source-state"
        const val FILE_NAME = "source-cursors.v1"
        const val KEY_ALIAS = "lifeos.live.source.cursors.v1"
        const val VERSION = 1
        const val MAX_SOURCES = 1_024
        const val MAX_TEXT_BYTES = 16 * 1024
        const val MAX_PLAINTEXT_BYTES = 2 * 1024 * 1024
        val processMutex = Mutex()
    }
}
