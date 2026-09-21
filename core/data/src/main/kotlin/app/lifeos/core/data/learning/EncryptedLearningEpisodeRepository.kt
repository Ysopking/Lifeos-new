package app.lifeos.core.data.learning

import android.content.Context
import app.lifeos.core.data.security.VaultAssociatedData
import app.lifeos.core.data.security.VersionedPathBoundVaultSupport
import app.lifeos.core.runtime.reasoning.LearningEpisode
import app.lifeos.core.runtime.reasoning.LearningEpisodeCodec
import app.lifeos.core.runtime.reasoning.LearningEpisodeId
import app.lifeos.core.runtime.reasoning.LearningEpisodeLoadReport
import app.lifeos.core.runtime.reasoning.LearningEpisodeRepository
import app.lifeos.core.runtime.reasoning.LearningEpisodeWriteResult
import java.io.File
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Path-bound encrypted storage for immutable B374 learning episode revisions.
 *
 * Physical identity is source-cycle hash + exact cycle revision + exact episode-id hash.
 * AES-GCM associated data binds ciphertext to that physical path so a valid container cannot be
 * transplanted to another cycle/revision/id location.
 */
class EncryptedLearningEpisodeRepository(
    context: Context,
) : LearningEpisodeRepository {
    private val root = context.filesDir.resolve(ROOT_DIRECTORY)
    private val episodesDirectory = root.resolve(EPISODES_DIRECTORY)
    private val key: SecretKey by lazy {
        VersionedPathBoundVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun save(
        episode: LearningEpisode,
    ): LearningEpisodeWriteResult = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            val target = episodeFile(episode)
            if (exists(target)) {
                val existing = readValidated(target)
                require(existing == episode) {
                    "Learning episode physical identity collision"
                }
                return@withLock LearningEpisodeWriteResult.Duplicate(existing)
            }

            val cycleEvents = logicalEpisodeFiles(cycleDirectory(episode.sourceCycleId))
                .map(::readValidated)
                .sortedBy { it.cycleRevision }
            val current = cycleEvents.lastOrNull()
            require(episode.cycleRevision == (current?.cycleRevision ?: 0L) + 1L) {
                "Learning episode persisted cycle revision is not contiguous"
            }
            require(episode.predecessorId == current?.id) {
                "Learning episode persisted predecessor does not match cycle head"
            }

            target.parentFile?.let { parent ->
                check(parent.isDirectory || parent.mkdirs()) {
                    "Learning episode cycle directory unavailable"
                }
            }
            writeEpisode(target, episode)
            require(readValidated(target) == episode) {
                "Learning episode persistence verification failed"
            }
            LearningEpisodeWriteResult.Stored(episode)
        }
    }

    override suspend fun load(
        id: LearningEpisodeId,
    ): LearningEpisode? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            logicalEpisodeFiles(episodesDirectory, recursive = true)
                .firstNotNullOfOrNull { file ->
                    runCatching { readValidated(file) }
                        .getOrNull()
                        ?.takeIf { it.id == id }
                }
        }
    }

    override suspend fun loadReport(): LearningEpisodeLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                val episodes = mutableListOf<LearningEpisode>()
                val unreadable = mutableListOf<String>()
                logicalEpisodeFiles(episodesDirectory, recursive = true).forEach { file ->
                    runCatching { readValidated(file) }
                        .onSuccess(episodes::add)
                        .onFailure { unreadable += file.relativeTo(root).invariantSeparatorsPath }
                }
                LearningEpisodeLoadReport(
                    episodes = episodes
                        .distinctBy { it.id }
                        .sortedWith(
                            compareBy<LearningEpisode> { it.sourceCycleId }
                                .thenBy { it.cycleRevision }
                                .thenBy { it.id.value }
                        ),
                    unreadableEntries = unreadable.distinct().sorted(),
                )
            }
        }

    private fun writeEpisode(
        target: File,
        episode: LearningEpisode,
    ) {
        val plaintext = LearningEpisodeCodec.encode(episode)
        val encrypted = VersionedPathBoundVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            containerVersion = CONTAINER_VERSION,
            codecVersion = LearningEpisodeCodec.CODEC_VERSION,
            maxPlaintextBytes = LearningEpisodeCodec.MAX_PAYLOAD_BYTES,
            associatedData = associatedData(target),
        )
        VersionedPathBoundVaultSupport.atomicWrite(
            android.util.AtomicFile(target),
            encrypted,
        )
    }

    private fun readValidated(file: File): LearningEpisode {
        val container = VersionedPathBoundVaultSupport.readAtomic(
            android.util.AtomicFile(file),
            LearningEpisodeCodec.MAX_PAYLOAD_BYTES,
        )
        val plaintext = VersionedPathBoundVaultSupport.decrypt(
            container = container,
            key = key,
            expectedContainerVersion = CONTAINER_VERSION,
            expectedCodecVersion = LearningEpisodeCodec.CODEC_VERSION,
            maxPlaintextBytes = LearningEpisodeCodec.MAX_PAYLOAD_BYTES,
            associatedData = associatedData(file),
        )
        val episode = LearningEpisodeCodec.decode(plaintext)
        require(file.parentFile?.parentFile == episodesDirectory) {
            "Learning episode segment is outside the episode directory"
        }
        require(file.parentFile?.name == sha256(episode.sourceCycleId)) {
            "Learning episode source cycle does not match physical path"
        }
        require(parseRevision(file) == episode.cycleRevision) {
            "Learning episode revision does not match physical path"
        }
        require(parseEpisodeDigest(file) == sha256(episode.id.value)) {
            "Learning episode id does not match physical path"
        }
        return episode
    }

    private fun cycleDirectory(sourceCycleId: String): File =
        episodesDirectory.resolve(sha256(sourceCycleId))

    private fun episodeFile(episode: LearningEpisode): File =
        cycleDirectory(episode.sourceCycleId).resolve(
            REVISION_PREFIX +
                episode.cycleRevision.toString().padStart(REVISION_WIDTH, '0') +
                ID_SEPARATOR +
                sha256(episode.id.value) +
                FILE_SUFFIX
        )

    private fun parseRevision(file: File): Long {
        val body = file.name.removePrefix(REVISION_PREFIX).removeSuffix(FILE_SUFFIX)
        val index = body.indexOf(ID_SEPARATOR)
        require(index > 0) { "Invalid learning episode segment name" }
        return requireNotNull(body.substring(0, index).toLongOrNull()) {
            "Invalid learning episode revision"
        }
    }

    private fun parseEpisodeDigest(file: File): String {
        val body = file.name.removePrefix(REVISION_PREFIX).removeSuffix(FILE_SUFFIX)
        val index = body.indexOf(ID_SEPARATOR)
        require(index > 0 && index < body.lastIndex) {
            "Invalid learning episode segment name"
        }
        return body.substring(index + ID_SEPARATOR.length).also {
            require(it.matches(Regex("[0-9a-f]{64}"))) {
                "Invalid learning episode id digest in path"
            }
        }
    }

    private fun logicalEpisodeFiles(
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
                    isEpisodeSegment(file.name) -> file
                    isEpisodeBackup(file.name) ->
                        File(file.path.removeSuffix(ATOMIC_BACKUP_SUFFIX))
                    else -> null
                }
            }
            .distinctBy { it.path }
            .sortedBy { it.path }
            .toList()
    }

    private fun isEpisodeSegment(name: String): Boolean =
        name.startsWith(REVISION_PREFIX) && name.endsWith(FILE_SUFFIX)

    private fun isEpisodeBackup(name: String): Boolean =
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
            "Learning episode vault unavailable"
        }
        check(episodesDirectory.isDirectory || episodesDirectory.mkdirs()) {
            "Learning episode segment directory unavailable"
        }
    }

    private companion object {
        const val ROOT_DIRECTORY = "learning-episode-ledger"
        const val EPISODES_DIRECTORY = "episodes"
        const val REVISION_PREFIX = "revision-"
        const val REVISION_WIDTH = 20
        const val ID_SEPARATOR = "--"
        const val FILE_SUFFIX = ".lepisode"
        const val ATOMIC_BACKUP_SUFFIX = ".bak"
        const val CONTAINER_VERSION = 1
        const val KEY_ALIAS = "lifeos.learning.episode.v1"
        const val AAD_DOMAIN = "lifeos.learning.episode.v1"
        val processMutex = Mutex()
    }
}
