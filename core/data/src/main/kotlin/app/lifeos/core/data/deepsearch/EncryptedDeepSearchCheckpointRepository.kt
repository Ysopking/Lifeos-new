package app.lifeos.core.data.deepsearch

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.data.security.VaultAssociatedData
import app.lifeos.core.runtime.deepsearch.DeepSearchCheckpointLoadReport
import app.lifeos.core.runtime.deepsearch.DeepSearchCheckpointRepository
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionId
import app.lifeos.core.runtime.deepsearch.DeepSearchStoredCheckpoint
import app.lifeos.core.runtime.deepsearch.DeepSearchStoredCheckpointCodec
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Encrypted CAS checkpoint repository for DeepSearch v2.
 *
 * Every mission owns one atomic encrypted checkpoint file. Existing unreadable state is never
 * replaced: reads report corruption and compare-and-set fails closed by propagating the decode
 * failure. Mission ids are already constrained to a vault-safe prefix plus lowercase hex digest.
 */
class EncryptedDeepSearchCheckpointRepository(context: Context) : DeepSearchCheckpointRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY).resolve(CHECKPOINT_DIRECTORY)
    private val key: SecretKey by lazy { EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS) }

    override suspend fun load(missionId: DeepSearchMissionId): DeepSearchCheckpointLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val file = fileFor(missionId)
                if (!exists(file)) return@withLock DeepSearchCheckpointLoadReport(null)
                try {
                    val value = readStrict(file, missionId)
                    require(value.missionId == missionId) { "DeepSearch checkpoint mission mismatch" }
                    DeepSearchCheckpointLoadReport(value)
                } catch (_: Exception) {
                    DeepSearchCheckpointLoadReport(
                        value = null,
                        unreadableEntries = listOf(file.name),
                    )
                }
            }
        }

    override suspend fun compareAndSet(
        missionId: DeepSearchMissionId,
        expectedRevision: Long,
        updated: DeepSearchStoredCheckpoint,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            require(updated.missionId == missionId)
            require(updated.revision == expectedRevision + 1L) {
                "DeepSearch checkpoint revision must advance exactly once"
            }
            ensureDirectory()
            val file = fileFor(missionId)
            val current = if (exists(file)) readStrict(file, missionId) else null
            val currentRevision = current?.revision ?: 0L
            if (currentRevision != expectedRevision) return@withLock false
            write(file, updated)
            true
        }
    }

    private fun readStrict(
        file: File,
        expectedMissionId: DeepSearchMissionId,
    ): DeepSearchStoredCheckpoint {
        val container = EncryptedLedgerVaultSupport.readAtomic(
            target = file,
            maxPlaintextBytes = DeepSearchStoredCheckpointCodec.MAX_PAYLOAD_BYTES,
        )
        val decrypted = EncryptedLedgerVaultSupport.decryptPathBoundOrLegacy(
            container = container,
            key = key,
            maxPlaintextBytes = DeepSearchStoredCheckpointCodec.MAX_PAYLOAD_BYTES,
            associatedData = associatedData(file),
        )
        val value = DeepSearchStoredCheckpointCodec.decode(decrypted.plaintext)
        require(value.missionId == expectedMissionId) { "DeepSearch checkpoint mission mismatch" }
        if (decrypted.migratedFromUnboundLegacy) write(file, value)
        return value
    }

    private fun write(file: File, value: DeepSearchStoredCheckpoint) {
        val plaintext = DeepSearchStoredCheckpointCodec.encode(value)
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = DeepSearchStoredCheckpointCodec.MAX_PAYLOAD_BYTES,
            associatedData = associatedData(file),
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, container)
    }

    private fun associatedData(file: File): ByteArray =
        VaultAssociatedData.forPath("deep-search-checkpoint/v2", directory, file)

    private fun fileFor(missionId: DeepSearchMissionId): File =
        directory.resolve("${missionId.value}.dscp")

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "DeepSearch checkpoint vault unavailable" }
    }

    private fun exists(target: File): Boolean = target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "deep-search-v2"
        const val CHECKPOINT_DIRECTORY = "checkpoints"
        const val KEY_ALIAS = "lifeos.deep.search.checkpoint.v2"
        val processMutex = Mutex()
    }
}
