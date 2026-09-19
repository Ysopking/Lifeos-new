package app.lifeos.core.data.world

import android.content.Context
import android.util.AtomicFile
import app.lifeos.core.data.security.VersionedPathBoundVaultSupport
import app.lifeos.core.runtime.world.WorldEquationPack
import app.lifeos.core.runtime.world.WorldEquationPackCodec
import app.lifeos.core.runtime.world.WorldEquationPackLoadReport
import app.lifeos.core.runtime.world.WorldEquationPackRepository
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedWorldEquationPackRepository(
    context: Context,
) : WorldEquationPackRepository {
    private val directory = context.filesDir.resolve("world-equation-pack-vault")
    private val key: SecretKey by lazy {
        VersionedPathBoundVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun putIfAbsent(pack: WorldEquationPack): Unit =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val target = targetFor(pack.version)
                if (exists(target)) {
                    val existing = readValidated(target)
                    require(existing.fingerprint() == pack.fingerprint()) {
                        "WorldEquationPack version " + pack.version +
                            " already maps to another durable artifact"
                    }
                    return@withLock
                }
                write(target, pack)
                val reloaded = readValidated(target)
                require(reloaded.fingerprint() == pack.fingerprint()) {
                    "WorldEquationPack did not round-trip durably"
                }
            }
        }

    override suspend fun load(version: String): WorldEquationPack? =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val target = targetFor(version)
                if (!exists(target)) return@withLock null
                readValidated(target).also {
                    require(it.version == version) {
                        "WorldEquationPack identity mismatch"
                    }
                }
            }
        }

    override suspend fun loadReport(): WorldEquationPackLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val files = directory.listFiles()
                    ?: throw IOException("WorldEquationPack vault cannot be listed")
                val packs = mutableListOf<WorldEquationPack>()
                val failures = mutableListOf<String>()
                files
                    .map { it.name.removeSuffix(".bak") }
                    .filter { it.endsWith(FILE_SUFFIX) }
                    .distinct()
                    .sorted()
                    .forEach { name ->
                        val target = AtomicFile(directory.resolve(name))
                        try {
                            packs += readValidated(target)
                        } catch (_: Exception) {
                            failures += name
                        }
                    }
                WorldEquationPackLoadReport(
                    packs = packs.distinctBy { it.version }.sortedBy { it.version },
                    unreadableEntries = failures.distinct().sorted(),
                )
            }
        }

    private fun write(
        target: AtomicFile,
        pack: WorldEquationPack,
    ) {
        val container = VersionedPathBoundVaultSupport.encrypt(
            plaintext = WorldEquationPackCodec.encode(pack),
            key = key,
            containerVersion = CONTAINER_VERSION,
            codecVersion = WorldEquationPackCodec.VERSION,
            maxPlaintextBytes = WorldEquationPackCodec.MAX_ENCODED_BYTES,
            associatedData = aad(target),
        )
        VersionedPathBoundVaultSupport.atomicWrite(target, container)
    }

    private fun readValidated(
        target: AtomicFile,
    ): WorldEquationPack {
        val plaintext = VersionedPathBoundVaultSupport.decrypt(
            container = VersionedPathBoundVaultSupport.readAtomic(
                target = target,
                maxPlaintextBytes = WorldEquationPackCodec.MAX_ENCODED_BYTES,
            ),
            key = key,
            expectedContainerVersion = CONTAINER_VERSION,
            expectedCodecVersion = WorldEquationPackCodec.VERSION,
            maxPlaintextBytes = WorldEquationPackCodec.MAX_ENCODED_BYTES,
            associatedData = aad(target),
        )
        val pack = WorldEquationPackCodec.decode(plaintext)
        require(target.baseFile == targetFor(pack.version).baseFile) {
            "WorldEquationPack payload does not match physical path"
        }
        return pack
    }

    private fun targetFor(version: String): AtomicFile {
        require(version.isNotBlank())
        return AtomicFile(directory.resolve(sha256(version) + FILE_SUFFIX))
    }

    private fun exists(target: AtomicFile): Boolean =
        target.baseFile.exists() ||
            target.baseFile.resolveSibling(target.baseFile.name + ".bak").exists()

    private fun aad(target: AtomicFile): ByteArray =
        ("world-equation-pack-vault/" + target.baseFile.name)
            .toByteArray(Charsets.UTF_8)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "WorldEquationPack vault unavailable"
        }
    }

    private companion object {
        val processMutex = Mutex()
        const val KEY_ALIAS = "lifeos.world.equation.pack.v1"
        const val CONTAINER_VERSION = 1
        const val FILE_SUFFIX = ".weqpack"
    }
}
