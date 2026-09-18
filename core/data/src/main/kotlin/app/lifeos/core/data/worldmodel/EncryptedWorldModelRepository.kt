package app.lifeos.core.data.worldmodel

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.level7.WorldModelHead
import app.lifeos.core.runtime.level7.WorldModelRepository
import app.lifeos.core.runtime.level7.WorldModelSnapshot
import app.lifeos.core.runtime.level7.WorldTransitionRule
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

class EncryptedWorldModelRepository(
    context: Context,
) : WorldModelRepository {
    private val root = context.filesDir.resolve(ROOT_DIRECTORY)
    private val snapshots = root.resolve(SNAPSHOT_DIRECTORY)
    private val headFile = root.resolve(HEAD_FILE)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun saveSnapshot(snapshot: WorldModelSnapshot): Unit =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                val file = snapshotFile(snapshot.id)
                if (exists(file)) {
                    require(readSnapshot(file, snapshot.id) == snapshot) {
                        "WorldModel snapshot id collision"
                    }
                    return@withLock
                }
                val encrypted = EncryptedLedgerVaultSupport.encrypt(
                    plaintext = Codec.encodeSnapshot(snapshot),
                    key = key,
                    maxPlaintextBytes = MAX_SNAPSHOT_BYTES,
                    associatedData = snapshotAssociatedData(snapshot.id),
                )
                EncryptedLedgerVaultSupport.atomicWrite(file, encrypted)
                require(readSnapshot(file, snapshot.id) == snapshot)
            }
        }

    override suspend fun loadSnapshot(id: String): WorldModelSnapshot? =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                val file = snapshotFile(id)
                if (!exists(file)) return@withLock null
                readSnapshot(file, id)
            }
        }

    override suspend fun loadHead(): WorldModelHead? =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                if (!exists(headFile)) return@withLock null
                readHead()
            }
        }

    override suspend fun compareAndSetHead(
        expectedRevision: Long?,
        next: WorldModelHead,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            val current = if (exists(headFile)) readHead() else null
            if (current?.revision != expectedRevision) return@withLock false
            require(next.revision == (expectedRevision ?: 0L) + 1L) {
                "WorldModel head revision must advance exactly once"
            }
            require(next.predecessorSnapshotId == current?.activeSnapshotId) {
                "WorldModel predecessor does not match current active snapshot"
            }
            val target = requireNotNull(loadSnapshotUnlocked(next.activeSnapshotId)) {
                "WorldModel head cannot point to a missing snapshot"
            }
            require(target.revision == next.revision) {
                "WorldModel head/snapshot revision mismatch"
            }
            current?.let {
                require(readHead() == it) {
                    "WorldModel head changed during CAS verification"
                }
            }
            val encrypted = EncryptedLedgerVaultSupport.encrypt(
                plaintext = Codec.encodeHead(next),
                key = key,
                maxPlaintextBytes = MAX_HEAD_BYTES,
                associatedData = headAssociatedData(),
            )
            EncryptedLedgerVaultSupport.atomicWrite(headFile, encrypted)
            require(readHead() == next)
            true
        }
    }

    private fun loadSnapshotUnlocked(id: String): WorldModelSnapshot? {
        val file = snapshotFile(id)
        if (!exists(file)) return null
        return readSnapshot(file, id)
    }

    private fun readSnapshot(file: File, expectedId: String): WorldModelSnapshot {
        require(file == snapshotFile(expectedId)) {
            "WorldModel snapshot physical path mismatch"
        }
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(file, MAX_SNAPSHOT_BYTES),
            key = key,
            maxPlaintextBytes = MAX_SNAPSHOT_BYTES,
            associatedData = snapshotAssociatedData(expectedId),
        )
        val decoded = Codec.decodeSnapshot(plaintext)
        require(decoded.id == expectedId) {
            "WorldModel snapshot payload/path identity mismatch"
        }
        return decoded
    }

    private fun readHead(): WorldModelHead {
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(headFile, MAX_HEAD_BYTES),
            key = key,
            maxPlaintextBytes = MAX_HEAD_BYTES,
            associatedData = headAssociatedData(),
        )
        return Codec.decodeHead(plaintext)
    }

    private fun snapshotFile(id: String): File {
        require(id.startsWith("world-model:"))
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
        const val ROOT_DIRECTORY = "world-model-vault"
        const val SNAPSHOT_DIRECTORY = "snapshots"
        const val SNAPSHOT_SUFFIX = ".worldmodel"
        const val HEAD_FILE = "head.worldmodel"
        const val KEY_ALIAS = "lifeos.world.model.v1"
        const val MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024
        const val MAX_HEAD_BYTES = 64 * 1024
        val processMutex = Mutex()
    }
}

