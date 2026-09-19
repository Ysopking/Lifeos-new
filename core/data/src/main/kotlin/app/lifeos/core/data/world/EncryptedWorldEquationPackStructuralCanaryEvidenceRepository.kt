package app.lifeos.core.data.world

import android.content.Context
import android.util.AtomicFile
import app.lifeos.core.runtime.world.WorldEquationPackStructuralCanaryEvidenceCodec
import app.lifeos.core.runtime.world.WorldEquationPackStructuralCanaryEvidenceLoadReport
import app.lifeos.core.data.security.VersionedPathBoundVaultSupport
import app.lifeos.core.runtime.world.WorldEquationPackStructuralCanaryEvidenceRecord
import app.lifeos.core.runtime.world.WorldEquationPackStructuralCanaryEvidenceRepository
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedWorldEquationPackStructuralCanaryEvidenceRepository(
    context: Context,
) : WorldEquationPackStructuralCanaryEvidenceRepository {
    private val directory = context.filesDir.resolve("world-equation-pack-canary-evidence-vault")
    private val key: SecretKey by lazy {
        VersionedPathBoundVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun load(
        planFingerprint: String,
    ): WorldEquationPackStructuralCanaryEvidenceRecord? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val target = targetFor(planFingerprint)
            if (!exists(target)) return@withLock null
            readValidated(target).also { record ->
                require(record.evidence.planFingerprint == planFingerprint) {
                    "Structural canary evidence identity mismatch"
                }
            }
        }
    }

    override suspend fun compareAndSet(
        expected: WorldEquationPackStructuralCanaryEvidenceRecord?,
        next: WorldEquationPackStructuralCanaryEvidenceRecord,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val target = targetFor(next.evidence.planFingerprint)
            val current = if (exists(target)) readValidated(target) else null
            if (current?.fingerprint != expected?.fingerprint) {
                return@withLock false
            }
            if (expected == null) {
                next.requireInitialRecord()
            } else {
                next.requireSuccessorOf(expected)
            }

            write(target, next)
            val durable = readValidated(target)
            require(durable.fingerprint == next.fingerprint) {
                "Structural canary evidence did not round-trip durably"
            }
            true
        }
    }

    override suspend fun loadReport(): WorldEquationPackStructuralCanaryEvidenceLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val files = directory.listFiles()
                    ?: throw IOException("Structural canary evidence vault cannot be listed")
                val records = mutableListOf<WorldEquationPackStructuralCanaryEvidenceRecord>()
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
                WorldEquationPackStructuralCanaryEvidenceLoadReport(
                    records = records
                        .distinctBy { it.evidence.planFingerprint }
                        .sortedBy { it.evidence.planFingerprint },
                    unreadableEntries = failures.distinct().sorted(),
                )
            }
        }

    private fun write(
        target: AtomicFile,
        record: WorldEquationPackStructuralCanaryEvidenceRecord,
    ) {
        val container = VersionedPathBoundVaultSupport.encrypt(
            plaintext = WorldEquationPackStructuralCanaryEvidenceCodec.encode(record),
            key = key,
            containerVersion = CONTAINER_VERSION,
            codecVersion = WorldEquationPackStructuralCanaryEvidenceCodec.VERSION,
            maxPlaintextBytes = WorldEquationPackStructuralCanaryEvidenceCodec.MAX_ENCODED_BYTES,
            associatedData = aad(target),
        )
        VersionedPathBoundVaultSupport.atomicWrite(target, container)
    }

    private fun readValidated(
        target: AtomicFile,
    ): WorldEquationPackStructuralCanaryEvidenceRecord {
        val plaintext = VersionedPathBoundVaultSupport.decrypt(
            container = VersionedPathBoundVaultSupport.readAtomic(
                target = target,
                maxPlaintextBytes = WorldEquationPackStructuralCanaryEvidenceCodec.MAX_ENCODED_BYTES,
            ),
            key = key,
            expectedContainerVersion = CONTAINER_VERSION,
            expectedCodecVersion = WorldEquationPackStructuralCanaryEvidenceCodec.VERSION,
            maxPlaintextBytes = WorldEquationPackStructuralCanaryEvidenceCodec.MAX_ENCODED_BYTES,
            associatedData = aad(target),
        )
        val decoded = WorldEquationPackStructuralCanaryEvidenceCodec.decode(plaintext)
        require(target.baseFile == targetFor(decoded.evidence.planFingerprint).baseFile) {
            "Structural canary evidence payload does not match physical path"
        }
        return decoded
    }

    private fun targetFor(planFingerprint: String): AtomicFile {
        require(planFingerprint.isNotBlank())
        return AtomicFile(directory.resolve(sha256(planFingerprint) + FILE_SUFFIX))
    }

    private fun exists(target: AtomicFile): Boolean =
        target.baseFile.exists() ||
            target.baseFile.resolveSibling(target.baseFile.name + ".bak").exists()

    private fun aad(target: AtomicFile): ByteArray =
        ("world-equation-pack-canary-evidence-vault/" + target.baseFile.name)
            .toByteArray(Charsets.UTF_8)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Structural canary evidence vault unavailable"
        }
    }

    private companion object {
        val processMutex = Mutex()
        const val KEY_ALIAS = "lifeos.world.equation.pack.canary.evidence.v1"
        const val CONTAINER_VERSION = 1
        const val FILE_SUFFIX = ".wepce"
    }
}
