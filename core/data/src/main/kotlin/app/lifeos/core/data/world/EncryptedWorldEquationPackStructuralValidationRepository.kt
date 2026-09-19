package app.lifeos.core.data.world

import android.content.Context
import android.util.AtomicFile
import app.lifeos.core.data.security.VersionedPathBoundVaultSupport
import app.lifeos.core.runtime.world.WorldEquationPackStructuralValidationBundle
import app.lifeos.core.runtime.world.WorldEquationPackStructuralValidationCodec
import app.lifeos.core.runtime.world.WorldEquationPackStructuralValidationLoadReport
import app.lifeos.core.runtime.world.WorldEquationPackStructuralValidationRepository
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedWorldEquationPackStructuralValidationRepository(
    context: Context,
) : WorldEquationPackStructuralValidationRepository {
    private val directory = context.filesDir.resolve("world-equation-pack-validation-vault")
    private val key: SecretKey by lazy {
        VersionedPathBoundVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun putIfAbsent(
        bundle: WorldEquationPackStructuralValidationBundle,
    ): Unit = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val target = targetFor(bundle.candidatePackFingerprint)
            if (exists(target)) {
                val existing = readValidated(target)
                require(existing.fingerprint == bundle.fingerprint) {
                    "Structural validation candidate already maps to another durable bundle"
                }
                return@withLock
            }
            write(target, bundle)
            val durable = readValidated(target)
            require(durable.fingerprint == bundle.fingerprint) {
                "Structural validation bundle did not round-trip durably"
            }
        }
    }

    override suspend fun load(
        candidatePackFingerprint: String,
    ): WorldEquationPackStructuralValidationBundle? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val target = targetFor(candidatePackFingerprint)
            if (!exists(target)) return@withLock null
            readValidated(target).also {
                require(it.candidatePackFingerprint == candidatePackFingerprint) {
                    "Structural validation identity mismatch"
                }
            }
        }
    }

    override suspend fun loadReport(): WorldEquationPackStructuralValidationLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val files = directory.listFiles()
                    ?: throw IOException("Structural validation vault cannot be listed")
                val bundles = mutableListOf<WorldEquationPackStructuralValidationBundle>()
                val failures = mutableListOf<String>()
                files
                    .map { it.name.removeSuffix(".bak") }
                    .filter { it.endsWith(FILE_SUFFIX) }
                    .distinct()
                    .sorted()
                    .forEach { name ->
                        val target = AtomicFile(directory.resolve(name))
                        try {
                            bundles += readValidated(target)
                        } catch (_: Exception) {
                            failures += name
                        }
                    }
                WorldEquationPackStructuralValidationLoadReport(
                    bundles = bundles
                        .distinctBy { it.candidatePackFingerprint }
                        .sortedBy { it.candidatePackFingerprint },
                    unreadableEntries = failures.distinct().sorted(),
                )
            }
        }

    private fun write(
        target: AtomicFile,
        bundle: WorldEquationPackStructuralValidationBundle,
    ) {
        val container = VersionedPathBoundVaultSupport.encrypt(
            plaintext = WorldEquationPackStructuralValidationCodec.encode(bundle),
            key = key,
            containerVersion = CONTAINER_VERSION,
            codecVersion = WorldEquationPackStructuralValidationCodec.VERSION,
            maxPlaintextBytes = WorldEquationPackStructuralValidationCodec.MAX_ENCODED_BYTES,
            associatedData = aad(target),
        )
        VersionedPathBoundVaultSupport.atomicWrite(target, container)
    }

    private fun readValidated(
        target: AtomicFile,
    ): WorldEquationPackStructuralValidationBundle {
        val plaintext = VersionedPathBoundVaultSupport.decrypt(
            container = VersionedPathBoundVaultSupport.readAtomic(
                target = target,
                maxPlaintextBytes = WorldEquationPackStructuralValidationCodec.MAX_ENCODED_BYTES,
            ),
            key = key,
            expectedContainerVersion = CONTAINER_VERSION,
            expectedCodecVersion = WorldEquationPackStructuralValidationCodec.VERSION,
            maxPlaintextBytes = WorldEquationPackStructuralValidationCodec.MAX_ENCODED_BYTES,
            associatedData = aad(target),
        )
        val decoded = WorldEquationPackStructuralValidationCodec.decode(plaintext)
        require(target.baseFile == targetFor(decoded.candidatePackFingerprint).baseFile) {
            "Structural validation payload does not match physical path"
        }
        return decoded
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
        ("world-equation-pack-validation-vault/" + target.baseFile.name)
            .toByteArray(Charsets.UTF_8)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Structural validation vault unavailable"
        }
    }

    private companion object {
        val processMutex = Mutex()
        const val KEY_ALIAS = "lifeos.world.equation.pack.validation.v1"
        const val CONTAINER_VERSION = 1
        const val FILE_SUFFIX = ".wepkv"
    }
}
