package app.lifeos.next

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.next.kernel.HardwareResourceIntelligenceRuntime
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.PriorityQueue
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class StorageCleanupKind {
    EXACT_DUPLICATE,
    TEMPORARY_FILE,
    STALE_INSTALLER,
    LARGE_REVIEW,
    REORGANIZE,
}

internal enum class StorageIntelligencePhase {
    METADATA_SCAN,
    CONTENT_FINGERPRINT,
}

internal data class StorageIndexedFile(
    val volumeId: String,
    val relativePath: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val category: AndroidFileCategory,
    val suspectedEncrypted: Boolean,
    val contentFingerprint: String?,
) {
    init {
        require(volumeId.isNotBlank())
        require(relativePath.isNotBlank())
        require(sizeBytes >= 0L)
        require(modifiedAtMillis >= 0L)
        require(contentFingerprint == null || contentFingerprint.matches(Regex("[0-9a-f]{64}")))
    }
}

internal data class StorageCleanupCandidate(
    val volumeId: String,
    val relativePath: String,
    val kind: StorageCleanupKind,
    val reclaimableBytes: Long,
    val reason: String,
    val safeToTrashAfterOwnerApproval: Boolean,
    val suggestedDirectory: String? = null,
) {
    init {
        require(volumeId.isNotBlank())
        require(relativePath.isNotBlank())
        require(reclaimableBytes >= 0L)
        require(reason.isNotBlank())
        require(suggestedDirectory == null || suggestedDirectory.isNotBlank())
    }
}

internal data class StorageIntelligenceSnapshot(
    val capturedAt: Instant,
    val phase: StorageIntelligencePhase,
    val indexedFiles: Long,
    val indexedBytes: Long,
    val fullyFingerprintFiles: Long,
    val fullyFingerprintBytes: Long,
    val batchScannedFiles: Int,
    val batchScannedBytes: Long,
    val batchFingerprintBytes: Long,
    val unreadablePaths: Int,
    val inventoryComplete: Boolean,
    val contentReadComplete: Boolean,
    val cleanupCandidates: List<StorageCleanupCandidate>,
    val reclaimableBytes: Long,
)

/**
 * Hardware-aware, resumable, owner-local storage intelligence.
 *
 * Phase 1 inventories every file reachable on every discoverable shared-storage volume. Phase 2
 * reads file bytes in fixed chunks and builds a resumable SHA-256 chain, so even very large files
 * are eventually fully read without forcing a 200+ GB one-shot scan. Contents are never copied into
 * LIFEOS. The durable index stores metadata, hash progress and final content fingerprints only.
 *
 * Android security boundaries remain intact: other apps' protected private sandboxes are not
 * bypassed. No destructive action is performed by this runtime.
 */
