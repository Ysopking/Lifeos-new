package app.lifeos.core.data.cognition

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.CognitiveModuleHead
import app.lifeos.core.runtime.CognitiveModuleSnapshot
import app.lifeos.core.runtime.CognitiveModuleSnapshotRepository
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

class EncryptedCognitiveModuleSnapshotRepository(
    context: Context,
) : CognitiveModuleSnapshotRepository {
    private val root = context.filesDir.resolve(ROOT_DIRECTORY)
    private val snapshots = root.resolve(SNAPSHOT_DIRECTORY)
    private val headFile = root.resolve(HEAD_FILE)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun save(snapshot: CognitiveModuleSnapshot): Unit =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                val file = snapshotFile(snapshot.id)
                if (exists(file)) {
                    require(readSnapshot(file, snapshot.id) == snapshot)
                    return@withLock
                }
                EncryptedLedgerVaultSupport.atomicWrite(
                    file,
                    EncryptedLedgerVaultSupport.encrypt(
                        plaintext = Codec.encodeSnapshot(snapshot),
                        key = key,
                        maxPlaintextBytes = MAX_SNAPSHOT_BYTES,
                        associatedData = snapshotAssociatedData(snapshot.id),
                    ),
                )
                require(readSnapshot(file, snapshot.id) == snapshot)
            }
        }

    override suspend fun load(id: String): CognitiveModuleSnapshot? =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                val file = snapshotFile(id)
                if (!exists(file)) return@withLock null
                readSnapshot(file, id)
            }
        }

    override suspend fun loadHead(): CognitiveModuleHead? =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                if (!exists(headFile)) return@withLock null
                readHead()
            }
        }

    override suspend fun compareAndSetHead(
        expectedRevision: Long?,
        next: CognitiveModuleHead,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            val current = if (exists(headFile)) readHead() else null
            if (current?.revision != expectedRevision) return@withLock false
            require(next.revision == (expectedRevision ?: 0L) + 1L)
            val target = requireNotNull(loadSnapshotUnlocked(next.activeSnapshotId)) {
                "Cognitive module head cannot point to missing snapshot"
            }
            require(target.revision == next.revision)
            current?.let { require(readHead() == it) }
            EncryptedLedgerVaultSupport.atomicWrite(
                headFile,
                EncryptedLedgerVaultSupport.encrypt(
                    plaintext = Codec.encodeHead(next),
                    key = key,
                    maxPlaintextBytes = MAX_HEAD_BYTES,
                    associatedData = headAssociatedData(),
                ),
            )
            require(readHead() == next)
            true
        }
    }

    private fun loadSnapshotUnlocked(id: String): CognitiveModuleSnapshot? {
        val file = snapshotFile(id)
        if (!exists(file)) return null
        return readSnapshot(file, id)
    }

    private fun readSnapshot(
        file: File,
        expectedId: String,
    ): CognitiveModuleSnapshot {
        require(file == snapshotFile(expectedId))
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(file, MAX_SNAPSHOT_BYTES),
            key = key,
            maxPlaintextBytes = MAX_SNAPSHOT_BYTES,
            associatedData = snapshotAssociatedData(expectedId),
        )
        val decoded = Codec.decodeSnapshot(plaintext)
        require(decoded.id == expectedId)
        return decoded
    }

    private fun readHead(): CognitiveModuleHead {
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(headFile, MAX_HEAD_BYTES),
            key = key,
            maxPlaintextBytes = MAX_HEAD_BYTES,
            associatedData = headAssociatedData(),
        )
        return Codec.decodeHead(plaintext)
    }

    private fun snapshotFile(id: String): File {
        require(id.startsWith("cognitive-modules:"))
        return snapshots.resolve(sha256(id) + SNAPSHOT_SUFFIX)
    }

    private fun snapshotAssociatedData(id: String): ByteArray =
        "$ROOT_DIRECTORY/$SNAPSHOT_DIRECTORY/$id".encodeToByteArray()

    private fun headAssociatedData(): ByteArray =
        "$ROOT_DIRECTORY/$HEAD_FILE".encodeToByteArray()

    private fun ensureDirectories() {
        check(root.isDirectory || root.mkdirs())
        check(snapshots.isDirectory || snapshots.mkdirs())
    }

    private fun exists(file: File): Boolean =
        file.exists() || File("${file.path}.bak").exists()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val ROOT_DIRECTORY = "cognitive-module-vault"
        const val SNAPSHOT_DIRECTORY = "snapshots"
        const val SNAPSHOT_SUFFIX = ".modules"
        const val HEAD_FILE = "head.modules"
        const val KEY_ALIAS = "lifeos.cognitive.modules.v1"
        const val MAX_SNAPSHOT_BYTES = 1024 * 1024
        const val MAX_HEAD_BYTES = 32 * 1024
        val processMutex = Mutex()
    }
}

private object Codec {
    private const val SNAPSHOT_VERSION = 1
    private const val HEAD_VERSION = 1
    private const val MAX_MODULES = 1024

    fun encodeSnapshot(snapshot: CognitiveModuleSnapshot): ByteArray =
        ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(SNAPSHOT_VERSION)
                data.writeUTF(snapshot.id)
                data.writeLong(snapshot.revision)
                data.writeUTF(snapshot.extensionSnapshotId)
                data.writeBoolean(snapshot.predecessorSnapshotId != null)
                snapshot.predecessorSnapshotId?.let(data::writeUTF)
                data.writeInt(snapshot.moduleFingerprints.size)
                snapshot.moduleFingerprints.forEach(data::writeUTF)
            }
            output.toByteArray()
        }

    fun decodeSnapshot(bytes: ByteArray): CognitiveModuleSnapshot =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == SNAPSHOT_VERSION)
            val id = input.readUTF()
            val revision = input.readLong()
            val extensionSnapshotId = input.readUTF()
            val predecessor = if (input.readBoolean()) input.readUTF() else null
            val count = input.readInt().also { require(it in 1..MAX_MODULES) }
            val fingerprints = List(count) { input.readUTF() }
            require(input.available() == 0)
            CognitiveModuleSnapshot.restore(
                id = id,
                revision = revision,
                extensionSnapshotId = extensionSnapshotId,
                moduleFingerprints = fingerprints,
                predecessorSnapshotId = predecessor,
            )
        }

    fun encodeHead(head: CognitiveModuleHead): ByteArray =
        ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(HEAD_VERSION)
                data.writeLong(head.revision)
                data.writeUTF(head.activeSnapshotId)
                data.writeUTF(head.fingerprint)
            }
            output.toByteArray()
        }

    fun decodeHead(bytes: ByteArray): CognitiveModuleHead =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == HEAD_VERSION)
            val head = CognitiveModuleHead(
                revision = input.readLong(),
                activeSnapshotId = input.readUTF(),
                fingerprint = input.readUTF(),
            )
            require(input.available() == 0)
            head
        }
}
