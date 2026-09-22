package app.lifeos.core.data.agency

import android.content.Context
import android.util.AtomicFile
import app.lifeos.core.data.security.VaultAssociatedData
import app.lifeos.core.data.security.VersionedPathBoundVaultSupport
import app.lifeos.core.runtime.agency.ExternalActionGraphId
import app.lifeos.core.runtime.agency.ExternalActionGraphLoadReport
import app.lifeos.core.runtime.agency.ExternalActionGraphRevision
import app.lifeos.core.runtime.agency.ExternalActionGraphWriteResult
import app.lifeos.core.runtime.agency.ExternalActionReceiptGraph
import app.lifeos.core.runtime.agency.ExternalActionReceiptGraphCodec
import app.lifeos.core.runtime.agency.ExternalActionReceiptGraphRepository
import java.io.File
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * B411 encrypted path-bound append-only provenance graph.
 *
 * Canonical immediate EffectReceipt persistence remains in EncryptedExternalEffectReceiptRepository.
 * This repository stores only B411 graph revisions that reference those immutable receipt identities.
 */
class EncryptedExternalActionReceiptGraphRepository(
    context: Context,
) : ExternalActionReceiptGraphRepository {
    private val root = context.filesDir.resolve(ROOT_DIRECTORY)
    private val graphsDirectory = root.resolve(GRAPHS_DIRECTORY)

    private val key: SecretKey by lazy {
        VersionedPathBoundVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun append(
        revision: ExternalActionGraphRevision,
    ): ExternalActionGraphWriteResult = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            val target = revisionFile(revision)
            if (exists(target)) {
                val existing = readValidated(target)
                require(existing == revision) {
                    "External action graph physical revision collision"
                }
                return@withLock ExternalActionGraphWriteResult.Duplicate(existing)
            }

            val history = validatedHistory(revision.graphId)
            val current = history.lastOrNull()
            require(revision.revision == (current?.revision ?: 0L) + 1L) {
                "External action graph persisted revision is not contiguous"
            }
            require(revision.predecessorRevisionId == current?.revisionId) {
                "External action graph predecessor does not match durable head"
            }

            target.parentFile?.let { parent ->
                check(parent.isDirectory || parent.mkdirs()) {
                    "External action graph directory unavailable"
                }
            }
            writeRevision(target, revision)
            require(readValidated(target) == revision) {
                "External action graph persistence verification failed"
            }

            // Replay the new exact durable chain before reporting success.
            ExternalActionReceiptGraph.replay(history + revision)
            ExternalActionGraphWriteResult.Stored(revision)
        }
    }

    override suspend fun load(
        graphId: ExternalActionGraphId,
    ): List<ExternalActionGraphRevision> = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            validatedHistory(graphId)
        }
    }

    override suspend fun loadReport(): ExternalActionGraphLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                val revisions = mutableListOf<ExternalActionGraphRevision>()
                val unreadable = mutableListOf<String>()

                logicalRevisionFiles(graphsDirectory, recursive = true).forEach { file ->
                    runCatching { readValidated(file) }
                        .onSuccess(revisions::add)
                        .onFailure {
                            unreadable += file.relativeTo(root).invariantSeparatorsPath
                        }
                }

                val readable = revisions
                    .distinctBy { it.revisionId }
                    .sortedWith(
                        compareBy<ExternalActionGraphRevision> { it.graphId.value }
                            .thenBy { it.revision }
                    )

                // A missing middle revision is semantic corruption even when every remaining
                // ciphertext decrypts successfully.
                readable.groupBy { it.graphId }.forEach { (_, graphRevisions) ->
                    runCatching { ExternalActionReceiptGraph.replay(graphRevisions) }
                        .onFailure {
                            graphRevisions.forEach { revision ->
                                unreadable += physicalRelativePath(revision)
                            }
                        }
                }

                ExternalActionGraphLoadReport(
                    revisions = readable,
                    unreadableEntries = unreadable.distinct().sorted(),
                )
            }
        }

    private fun validatedHistory(
        graphId: ExternalActionGraphId,
    ): List<ExternalActionGraphRevision> {
        val revisions = logicalRevisionFiles(graphDirectory(graphId))
            .map(::readValidated)
            .sortedBy { it.revision }

        if (revisions.isNotEmpty()) {
            require(revisions.all { it.graphId == graphId }) {
                "External action graph directory contains another graph"
            }
            ExternalActionReceiptGraph.replay(revisions)
        }
        return revisions
    }

    private fun writeRevision(
        target: File,
        revision: ExternalActionGraphRevision,
    ) {
        val plaintext = ExternalActionReceiptGraphCodec.encode(revision)
        val encrypted = VersionedPathBoundVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            containerVersion = CONTAINER_VERSION,
            codecVersion = ExternalActionReceiptGraphCodec.CODEC_VERSION,
            maxPlaintextBytes = ExternalActionReceiptGraphCodec.MAX_PAYLOAD_BYTES,
            associatedData = associatedData(target),
        )
        VersionedPathBoundVaultSupport.atomicWrite(
            AtomicFile(target),
            encrypted,
        )
    }

    private fun readValidated(
        file: File,
    ): ExternalActionGraphRevision {
        val container = VersionedPathBoundVaultSupport.readAtomic(
            AtomicFile(file),
            ExternalActionReceiptGraphCodec.MAX_PAYLOAD_BYTES,
        )
        val plaintext = VersionedPathBoundVaultSupport.decrypt(
            container = container,
            key = key,
            expectedContainerVersion = CONTAINER_VERSION,
            expectedCodecVersion = ExternalActionReceiptGraphCodec.CODEC_VERSION,
            maxPlaintextBytes = ExternalActionReceiptGraphCodec.MAX_PAYLOAD_BYTES,
            associatedData = associatedData(file),
        )
        val revision = ExternalActionReceiptGraphCodec.decode(plaintext)

        require(file.parentFile?.parentFile == graphsDirectory) {
            "External action graph segment is outside graph directory"
        }
        require(file.parentFile?.name == sha256(revision.graphId.value)) {
            "External action graph id does not match physical path"
        }
        require(parseRevision(file) == revision.revision) {
            "External action graph revision does not match physical path"
        }
        require(parseRevisionDigest(file) == sha256(revision.revisionId.value)) {
            "External action graph revision id does not match physical path"
        }
        return revision
    }

    private fun graphDirectory(
        graphId: ExternalActionGraphId,
    ): File = graphsDirectory.resolve(sha256(graphId.value))

    private fun revisionFile(
        revision: ExternalActionGraphRevision,
    ): File = graphDirectory(revision.graphId).resolve(
        REVISION_PREFIX +
            revision.revision.toString().padStart(REVISION_WIDTH, '0') +
            ID_SEPARATOR +
            sha256(revision.revisionId.value) +
            FILE_SUFFIX
    )

    private fun physicalRelativePath(
        revision: ExternalActionGraphRevision,
    ): String = revisionFile(revision)
        .relativeTo(root)
        .invariantSeparatorsPath

    private fun parseRevision(file: File): Long {
        val body = file.name
            .removePrefix(REVISION_PREFIX)
            .removeSuffix(FILE_SUFFIX)
        val split = body.indexOf(ID_SEPARATOR)
        require(split > 0)
        return requireNotNull(body.substring(0, split).toLongOrNull()) {
            "Invalid external action graph revision"
        }
    }

    private fun parseRevisionDigest(file: File): String {
        val body = file.name
            .removePrefix(REVISION_PREFIX)
            .removeSuffix(FILE_SUFFIX)
        val split = body.indexOf(ID_SEPARATOR)
        require(split > 0 && split < body.lastIndex)
        return body.substring(split + ID_SEPARATOR.length).also {
            require(it.matches(Regex("[0-9a-f]{64}"))) {
                "Invalid external action graph revision digest"
            }
        }
    }

    private fun logicalRevisionFiles(
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
                    isRevisionSegment(file.name) -> file
                    isRevisionBackup(file.name) ->
                        File(file.path.removeSuffix(ATOMIC_BACKUP_SUFFIX))
                    else -> null
                }
            }
            .distinctBy { it.path }
            .sortedBy { it.path }
            .toList()
    }

    private fun isRevisionSegment(name: String): Boolean =
        name.startsWith(REVISION_PREFIX) &&
            name.endsWith(FILE_SUFFIX)

    private fun isRevisionBackup(name: String): Boolean =
        name.startsWith(REVISION_PREFIX) &&
            name.endsWith(FILE_SUFFIX + ATOMIC_BACKUP_SUFFIX)

    private fun associatedData(file: File): ByteArray =
        VaultAssociatedData.forPath(
            domain = AAD_DOMAIN,
            root = root,
            file = file,
        )

    private fun exists(file: File): Boolean =
        file.exists() ||
            File(file.path + ATOMIC_BACKUP_SUFFIX).exists()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectories() {
        check(root.isDirectory || root.mkdirs()) {
            "External action graph vault unavailable"
        }
        check(graphsDirectory.isDirectory || graphsDirectory.mkdirs()) {
            "External action graph segment directory unavailable"
        }
    }

    private companion object {
        const val ROOT_DIRECTORY = "external-action-receipt-graph"
        const val GRAPHS_DIRECTORY = "graphs"
        const val REVISION_PREFIX = "revision-"
        const val REVISION_WIDTH = 20
        const val ID_SEPARATOR = "--"
        const val FILE_SUFFIX = ".eagraph"
        const val ATOMIC_BACKUP_SUFFIX = ".bak"
        const val CONTAINER_VERSION = 1
        const val KEY_ALIAS = "lifeos.external.action.graph.v1"
        const val AAD_DOMAIN = "lifeos.external.action.graph.v1"
        val processMutex = Mutex()
    }
}
