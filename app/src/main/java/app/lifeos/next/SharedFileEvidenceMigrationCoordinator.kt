package app.lifeos.next

import android.content.Context
import app.lifeos.core.data.LiveSourceSyncResult
import app.lifeos.core.data.LiveSourceSyncSnapshot
import app.lifeos.core.runtime.life.DurableLifeMemoryRuntime
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One-way migration barrier from legacy shared-file LifeSource metadata to semantic LiveData files.
 *
 * Legacy evidence is retired only after Storage Intelligence completed a full inventory and the
 * incremental file connector durably consumed every journal revision visible at that boundary.
 */
internal class SharedFileEvidenceMigrationCoordinator(
    context: Context,
    private val memory: DurableLifeMemoryRuntime,
    private val inventory: AndroidStorageInventoryStore =
        AndroidStorageInventoryStore(context),
    private val now: () -> Instant = Instant::now,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Volatile
    private var storageSnapshot: StorageIntelligenceSnapshot? = null

    @Volatile
    private var liveSnapshot: LiveSourceSyncSnapshot? = null

    private val mutex = Mutex()

    fun onStorageSnapshot(snapshot: StorageIntelligenceSnapshot) {
        storageSnapshot = snapshot
        schedule()
    }

    fun onLiveSnapshot(snapshot: LiveSourceSyncSnapshot) {
        liveSnapshot = snapshot
        schedule()
    }

    private fun schedule() {
        scope.launch {
            mutex.withLock {
                retireIfReplacementCaughtUp()
            }
        }
    }

    private suspend fun retireIfReplacementCaughtUp() {
        val storage = storageSnapshot ?: return
        if (!storage.inventoryComplete) return

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
        val journalHead =
            inventory.currentChangeRevision()

        if (cursorRevision < journalHead) return

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
