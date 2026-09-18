package app.lifeos.core.data.extension

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.extension.CoefficientSchemaFingerprint
import app.lifeos.core.runtime.extension.ExtensionAbiVersion
import app.lifeos.core.runtime.extension.ExtensionEntrypoint
import app.lifeos.core.runtime.extension.ExtensionId
import app.lifeos.core.runtime.extension.ExtensionKind
import app.lifeos.core.runtime.extension.ExtensionManifest
import app.lifeos.core.runtime.extension.ExtensionRegistrationMode
import app.lifeos.core.runtime.extension.ExtensionRegistryEntry
import app.lifeos.core.runtime.extension.ExtensionRegistryHead
import app.lifeos.core.runtime.extension.ExtensionRegistryHeadRepository
import app.lifeos.core.runtime.extension.ExtensionRegistrySnapshot
import app.lifeos.core.runtime.extension.ExtensionRegistrySnapshotRepository
import app.lifeos.core.runtime.extension.ExtensionRevisionRef
import app.lifeos.core.runtime.extension.ExtensionVersion
import app.lifeos.core.runtime.extension.ExtensionWorldContract
import app.lifeos.core.runtime.extension.ProjectionContractFingerprint
import app.lifeos.core.runtime.extension.WorldEquationVersion
import app.lifeos.core.runtime.extension.WorldNodeSchemaVersion
import app.lifeos.core.runtime.extension.WorldSignalSchemaVersion
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedExtensionRegistrySnapshotRepository(
    context: Context,
) : ExtensionRegistrySnapshotRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY).resolve(SNAPSHOT_DIRECTORY)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun save(snapshot: ExtensionRegistrySnapshot): Unit =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val target = fileFor(snapshot.id)
                if (exists(target)) {
                    require(read(target) == snapshot) {
                        "Extension registry snapshot id collision"
                    }
                    return@withLock
                }
                val bytes = ExtensionRegistryCodec.encodeSnapshot(snapshot)
                EncryptedLedgerVaultSupport.atomicWrite(
                    target,
                    EncryptedLedgerVaultSupport.encrypt(
                        bytes,
                        key,
                        MAX_SNAPSHOT_BYTES,
                        associatedData(target),
                    ),
                )
                require(read(target) == snapshot)
            }
        }

    override suspend fun load(id: String): ExtensionRegistrySnapshot? =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val target = fileFor(id)
                if (!exists(target)) return@withLock null
                read(target).also { require(it.id == id) }
            }
        }

    private fun read(file: File): ExtensionRegistrySnapshot {
        val bytes = EncryptedLedgerVaultSupport.decrypt(
            EncryptedLedgerVaultSupport.readAtomic(file, MAX_SNAPSHOT_BYTES),
            key,
            MAX_SNAPSHOT_BYTES,
            associatedData(file),
        )
        val snapshot = ExtensionRegistryCodec.decodeSnapshot(bytes)
        require(file == fileFor(snapshot.id)) {
            "Extension registry snapshot physical-path binding mismatch"
        }
        return snapshot
    }

    private fun fileFor(id: String): File {
        require(id.startsWith("extension-registry:"))
        return directory.resolve(sha256(id) + SNAPSHOT_SUFFIX)
    }

    private fun associatedData(file: File): ByteArray =
        "$ROOT_DIRECTORY/$SNAPSHOT_DIRECTORY/${file.name}".encodeToByteArray()

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs())
    }

    private fun exists(file: File): Boolean =
        file.exists() || File("${file.path}.bak").exists()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ROOT_DIRECTORY = "extension-registry-vault"
        const val SNAPSHOT_DIRECTORY = "snapshots"
        const val SNAPSHOT_SUFFIX = ".extension"
        const val KEY_ALIAS = "lifeos.extension.registry.v1"
        const val MAX_SNAPSHOT_BYTES = 1024 * 1024
        val processMutex = Mutex()
    }
}

