package app.lifeos.core.data.boot

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexHead
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.runtime.boot.IncrementalBootPhotonManifest
import app.lifeos.core.runtime.boot.IncrementalBootPhotonManifestLoadReport
import app.lifeos.core.runtime.boot.IncrementalBootPhotonManifestRepository
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

/**
 * Encrypted single-head cache for M210. This is never source truth; deletion/corruption simply
 * causes an index-snapshot rebuild on the next boot.
 */
class EncryptedIncrementalBootPhotonManifestRepository(
    context: Context,
) : IncrementalBootPhotonManifestRepository {
    private val root = context.filesDir.resolve(ROOT_DIRECTORY)
    private val manifestFile = root.resolve(MANIFEST_FILE)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun loadReport(): IncrementalBootPhotonManifestLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureRoot()
                if (!exists(manifestFile)) {
                    return@withLock IncrementalBootPhotonManifestLoadReport(null)
                }
                runCatching { readManifest() }
                    .fold(
                        onSuccess = { IncrementalBootPhotonManifestLoadReport(it) },
                        onFailure = { error ->
                            IncrementalBootPhotonManifestLoadReport(
                                manifest = null,
                                corrupted = true,
                                message = error.message ?: "incremental-boot-manifest-corrupt",
                            )
                        },
                    )
            }
        }

    override suspend fun compareAndSet(
        expectedFingerprint: String?,
        next: IncrementalBootPhotonManifest,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureRoot()
            val current = if (exists(manifestFile)) {
                runCatching { readManifest() }.getOrNull()
            } else {
                null
            }
            if (current?.fingerprint != expectedFingerprint) {
                if (!(current == null && expectedFingerprint == null)) return@withLock false
            }
            writeManifest(next)
            val persisted = readManifest()
            require(persisted == next) {
                "Incremental boot manifest did not round-trip after durable write"
            }
            true
        }
    }

    private fun writeManifest(manifest: IncrementalBootPhotonManifest) {
        val plaintext = Codec.encode(manifest)
        val encrypted = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = MAX_MANIFEST_BYTES,
            associatedData = associatedData(),
        )
        EncryptedLedgerVaultSupport.atomicWrite(manifestFile, encrypted)
    }

    private fun readManifest(): IncrementalBootPhotonManifest {
        val container = EncryptedLedgerVaultSupport.readAtomic(
            manifestFile,
            MAX_MANIFEST_BYTES,
        )
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = container,
            key = key,
            maxPlaintextBytes = MAX_MANIFEST_BYTES,
            associatedData = associatedData(),
        )
        return Codec.decode(plaintext)
    }

    private fun associatedData(): ByteArray =
        (ROOT_DIRECTORY + "/" + MANIFEST_FILE).encodeToByteArray()

    private fun ensureRoot() {
        check(root.isDirectory || root.mkdirs()) {
            "Incremental boot manifest vault unavailable"
        }
    }

    private fun exists(file: File): Boolean =
        file.exists() || File(file.path + ".bak").exists()

    private object Codec {
        const val VERSION = 1
        const val MAX_REFS = 250_000

        fun encode(manifest: IncrementalBootPhotonManifest): ByteArray =
            ByteArrayOutputStream().let { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(VERSION)
                    data.writeLong(manifest.indexHead.snapshotGeneration)
                    data.writeUTF(manifest.indexHead.snapshotFingerprint)
                    data.writeLong(manifest.indexHead.lastJournalSequence)
                    data.writeInt(manifest.latestRefs.size)
                    manifest.latestRefs.forEach { ref ->
                        data.writeUTF(ref.photonId.value)
                        data.writeLong(ref.revision)
                    }
                    data.writeUTF(manifest.fingerprint)
                }
                output.toByteArray().also {
                    require(it.isNotEmpty() && it.size <= MAX_MANIFEST_BYTES)
                }
            }

        fun decode(bytes: ByteArray): IncrementalBootPhotonManifest {
            require(bytes.isNotEmpty() && bytes.size <= MAX_MANIFEST_BYTES)
            val input = DataInputStream(ByteArrayInputStream(bytes))
            require(input.readInt() == VERSION) {
                "Unsupported incremental boot manifest version"
            }
            val head = PhotonIndexHead(
                snapshotGeneration = input.readLong(),
                snapshotFingerprint = input.readUTF(),
                lastJournalSequence = input.readLong(),
            )
            val count = input.readInt().also {
                require(it in 0..MAX_REFS) {
                    "Invalid incremental boot manifest ref count"
                }
            }
            val refs = List(count) {
                PhotonRevisionRef(
                    photonId = PhotonId(input.readUTF()),
                    revision = input.readLong(),
                )
            }
            val storedFingerprint = input.readUTF()
            require(input.available() == 0) {
                "Trailing incremental boot manifest bytes"
            }
            val manifest = IncrementalBootPhotonManifest(
                indexHead = head,
                latestRefs = refs,
            )
            require(manifest.fingerprint == storedFingerprint) {
                "Incremental boot manifest fingerprint mismatch"
            }
            return manifest
        }
    }

    private companion object {
        const val ROOT_DIRECTORY = "incremental-boot-photon-manifest"
        const val MANIFEST_FILE = "head.iboot"
        const val KEY_ALIAS = "lifeos.incremental.boot.photon.manifest.v1"
        const val MAX_MANIFEST_BYTES = 24 * 1024 * 1024
        val processMutex = Mutex()
    }
}