internal class AndroidStorageIntelligenceRuntime(
    context: Context,
    private val hardware: HardwareResourceIntelligenceRuntime,
    private val inventory: AndroidStorageInventoryStore = AndroidStorageInventoryStore(context),
    private val roots: () -> List<SharedStorageRoot> = { SharedStorageRoots.discover(context) },
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun runNextSlice(): StorageIntelligenceSnapshot = withContext(Dispatchers.IO) {
        val discoveredRoots = roots()
        require(discoveredRoots.isNotEmpty()) { "No owner-readable shared-storage root is available" }
        val hw = hardware.currentHardwareSnapshot()
        val metadataBudget = metadataBudget(hw.availableProcessors, hw.batteryFraction, hw.charging, hw.thermalState.name)
        val fingerprintBudget = fingerprintBudget(hw.batteryFraction, hw.charging, hw.thermalState.name)

        var phase = currentPhase()
        var batchScannedFiles = 0
        var batchScannedBytes = 0L
        var batchFingerprintBytes = 0L
        var unreadable = 0
        var inventoryComplete = phase == StorageIntelligencePhase.CONTENT_FINGERPRINT

        if (phase == StorageIntelligencePhase.METADATA_SCAN) {
            val scanId = prefs.getString(KEY_SCAN_ID, null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_SCAN_ID, it).apply()
            }
            var rootIndex = prefs.getInt(KEY_ROOT_INDEX, 0).coerceIn(0, discoveredRoots.lastIndex)
            var cursor = prefs.getString(KEY_CURSOR, null)
            var remaining = metadataBudget

            while (remaining > 0 && rootIndex < discoveredRoots.size) {
                val root = discoveredRoots[rootIndex]
                val page = StorageTreePager.page(root.root, cursor, remaining)
                inventory.upsertMetadata(scanId, root.id, page.files)
                batchScannedFiles += page.files.size
                batchScannedBytes += page.files.sumOf { it.file.length().coerceAtLeast(0L) }
                unreadable += page.unreadablePaths
                remaining -= page.files.size

                if (page.complete) {
                    rootIndex += 1
                    cursor = null
                    prefs.edit()
                        .putInt(KEY_ROOT_INDEX, rootIndex)
                        .remove(KEY_CURSOR)
                        .apply()
                } else {
                    cursor = page.nextPosition
                    prefs.edit()
                        .putInt(KEY_ROOT_INDEX, rootIndex)
                        .putString(KEY_CURSOR, cursor)
                        .apply()
                    break
                }
            }

            if (rootIndex >= discoveredRoots.size) {
                inventory.purgeNotSeen(scanId)
                inventoryComplete = true
                phase = StorageIntelligencePhase.CONTENT_FINGERPRINT
                prefs.edit()
                    .putString(KEY_PHASE, phase.name)
                    .putInt(KEY_ROOT_INDEX, 0)
                    .remove(KEY_CURSOR)
                    .apply()
            }
        }

        if (phase == StorageIntelligencePhase.CONTENT_FINGERPRINT && fingerprintBudget > 0L) {
            val scanId = requireNotNull(prefs.getString(KEY_SCAN_ID, null)) {
                "Content fingerprint phase requires the completed inventory scan id"
            }
            val result = fingerprintPending(scanId, fingerprintBudget)
            batchFingerprintBytes += result.bytesRead
            unreadable += result.unreadable
        }

        val summary = inventory.summary()
        val contentReadComplete = inventoryComplete && summary.fingerprintComplete
        if (contentReadComplete) {
            prefs.edit()
                .putString(KEY_PHASE, StorageIntelligencePhase.METADATA_SCAN.name)
                .remove(KEY_SCAN_ID)
                .putInt(KEY_ROOT_INDEX, 0)
                .remove(KEY_CURSOR)
                .apply()
        }

        val cleanup = StorageCleanupPlanner.plan(
            duplicateGroups = inventory.exactDuplicateGroups(),
            reviewEntries = inventory.reviewEntries(),
        )

        StorageIntelligenceSnapshot(
            capturedAt = Instant.now(),
            phase = phase,
            indexedFiles = summary.indexedFiles,
            indexedBytes = summary.indexedBytes,
            fullyFingerprintFiles = summary.fullyFingerprintedFiles,
            fullyFingerprintBytes = summary.fullyFingerprintedBytes,
            batchScannedFiles = batchScannedFiles,
            batchScannedBytes = batchScannedBytes,
            batchFingerprintBytes = batchFingerprintBytes,
            unreadablePaths = unreadable,
            inventoryComplete = inventoryComplete,
            contentReadComplete = contentReadComplete,
            cleanupCandidates = cleanup,
            reclaimableBytes = cleanup
                .filter { it.kind != StorageCleanupKind.LARGE_REVIEW && it.kind != StorageCleanupKind.REORGANIZE }
                .sumOf { it.reclaimableBytes },
        )
    }

    private fun currentPhase(): StorageIntelligencePhase =
        prefs.getString(KEY_PHASE, null)
            ?.let { runCatching { StorageIntelligencePhase.valueOf(it) }.getOrNull() }
            ?: StorageIntelligencePhase.METADATA_SCAN

    private fun fingerprintPending(scanId: String, budgetBytes: Long): FingerprintSliceResult {
        var remaining = budgetBytes
        var readBytes = 0L
        var unreadable = 0
        val pending = inventory.pendingHashEntries(HASH_CANDIDATES_PER_SLICE)

        for (entry in pending) {
            if (remaining <= 0L) break
            val file = File(entry.absolutePath)
            if (!file.exists() || !file.isFile || !file.canRead()) {
                unreadable += 1
                continue
            }
            val liveSize = file.length().coerceAtLeast(0L)
            val liveModified = file.lastModified().coerceAtLeast(0L)
            if (liveSize != entry.sizeBytes || liveModified != entry.modifiedAtMillis) {
                inventory.upsertMetadata(
                    scanId = scanId,
                    volumeId = entry.volumeId,
                    files = listOf(StorageTreePager.Entry(file, entry.relativePath)),
                )
                continue
            }

            var offset = entry.hashOffsetBytes
            var chain = entry.hashChain ?: initialChain(entry.sizeBytes)
            if (entry.sizeBytes == 0L) {
                inventory.updateHashProgress(entry, 0L, chain, chain)
                continue
            }

            val maxRead = minOf(CHUNK_BYTES, remaining, entry.sizeBytes - offset)
            if (maxRead <= 0L) continue
            val chunk = ByteArray(maxRead.toInt())
            val actual = runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    raf.read(chunk)
                }
            }.getOrElse {
                unreadable += 1
                -1
            }
            if (actual <= 0) continue

            val chunkHash = sha256(chunk, actual)
            chain = sha256String(
                "lifeos-content-chain/v1|$chain|$offset|$actual|$chunkHash"
            )
            offset += actual
            readBytes += actual
            remaining -= actual
            val final = if (offset == entry.sizeBytes) chain else null
            inventory.updateHashProgress(entry, offset, chain, final)
        }
        return FingerprintSliceResult(readBytes, unreadable)
    }

    private fun initialChain(sizeBytes: Long): String =
        sha256String("lifeos-content-chain/v1|size=$sizeBytes")

    private fun sha256(bytes: ByteArray, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes, 0, length)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sha256String(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun metadataBudget(
        processors: Int,
        battery: Double?,
        charging: Boolean?,
        thermal: String,
    ): Int = when {
        thermal in CRITICAL_THERMAL -> 64
        battery != null && battery < 0.15 && charging != true -> 128
        charging == true && processors >= 6 -> 4_000
        processors >= 4 -> 2_000
        else -> 750
    }

    private fun fingerprintBudget(
        battery: Double?,
        charging: Boolean?,
        thermal: String,
    ): Long = when {
        thermal in CRITICAL_THERMAL -> 0L
        battery != null && battery < 0.20 && charging != true -> 0L
        charging == true -> 512L * 1024L * 1024L
        else -> 96L * 1024L * 1024L
    }

    private data class FingerprintSliceResult(val bytesRead: Long, val unreadable: Int)

    private companion object {
        const val PREFS = "lifeos-storage-intelligence"
        const val KEY_PHASE = "phase"
        const val KEY_SCAN_ID = "scan-id"
        const val KEY_ROOT_INDEX = "root-index"
        const val KEY_CURSOR = "cursor"
        const val HASH_CANDIDATES_PER_SLICE = 512
        const val CHUNK_BYTES = 4L * 1024L * 1024L
        val CRITICAL_THERMAL = setOf("CRITICAL", "EMERGENCY", "SHUTDOWN")
    }
}

