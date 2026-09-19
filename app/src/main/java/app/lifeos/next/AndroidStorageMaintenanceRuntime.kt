package app.lifeos.next

import android.content.Context
import app.lifeos.core.runtime.policy.OwnerEffectExposureResult
import app.lifeos.next.kernel.HardwareResourceIntelligenceRuntime
import app.lifeos.next.kernel.PrivateOwnerEffectAuthority
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant

internal enum class StorageMaintenanceStatus {
    TRASHED,
    REORGANIZED,
    RESTORED,
    PURGED,
    BLOCKED,
    SKIPPED,
}

internal data class StorageMaintenanceActionResult(
    val status: StorageMaintenanceStatus,
    val volumeId: String,
    val sourceRelativePath: String,
    val destinationRelativePath: String? = null,
    val trashRecordId: String? = null,
    val detail: String,
) {
    init {
        require(volumeId.isNotBlank())
        require(sourceRelativePath.isNotBlank())
        require(detail.isNotBlank())
    }
}

internal data class StorageMaintenanceBatchResult(
    val actions: List<StorageMaintenanceActionResult>,
) {
    val trashed: Int get() = actions.count { it.status == StorageMaintenanceStatus.TRASHED }
    val reorganized: Int get() = actions.count { it.status == StorageMaintenanceStatus.REORGANIZED }
    val restored: Int get() = actions.count { it.status == StorageMaintenanceStatus.RESTORED }
    val purged: Int get() = actions.count { it.status == StorageMaintenanceStatus.PURGED }
    val blocked: Int get() = actions.count { it.status == StorageMaintenanceStatus.BLOCKED }
    val skipped: Int get() = actions.count { it.status == StorageMaintenanceStatus.SKIPPED }
}

internal data class StorageOrganizationBatchResult(
    val maintenance: StorageMaintenanceBatchResult,
    val scannedInventoryEntries: Int,
    val complete: Boolean,
)

/**
 * Owner-gated execution layer for storage-intelligence proposals.
 *
 * Cleanup is reversible first: exact duplicates, temporary files and reviewed installer candidates
 * move into LIFEOS trash rather than being deleted. Permanent deletion is only available for trash
 * records older than the retention period and is itself owner-policy gated. Reorganization never
 * overwrites an existing destination.
 */
