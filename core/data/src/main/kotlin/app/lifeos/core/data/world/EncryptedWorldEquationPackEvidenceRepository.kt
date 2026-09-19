package app.lifeos.core.data.world

import android.content.Context
import android.util.AtomicFile
import app.lifeos.core.data.security.VersionedPathBoundVaultSupport
import app.lifeos.core.runtime.world.WorldEquationPackEvidenceCodec
import app.lifeos.core.runtime.world.WorldEquationPackEvidenceLoadReport
import app.lifeos.core.runtime.world.WorldEquationPackEvidenceRecord
import app.lifeos.core.runtime.world.WorldEquationPackEvidenceRepository
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedWorldEquationPackEvidenceRepository(
    context: Context,
) : WorldEquationPackEvidenceRepository {
    private val directory = context.filesDir.resolve("world-equation-pack-evidence-vault")
    private val key: SecretKey by lazy {
        VersionedPathBoundVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun load(
        candidatePackFingerprint: String,
    ): WorldEquationPackEvidenceRecord? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val target = targetFor(candidatePackFingerprint)
            if (!exists(target)) return@withLock null
            readValidated(target).also {
                require(it.candidatePackFingerprint == candidatePackFingerprint) {
                    "Structural pack evidence identity mismatch"
                }
            }
        }
    }

    override suspend fun compareAndSet(
        candidatePackFingerprint: String,
        expectedRevision: Long?,
        next: WorldEquationPackEvidenceRecord,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(candidatePackFingerprint.isNotBlank())
            require(next.candidatePackFingerprint == candidatePackFingerprint)
            ensureDirectory()
            val target = targetFor(candidatePackFingerprint)
            val current = if (exists(target)) readValidated(target) else null
            if (current?.revision != expectedRevision) return@withLock false
            if (current == null) {
                require(expectedRevision == null)
                next.requireInitialRecord()
            } else {
                next.requireSuccessorOf(current)
            }
            write(target, next)
            val durable = readValidated(target)
            require(durable.fingerprint == next.fingerprint) {
                "Structural pack evidence did not round-trip durably"
            }
            true
        }
    }

    override suspend fun loadReport(): WorldEquationPackEvidenceLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val files = directory.listFiles()
                    ?: throw IOException("Structural pack evidence vault cannot be listed")
                val records = mutableListOf<WorldEquationPackEvidenceRecord>()
                val failures = mutableListOf<String>()
                files
                    .map { it.name.removeSuffix(".bak") }
                    .filter { it.endsWith(FILE_SUFFIX) }
                    .distinct()
                    .sorted()
                    .forEach { name ->
                        val target = AtomicFile(directory.resolve(name))
                        try {
                            records += readValidated(target)
                        } catch (_: Exception) {
                            failures += name
                        }
                    }
                WorldEquationPackEvidenceLoadReport(
                    records = records
                        .distinctBy { it.candidatePackFingerprint }
                        .sortedBy { it.id },
                    unreadableEntries = failures.distinct().sorted(),
                )
            }
        }

    private fun write(
        target: AtomicFile,
        record: WorldEquationPackEvidenceRecord,
    ) {
        val container = VersionedPathBoundVaultSupport.encrypt(
            plaintext = WorldEquationPackEvidenceCodec.encode(record),
            key = key,
            containerVersion = CONTAINER_VERSION,
            codecVersion = WorldEquationPackEvidenceCodec.VERSION,
            maxPlaintextBytes = WorldEquationPackEvidenceCodec.MAX_ENCODED_BYTES,
            associatedData = aad(target),
        )
        VersionedPathBoundVaultSupport.atomicWrite(target, container)
    }

    private fun readValidated(
        target: AtomicFile,
    ): WorldEquationPackEvidenceRecord {
        val plaintext = VersionedPathBoundVaultSupport.decrypt(
            container = VersionedPathBoundVaultSupport.readAtomic(
                target = target,
                maxPlaintextBytes = WorldEquationPackEvidenceCodec.MAX_ENCODED_BYTES,
            ),
            key = key,
            expectedContainerVersion = CONTAINER_VERSION,
            expectedCodecVersion = WorldEquationPackEvidenceCodec.VERSION,
            maxPlaintextBytes = WorldEquationPackEvidenceCodec.MAX_ENCODED_BYTES,
            associatedData = aad(target),
        )
        val record = WorldEquationPackEvidenceCodec.decode(plaintext)
        require(target.baseFile == targetFor(record.candidatePackFingerprint).baseFile) {
            "Structural pack evidence payload does not match physical path"
        }
        return record
    }

    private fun targetFor(candidatePackFingerprint: String): AtomicFile {
        require(candidatePackFingerprint.isNotBlank())
        return AtomicFile(
            directory.resolve(sha256(candidatePackFingerprint) + FILE_SUFFIX)
        )
    }

    private fun exists(target: AtomicFile): Boolean =
        target.baseFile.exists() ||
            target.baseFile.resolveSibling(target.baseFile.name + ".bak").exists()

    private fun aad(target: AtomicFile): ByteArray =
        ("world-equation-pack-evidence-vault/" + target.baseFile.name)
            .toByteArray(Charsets.UTF_8)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Structural pack evidence vault unavailable"
        }
    }

    private companion object {
        val processMutex = Mutex()
        const val KEY_ALIAS = "lifeos.world.equation.pack.evidence.v1"
        const val CONTAINER_VERSION = 1
        const val FILE_SUFFIX = ".wepke"
    }
}