internal object StorageCleanupPlanner {
    fun plan(
        duplicateGroups: List<List<StorageIndexedFile>>,
        reviewEntries: List<StorageIndexedFile>,
    ): List<StorageCleanupCandidate> {
        val candidates = mutableListOf<StorageCleanupCandidate>()

        duplicateGroups.forEach { duplicates ->
            if (duplicates.size < 2) return@forEach
            val keeper = duplicates.minWithOrNull(
                compareBy<StorageIndexedFile>(
                    { pathPenalty(it.relativePath) },
                    { it.relativePath.length },
                    { it.volumeId },
                    { it.relativePath },
                )
            ) ?: return@forEach
            duplicates
                .filterNot {
                    it.volumeId == keeper.volumeId && it.relativePath == keeper.relativePath
                }
                .forEach { duplicate ->
                    candidates += StorageCleanupCandidate(
                        volumeId = duplicate.volumeId,
                        relativePath = duplicate.relativePath,
                        kind = StorageCleanupKind.EXACT_DUPLICATE,
                        reclaimableBytes = duplicate.sizeBytes,
                        reason = "byte-identical duplicate; keep " + keeper.volumeId + ":" + keeper.relativePath,
                        safeToTrashAfterOwnerApproval =
                            !duplicate.suspectedEncrypted &&
                                duplicate.category !in PROTECTED_CATEGORIES,
                    )
                }
        }

        reviewEntries.forEach { file ->
            if (
                looksTemporary(file.relativePath) &&
                file.category !in PROTECTED_CATEGORIES &&
                !file.suspectedEncrypted
            ) {
                candidates += StorageCleanupCandidate(
                    volumeId = file.volumeId,
                    relativePath = file.relativePath,
                    kind = StorageCleanupKind.TEMPORARY_FILE,
                    reclaimableBytes = file.sizeBytes,
                    reason = "temporary/cache-like file; reversible trash candidate",
                    safeToTrashAfterOwnerApproval = true,
                )
            }
            if (
                file.category == AndroidFileCategory.ARCHIVE &&
                file.relativePath.endsWith(".apk", ignoreCase = true)
            ) {
                candidates += StorageCleanupCandidate(
                    volumeId = file.volumeId,
                    relativePath = file.relativePath,
                    kind = StorageCleanupKind.STALE_INSTALLER,
                    reclaimableBytes = file.sizeBytes,
                    reason = "APK/installer file; owner review before reversible trash",
                    safeToTrashAfterOwnerApproval = true,
                )
            }
            if (file.sizeBytes >= AndroidStorageInventoryStore.LARGE_REVIEW_BYTES) {
                candidates += StorageCleanupCandidate(
                    volumeId = file.volumeId,
                    relativePath = file.relativePath,
                    kind = StorageCleanupKind.LARGE_REVIEW,
                    reclaimableBytes = 0L,
                    reason = "large unique file; retention review only",
                    safeToTrashAfterOwnerApproval = false,
                )
            }
            suggestedDirectory(file)?.let { target ->
                if (!alreadyIn(file.relativePath, target)) {
                    candidates += StorageCleanupCandidate(
                        volumeId = file.volumeId,
                        relativePath = file.relativePath,
                        kind = StorageCleanupKind.REORGANIZE,
                        reclaimableBytes = 0L,
                        reason = "category-based organization candidate",
                        safeToTrashAfterOwnerApproval = false,
                        suggestedDirectory = target,
                    )
                }
            }
        }

        return candidates
            .distinctBy { Triple(it.volumeId, it.relativePath, it.kind) }
            .sortedWith(
                compareBy<StorageCleanupCandidate>(
                    { it.kind.name },
                    { it.volumeId },
                    { it.relativePath },
                )
            )
    }

