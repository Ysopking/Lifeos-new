package app.lifeos.core.data.web

import android.content.Context
import android.util.AtomicFile
import app.lifeos.core.data.security.VaultAssociatedData
import app.lifeos.core.data.security.VersionedPathBoundVaultSupport
import app.lifeos.core.runtime.web.WebSessionCodec
import app.lifeos.core.runtime.web.WebSessionId
import app.lifeos.core.runtime.web.WebSessionLoadReport
import app.lifeos.core.runtime.web.WebSessionRepository
import app.lifeos.core.runtime.web.WebSessionSnapshot
import app.lifeos.core.runtime.web.WebSessionWriteResult
import java.io.File
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * B402 path-bound encrypted persistence for origin-scoped Web session revisions.
 *
 * The existing unified versioned vault owns AES-GCM/key handling. Ciphertext associated data binds
 * every payload to its exact physical path. B402 does not authorize network access, apply cookies,
 * construct Authorization headers, or activate a session in a browser transport.
 */
class EncryptedWebSessionRepository(
    context: Context,
) : WebSessionRepository {
    private val root = context.filesDir.resolve(ROOT_DIRECTORY)
    private val sessionsDirectory = root.resolve(SESSIONS_DIRECTORY)
    private val key: SecretKey by lazy {
        VersionedPathBoundVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun save(
        snapshot: WebSessionSnapshot,
    ): WebSessionWriteResult = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            val target = sessionFile(snapshot)
            if (exists(target)) {
                val existing = readValidated(target)
                require(existing == snapshot) {
                    "Web session physical identity collision"
                }
                return@withLock WebSessionWriteResult.Duplicate(existing)
            }

            val history = validatedSessionHistory(snapshot.sessionId)
            val current = history.lastOrNull()
            require(snapshot.revision == (current?.revision ?: 0L) + 1L) {
                "Web session persisted revision is not contiguous"
            }
            require(snapshot.predecessorRevisionId == current?.revisionId) {
                "Web session persisted predecessor does not match session head"
            }

            target.parentFile?.let { parent ->
                check(parent.isDirectory || parent.mkdirs()) {
                    "Web session directory unavailable"
                }
            }
            writeSnapshot(target, snapshot)
            require(readValidated(target) == snapshot) {
                "Web session persistence verification failed"
            }
            WebSessionWriteResult.Stored(snapshot)
        }
    }

    override suspend fun loadLatest(
        sessionId: WebSessionId,
    ): WebSessionSnapshot? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            validatedSessionHistory(sessionId).lastOrNull()
        }
    }

    override suspend fun loadReport(): WebSessionLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                val snapshots = mutableListOf<WebSessionSnapshot>()
                val unreadable = mutableListOf<String>()
                logicalSessionFiles(sessionsDirectory, recursive = true).forEach { file ->
                    runCatching { readValidated(file) }
                        .onSuccess(snapshots::add)
                        .onFailure {
                            unreadable += file.relativeTo(root).invariantSeparatorsPath
                        }
                }
                WebSessionLoadReport(
                    snapshots = snapshots
                        .distinctBy { it.revisionId }
                        .sortedWith(
                            compareBy<WebSessionSnapshot> { it.sessionId.value }
                                .thenBy { it.revision }
                        ),
                    unreadableEntries = unreadable.distinct().sorted(),
                )
            }
        }

    private fun validatedSessionHistory(
        sessionId: WebSessionId,
    ): List<WebSessionSnapshot> {
        val snapshots = logicalSessionFiles(sessionDirectory(sessionId))
            .map(::readValidated)
            .sortedBy { it.revision }

        snapshots.forEachIndexed { index, snapshot ->
            require(snapshot.sessionId == sessionId) {
                "Web session directory contains another session"
            }
            require(snapshot.revision == index.toLong() + 1L) {
                "Web session history has a missing/non-contiguous revision"
            }
            val expectedPredecessor =
                if (index == 0) null else snapshots[index - 1].revisionId
            require(snapshot.predecessorRevisionId == expectedPredecessor) {
                "Web session history predecessor mismatch"
            }
        }
        return snapshots
    }

    private fun writeSnapshot(
        target: File,
        snapshot: WebSessionSnapshot,
    ) {
        val plaintext = WebSessionCodec.encode(snapshot)
        val encrypted = VersionedPathBoundVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            containerVersion = CONTAINER_VERSION,
            codecVersion = WebSessionCodec.CODEC_VERSION,
            maxPlaintextBytes = WebSessionCodec.MAX_PAYLOAD_BYTES,
            associatedData = associatedData(target),
        )
        VersionedPathBoundVaultSupport.atomicWrite(AtomicFile(target), encrypted)
    }

    private fun readValidated(file: File): WebSessionSnapshot {
        val container = VersionedPathBoundVaultSupport.readAtomic(
            AtomicFile(file),
            WebSessionCodec.MAX_PAYLOAD_BYTES,
        )
        val plaintext = VersionedPathBoundVaultSupport.decrypt(
            container = container,
            key = key,
            expectedContainerVersion = CONTAINER_VERSION,
            expectedCodecVersion = WebSessionCodec.CODEC_VERSION,
            maxPlaintextBytes = WebSessionCodec.MAX_PAYLOAD_BYTES,
            associatedData = associatedData(file),
        )
        val snapshot = WebSessionCodec.decode(plaintext)

        require(file.parentFile?.parentFile == sessionsDirectory) {
            "Web session segment is outside the session directory"
        }
        require(file.parentFile?.name == sha256(snapshot.sessionId.value)) {
            "Web session id does not match physical path"
        }
        require(parseRevision(file) == snapshot.revision) {
            "Web session revision does not match physical path"
        }
        require(parseRevisionDigest(file) == sha256(snapshot.revisionId.value)) {
            "Web session revision id does not match physical path"
        }
        return snapshot
    }

    private fun sessionDirectory(sessionId: WebSessionId): File =
        sessionsDirectory.resolve(sha256(sessionId.value))

    private fun sessionFile(snapshot: WebSessionSnapshot): File =
        sessionDirectory(snapshot.sessionId).resolve(
            REVISION_PREFIX +
                snapshot.revision.toString().padStart(REVISION_WIDTH, '0') +
                ID_SEPARATOR +
                sha256(snapshot.revisionId.value) +
                FILE_SUFFIX
        )

    private fun parseRevision(file: File): Long {
        val body = file.name.removePrefix(REVISION_PREFIX).removeSuffix(FILE_SUFFIX)
        val index = body.indexOf(ID_SEPARATOR)
        require(index > 0) { "Invalid Web session segment name" }
        return requireNotNull(body.substring(0, index).toLongOrNull()) {
            "Invalid Web session revision"
        }
    }

    private fun parseRevisionDigest(file: File): String {
        val body = file.name.removePrefix(REVISION_PREFIX).removeSuffix(FILE_SUFFIX)
        val index = body.indexOf(ID_SEPARATOR)
        require(index > 0 && index < body.lastIndex) {
            "Invalid Web session segment name"
        }
        return body.substring(index + ID_SEPARATOR.length).also {
            require(it.matches(Regex("[0-9a-f]{64}"))) {
                "Invalid Web session revision digest in path"
            }
        }
    }

    private fun logicalSessionFiles(
        directory: File,
        recursive: Boolean = false,
    ): List<File> {
        if (!directory.exists()) return emptyList()
        val files = if (recursive) {
            directory.walkTopDown().filter { it.isFile }.toList()
        } else {
            directory.listFiles().orEmpty().filter { it.isFile }
        }
        return files.asSequence()
            .mapNotNull { file ->
                when {
                    isSessionSegment(file.name) -> file
                    isSessionBackup(file.name) ->
                        File(file.path.removeSuffix(ATOMIC_BACKUP_SUFFIX))
                    else -> null
                }
            }
            .distinctBy { it.path }
            .sortedBy { it.path }
            .toList()
    }

    private fun isSessionSegment(name: String): Boolean =
        name.startsWith(REVISION_PREFIX) && name.endsWith(FILE_SUFFIX)

    private fun isSessionBackup(name: String): Boolean =
        name.startsWith(REVISION_PREFIX) &&
            name.endsWith(FILE_SUFFIX + ATOMIC_BACKUP_SUFFIX)

    private fun associatedData(file: File): ByteArray =
        VaultAssociatedData.forPath(
            domain = AAD_DOMAIN,
            root = root,
            file = file,
        )

    private fun exists(file: File): Boolean =
        file.exists() || File(file.path + ATOMIC_BACKUP_SUFFIX).exists()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectories() {
        check(root.isDirectory || root.mkdirs()) {
            "Web session vault unavailable"
        }
        check(sessionsDirectory.isDirectory || sessionsDirectory.mkdirs()) {
            "Web session segment directory unavailable"
        }
    }

    private companion object {
        const val ROOT_DIRECTORY = "web-session-vault"
        const val SESSIONS_DIRECTORY = "sessions"
        const val REVISION_PREFIX = "revision-"
        const val REVISION_WIDTH = 20
        const val ID_SEPARATOR = "--"
        const val FILE_SUFFIX = ".wsession"
        const val ATOMIC_BACKUP_SUFFIX = ".bak"
        const val CONTAINER_VERSION = 1
        const val KEY_ALIAS = "lifeos.web.session.v1"
        const val AAD_DOMAIN = "lifeos.web.session.v1"
        val processMutex = Mutex()
    }
}
