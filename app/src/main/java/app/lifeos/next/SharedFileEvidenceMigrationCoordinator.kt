package app.lifeos.next

import android.content.Context
import app.lifeos.core.data.LiveSourceSyncResult
import app.lifeos.core.data.LiveSourceSyncSnapshot
import app.lifeos.core.runtime.life.DurableLifeMemoryRuntime
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * One-way migration barrier from legacy shared-file LifeSource metadata to semantic LiveData files.
 *
 * Legacy evidence is retired only after Storage Intelligence completed a full inventory and the
 * incremental file connector durably consumed every journal revision visible at that boundary.
 * Durable journal rows are compacted only after the LiveSource cursor itself was committed.
 */
internal class SharedFileEvidenceMigrationCoordinator(
    private val memory: DurableLifeMemoryRuntime,
    private val inventory: StorageChangeJournal,
    private val now: () -> Instant = Instant::now,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(
        context: Context,
        memory: DurableLifeMemoryRuntime,
    ) : this(
        memory = memory,
        inventory = AndroidStorageInventoryStore(context),
    )

    @Volatile
    private var storageSnapshot: StorageIntelligenceSnapshot? = null

    @Volatile
    private var liveSnapshot: LiveSourceSyncSnapshot? = null

    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val worker = scope.launch {
        for (signal in signals) {
            reconcile()
        }
    }

    fun onStorageSnapshot(snapshot: StorageIntelligenceSnapshot) {
        storageSnapshot = snapshot
        schedule()
    }

    fun onLiveSnapshot(snapshot: LiveSourceSyncSnapshot) {
        liveSnapshot = snapshot
        schedule()
    }

    private fun schedule() {
        check(worker.isActive) {
            "Shared-file migration worker is not active"
        }
        signals.trySend(Unit)
    }

    private suspend fun reconcile() {
        val result = liveSnapshot
            ?.results
            ?.singleOrNull {
                it.sourceId.value ==
                    AndroidSharedFilesInitialDataSource.SOURCE_ID
            }
            ?: return

        val state = when (result) {
            is LiveSourceSyncResult.Bootstrapped ->
                result.state

            is LiveSourceSyncResult.Advanced ->
                result.state

            else -> return
        }

        val cursorRevision =
            state.cursor?.value?.toLongOrNull()
                ?: return

        // Cursor persistence happens after every projected delta was accepted by LiveDataHub.
        // Only that durable boundary is allowed to release historical journal rows.
        inventory.pruneChangesThrough(cursorRevision)

        val storage = storageSnapshot ?: return
        if (!storage.inventoryComplete) return

        val pendingHead =
            inventory.currentChangeRevision()
        if (pendingHead > cursorRevision) return

        memory.retireSourceEvidence(
            sourceId =
                AndroidSharedFilesInitialDataSource.SOURCE_ID,
            replacementAuthority =
                REPLACEMENT_AUTHORITY,
            at = now(),
        )
    }

    private companion object {
        const val REPLACEMENT_AUTHORITY =
            "live-data:android-files"
    }
}