    private fun suggestedDirectory(file: StorageIndexedFile): String? = when (file.category) {
        AndroidFileCategory.IMAGE -> "Pictures/LIFEOS"
        AndroidFileCategory.VIDEO -> "Movies/LIFEOS"
        AndroidFileCategory.AUDIO -> "Music/LIFEOS"
        AndroidFileCategory.DOCUMENT -> "Documents/LIFEOS"
        AndroidFileCategory.ARCHIVE -> "Documents/LIFEOS/Archives"
        AndroidFileCategory.BACKUP -> "Documents/LIFEOS/Backups"
        AndroidFileCategory.DATABASE,
        AndroidFileCategory.WHATSAPP,
        AndroidFileCategory.UNKNOWN,
        -> null
    }

    private fun alreadyIn(path: String, directory: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.startsWith(directory.trimEnd('/') + "/")
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
}

internal data class SharedStorageRoot(
    val id: String,
    val root: File,
) {
    init {
        require(id.isNotBlank())
    }
}

internal object SharedStorageRoots {
    fun discover(context: Context): List<SharedStorageRoot> {
        val candidates = linkedSetOf<File>()
        candidates += Environment.getExternalStorageDirectory()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val storage = context.getSystemService(StorageManager::class.java)
            storage.storageVolumes.mapNotNullTo(candidates) { it.directory }
        }

        context.getExternalFilesDirs(null).forEach { appDir ->
            if (appDir == null) return@forEach
            val normalized = appDir.absolutePath.replace('\\', '/')
            val marker = "/Android/data/"
            val index = normalized.indexOf(marker)
            if (index > 0) candidates += File(normalized.substring(0, index))
        }

        return candidates.mapNotNull { candidate ->
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@mapNotNull null
            if (!canonical.exists() || !canonical.isDirectory || !canonical.canRead()) return@mapNotNull null
            SharedStorageRoot(
                id = "shared-" + StableCognitiveIds.fingerprint(
                    "shared-storage-root/v1",
                    canonical.absolutePath,
                ).take(16),
                root = canonical,
            )
        }.distinctBy { it.root.absolutePath }
            .sortedBy { it.root.absolutePath }
    }
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
        val rootCanonical = root.canonicalFile
        val rootPath = rootCanonical.absolutePath.trimEnd(File.separatorChar)
        val after = afterPosition.orEmpty()
        val queue = PriorityQueue<Candidate>(compareBy { it.relativePath })
        val visitedDirectories = linkedSetOf<String>()
        var unreadable = 0

        val rootChildren = runCatching { rootCanonical.listFiles() }.getOrNull()
            ?: return Page(emptyList(), null, true, 1)
        rootChildren.forEach { child -> candidate(rootPath, child)?.let(queue::add) }

        val files = mutableListOf<Entry>()
        var last: String? = null
        var hasMore = false
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (current.relativePath == TRASH_ROOT || current.relativePath.startsWith(TRASH_ROOT + "/")) {
                continue
            }
            val file = current.file
            if (Files.isSymbolicLink(file.toPath())) {
                unreadable += 1
                continue
            }
            if (file.isDirectory) {
                val canonical = runCatching { file.canonicalPath }.getOrNull()
                if (
                    canonical == null ||
                    !insideRoot(rootPath, canonical) ||
                    !visitedDirectories.add(canonical)
                ) {
                    unreadable += 1
                    continue
                }
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
        if (!insideRoot(rootPath, absolute)) return null
        val relative = absolute.removePrefix(rootPath)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
            .trim()
        return relative.takeIf { it.isNotBlank() }?.let { Candidate(file, it) }
    }

    private fun insideRoot(rootPath: String, path: String): Boolean =
        path == rootPath || path.startsWith(rootPath + File.separator)

    const val TRASH_ROOT = ".lifeos-trash"
}
