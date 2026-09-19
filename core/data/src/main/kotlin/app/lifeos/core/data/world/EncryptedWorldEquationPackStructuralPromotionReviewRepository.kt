package app.lifeos.core.data.world

import android.content.Context
import android.util.AtomicFile
import app.lifeos.core.data.security.VersionedPathBoundVaultSupport
import app.lifeos.core.runtime.world.WorldEquationPackStructuralPromotionReviewBundle
import app.lifeos.core.runtime.world.WorldEquationPackStructuralPromotionReviewCodec
import app.lifeos.core.runtime.world.WorldEquationPackStructuralPromotionReviewLoadReport
import app.lifeos.core.runtime.world.WorldEquationPackStructuralPromotionReviewRepository
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedWorldEquationPackStructuralPromotionReviewRepository(
    context: Context,
) : WorldEquationPackStructuralPromotionReviewRepository {
    private val directory = context.filesDir.resolve("world-equation-pack-promotion-review-vault")
    private val key: SecretKey by lazy {
        VersionedPathBoundVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun putIfAbsent(
        bundle: WorldEquationPackStructuralPromotionReviewBundle,
    ): Unit = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val target = targetFor(bundle.fingerprint)
            if (exists(target)) {
                val existing = readValidated(target)
                require(existing == bundle) {
                    "Structural promotion review fingerprint collision"
                }
                return@withLock
            }
            write(target, bundle)
            require(readValidated(target) == bundle) {
                "Structural promotion review bundle did not round-trip durably"
            }
        }
    }

    override suspend fun load(
        bundleFingerprint: String,
    ): WorldEquationPackStructuralPromotionReviewBundle? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val target = targetFor(bundleFingerprint)
            if (!exists(target)) return@withLock null
            readValidated(target).also {
                require(it.fingerprint == bundleFingerprint) {
                    "Structural promotion review identity mismatch"
                }
            }
        }
    }

    override suspend fun loadReport(): WorldEquationPackStructuralPromotionReviewLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val files = directory.listFiles()
                    ?: throw IOException("Structural promotion review vault cannot be listed")
                val bundles = mutableListOf<WorldEquationPackStructuralPromotionReviewBundle>()
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
                WorldEquationPackStructuralPromotionReviewLoadReport(
                    bundles = bundles.distinctBy { it.fingerprint }.sortedBy { it.fingerprint },
                    unreadableEntries = failures.distinct().sorted(),
                )
            }
        }

    private fun write(
        target: AtomicFile,
        bundle: WorldEquationPackStructuralPromotionReviewBundle,
    ) {
        val container = VersionedPathBoundVaultSupport.encrypt(
            plaintext = WorldEquationPackStructuralPromotionReviewCodec.encode(bundle),
            key = key,
            containerVersion = CONTAINER_VERSION,
            codecVersion = WorldEquationPackStructuralPromotionReviewCodec.VERSION,
            maxPlaintextBytes =
                WorldEquationPackStructuralPromotionReviewCodec.MAX_ENCODED_BYTES,
            associatedData = aad(target),
        )
        VersionedPathBoundVaultSupport.atomicWrite(target, container)
    }

    private fun readValidated(
        target: AtomicFile,
    ): WorldEquationPackStructuralPromotionReviewBundle {
        val plaintext = VersionedPathBoundVaultSupport.decrypt(
            container = VersionedPathBoundVaultSupport.readAtomic(
                target = target,
                maxPlaintextBytes =
                    WorldEquationPackStructuralPromotionReviewCodec.MAX_ENCODED_BYTES,
            ),
            key = key,
            expectedContainerVersion = CONTAINER_VERSION,
            expectedCodecVersion =
                WorldEquationPackStructuralPromotionReviewCodec.VERSION,
            maxPlaintextBytes =
                WorldEquationPackStructuralPromotionReviewCodec.MAX_ENCODED_BYTES,
            associatedData = aad(target),
        )
        val bundle =
            WorldEquationPackStructuralPromotionReviewCodec.decode(plaintext)
        require(target.baseFile == targetFor(bundle.fingerprint).baseFile) {
            "Structural promotion review payload does not match physical path"
        }
        return bundle
    }

    private fun targetFor(bundleFingerprint: String): AtomicFile {
        require(bundleFingerprint.isNotBlank())
        return AtomicFile(directory.resolve(sha256(bundleFingerprint) + FILE_SUFFIX))
    }

    private fun exists(target: AtomicFile): Boolean =
        target.baseFile.exists() ||
            target.baseFile.resolveSibling(target.baseFile.name + ".bak").exists()

    private fun aad(target: AtomicFile): ByteArray =
        ("world-equation-pack-promotion-review-vault/" + target.baseFile.name)
            .toByteArray(Charsets.UTF_8)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Structural promotion review vault unavailable"
        }
    }

    private companion object {
        val processMutex = Mutex()
        const val KEY_ALIAS = "lifeos.world.equation.pack.promotion.review.v1"
        const val CONTAINER_VERSION = 1
        const val FILE_SUFFIX = ".weppr"
    }
}
