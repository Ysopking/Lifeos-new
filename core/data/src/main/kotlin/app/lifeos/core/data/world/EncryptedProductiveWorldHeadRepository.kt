package app.lifeos.core.data.world

import android.content.Context
import app.lifeos.core.data.security.DurableSchemaDescriptor
import app.lifeos.core.data.security.DurableSchemaGenerationStore
import app.lifeos.core.data.security.DurableSchemaSource
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
    private val schemaStore = DurableSchemaGenerationStore(
        root = directory,
        descriptor = SCHEMA_DESCRIPTOR,
    )
    @Volatile
    private var preparedDataRoot: File? = null
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun load(): ProductiveWorldHead? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            val dataRoot = prepareDataRoot()
            val headFile = dataRoot.resolve(HEAD_FILE)
            if (!exists(headFile)) return@withLock null
            readHead(headFile, dataRoot)
        }
    }

    override suspend fun compareAndSet(
        expectedRevision: Long?,
        next: ProductiveWorldHead,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            val dataRoot = prepareDataRoot()
            val headFile = dataRoot.resolve(HEAD_FILE)
            val current = if (exists(headFile)) readHead(headFile, dataRoot) else null
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
                require(it == readHead(headFile, dataRoot)) {
                    "Productive world head changed during CAS validation"
                }
            }
            writeHead(headFile, next, dataRoot)
            val persisted = readHead(headFile, dataRoot)
            require(persisted.revision == next.revision)
            require(persisted.fingerprint == next.fingerprint)
            true
        }
    }

    override suspend fun loadReport(): ProductiveWorldHeadLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                val dataRoot = prepareDataRoot()
                val headFile = dataRoot.resolve(HEAD_FILE)
                if (!exists(headFile)) {
                    return@withLock ProductiveWorldHeadLoadReport(
                        head = null,
                        corrupted = false,
                        message = null,
                    )
                }
                runCatching { readHead(headFile, dataRoot) }.fold(
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

    private fun readHead(
        file: File,
        dataRoot: File,
    ): ProductiveWorldHead {
        require(file == dataRoot.resolve(HEAD_FILE)) {
            "Unexpected productive world head path"
        }
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(file, MAX_PLAINTEXT_BYTES),
            key = key,
            maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
            associatedData = associatedData(dataRoot),
        )
        return ProductiveWorldHeadCodec.decode(plaintext)
    }

    private fun writeHead(
        file: File,
        head: ProductiveWorldHead,
        dataRoot: File,
    ) {
        require(file == dataRoot.resolve(HEAD_FILE)) {
            "Unexpected productive world head path"
        }
        val plaintext = ProductiveWorldHeadCodec.encode(head)
        val encrypted = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
            associatedData = associatedData(dataRoot),
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, encrypted)
    }

    private fun prepareDataRoot(): File {
        preparedDataRoot?.let { return it }
        check(directory.isDirectory || directory.mkdirs()) {
            "Productive world head vault unavailable"
        }
        val legacyHead = directory.resolve(HEAD_FILE)
        val prepared = schemaStore.prepare(
            legacyExists = { exists(legacyHead) },
            migrate = { source, target ->
                val sourceHead = source.root.resolve(HEAD_FILE)
                if (exists(sourceHead)) {
                    val head = when (source) {
                        is DurableSchemaSource.Legacy ->
                            readLegacyHead(sourceHead)
                        is DurableSchemaSource.Generation ->
                            readHead(sourceHead, source.root)
                    }
                    writeHead(target.resolve(HEAD_FILE), head, target)
                }
            },
            validate = { target ->
                val targetHead = target.resolve(HEAD_FILE)
                if (exists(targetHead)) {
                    readHead(targetHead, target)
                }
            },
        )
        return prepared.activeRoot.also { preparedDataRoot = it }
    }

    private fun readLegacyHead(file: File): ProductiveWorldHead {
        require(file == directory.resolve(HEAD_FILE)) {
            "Unexpected legacy productive world head path"
        }
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(file, MAX_PLAINTEXT_BYTES),
            key = key,
            maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
            associatedData = "$ROOT_DIRECTORY/$HEAD_FILE".encodeToByteArray(),
        )
        return ProductiveWorldHeadCodec.decode(plaintext)
    }

    private fun associatedData(dataRoot: File): ByteArray {
        require(dataRoot.parentFile?.parentFile?.name == SCHEMA_DIRECTORY) {
            "Productive world head must use a schema generation root"
        }
        require(dataRoot.parentFile?.name == GENERATIONS_DIRECTORY) {
            "Productive world head generation path mismatch"
        }
        return (
            "$ROOT_DIRECTORY/$SCHEMA_DIRECTORY/$GENERATIONS_DIRECTORY/" +
                "${dataRoot.name}/$HEAD_FILE"
            ).encodeToByteArray()
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
        const val SCHEMA_DIRECTORY = ".schema"
        const val GENERATIONS_DIRECTORY = "generations"
        const val HEAD_FILE = "head.pworld"
        const val KEY_ALIAS = "lifeos.productive.world.head.v1"
        const val MAX_PLAINTEXT_BYTES = 32 * 1024
        val SCHEMA_DESCRIPTOR = DurableSchemaDescriptor(
            storeId = "productive-world-head",
            currentVersion = 2,
            minimumReadableVersion = 1,
            schemaFingerprint = DurableSchemaDescriptor.fingerprintOf(
                "productive-world-head|generation-layout-v2|head-codec-v1|" +
                    "aes-gcm|generation-bound-aad"
            ),
        )
        val processMutex = Mutex()
    }
}
