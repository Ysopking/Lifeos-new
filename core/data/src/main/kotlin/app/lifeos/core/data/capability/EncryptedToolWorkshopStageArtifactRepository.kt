package app.lifeos.core.data.capability

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.capability.ToolWorkshopJobId
import app.lifeos.core.runtime.capability.ToolWorkshopJobState
import app.lifeos.core.runtime.capability.ToolWorkshopStageArtifact
import app.lifeos.core.runtime.capability.ToolWorkshopStageArtifactCodec
import app.lifeos.core.runtime.capability.ToolWorkshopStageArtifactRepository
import java.io.File
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Immutable encrypted stage outputs addressed directly by (jobId, stage). */
class EncryptedToolWorkshopStageArtifactRepository(context: Context) : ToolWorkshopStageArtifactRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val recordsDirectory = directory.resolve("records")
    private val legacyFile = directory.resolve(FILE_NAME)
    private val key: SecretKey by lazy { EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS) }

    override suspend fun persist(artifact: ToolWorkshopStageArtifact) = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureMigrated()
            val target = targetFor(artifact.jobId, artifact.stage)
            if (exists(target)) {
                val existing = readValidatedOne(target)
                require(existing.fingerprint == artifact.fingerprint && existing.payload == artifact.payload) {
                    "ToolWorkshop stage artifact is immutable once persisted"
                }
                return@withLock
            }
            writeOne(target, artifact)
        }
    }

    override suspend fun load(
        jobId: ToolWorkshopJobId,
        stage: ToolWorkshopJobState,
    ): ToolWorkshopStageArtifact? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureMigrated()
            val target = targetFor(jobId, stage)
            if (!exists(target)) null else readValidatedOne(target).also {
                require(it.jobId == jobId && it.stage == stage) {
                    "ToolWorkshop stage artifact identity mismatch"
                }
            }
        }
    }

    override suspend fun loadAll(jobId: ToolWorkshopJobId): List<ToolWorkshopStageArtifact> =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureMigrated()
                val jobDirectory = recordsDirectory.resolve(sha256(jobId.value))
                if (!jobDirectory.exists()) return@withLock emptyList()
                jobDirectory.listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(RECORD_SUFFIX) }
                    .map(::readValidatedOne)
                    .onEach { require(it.jobId == jobId) }
                    .sortedBy { it.stage.ordinal }
            }
        }

    private fun ensureMigrated() {
        ensureDirectory()
        if (recordsDirectory.walkTopDown().any { it.isFile && it.name.endsWith(RECORD_SUFFIX) }) return
        if (!exists(legacyFile)) return
        readMany(legacyFile).forEach { artifact ->
            val target = targetFor(artifact.jobId, artifact.stage)
            if (!exists(target)) writeOne(target, artifact)
        }
    }

    private fun readOne(file: File): ToolWorkshopStageArtifact {
        val values = readMany(file)
        require(values.size == 1) { "Stage artifact record must contain one artifact" }
        return values.single()
    }

    private fun readValidatedOne(file: File): ToolWorkshopStageArtifact {
        val artifact = readOne(file)
        require(file == targetFor(artifact.jobId, artifact.stage)) {
            "ToolWorkshop stage artifact payload does not match record path"
        }
        return artifact
    }

    private fun readMany(file: File): List<ToolWorkshopStageArtifact> {
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

    private fun writeOne(file: File, artifact: ToolWorkshopStageArtifact) {
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val plaintext = ToolWorkshopStageArtifactCodec.encode(listOf(artifact))
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = ToolWorkshopStageArtifactCodec.MAX_FILE_BYTES,
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, container)
    }

    private fun targetFor(jobId: ToolWorkshopJobId, stage: ToolWorkshopJobState): File =
        recordsDirectory.resolve(sha256(jobId.value)).resolve("${stage.ordinal}-${stage.name.lowercase()}$RECORD_SUFFIX")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "ToolWorkshop stage artifact vault unavailable" }
        check(recordsDirectory.isDirectory || recordsDirectory.mkdirs()) {
            "ToolWorkshop stage artifact record directory unavailable"
        }
    }

    private fun exists(target: File): Boolean =
        target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "tool-workshop-stage-artifacts"
        const val FILE_NAME = "tool-workshop-stage-artifacts.twa"
        const val RECORD_SUFFIX = ".twa"
        const val KEY_ALIAS = "lifeos.tool.workshop.stage.v1"
        val processMutex = Mutex()
    }
}
