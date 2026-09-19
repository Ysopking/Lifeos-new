package app.lifeos.next

import android.content.Context
import android.os.Environment
import app.lifeos.next.kernel.HardwareResourceIntelligenceRuntime
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.PriorityQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class StorageCleanupKind {
    EXACT_DUPLICATE,
    TEMPORARY_FILE,
    STALE_INSTALLER,
    LARGE_REVIEW,
}

internal data class StorageIndexedFile(
    val relativePath: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val category: AndroidFileCategory,
    val suspectedEncrypted: Boolean,
    val sha256: String?,
) {
    init {
        require(relativePath.isNotBlank())
        require(sizeBytes >= 0L)
        require(modifiedAtMillis >= 0L)
        require(sha256 == null || sha256.matches(Regex("[0-9a-f]{64}")))
    }
}

internal data class StorageCleanupCandidate(
    val relativePath: String,
    val kind: StorageCleanupKind,
    val reclaimableBytes: Long,
    val reason: String,
    val safeToTrashAfterOwnerApproval: Boolean,
)

internal data class StorageIntelligenceSnapshot(
    val capturedAt: Instant,
    val scannedFiles: Int,
    val scannedBytes: Long,
    val hashedFiles: Int,
    val hashedBytes: Long,
    val unreadablePaths: Int,
    val complete: Boolean,
    val nextPosition: String?,
    val cleanupCandidates: List<StorageCleanupCandidate>,
    val reclaimableBytes: Long,
)

/**
 * Hardware-aware owner-local storage inventory.
 *
 * Every file reachable below shared external storage is enumerated when Android grants broad file
 * access. Protected private sandboxes of other apps remain outside Android's permission model.
 * This class never deletes anything; it only emits cleanup candidates.
 */
