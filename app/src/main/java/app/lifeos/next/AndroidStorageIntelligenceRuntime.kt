package app.lifeos.next

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.resource.HardwareStateSnapshot
import app.lifeos.core.runtime.resource.HardwareWorkPriority
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.next.kernel.HardwareResourceDecision
import app.lifeos.next.kernel.HardwareResourceIntelligenceRuntime
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.PriorityQueue
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val hardwareSnapshotFingerprint: String,
    val hardwareWorldFormulaBound: Boolean,
    val processingParallelism: Int,
    val fingerprintChunkBytes: Long,
    val fingerprintBudgetBytes: Long,
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
        val processing = resolveProcessingProfile(hw)
        val metadataBudget = processing.metadataFileBudget
        val fingerprintBudget = processing.fingerprintByteBudget

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
            val result = fingerprintPending(
                scanId = scanId,
                budgetBytes = fingerprintBudget,
                parallelReaders = processing.parallelReaders,
                chunkBytes = processing.chunkBytes,
            )
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
            hardwareSnapshotFingerprint = hw.fingerprint(),
            hardwareWorldFormulaBound = processing.worldFormulaBound,
            processingParallelism = processing.parallelReaders,
            fingerprintChunkBytes = processing.chunkBytes,
            fingerprintBudgetBytes = processing.fingerprintByteBudget,
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

    private suspend fun fingerprintPending(
        scanId: String,
        budgetBytes: Long,
        parallelReaders: Int,
        chunkBytes: Long,
    ): FingerprintSliceResult = coroutineScope {
        require(parallelReaders > 0)
        require(chunkBytes > 0L)
        var remaining = budgetBytes
        var readBytes = 0L
        var unreadable = 0

        while (remaining > 0L) {
            val pending = inventory.pendingHashEntries(
                (parallelReaders * HASH_CANDIDATES_PER_READER).coerceAtLeast(parallelReaders)
            )
            if (pending.isEmpty()) break

            val assignments = mutableListOf<FingerprintReadAssignment>()
            var madeProgress = false

            for (entry in pending) {
                if (assignments.size >= parallelReaders || remaining <= 0L) break
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
                    madeProgress = true
                    continue
                }

                val chain = entry.hashChain ?: initialChain(entry.sizeBytes)
                if (entry.sizeBytes == 0L) {
                    inventory.updateHashProgress(entry, 0L, chain, chain)
                    madeProgress = true
                    continue
                }

                val maxRead = minOf(chunkBytes, remaining, entry.sizeBytes - entry.hashOffsetBytes)
                if (maxRead <= 0L) continue
                assignments += FingerprintReadAssignment(
                    entry = entry,
                    file = file,
                    offset = entry.hashOffsetBytes,
                    bytes = maxRead.toInt(),
                    previousChain = chain,
                )
                remaining -= maxRead
            }

            if (assignments.isEmpty()) {
                if (!madeProgress) break
                continue
            }

            val results = assignments.map { assignment ->
                async(Dispatchers.IO) { readFingerprintChunk(assignment) }
            }.awaitAll()

            var unusedReservation = 0L
            results.forEach { result ->
                when (result) {
                    is FingerprintReadResult.Success -> {
                        val nextChain = sha256String(
                            "lifeos-content-chain/v1|" +
                                result.assignment.previousChain + "|" +
                                result.assignment.offset + "|" +
                                result.actualBytes + "|" +
                                result.chunkHash
                        )
                        val nextOffset = result.assignment.offset + result.actualBytes
                        val final = if (nextOffset == result.assignment.entry.sizeBytes) nextChain else null
                        inventory.updateHashProgress(
                            result.assignment.entry,
                            nextOffset,
                            nextChain,
                            final,
                        )
                        readBytes += result.actualBytes
                        unusedReservation += result.assignment.bytes - result.actualBytes
                    }

                    is FingerprintReadResult.Changed -> {
                        inventory.upsertMetadata(
                            scanId = scanId,
                            volumeId = result.assignment.entry.volumeId,
                            files = listOf(
                                StorageTreePager.Entry(
                                    result.assignment.file,
                                    result.assignment.entry.relativePath,
                                )
                            ),
                        )
                        unusedReservation += result.assignment.bytes.toLong()
                    }

                    is FingerprintReadResult.Unreadable -> {
                        unreadable += 1
                        unusedReservation += result.assignment.bytes.toLong()
                    }
                }
            }
            remaining += unusedReservation
        }

        FingerprintSliceResult(readBytes, unreadable)
    }

    private fun readFingerprintChunk(
        assignment: FingerprintReadAssignment,
    ): FingerprintReadResult {
        val chunk = ByteArray(assignment.bytes)
        val actual = runCatching {
            RandomAccessFile(assignment.file, "r").use { raf ->
                raf.seek(assignment.offset)
                raf.read(chunk)
            }
        }.getOrElse {
            return FingerprintReadResult.Unreadable(assignment)
        }
        if (actual <= 0) return FingerprintReadResult.Unreadable(assignment)
        if (
            assignment.file.length().coerceAtLeast(0L) != assignment.entry.sizeBytes ||
            assignment.file.lastModified().coerceAtLeast(0L) != assignment.entry.modifiedAtMillis
        ) {
            return FingerprintReadResult.Changed(assignment)
        }
        return FingerprintReadResult.Success(
            assignment = assignment,
            actualBytes = actual,
            chunkHash = sha256(chunk, actual),
        )
    }

    private suspend fun resolveProcessingProfile(
        snapshot: HardwareStateSnapshot,
    ): HardwareStorageProcessingProfile {
        val hardQuota = ResourceBudgetQuota(
            elapsedMillis = 120_000L,
            workUnits = 120_000L,
            memoryBytes = 512L * 1024L * 1024L,
            ioBytes = 2L * 1024L * 1024L * 1024L,
            networkBytes = 0L,
            candidates = 8_192L,
        )
        val requested = ResourceBudgetUsage(
            elapsedMillis = 120_000L,
            workUnits = 120_000L,
            memoryBytes = 512L * 1024L * 1024L,
            ioBytes = 2L * 1024L * 1024L * 1024L,
            networkBytes = 0L,
            candidates = 8_192L,
        )
        val priority = if (
            snapshot.charging == true &&
            snapshot.computeHeadroom() >= 0.50 &&
            snapshot.thermalHeadroom() >= 0.80
        ) {
            HardwareWorkPriority.HIGH
        } else {
            HardwareWorkPriority.NORMAL
        }

        return when (val decision = hardware.evaluate(hardQuota, requested, priority)) {
            is HardwareResourceDecision.Blocked -> HardwareStorageProcessingProfile(
                metadataFileBudget = 64,
                fingerprintByteBudget = 0L,
                parallelReaders = 1,
                chunkBytes = CANONICAL_CHUNK_BYTES,
                worldFormulaBound = false,
            )

            is HardwareResourceDecision.Ready -> {
                val quota = decision.plan.effectiveQuota
                val compute = snapshot.computeHeadroom()
                val memory = snapshot.memoryHeadroom() ?: 0.70
                val cpuIdle = 1.0 - (snapshot.cpuLoadFraction ?: 0.0)
                val parallelism = (
                    snapshot.availableProcessors.toDouble() *
                        compute *
                        cpuIdle.coerceIn(0.25, 1.0)
                    ).toInt()
                    .coerceIn(1, MAX_PARALLEL_READERS)
                val maxReadersByMemory = (
                    quota.memoryBytes / (CANONICAL_CHUNK_BYTES * 2L)
                    ).toInt().coerceAtLeast(1)
                val boundedParallelism = minOf(parallelism, maxReadersByMemory)
                    .coerceIn(1, MAX_PARALLEL_READERS)
                val fingerprintBudget = if (quota.memoryBytes < CANONICAL_CHUNK_BYTES) {
                    0L
                } else {
                    quota.ioBytes.coerceIn(0L, 2L * 1024L * 1024L * 1024L)
                }
                HardwareStorageProcessingProfile(
                    metadataFileBudget = quota.candidates
                        .coerceIn(64L, 8_192L)
                        .toInt(),
                    fingerprintByteBudget = fingerprintBudget,
                    parallelReaders = boundedParallelism,
                    chunkBytes = CANONICAL_CHUNK_BYTES,
                    worldFormulaBound = true,
                )
            }
        }
    }

    private data class FingerprintReadAssignment(
        val entry: StorageInventoryEntry,
        val file: File,
        val offset: Long,
        val bytes: Int,
        val previousChain: String,
    )

    private sealed interface FingerprintReadResult {
        val assignment: FingerprintReadAssignment

        data class Success(
            override val assignment: FingerprintReadAssignment,
            val actualBytes: Int,
            val chunkHash: String,
        ) : FingerprintReadResult

        data class Changed(
            override val assignment: FingerprintReadAssignment,
        ) : FingerprintReadResult

        data class Unreadable(
            override val assignment: FingerprintReadAssignment,
        ) : FingerprintReadResult
    }

    private data class HardwareStorageProcessingProfile(
        val metadataFileBudget: Int,
        val fingerprintByteBudget: Long,
        val parallelReaders: Int,
        val chunkBytes: Long,
        val worldFormulaBound: Boolean,
    )

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

    private data class FingerprintSliceResult(val bytesRead: Long, val unreadable: Int)

    private companion object {
        const val PREFS = "lifeos-storage-intelligence"
        const val KEY_PHASE = "phase"
        const val KEY_SCAN_ID = "scan-id"
        const val KEY_ROOT_INDEX = "root-index"
        const val KEY_CURSOR = "cursor"
        const val HASH_CANDIDATES_PER_READER = 8
        const val MAX_PARALLEL_READERS = 8
        const val CANONICAL_CHUNK_BYTES = 4L * 1024L * 1024L
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
