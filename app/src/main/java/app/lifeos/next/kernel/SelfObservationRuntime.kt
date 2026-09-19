package app.lifeos.next.kernel

import app.lifeos.core.data.LiveSourceSyncResult
import app.lifeos.core.data.LiveSourceSyncSnapshot
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatus
import app.lifeos.core.runtime.health.HealthSnapshot
import app.lifeos.core.runtime.health.SelfHealingIncidentSnapshot
import app.lifeos.core.runtime.life.DurableLifeMemorySnapshot
import app.lifeos.core.runtime.resource.HardwareStateSnapshot
import app.lifeos.core.runtime.self.SelfLiveSourceProjectionInput
import app.lifeos.core.runtime.self.SelfObservationAuthorityReader
import app.lifeos.core.runtime.self.SelfObservationSource
import app.lifeos.core.runtime.self.SelfStateProjectionInputs
import app.lifeos.core.runtime.self.SelfStateProjectionResult
import app.lifeos.core.runtime.self.SelfStateProjector
import app.lifeos.core.runtime.topology.LifeOsRuntimeTopologySnapshot
import java.time.Instant

/**
 * APK read-only bridge for verified self observation. Every dependency is a reader function so this
 * runtime cannot acquire mutation authority over the underlying stores.
 */
internal class SelfObservationRuntime(
    private val photonIndex: suspend () -> PhotonIndexReport,
    private val memorySnapshot: () -> DurableLifeMemorySnapshot?,
    private val authorityReader: SelfObservationAuthorityReader,
    private val topologySnapshot: suspend () -> LifeOsRuntimeTopologySnapshot?,
    private val healthSnapshot: suspend () -> HealthSnapshot,
    private val hardwareSnapshot: () -> HardwareStateSnapshot,
    private val toolStatus: suspend () -> GeneratedToolRuntimeStatus,
    private val activeRepairs: suspend () -> List<SelfHealingIncidentSnapshot>,
    private val liveSourceSnapshot: () -> LiveSourceSyncSnapshot?,
    private val liveSourceFailure: () -> String?,
    private val now: () -> Instant = Instant::now,
    private val projector: SelfStateProjector = SelfStateProjector(),
) {
    suspend fun capture(): SelfStateProjectionResult = projector.project(
        SelfStateProjectionInputs(
            capturedAt = now(),
            photonIndex = source { photonIndex() },
            memory = memorySnapshot()?.let { SelfObservationSource.Available(it) }
                ?: SelfObservationSource.Unavailable("life-memory-not-projected"),
            authorities = source { authorityReader.snapshot() },
            runtimeTopology = sourceNullable(
                unavailable = "runtime-topology-not-ready",
                block = topologySnapshot,
            ),
            health = source { healthSnapshot() },
            resources = sourceSync { hardwareSnapshot() },
            recovery = source { activeRepairs() },
            tools = source { toolStatus() },
            liveSources = liveSourceProjection(),
        )
    )

    private suspend fun <T> source(block: suspend () -> T): SelfObservationSource<T> = try {
        SelfObservationSource.Available(block())
    } catch (error: Exception) {
        SelfObservationSource.Failed(error.message ?: error::class.simpleName ?: "self-observation-read-failed")
    }

    private fun <T> sourceSync(block: () -> T): SelfObservationSource<T> = try {
        SelfObservationSource.Available(block())
    } catch (error: Exception) {
        SelfObservationSource.Failed(error.message ?: error::class.simpleName ?: "self-observation-read-failed")
    }

    private suspend fun <T : Any> sourceNullable(
        unavailable: String,
        block: suspend () -> T?,
    ): SelfObservationSource<T> = try {
        block()?.let { SelfObservationSource.Available(it) }
            ?: SelfObservationSource.Unavailable(unavailable)
    } catch (error: Exception) {
        SelfObservationSource.Failed(error.message ?: error::class.simpleName ?: "self-observation-read-failed")
    }

    private fun liveSourceProjection(): SelfObservationSource<SelfLiveSourceProjectionInput> {
        val snapshot = liveSourceSnapshot()
        if (snapshot == null) {
            val failure = liveSourceFailure()
            return if (failure.isNullOrBlank()) {
                SelfObservationSource.Unavailable("live-source-sync-not-observed")
            } else {
                SelfObservationSource.Failed(failure)
            }
        }
        val healthy = linkedSetOf<String>()
        val blocked = linkedSetOf<String>()
        val failed = linkedSetOf<String>()
        snapshot.results.forEach { result ->
            val id = result.sourceId.value
            when (result) {
                is LiveSourceSyncResult.Bootstrapped,
                is LiveSourceSyncResult.IdleWithoutIncrementalCursor,
                is LiveSourceSyncResult.Advanced -> healthy += id

                is LiveSourceSyncResult.PermissionBlocked,
                is LiveSourceSyncResult.CapacityBlocked,
                is LiveSourceSyncResult.DeltaBlocked -> blocked += id

                is LiveSourceSyncResult.IdentityChanged,
                is LiveSourceSyncResult.StateUnreadable,
                is LiveSourceSyncResult.StateConflict,
                is LiveSourceSyncResult.SourceUnavailable -> failed += id
            }
        }
        val all = snapshot.results.mapTo(linkedSetOf()) { it.sourceId.value }
        return SelfObservationSource.Available(
            SelfLiveSourceProjectionInput(
                sourceIds = all,
                healthySourceIds = healthy,
                blockedSourceIds = blocked,
                failedSourceIds = failed,
                sourceStateFingerprint = liveSourceFingerprint(snapshot),
            )
        )
    }

    private fun liveSourceFingerprint(snapshot: LiveSourceSyncSnapshot): String =
        StableFieldIds.fingerprint(
            "lifeos-self-live-sources/v1",
            *snapshot.results.sortedBy { it.sourceId.value }
                .flatMap(::liveSourceParts)
                .toTypedArray(),
        )

    private fun liveSourceParts(result: LiveSourceSyncResult): List<String> = when (result) {
        is LiveSourceSyncResult.PermissionBlocked -> listOf(
            sourceBase(result, "permission-blocked"),
            "reasons:" + result.reasons.sorted().joinToString("|"),
            "revision:" + result.state.revision,
        )
        is LiveSourceSyncResult.IdentityChanged -> listOf(
            sourceBase(result, "identity-changed"),
            "durable:" + result.durableIdentityFingerprint,
            "observed:" + result.observedIdentityFingerprint,
        )
        is LiveSourceSyncResult.StateUnreadable -> listOf(
            sourceBase(result, "state-unreadable"),
            "message:" + result.message,
        )
        is LiveSourceSyncResult.StateConflict -> listOf(
            sourceBase(result, "state-conflict"),
            "revision:" + (result.actualRevision?.toString() ?: "null"),
        )
        is LiveSourceSyncResult.SourceUnavailable -> listOf(
            sourceBase(result, "source-unavailable"),
            "message:" + result.message,
            "revision:" + (result.state?.revision?.toString() ?: "null"),
        )
        is LiveSourceSyncResult.CapacityBlocked -> listOf(
            sourceBase(result, "capacity-blocked"),
            "objects:" + result.distinctObjectCount,
            "capacity:" + result.capacity,
            "revision:" + result.state.revision,
        )
        is LiveSourceSyncResult.Bootstrapped -> listOf(
            sourceBase(result, "bootstrapped"),
            "items:" + result.inventoryItemCount,
            "revision:" + result.state.revision,
        )
        is LiveSourceSyncResult.IdleWithoutIncrementalCursor -> listOf(
            sourceBase(result, "idle"),
            "revision:" + result.state.revision,
        )
        is LiveSourceSyncResult.Advanced -> listOf(
            sourceBase(result, "advanced"),
            "observed:" + result.observedDeltaCount,
            "coalesced:" + result.coalescedDeltaCount,
            "accepted:" + result.acceptedDeltaCount,
            "revision:" + result.state.revision,
        )
        is LiveSourceSyncResult.DeltaBlocked -> listOf(
            sourceBase(result, "delta-blocked"),
            "delta:" + result.deltaId,
            "reasons:" + result.reasons.sorted().joinToString("|"),
            "revision:" + result.state.revision,
        )
    }

    private fun sourceBase(result: LiveSourceSyncResult, kind: String): String =
        "source:" + result.sourceId.value + ":" + kind
}