internal class AndroidStorageIntelligenceRuntime(
    context: Context,
    private val hardware: HardwareResourceIntelligenceRuntime,
    private val rootProvider: () -> File = Environment::getExternalStorageDirectory,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun scanNextBatch(): StorageIntelligenceSnapshot = withContext(Dispatchers.IO) {
        val root = rootProvider().canonicalFile
        require(root.exists() && root.isDirectory && root.canRead()) {
            "Shared storage root is unavailable"
        }

        val hw = hardware.currentHardwareSnapshot()
        val maxFiles = when {
            hw.thermalState.name in setOf("CRITICAL", "EMERGENCY", "SHUTDOWN") -> 64
            hw.batteryFraction != null && hw.batteryFraction < 0.15 && hw.charging != true -> 128
            hw.charging == true && hw.availableProcessors >= 6 -> 4_000
            hw.availableProcessors >= 4 -> 2_000
            else -> 750
        }
        val hashBudgetBytes = when {
            hw.thermalState.name in setOf("CRITICAL", "EMERGENCY", "SHUTDOWN") -> 0L
            hw.batteryFraction != null && hw.batteryFraction < 0.20 && hw.charging != true -> 0L
            hw.charging == true -> 512L * 1024L * 1024L
            else -> 96L * 1024L * 1024L
        }

        val after = prefs.getString(KEY_CURSOR, null)
        val page = StorageTreePager.page(root, after, maxFiles)
        var hashedBytes = 0L
        var hashedFiles = 0
        var unreadable = page.unreadablePaths
        val indexed = page.files.map { file ->
            val classification = AndroidFileMetadataClassifier.classify(
                relativePath = file.relativePath,
                displayName = file.file.name,
                mimeType = null,
            )
            val length = file.file.length().coerceAtLeast(0L)
            val mayHash = file.file.canRead() &&
                length <= MAX_SINGLE_HASH_BYTES &&
                hashedBytes + length <= hashBudgetBytes
            val sha = if (mayHash) {
                runCatching { sha256(file.file) }
                    .onFailure { unreadable += 1 }
                    .getOrNull()
                    ?.also {
                        hashedFiles += 1
                        hashedBytes += length
                    }
            } else null

            StorageIndexedFile(
                relativePath = file.relativePath,
                absolutePath = file.file.absolutePath,
                sizeBytes = length,
                modifiedAtMillis = file.file.lastModified().coerceAtLeast(0L),
                category = classification.category,
                suspectedEncrypted = classification.suspectedEncrypted,
                sha256 = sha,
            )
        }

        val candidates = StorageCleanupPlanner.plan(indexed)
        if (page.complete) {
            prefs.edit().remove(KEY_CURSOR).apply()
        } else {
            prefs.edit().putString(KEY_CURSOR, page.nextPosition).apply()
        }

        StorageIntelligenceSnapshot(
            capturedAt = Instant.now(),
            scannedFiles = indexed.size,
            scannedBytes = indexed.sumOf { it.sizeBytes },
            hashedFiles = hashedFiles,
            hashedBytes = hashedBytes,
            unreadablePaths = unreadable,
            complete = page.complete,
            nextPosition = page.nextPosition,
            cleanupCandidates = candidates,
            reclaimableBytes = candidates.sumOf { it.reclaimableBytes },
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val PREFS = "lifeos-storage-intelligence"
        const val KEY_CURSOR = "scan-cursor"
        const val HASH_BUFFER_BYTES = 256 * 1024
        const val MAX_SINGLE_HASH_BYTES = 2L * 1024L * 1024L * 1024L
    }
}

internal object StorageCleanupPlanner {
    fun plan(files: List<StorageIndexedFile>): List<StorageCleanupCandidate> {
        val candidates = mutableListOf<StorageCleanupCandidate>()

        files.asSequence()
            .filter { it.sha256 != null && it.sizeBytes > 0L }
            .groupBy { it.sizeBytes to it.sha256 }
            .values
            .filter { it.size > 1 }
            .forEach { duplicates ->
                val keeper = duplicates.minWithOrNull(
                    compareBy<StorageIndexedFile>(
                        { pathPenalty(it.relativePath) },
                        { it.relativePath.length },
                        { it.relativePath },
                    )
                ) ?: return@forEach
                duplicates
                    .filterNot { it.relativePath == keeper.relativePath }
                    .sortedBy { it.relativePath }
                    .forEach { duplicate ->
                        candidates += StorageCleanupCandidate(
                            relativePath = duplicate.relativePath,
                            kind = StorageCleanupKind.EXACT_DUPLICATE,
                            reclaimableBytes = duplicate.sizeBytes,
                            reason = "byte-identical duplicate; keep " + keeper.relativePath,
                            safeToTrashAfterOwnerApproval =
                                !duplicate.suspectedEncrypted &&
                                    duplicate.category !in PROTECTED_CATEGORIES,
                        )
                    }
            }

        files.asSequence()
            .filter { looksTemporary(it.relativePath) }
            .filter { it.category !in PROTECTED_CATEGORIES && !it.suspectedEncrypted }
            .forEach { file ->
                candidates += StorageCleanupCandidate(
                    relativePath = file.relativePath,
                    kind = StorageCleanupKind.TEMPORARY_FILE,
                    reclaimableBytes = file.sizeBytes,
                    reason = "temporary/cache-like filename; review before trashing",
                    safeToTrashAfterOwnerApproval = true,
                )
            }

        files.asSequence()
            .filter { it.category == AndroidFileCategory.ARCHIVE }
            .filter { it.relativePath.endsWith(".apk", ignoreCase = true) }
            .forEach { file ->
                candidates += StorageCleanupCandidate(
                    relativePath = file.relativePath,
                    kind = StorageCleanupKind.STALE_INSTALLER,
                    reclaimableBytes = file.sizeBytes,
                    reason = "APK/installer candidate; review before trashing",
                    safeToTrashAfterOwnerApproval = true,
                )
            }

        files.asSequence()
            .filter { it.sizeBytes >= LARGE_FILE_REVIEW_BYTES }
            .filter { large -> candidates.none { it.relativePath == large.relativePath } }
            .forEach { file ->
                candidates += StorageCleanupCandidate(
                    relativePath = file.relativePath,
                    kind = StorageCleanupKind.LARGE_REVIEW,
                    reclaimableBytes = 0L,
                    reason = "large file; review placement/retention, never auto-delete",
                    safeToTrashAfterOwnerApproval = false,
                )
            }

        return candidates
            .distinctBy { it.relativePath to it.kind }
            .sortedWith(compareBy<StorageCleanupCandidate>({ it.kind.name }, { it.relativePath }))
    }

    private fun pathPenalty(path: String): Int {
        val p = path.replace('\\', '/').lowercase()
        return when {
            p.startsWith("dcim/") || p.startsWith("pictures/") ||
                p.startsWith("documents/") || p.startsWith("music/") ||
                p.startsWith("movies/") -> 0
            p.startsWith("download/") -> 1
            "/cache/" in ("/" + p + "/") || "/tmp/" in ("/" + p + "/") -> 5
            else -> 2
        }
    }

    private fun looksTemporary(path: String): Boolean {
        val p = path.lowercase()
        val name = p.substringAfterLast('/')
        return name.endsWith(".tmp") ||
            name.endsWith(".temp") ||
            name.endsWith(".part") ||
            name.endsWith(".crdownload") ||
            name.startsWith("~") ||
            "/cache/" in ("/" + p + "/") ||
            "/tmp/" in ("/" + p + "/")
    }

    private val PROTECTED_CATEGORIES = setOf(
        AndroidFileCategory.DATABASE,
        AndroidFileCategory.BACKUP,
        AndroidFileCategory.WHATSAPP,
    )
    private const val LARGE_FILE_REVIEW_BYTES = 2L * 1024L * 1024L * 1024L
}

internal object StorageTreePager {
    internal data class Entry(val file: File, val relativePath: String)
    internal data class Page(
        val files: List<Entry>,
        val nextPosition: String?,
        val complete: Boolean,
        val unreadablePaths: Int,
    )

    private data class Candidate(val file: File, val relativePath: String)

    fun page(root: File, afterPosition: String?, limit: Int): Page {
        require(limit > 0)
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar)
        val after = afterPosition.orEmpty()
        val queue = PriorityQueue<Candidate>(compareBy { it.relativePath })
        var unreadable = 0
        val rootChildren = runCatching { root.listFiles() }.getOrNull()
        if (rootChildren == null) return Page(emptyList(), null, true, 1)
        rootChildren.forEach { child -> candidate(rootPath, child)?.let(queue::add) }

        val files = mutableListOf<Entry>()
        var last: String? = null
        var hasMore = false
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            val file = current.file
            if (file.isDirectory) {
                val children = runCatching { file.listFiles() }.getOrNull()
                if (children == null) unreadable += 1
                else children.forEach { child -> candidate(rootPath, child)?.let(queue::add) }
                continue
            }
            if (!file.isFile || current.relativePath <= after) continue
            if (files.size >= limit) {
                hasMore = true
                break
            }
            files += Entry(file, current.relativePath)
            last = current.relativePath
        }
        return Page(
            files = files,
            nextPosition = if (hasMore) last else null,
            complete = !hasMore,
            unreadablePaths = unreadable,
        )
    }

    private fun candidate(rootPath: String, file: File): Candidate? {
        val absolute = file.absoluteFile.absolutePath
        if (!(absolute == rootPath || absolute.startsWith(rootPath + File.separator))) return null
        val relative = absolute.removePrefix(rootPath)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
            .trim()
        return relative.takeIf { it.isNotBlank() }?.let { Candidate(file, it) }
    }
}