class EncryptedExtensionRegistryHeadRepository(
    context: Context,
) : ExtensionRegistryHeadRepository {
    private val root = context.filesDir.resolve(ROOT_DIRECTORY)
    private val headFile = root.resolve(HEAD_FILE)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun load(): ExtensionRegistryHead? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            if (!exists(headFile)) return@withLock null
            readHead()
        }
    }

    override suspend fun compareAndSet(
        expectedRevision: Long?,
        next: ExtensionRegistryHead,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val current = if (exists(headFile)) readHead() else null
            if (current?.revision != expectedRevision) return@withLock false
            require(next.revision == (expectedRevision ?: 0L) + 1L)
            require(next.predecessorSnapshotId == current?.activeSnapshotId)
            writeHead(next)
            require(readHead() == next)
            true
        }
    }

    private fun readHead(): ExtensionRegistryHead {
        val bytes = EncryptedLedgerVaultSupport.decrypt(
            EncryptedLedgerVaultSupport.readAtomic(headFile, MAX_HEAD_BYTES),
            key,
            MAX_HEAD_BYTES,
            associatedData(),
        )
        return ExtensionRegistryCodec.decodeHead(bytes)
    }

    private fun writeHead(head: ExtensionRegistryHead) {
        val bytes = ExtensionRegistryCodec.encodeHead(head)
        EncryptedLedgerVaultSupport.atomicWrite(
            headFile,
            EncryptedLedgerVaultSupport.encrypt(
                bytes,
                key,
                MAX_HEAD_BYTES,
                associatedData(),
            ),
        )
    }

    private fun associatedData(): ByteArray =
        "$ROOT_DIRECTORY/$HEAD_FILE".encodeToByteArray()

    private fun ensureDirectory() {
        check(root.isDirectory || root.mkdirs())
    }

    private fun exists(file: File): Boolean =
        file.exists() || File("${file.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "extension-registry-vault"
        const val HEAD_FILE = "head.extension"
        const val KEY_ALIAS = "lifeos.extension.registry.v1"
        const val MAX_HEAD_BYTES = 32 * 1024
        val processMutex = Mutex()
    }
}

private object ExtensionRegistryCodec {
    private const val SNAPSHOT_VERSION = 1
    private const val HEAD_VERSION = 1
    private const val MAX_ENTRIES = 256
    private const val MAX_ENTRYPOINTS = 64
    private const val MAX_DEPENDENCIES = 64