private object Codec {
    private const val SNAPSHOT_VERSION = 1
    private const val HEAD_VERSION = 1
    private const val MAX_GRAPH_IDS = 1024
    private const val MAX_RULES = 4096

    fun encodeSnapshot(snapshot: WorldModelSnapshot): ByteArray =
        ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(SNAPSHOT_VERSION)
                data.writeUTF(snapshot.id)
                data.writeLong(snapshot.revision)
                data.writeUTF(snapshot.equationVersion)
                data.writeBoolean(snapshot.predecessorSnapshotId != null)
                snapshot.predecessorSnapshotId?.let(data::writeUTF)
                data.writeInt(snapshot.graphCandidateIds.size)
                snapshot.graphCandidateIds.sorted().forEach(data::writeUTF)
                data.writeInt(snapshot.transitionRules.size)
                snapshot.transitionRules.forEach { rule ->
                    data.writeUTF(rule.sourceFingerprint)
                    data.writeUTF(rule.actionFingerprint)
                    data.writeUTF(rule.targetFingerprint)
                    data.writeDouble(rule.confidence)
                }
            }
            output.toByteArray()
        }

    fun decodeSnapshot(bytes: ByteArray): WorldModelSnapshot =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == SNAPSHOT_VERSION)
            val id = input.readUTF()
            val revision = input.readLong()
            val equationVersion = input.readUTF()
            val predecessor = if (input.readBoolean()) input.readUTF() else null
            val graphCount = input.readInt().also { require(it in 1..MAX_GRAPH_IDS) }
            val graphIds = List(graphCount) { input.readUTF() }
            val ruleCount = input.readInt().also { require(it in 0..MAX_RULES) }
            val rules = List(ruleCount) {
                WorldTransitionRule(
                    sourceFingerprint = input.readUTF(),
                    actionFingerprint = input.readUTF(),
                    targetFingerprint = input.readUTF(),
                    confidence = input.readDouble(),
                )
            }
            require(input.available() == 0)
            val snapshot = WorldModelSnapshot.create(
                revision = revision,
                equationVersion = equationVersion,
                graphCandidateIds = graphIds,
                transitionRules = rules,
                predecessorSnapshotId = predecessor,
            )
            require(snapshot.id == id)
            snapshot
        }

    fun encodeHead(head: WorldModelHead): ByteArray =
        ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(HEAD_VERSION)
                data.writeLong(head.revision)
                data.writeUTF(head.activeSnapshotId)
                data.writeBoolean(head.predecessorSnapshotId != null)
                head.predecessorSnapshotId?.let(data::writeUTF)
                data.writeUTF(head.fingerprint)
            }
            output.toByteArray()
        }

    fun decodeHead(bytes: ByteArray): WorldModelHead =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == HEAD_VERSION)
            val head = WorldModelHead(
                revision = input.readLong(),
                activeSnapshotId = input.readUTF(),
                predecessorSnapshotId = if (input.readBoolean()) input.readUTF() else null,
                fingerprint = input.readUTF(),
            )
            require(input.available() == 0)
            head
        }
}