internal class AndroidStorageMaintenanceRuntime(
    private val context: Context,
    private val hardware: HardwareResourceIntelligenceRuntime,
    private val inventory: AndroidStorageInventoryStore = AndroidStorageInventoryStore(context),
    private val store: AndroidStorageMaintenanceStore = AndroidStorageMaintenanceStore(context),
    private val roots: () -> List<SharedStorageRoot> = { SharedStorageRoots.discover(context) },
    private val now: () -> Instant = Instant::now,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun applyCandidates(
        candidates: List<StorageCleanupCandidate>,
        includeReorganization: Boolean,
    ): StorageMaintenanceBatchResult {
        reconcilePrepared()
        val actions = mutableListOf<StorageMaintenanceActionResult>()
        candidates
            .sortedWith(
                compareBy<StorageCleanupCandidate>(
                    { it.kind.name },
                    { it.volumeId },
                    { it.relativePath },
                )
            )
            .forEach { candidate ->
                val action = when {
                    candidate.kind == StorageCleanupKind.REORGANIZE && includeReorganization ->
                        reorganize(candidate)
                    candidate.kind in REVERSIBLE_TRASH_KINDS &&
                        candidate.safeToTrashAfterOwnerApproval ->
                        moveToTrash(candidate)
                    else -> StorageMaintenanceActionResult(
                        status = StorageMaintenanceStatus.SKIPPED,
                        volumeId = candidate.volumeId,
                        sourceRelativePath = candidate.relativePath,
                        detail = "candidate-not-enabled-for-this-maintenance-pass",
                    )
                }
                actions += action
            }
        return StorageMaintenanceBatchResult(actions)
    }

    suspend fun organizeNextBatch(): StorageOrganizationBatchResult {
        reconcilePrepared()
        val after = preferences.getString(KEY_ORGANIZATION_CURSOR, null)
        val hardwareSnapshot = hardware.currentHardwareSnapshot()
        val pageSize = when {
            hardwareSnapshot.thermalState.name in CRITICAL_THERMAL -> 64
            hardwareSnapshot.batteryFraction != null &&
                hardwareSnapshot.batteryFraction < 0.20 &&
                hardwareSnapshot.charging != true -> 128
            hardwareSnapshot.charging == true &&
                hardwareSnapshot.availableProcessors >= 6 -> 1_024
            hardwareSnapshot.availableProcessors >= 4 -> 512
            else -> 256
        }
        val page = inventory.loadPage(afterPosition = after, limit = pageSize)
        val candidates = page.entries.mapNotNull { entry ->
            val target = StorageCleanupPlanner.suggestedDirectory(entry) ?: return@mapNotNull null
            val normalized = entry.relativePath.replace('\\', '/')
            if (normalized.startsWith(target.trimEnd('/') + "/")) return@mapNotNull null
            StorageCleanupCandidate(
                volumeId = entry.volumeId,
                relativePath = entry.relativePath,
                kind = StorageCleanupKind.REORGANIZE,
                reclaimableBytes = 0L,
                reason = "full-inventory category organization candidate",
                safeToTrashAfterOwnerApproval = false,
                suggestedDirectory = target,
                expectedModifiedAtMillis = entry.modifiedAtMillis,
            )
        }
        val actions = candidates.map { candidate -> reorganize(candidate) }
        if (page.complete) {
            preferences.edit().remove(KEY_ORGANIZATION_CURSOR).apply()
        } else {
            preferences.edit().putString(KEY_ORGANIZATION_CURSOR, page.nextPosition).apply()
        }
        return StorageOrganizationBatchResult(
            maintenance = StorageMaintenanceBatchResult(actions),
            scannedInventoryEntries = page.entries.size,
            complete = page.complete,
        )
    }

    suspend fun restore(recordId: String): StorageMaintenanceActionResult {
        reconcilePrepared()
        val record = requireNotNull(store.load(recordId)) {
            "Storage trash record does not exist"
        }
        if (record.state != StorageTrashState.TRASHED) {
            return StorageMaintenanceActionResult(
                status = StorageMaintenanceStatus.SKIPPED,
                volumeId = record.volumeId,
                sourceRelativePath = record.trashRelativePath,
                trashRecordId = record.id,
                detail = "trash-record-is-not-restorable",
            )
        }
        val root = requireRoot(record.volumeId)
        val trash = resolveInside(root.root, record.trashRelativePath)
        if (!trash.exists()) {
            return StorageMaintenanceActionResult(
                status = StorageMaintenanceStatus.SKIPPED,
                volumeId = record.volumeId,
                sourceRelativePath = record.trashRelativePath,
                trashRecordId = record.id,
                detail = "trash-payload-is-missing",
            )
        }
        val preferred = resolveInside(root.root, record.originalRelativePath)
        val destination = uniqueDestination(
            preferred = preferred,
            stableSuffix = record.id.take(12),
        )
        val resource = "restore/" + record.volumeId + "/" + record.originalRelativePath
        return when (
            val exposure = PrivateOwnerEffectAuthority.expose(
                context = context,
                request = PrivateOwnerEffectAuthority.storageMaintenanceRequest(resource),
            ) {
                moveExact(trash, destination)
            }
        ) {
            is OwnerEffectExposureResult.Blocked -> StorageMaintenanceActionResult(
                status = StorageMaintenanceStatus.BLOCKED,
                volumeId = record.volumeId,
                sourceRelativePath = record.trashRelativePath,
                destinationRelativePath = relative(root.root, destination),
                trashRecordId = record.id,
                detail = "owner-policy-blocked-restore",
            )
            is OwnerEffectExposureResult.Exposed -> {
                store.settle(
                    id = record.id,
                    expected = StorageTrashState.TRASHED,
                    state = StorageTrashState.RESTORED,
                    settledAt = now(),
                )
                StorageMaintenanceActionResult(
                    status = StorageMaintenanceStatus.RESTORED,
                    volumeId = record.volumeId,
                    sourceRelativePath = record.trashRelativePath,
                    destinationRelativePath = relative(root.root, destination),
                    trashRecordId = record.id,
                    detail = "restored-from-lifeos-trash",
                )
            }
        }
    }

    suspend fun purgeExpired(
        retention: Duration = DEFAULT_TRASH_RETENTION,
    ): StorageMaintenanceBatchResult {
        require(!retention.isNegative && !retention.isZero)
        reconcilePrepared()
        val cutoff = now().minus(retention)
        val actions = mutableListOf<StorageMaintenanceActionResult>()
        store.loadByStates(setOf(StorageTrashState.TRASHED))
            .filter { !it.preparedAt.isAfter(cutoff) }
            .forEach { record ->
                val root = requireRoot(record.volumeId)
                val trash = resolveInside(root.root, record.trashRelativePath)
                val resource = "purge/" + record.volumeId + "/" + record.trashRelativePath
                val action = when (
                    val exposure = PrivateOwnerEffectAuthority.expose(
                        context = context,
                        request = PrivateOwnerEffectAuthority.storageMaintenanceRequest(resource),
                    ) {
                        if (trash.exists()) {
                            check(trash.isFile) { "Storage trash payload is not a file" }
                            check(trash.delete()) { "Could not permanently delete storage trash payload" }
                            pruneEmptyTrashParents(root.root, trash.parentFile)
                        }
                    }
                ) {
                    is OwnerEffectExposureResult.Blocked -> StorageMaintenanceActionResult(
                        status = StorageMaintenanceStatus.BLOCKED,
                        volumeId = record.volumeId,
                        sourceRelativePath = record.trashRelativePath,
                        trashRecordId = record.id,
                        detail = "owner-policy-blocked-purge",
                    )
                    is OwnerEffectExposureResult.Exposed -> {
                        store.settle(
                            id = record.id,
                            expected = StorageTrashState.TRASHED,
                            state = StorageTrashState.PURGED,
                            settledAt = now(),
                        )
                        StorageMaintenanceActionResult(
                            status = StorageMaintenanceStatus.PURGED,
                            volumeId = record.volumeId,
                            sourceRelativePath = record.trashRelativePath,
                            trashRecordId = record.id,
                            detail = "trash-retention-expired-and-purged",
                        )
                    }
                }
                actions += action
            }
        return StorageMaintenanceBatchResult(actions)
    }

    fun trashSnapshot(): List<StorageTrashRecord> = store.loadAll()

    fun activeTrashBytes(): Long = store.loadByStates(setOf(StorageTrashState.TRASHED))
        .sumOf { it.sizeBytes }

    private suspend fun moveToTrash(
        candidate: StorageCleanupCandidate,
    ): StorageMaintenanceActionResult {
        val root = requireRoot(candidate.volumeId)
        val source = resolveInside(root.root, candidate.relativePath)
        if (!source.exists() || !source.isFile) {
            return StorageMaintenanceActionResult(
                status = StorageMaintenanceStatus.SKIPPED,
                volumeId = candidate.volumeId,
                sourceRelativePath = candidate.relativePath,
                detail = "source-file-is-missing",
            )
        }
        if (!matchesObservedRevision(source, candidate)) {
            return StorageMaintenanceActionResult(
                status = StorageMaintenanceStatus.SKIPPED,
                volumeId = candidate.volumeId,
                sourceRelativePath = candidate.relativePath,
                detail = "source-file-changed-since-analysis",
            )
        }

        val existingPrepared = store.loadByStates(setOf(StorageTrashState.PREPARED))
            .firstOrNull {
                it.volumeId == candidate.volumeId &&
                    it.originalRelativePath == candidate.relativePath
            }
        val record = existingPrepared ?: store.prepare(
            StorageTrashRecord.prepare(
                volumeId = candidate.volumeId,
                originalRelativePath = candidate.relativePath,
                sizeBytes = source.length().coerceAtLeast(0L),
                modifiedAtMillis = source.lastModified().coerceAtLeast(0L),
                preparedAt = now(),
            )
        )
        val destination = resolveInside(root.root, record.trashRelativePath)
        val resource = "trash/" + candidate.volumeId + "/" + candidate.relativePath

        return when (
            val exposure = PrivateOwnerEffectAuthority.expose(
                context = context,
                request = PrivateOwnerEffectAuthority.storageMaintenanceRequest(resource),
            ) {
                if (source.exists()) {
                    require(!destination.exists()) {
                        "Prepared storage trash destination already exists while source still exists"
                    }
                    moveExact(source, destination)
                }
            }
        ) {
            is OwnerEffectExposureResult.Blocked -> StorageMaintenanceActionResult(
                status = StorageMaintenanceStatus.BLOCKED,
                volumeId = candidate.volumeId,
                sourceRelativePath = candidate.relativePath,
                destinationRelativePath = record.trashRelativePath,
                trashRecordId = record.id,
                detail = "owner-policy-blocked-trash",
            )
            is OwnerEffectExposureResult.Exposed -> {
                store.settle(
                    id = record.id,
                    expected = StorageTrashState.PREPARED,
                    state = StorageTrashState.TRASHED,
                    settledAt = now(),
                )
                StorageMaintenanceActionResult(
                    status = StorageMaintenanceStatus.TRASHED,
                    volumeId = candidate.volumeId,
                    sourceRelativePath = candidate.relativePath,
                    destinationRelativePath = record.trashRelativePath,
                    trashRecordId = record.id,
                    detail = "moved-to-reversible-lifeos-trash",
                )
            }
        }
    }

    private suspend fun reorganize(
        candidate: StorageCleanupCandidate,
    ): StorageMaintenanceActionResult {
        val targetDirectory = candidate.suggestedDirectory
            ?: return StorageMaintenanceActionResult(
                status = StorageMaintenanceStatus.SKIPPED,
                volumeId = candidate.volumeId,
                sourceRelativePath = candidate.relativePath,
                detail = "reorganization-target-is-missing",
            )
        val root = requireRoot(candidate.volumeId)
        val source = resolveInside(root.root, candidate.relativePath)
        if (!source.exists() || !source.isFile || !matchesObservedRevision(source, candidate)) {
            return StorageMaintenanceActionResult(
                status = StorageMaintenanceStatus.SKIPPED,
                volumeId = candidate.volumeId,
                sourceRelativePath = candidate.relativePath,
                detail = "source-file-missing-or-changed-since-analysis",
            )
        }
        val preferred = resolveInside(
            root.root,
            targetDirectory.trimEnd('/') + "/" + source.name,
        )
        val destination = uniqueDestination(
            preferred = preferred,
            stableSuffix = candidate.expectedModifiedAtMillis?.toString(16).orEmpty().ifBlank { "lifeos" },
        )
        if (source.canonicalFile == destination.canonicalFile) {
            return StorageMaintenanceActionResult(
                status = StorageMaintenanceStatus.SKIPPED,
                volumeId = candidate.volumeId,
                sourceRelativePath = candidate.relativePath,
                detail = "file-is-already-organized",
            )
        }
        val resource = "reorganize/" + candidate.volumeId + "/" + candidate.relativePath
        return when (
            val exposure = PrivateOwnerEffectAuthority.expose(
                context = context,
                request = PrivateOwnerEffectAuthority.storageMaintenanceRequest(resource),
            ) {
                moveExact(source, destination)
            }
        ) {
            is OwnerEffectExposureResult.Blocked -> StorageMaintenanceActionResult(
                status = StorageMaintenanceStatus.BLOCKED,
                volumeId = candidate.volumeId,
                sourceRelativePath = candidate.relativePath,
                destinationRelativePath = relative(root.root, destination),
                detail = "owner-policy-blocked-reorganization",
            )
            is OwnerEffectExposureResult.Exposed -> StorageMaintenanceActionResult(
                status = StorageMaintenanceStatus.REORGANIZED,
                volumeId = candidate.volumeId,
                sourceRelativePath = candidate.relativePath,
                destinationRelativePath = relative(root.root, destination),
                detail = "moved-into-category-directory",
            )
        }
    }

    private fun reconcilePrepared() {
        store.loadByStates(setOf(StorageTrashState.PREPARED)).forEach { record ->
            val root = roots().firstOrNull { it.id == record.volumeId } ?: return@forEach
            val source = resolveInside(root.root, record.originalRelativePath)
            val destination = resolveInside(root.root, record.trashRelativePath)
            if (!source.exists() && destination.exists() && destination.isFile) {
                store.settle(
                    id = record.id,
                    expected = StorageTrashState.PREPARED,
                    state = StorageTrashState.TRASHED,
                    settledAt = now(),
                )
            }
        }
    }

    private fun matchesObservedRevision(
        source: File,
        candidate: StorageCleanupCandidate,
    ): Boolean {
        if (source.length().coerceAtLeast(0L) != candidate.reclaimableBytes &&
            candidate.kind != StorageCleanupKind.REORGANIZE &&
            candidate.kind != StorageCleanupKind.LARGE_REVIEW
        ) {
            return false
        }
        val expectedModified = candidate.expectedModifiedAtMillis
        return expectedModified == null ||
            source.lastModified().coerceAtLeast(0L) == expectedModified
    }

    private fun requireRoot(volumeId: String): SharedStorageRoot =
        requireNotNull(roots().firstOrNull { it.id == volumeId }) {
            "Storage volume is not currently available"
        }

    private fun resolveInside(root: File, relativePath: String): File {
        require(relativePath.isNotBlank())
        require(!relativePath.startsWith("/"))
        val rootCanonical = root.canonicalFile
        val target = File(rootCanonical, relativePath).canonicalFile
        require(
            target.path == rootCanonical.path ||
                target.path.startsWith(rootCanonical.path + File.separator)
        ) {
            "Storage maintenance path escaped its volume root"
        }
        return target
    }

    private fun uniqueDestination(
        preferred: File,
        stableSuffix: String,
    ): File {
        if (!preferred.exists()) return preferred
        val parent = requireNotNull(preferred.parentFile)
        val name = preferred.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var attempt = 0
        while (attempt < MAX_DESTINATION_ATTEMPTS) {
            val suffix = if (attempt == 0) stableSuffix else stableSuffix + "-" + attempt
            val candidate = File(parent, base + "~lifeos-" + suffix + extension)
            if (!candidate.exists()) return candidate
            attempt += 1
        }
        error("Could not find a collision-free storage maintenance destination")
    }

    private fun moveExact(source: File, destination: File) {
        require(source.exists() && source.isFile)
        require(!destination.exists()) { "Storage maintenance never overwrites an existing file" }
        destination.parentFile?.let { parent ->
            check(parent.mkdirs() || parent.isDirectory) {
                "Could not create storage maintenance destination directory"
            }
        }
        runCatching {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(source.toPath(), destination.toPath())
        }
        check(!source.exists() && destination.exists()) {
            "Storage maintenance move did not reach its exact destination"
        }
    }

    private fun relative(root: File, file: File): String =
        root.canonicalFile.toPath().relativize(file.canonicalFile.toPath())
            .toString()
            .replace(File.separatorChar, '/')

    private fun pruneEmptyTrashParents(root: File, start: File?) {
        val trashRoot = resolveInside(root, StorageTreePager.TRASH_ROOT)
        var current = start
        while (current != null && current != trashRoot && current.path.startsWith(trashRoot.path)) {
            val children = current.listFiles()
            if (children != null && children.isEmpty()) {
                current.delete()
                current = current.parentFile
            } else {
                break
            }
        }
    }

    private companion object {
        const val PREFS = "lifeos-storage-maintenance"
        const val KEY_ORGANIZATION_CURSOR = "organization-cursor"
        val CRITICAL_THERMAL = setOf("CRITICAL", "EMERGENCY", "SHUTDOWN")
        val DEFAULT_TRASH_RETENTION: Duration = Duration.ofDays(30)
        val REVERSIBLE_TRASH_KINDS = setOf(
            StorageCleanupKind.EXACT_DUPLICATE,
            StorageCleanupKind.TEMPORARY_FILE,
            StorageCleanupKind.STALE_INSTALLER,
        )
        const val MAX_DESTINATION_ATTEMPTS = 100
    }
}