    fun encodeSnapshot(snapshot: ExtensionRegistrySnapshot): ByteArray =
        ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(SNAPSHOT_VERSION)
                data.writeUTF(snapshot.id)
                data.writeInt(snapshot.entries.size)
                snapshot.entries.forEach { entry ->
                    val manifest = entry.manifest
                    data.writeUTF(manifest.extensionId.value)
                    data.writeUTF(manifest.version.value)
                    data.writeInt(manifest.abiVersion.major)
                    data.writeInt(manifest.abiVersion.minor)
                    data.writeUTF(manifest.kind.name)
                    data.writeUTF(manifest.providerId)
                    data.writeUTF(manifest.registrationMode.name)
                    data.writeInt(manifest.entrypoints.size)
                    manifest.entrypoints
                        .sortedWith(compareBy({ it.contract }, { it.implementationId }))
                        .forEach {
                            data.writeUTF(it.contract)
                            data.writeUTF(it.implementationId)
                        }
                    val contract = entry.worldContract
                    data.writeInt(contract.worldSignalSchemaVersion.major)
                    data.writeInt(contract.worldSignalSchemaVersion.minor)
                    data.writeInt(contract.worldNodeSchemaVersion.major)
                    data.writeInt(contract.worldNodeSchemaVersion.minor)
                    data.writeUTF(contract.worldEquationVersion.id)
                    data.writeInt(contract.worldEquationVersion.major)
                    data.writeInt(contract.worldEquationVersion.minor)
                    data.writeUTF(contract.coefficientSchemaFingerprint.value)
                    data.writeUTF(contract.projectionContractFingerprint.value)
                    data.writeInt(entry.dependencies.size)
                    entry.dependencies
                        .sortedWith(compareBy({ it.extensionId.value }, { it.version.value }))
                        .forEach {
                            data.writeUTF(it.extensionId.value)
                            data.writeUTF(it.version.value)
                        }
                }
                data.writeInt(snapshot.topologicalOrder.size)
                snapshot.topologicalOrder.forEach {
                    data.writeUTF(it.extensionId.value)
                    data.writeUTF(it.version.value)
                }
            }
            output.toByteArray()
        }

    fun decodeSnapshot(bytes: ByteArray): ExtensionRegistrySnapshot {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == SNAPSHOT_VERSION)
        val encodedId = input.readUTF()
        val count = input.readInt().also { require(it in 1..MAX_ENTRIES) }
        val entries = List(count) {
            val id = ExtensionId(input.readUTF())
            val version = ExtensionVersion(input.readUTF())
            val abi = ExtensionAbiVersion(input.readInt(), input.readInt())
            val kind = ExtensionKind.valueOf(input.readUTF())
            val providerId = input.readUTF()
            val mode = ExtensionRegistrationMode.valueOf(input.readUTF())
            val entrypoints = buildSet {
                repeat(input.readInt().also { require(it in 1..MAX_ENTRYPOINTS) }) {
                    add(ExtensionEntrypoint(input.readUTF(), input.readUTF()))
                }
            }
            val contract = ExtensionWorldContract(
                worldSignalSchemaVersion = WorldSignalSchemaVersion(input.readInt(), input.readInt()),
                worldNodeSchemaVersion = WorldNodeSchemaVersion(input.readInt(), input.readInt()),
                worldEquationVersion = WorldEquationVersion(
                    input.readUTF(),
                    input.readInt(),
                    input.readInt(),
                ),
                coefficientSchemaFingerprint = CoefficientSchemaFingerprint(input.readUTF()),
                projectionContractFingerprint = ProjectionContractFingerprint(input.readUTF()),
            )
            val dependencies = buildSet {
                repeat(input.readInt().also { require(it in 0..MAX_DEPENDENCIES) }) {
                    add(
                        ExtensionRevisionRef(
                            ExtensionId(input.readUTF()),
                            ExtensionVersion(input.readUTF()),
                        )
                    )
                }
            }
            ExtensionRegistryEntry(
                manifest = ExtensionManifest(
                    extensionId = id,
                    version = version,
                    abiVersion = abi,
                    kind = kind,
                    providerId = providerId,
                    entrypoints = entrypoints,
                    registrationMode = mode,
                ),
                worldContract = contract,
                dependencies = dependencies,
            )
        }
        val orderCount = input.readInt().also { require(it == count) }
        val encodedOrder = List(orderCount) {
            ExtensionRevisionRef(
                ExtensionId(input.readUTF()),
                ExtensionVersion(input.readUTF()),
            )
        }
        require(input.available() == 0)
        val snapshot = ExtensionRegistrySnapshot.create(entries)
        require(snapshot.id == encodedId)
        require(snapshot.topologicalOrder == encodedOrder)
        return snapshot
    }

    fun encodeHead(head: ExtensionRegistryHead): ByteArray =
        ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(HEAD_VERSION)
                data.writeLong(head.revision)
                data.writeUTF(head.activeSnapshotId)
                data.writeBoolean(head.predecessorSnapshotId != null)
                head.predecessorSnapshotId?.let(data::writeUTF)
                data.writeUTF(head.snapshotFingerprint)
                data.writeUTF(head.fingerprint)
            }
            output.toByteArray()
        }

    fun decodeHead(bytes: ByteArray): ExtensionRegistryHead {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == HEAD_VERSION)
        val head = ExtensionRegistryHead.restore(
            revision = input.readLong(),
            activeSnapshotId = input.readUTF(),
            predecessorSnapshotId = if (input.readBoolean()) input.readUTF() else null,
            snapshotFingerprint = input.readUTF(),
            fingerprint = input.readUTF(),
        )
        require(input.available() == 0)
        return head
    }
}
