package app.lifeos.core.data.language

import android.content.Context
import android.util.AtomicFile
import app.lifeos.core.data.security.VersionedPathBoundVaultSupport
import app.lifeos.core.language.LinguisticLexiconSnapshot
import app.lifeos.core.runtime.personal.DurableLanguageRuntimeHead
import app.lifeos.core.runtime.personal.LanguageRuntimeStateCodec
import app.lifeos.core.runtime.personal.LanguageRuntimeStateRepository
import java.io.File
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedLanguageRuntimeRepository(
    context: Context,
) : LanguageRuntimeStateRepository {
    private val root = context.filesDir.resolve(ROOT_DIRECTORY)
    private val snapshots = root.resolve(SNAPSHOT_DIRECTORY)
    private val headFile = root.resolve(HEAD_FILE)
    private val key: SecretKey by lazy {
        VersionedPathBoundVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun saveSnapshot(snapshot: LinguisticLexiconSnapshot): Unit =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                val file = snapshotFile(snapshot.fingerprint)
                if (exists(file)) {
                    require(readSnapshot(file, snapshot.fingerprint) == snapshot) {
                        "Language runtime snapshot identity collision"
                    }
                    return@withLock
                }
                val encrypted = VersionedPathBoundVaultSupport.encrypt(
                    plaintext = LanguageRuntimeStateCodec.encodeSnapshot(snapshot),
                    key = key,
                    containerVersion = CONTAINER_VERSION,
                    codecVersion = SNAPSHOT_CODEC_VERSION,
                    maxPlaintextBytes = LanguageRuntimeStateCodec.MAX_SNAPSHOT_BYTES,
                    associatedData = snapshotAssociatedData(snapshot.fingerprint),
                )
                VersionedPathBoundVaultSupport.atomicWrite(AtomicFile(file), encrypted)
                require(readSnapshot(file, snapshot.fingerprint) == snapshot)
            }
        }

    override suspend fun loadSnapshot(
        fingerprint: String,
    ): LinguisticLexiconSnapshot? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            val file = snapshotFile(fingerprint)
            if (!exists(file)) return@withLock null
            readSnapshot(file, fingerprint)
        }
    }

    override suspend fun loadHead(): DurableLanguageRuntimeHead? =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                if (!exists(headFile)) return@withLock null
                readHead()
            }
        }

    override suspend fun compareAndSetHead(
        expectedRevision: Long?,
        next: DurableLanguageRuntimeHead,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            val current = if (exists(headFile)) readHead() else null
            if (current?.headRevision != expectedRevision) return@withLock false
            require(next.headRevision == (expectedRevision ?: 0L) + 1L) {
                "Language runtime head must advance exactly once"
            }
            require(next.previousActiveSnapshotFingerprint == current?.activeSnapshotFingerprint) {
                "Language runtime head previous-active binding mismatch"
            }
            val active = requireNotNull(loadSnapshotUnlocked(next.activeSnapshotFingerprint)) {
                "Language runtime head cannot point to a missing snapshot"
            }
            require(active.revision == next.activeLexiconRevision) {
                "Language runtime head/snapshot revision mismatch"
            }
            current?.let {
                require(readHead() == it) {
                    "Language runtime head changed during CAS verification"
                }
            }
            val encrypted = VersionedPathBoundVaultSupport.encrypt(
                plaintext = LanguageRuntimeStateCodec.encodeHead(next),
                key = key,
                containerVersion = CONTAINER_VERSION,
                codecVersion = HEAD_CODEC_VERSION,
                maxPlaintextBytes = LanguageRuntimeStateCodec.MAX_HEAD_BYTES,
                associatedData = headAssociatedData(),
            )
            VersionedPathBoundVaultSupport.atomicWrite(AtomicFile(headFile), encrypted)
            require(readHead() == next)
            true
        }
    }

    private fun loadSnapshotUnlocked(fingerprint: String): LinguisticLexiconSnapshot? {
        val file = snapshotFile(fingerprint)
        if (!exists(file)) return null
        return readSnapshot(file, fingerprint)
    }

    private fun readSnapshot(
        file: File,
        expectedFingerprint: String,
    ): LinguisticLexiconSnapshot {
        require(file == snapshotFile(expectedFingerprint)) {
            "Language runtime snapshot physical path mismatch"
        }
        val plaintext = VersionedPathBoundVaultSupport.decrypt(
            container = VersionedPathBoundVaultSupport.readAtomic(
                AtomicFile(file),
                LanguageRuntimeStateCodec.MAX_SNAPSHOT_BYTES,
            ),
            key = key,
            expectedContainerVersion = CONTAINER_VERSION,
            expectedCodecVersion = SNAPSHOT_CODEC_VERSION,
            maxPlaintextBytes = LanguageRuntimeStateCodec.MAX_SNAPSHOT_BYTES,
            associatedData = snapshotAssociatedData(expectedFingerprint),
        )
        return LanguageRuntimeStateCodec.decodeSnapshot(plaintext).also { snapshot ->
            require(snapshot.fingerprint == expectedFingerprint) {
                "Language runtime snapshot payload/path identity mismatch"
            }
        }
    }

    private fun readHead(): DurableLanguageRuntimeHead {
        val plaintext = VersionedPathBoundVaultSupport.decrypt(
            container = VersionedPathBoundVaultSupport.readAtomic(
                AtomicFile(headFile),
                LanguageRuntimeStateCodec.MAX_HEAD_BYTES,
            ),
            key = key,
            expectedContainerVersion = CONTAINER_VERSION,
            expectedCodecVersion = HEAD_CODEC_VERSION,
            maxPlaintextBytes = LanguageRuntimeStateCodec.MAX_HEAD_BYTES,
            associatedData = headAssociatedData(),
        )
        return LanguageRuntimeStateCodec.decodeHead(plaintext)
    }

    private fun snapshotFile(fingerprint: String): File {
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid language runtime snapshot fingerprint"
        }
        return snapshots.resolve(sha256(fingerprint) + SNAPSHOT_SUFFIX)
    }

    private fun snapshotAssociatedData(fingerprint: String): ByteArray =
        "$ROOT_DIRECTORY/$SNAPSHOT_DIRECTORY/$fingerprint".encodeToByteArray()

    private fun headAssociatedData(): ByteArray =
        "$ROOT_DIRECTORY/$HEAD_FILE".encodeToByteArray()

    private fun ensureDirectories() {
        check(root.isDirectory || root.mkdirs()) {
            "Language runtime vault unavailable"
        }
        check(snapshots.isDirectory || snapshots.mkdirs()) {
            "Language runtime snapshot vault unavailable"
        }
    }

    private fun exists(file: File): Boolean =
        file.exists() || File(file.path + ".bak").exists()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val ROOT_DIRECTORY = "language-runtime-vault"
        const val SNAPSHOT_DIRECTORY = "snapshots"
        const val SNAPSHOT_SUFFIX = ".langsnap"
        const val HEAD_FILE = "head.langruntime"
        const val KEY_ALIAS = "lifeos.language.runtime.v1"
        const val CONTAINER_VERSION = 1
        const val SNAPSHOT_CODEC_VERSION = 1
        const val HEAD_CODEC_VERSION = 1
        val processMutex = Mutex()
    }
}
