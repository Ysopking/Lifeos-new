package app.lifeos.core.data.world

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.world.CognitiveCycleId
import app.lifeos.core.runtime.world.ProductiveWorldHead
import app.lifeos.core.runtime.world.ProductiveWorldHeadLoadReport
import app.lifeos.core.runtime.world.ProductiveWorldHeadRepository
import app.lifeos.core.runtime.world.WorldFormulaSnapshotNamespace
import app.lifeos.core.runtime.world.WorldFormulaSnapshotRef
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

class EncryptedProductiveWorldHeadRepository(
    context: Context,
) : ProductiveWorldHeadRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val headFile = directory.resolve(HEAD_FILE)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun load(): ProductiveWorldHead? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            if (!exists(headFile)) return@withLock null
            readHead(headFile)
        }
    }

    override suspend fun compareAndSet(
        expectedRevision: Long?,
        next: ProductiveWorldHead,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val current = if (exists(headFile)) readHead(headFile) else null
            val currentRevision = current?.revision
            if (currentRevision != expectedRevision) return@withLock false
            require(next.revision == (expectedRevision ?: 0L) + 1L) {
                "Productive world head revision must advance exactly once"
            }
            require(next.predecessorSnapshotId == current?.activeSnapshot?.snapshotId) {
                "Productive world head predecessor does not match stored head"
            }
            current?.let {
                require(it.fingerprint.isNotBlank())
                require(it == readHead(headFile)) {
                    "Productive world head changed during CAS validation"
                }
            }
            writeHead(headFile, next)
            val persisted = readHead(headFile)
            require(persisted.revision == next.revision)
            require(persisted.fingerprint == next.fingerprint)
            true
        }
    }

    override suspend fun loadReport(): ProductiveWorldHeadLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                if (!exists(headFile)) {
                    return@withLock ProductiveWorldHeadLoadReport(
                        head = null,
                        corrupted = false,
                        message = null,
                    )
                }
                runCatching { readHead(headFile) }.fold(
                    onSuccess = {
                        ProductiveWorldHeadLoadReport(
                            head = it,
                            corrupted = false,
                            message = null,
                        )
                    },
                    onFailure = {
                        ProductiveWorldHeadLoadReport(
                            head = null,
                            corrupted = true,
                            message = it.message ?: "productive-world-head-corrupt",
                        )
                    },
                )
            }
        }

    private fun readHead(file: File): ProductiveWorldHead {
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(file, MAX_PLAINTEXT_BYTES),
            key = key,
            maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
            associatedData = associatedData(file),
        )
        return ProductiveWorldHeadCodec.decode(plaintext)
    }

    private fun writeHead(
        file: File,
        head: ProductiveWorldHead,
    ) {
        val plaintext = ProductiveWorldHeadCodec.encode(head)
        val encrypted = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
            associatedData = associatedData(file),
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, encrypted)
    }

    private fun associatedData(file: File): ByteArray {
        require(file == headFile) { "Unexpected productive world head path" }
        return "$ROOT_DIRECTORY/$HEAD_FILE".encodeToByteArray()
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Productive world head vault unavailable"
        }
    }

    private fun exists(file: File): Boolean =
        file.exists() || File("${file.path}.bak").exists()

    private object ProductiveWorldHeadCodec {
        private const val VERSION = 1

        fun encode(head: ProductiveWorldHead): ByteArray =
            ByteArrayOutputStream().let { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(VERSION)
                    data.writeLong(head.revision)
                    data.writeUTF(head.activeSnapshot.namespace.name)
                    data.writeUTF(head.activeSnapshot.snapshotId)
                    data.writeUTF(head.activeSnapshot.equationVersion)
                    data.writeBoolean(head.activeSnapshot.cycleId != null)
                    head.activeSnapshot.cycleId?.let { data.writeUTF(it.value) }
                    data.writeBoolean(head.predecessorSnapshotId != null)
                    head.predecessorSnapshotId?.let(data::writeUTF)
                    data.writeUTF(head.equationVersion)
                    data.writeUTF(head.cycleId.value)
                    data.writeUTF(head.cycleContextFingerprint)
                    data.writeUTF(head.fingerprint)
                }
                output.toByteArray().also {
                    require(it.isNotEmpty() && it.size <= MAX_PLAINTEXT_BYTES)
                }
            }

        fun decode(bytes: ByteArray): ProductiveWorldHead {
            require(bytes.isNotEmpty() && bytes.size <= MAX_PLAINTEXT_BYTES)
            val input = DataInputStream(ByteArrayInputStream(bytes))
            require(input.readInt() == VERSION) {
                "Unsupported productive world head codec version"
            }
            val revision = input.readLong()
            val namespace = WorldFormulaSnapshotNamespace.valueOf(input.readUTF())
            val snapshotId = input.readUTF()
            val snapshotEquationVersion = input.readUTF()
            val snapshotCycleId =
                if (input.readBoolean()) CognitiveCycleId(input.readUTF()) else null
            val predecessor = if (input.readBoolean()) input.readUTF() else null
            val equationVersion = input.readUTF()
            val cycleId = CognitiveCycleId(input.readUTF())
            val cycleContextFingerprint = input.readUTF()
            val fingerprint = input.readUTF()
            require(input.available() == 0) {
                "Trailing productive world head payload"
            }
            return ProductiveWorldHead.restore(
                revision = revision,
                activeSnapshot = WorldFormulaSnapshotRef(
                    namespace = namespace,
                    snapshotId = snapshotId,
                    equationVersion = snapshotEquationVersion,
                    cycleId = snapshotCycleId,
                ),
                predecessorSnapshotId = predecessor,
                equationVersion = equationVersion,
                cycleId = cycleId,
                cycleContextFingerprint = cycleContextFingerprint,
                fingerprint = fingerprint,
            )
        }
    }

    private companion object {
        const val ROOT_DIRECTORY = "productive-world-head-vault"
        const val HEAD_FILE = "head.pworld"
        const val KEY_ALIAS = "lifeos.productive.world.head.v1"
        const val MAX_PLAINTEXT_BYTES = 32 * 1024
        val processMutex = Mutex()
    }
}
