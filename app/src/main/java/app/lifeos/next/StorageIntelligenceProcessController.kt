package app.lifeos.next

import app.lifeos.next.kernel.HardwareResourceIntelligenceRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class StorageIntelligenceProcessController(
    private val storage: AndroidStorageIntelligenceRuntime,
    private val hardware: HardwareResourceIntelligenceRuntime,
    private val canRun: () -> Boolean,
    private val onSnapshot: (StorageIntelligenceSnapshot) -> Unit,
    private val onFailure: (String?) -> Unit,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private var job: Job? = null

    fun refresh() {
        if (!canRun()) return
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                while (currentCoroutineContext().isActive) {
                    if (!canRun()) break

                    val snapshot = storage.runNextSlice()
                    onSnapshot(snapshot)
                    onFailure(null)

                    val currentHardware = hardware.currentHardwareSnapshot()
                    val batteryFraction = currentHardware.batteryFraction
                    val delayMillis = when {
                        snapshot.contentReadComplete &&
                            currentHardware.charging == true ->
                            COMPLETE_RESCAN_CHARGING_MILLIS

                        snapshot.contentReadComplete ->
                            COMPLETE_RESCAN_IDLE_MILLIS

                        currentHardware.charging == true ->
                            1_000L

                        batteryFraction != null &&
                            batteryFraction < 0.20 ->
                            60_000L

                        else ->
                            15_000L
                    }
                    delay(delayMillis)
                }
            } catch (error: Exception) {
                onFailure(
                    error.message ?: error::class.simpleName
                    ?: "storage-intelligence-failed",
                )
            }
        }
    }

    private companion object {
        const val COMPLETE_RESCAN_CHARGING_MILLIS =
            5L * 60L * 1_000L
        const val COMPLETE_RESCAN_IDLE_MILLIS =
            15L * 60L * 1_000L
    }
}
