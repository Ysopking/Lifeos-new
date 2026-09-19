package app.lifeos.core.data.world

import android.content.Context
import android.util.AtomicFile
import app.lifeos.core.data.security.VersionedPathBoundVaultSupport
import app.lifeos.core.runtime.world.WorldEquationPackStructuralCanaryPlan
import app.lifeos.core.runtime.world.WorldEquationPackStructuralCanaryPlanCodec
import app.lifeos.core.runtime.world.WorldEquationPackStructuralCanaryPlanLoadReport
import app.lifeos.core.runtime.world.WorldEquationPackStructuralCanaryPlanRepository
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedWorldEquationPackStructuralCanaryPlanRepository(
    context: Context,
) : WorldEquationPackStructuralCanaryPlanRepository {
    private val directory = context.filesDir.resolve("world-equation-pack-canary-plan-vault")
    private val key: SecretKey by lazy {
        VersionedPathBoundVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun putIfAbsent(
        plan: WorldEquationPackStructuralCanaryPlan,
    ): Unit = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val target = targetFor(plan.fingerprint)
            if (exists(target)) {
                val existing = readValidated(target)
                require(existing == plan) {
                    "Structural canary plan fingerprint collision"
                }
                return@withLock
            }
            write(target, plan)
            require(readValidated(target) == plan) {
                "Structural canary plan did not round-trip durably"
            }
        }
    }

    override suspend fun load(
        planFingerprint: String,
    ): WorldEquationPackStructuralCanaryPlan? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val target = targetFor(planFingerprint)
            if (!exists(target)) return@withLock null
            readValidated(target).also {
                require(it.fingerprint == planFingerprint) {
                    "Structural canary plan identity mismatch"
                }
            }
        }
    }

    override suspend fun loadReport(): WorldEquationPackStructuralCanaryPlanLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val files = directory.listFiles()
                    ?: throw IOException("Structural canary plan vault cannot be listed")
                val plans = mutableListOf<WorldEquationPackStructuralCanaryPlan>()
                val failures = mutableListOf<String>()
                files
                    .map { it.name.removeSuffix(".bak") }
                    .filter { it.endsWith(FILE_SUFFIX) }
                    .distinct()
                    .sorted()
                    .forEach { name ->
                        val target = AtomicFile(directory.resolve(name))
                        try {
                            plans += readValidated(target)
                        } catch (_: Exception) {
                            failures += name
                        }
                    }
                WorldEquationPackStructuralCanaryPlanLoadReport(
                    plans = plans.distinctBy { it.fingerprint }.sortedBy { it.fingerprint },
                    unreadableEntries = failures.distinct().sorted(),
                )
            }
        }

    private fun write(
        target: AtomicFile,
        plan: WorldEquationPackStructuralCanaryPlan,
    ) {
        val container = VersionedPathBoundVaultSupport.encrypt(
            plaintext = WorldEquationPackStructuralCanaryPlanCodec.encode(plan),
            key = key,
            containerVersion = CONTAINER_VERSION,
            codecVersion = WorldEquationPackStructuralCanaryPlanCodec.VERSION,
            maxPlaintextBytes = WorldEquationPackStructuralCanaryPlanCodec.MAX_ENCODED_BYTES,
            associatedData = aad(target),
        )
        VersionedPathBoundVaultSupport.atomicWrite(target, container)
    }

    private fun readValidated(
        target: AtomicFile,
    ): WorldEquationPackStructuralCanaryPlan {
        val plaintext = VersionedPathBoundVaultSupport.decrypt(
            container = VersionedPathBoundVaultSupport.readAtomic(
                target = target,
                maxPlaintextBytes = WorldEquationPackStructuralCanaryPlanCodec.MAX_ENCODED_BYTES,
            ),
            key = key,
            expectedContainerVersion = CONTAINER_VERSION,
            expectedCodecVersion = WorldEquationPackStructuralCanaryPlanCodec.VERSION,
            maxPlaintextBytes = WorldEquationPackStructuralCanaryPlanCodec.MAX_ENCODED_BYTES,
            associatedData = aad(target),
        )
        val decoded = WorldEquationPackStructuralCanaryPlanCodec.decode(plaintext)
        require(target.baseFile == targetFor(decoded.fingerprint).baseFile) {
            "Structural canary plan payload does not match physical path"
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
        ("world-equation-pack-canary-plan-vault/" + target.baseFile.name)
            .toByteArray(Charsets.UTF_8)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Structural canary plan vault unavailable"
        }
    }

    private companion object {
        val processMutex = Mutex()
        const val KEY_ALIAS = "lifeos.world.equation.pack.canary.plan.v1"
        const val CONTAINER_VERSION = 1
        const val FILE_SUFFIX = ".wepcp"
    }
}
