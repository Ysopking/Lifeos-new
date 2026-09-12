package app.lifeos.core.data.capability

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.capability.ToolWorkshopJobId
import app.lifeos.core.runtime.capability.ToolWorkshopJobState
import app.lifeos.core.runtime.capability.ToolWorkshopStageArtifact
import app.lifeos.core.runtime.capability.ToolWorkshopStageArtifactCodec
import app.lifeos.core.runtime.capability.ToolWorkshopStageArtifactRepository
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encrypted immutable stage outputs for restart-safe V11 execution. */
class EncryptedToolWorkshopStageArtifactRepository(context: Context) : ToolWorkshopStageArtifactRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val file = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy { EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS) }

    override suspend fun persist(artifact: ToolWorkshopStageArtifact) = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val current = if (exists(file)) readStrict() else emptyList()
            val existing = current.singleOrNull { it.jobId == artifact.jobId && it.stage == artifact.stage }
            if (existing != null) {
                require(existing.fingerprint == artifact.fingerprint && existing.payload == artifact.payload) {
                    "ToolWorkshop stage artifact is immutable once persisted"
                }
                return@withLock
            }
            write(current + artifact)
        }
    }

    override suspend fun load(
        jobId: ToolWorkshopJobId,
        stage: ToolWorkshopJobState,
    ): ToolWorkshopStageArtifact? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            if (!exists(file)) return@withLock null
            readStrict().singleOrNull { it.jobId == jobId && it.stage == stage }
        }
    }

    override suspend fun loadAll(jobId: ToolWorkshopJobId): List<ToolWorkshopStageArtifact> =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                if (!exists(file)) return@withLock emptyList()
                readStrict().filter { it.jobId == jobId }.sortedBy { it.stage.ordinal }
            }
        }

    private fun readStrict(): List<ToolWorkshopStageArtifact> {
        val container = EncryptedLedgerVaultSupport.readAtomic(
            target = file,
            maxPlaintextBytes = ToolWorkshopStageArtifactCodec.MAX_FILE_BYTES,
        )
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = container,
            key = key,
            maxPlaintextBytes = ToolWorkshopStageArtifactCodec.MAX_FILE_BYTES,
        )
        return ToolWorkshopStageArtifactCodec.decode(plaintext)
    }

    private fun write(artifacts: List<ToolWorkshopStageArtifact>) {
        val plaintext = ToolWorkshopStageArtifactCodec.encode(artifacts)
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = ToolWorkshopStageArtifactCodec.MAX_FILE_BYTES,
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, container)
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "ToolWorkshop stage artifact vault unavailable" }
    }

    private fun exists(target: File): Boolean = target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "tool-workshop-stage-artifacts"
        const val FILE_NAME = "tool-workshop-stage-artifacts.twa"
        const val KEY_ALIAS = "lifeos.tool.workshop.stage.v1"
        val processMutex = Mutex()
    }
}
