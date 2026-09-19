package app.lifeos.core.data

import app.lifeos.core.model.Photon
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.livedata.LiveDataAccountObservation
import app.lifeos.core.runtime.livedata.LiveDataDelta
import app.lifeos.core.runtime.livedata.LiveDataHub
import app.lifeos.core.runtime.livedata.LiveDataIngestResult
import app.lifeos.core.runtime.livedata.LiveDataPermissionState
import app.lifeos.core.runtime.livedata.LiveDataStreamKind
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LiveSourceCursorState(
    val revision: Long,
    val sourceId: LiveSourceId,
    val connectorIdentityFingerprint: String,
    val cursor: SourceCursor?,
    val bootstrapped: Boolean,
    val baselineFingerprint: String?,
    val lastObservationRevision: Long,
    val updatedAt: Instant,
) {
    init {
        require(revision > 0L)
        require(connectorIdentityFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(baselineFingerprint == null || baselineFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(lastObservationRevision >= 0L)
        require(bootstrapped || cursor == null) {
            "Unbootstrapped live source cannot own a cursor"
        }
        require(bootstrapped || baselineFingerprint == null) {
            "Unbootstrapped live source cannot own a baseline fingerprint"
        }
    }

    fun bootstrap(
        nextCursor: SourceCursor?,
        baselineFingerprint: String,
        at: Instant,
    ): LiveSourceCursorState {
        require(!bootstrapped)
        require(baselineFingerprint.matches(Regex("[0-9a-f]{64}")))
        return copy(
            revision = Math.addExact(revision, 1L),
            cursor = nextCursor,
            bootstrapped = true,
            baselineFingerprint = baselineFingerprint,
            updatedAt = at,
        )
    }

    fun advance(
        nextCursor: SourceCursor,
        nextObservationRevision: Long,
        at: Instant,
    ): LiveSourceCursorState {
        require(bootstrapped)
        require(nextObservationRevision >= lastObservationRevision)
        return copy(
            revision = Math.addExact(revision, 1L),
            cursor = nextCursor,
            lastObservationRevision = nextObservationRevision,
            updatedAt = at,
        )
    }

    companion object {
        fun initial(
            sourceId: LiveSourceId,
            connectorIdentityFingerprint: String,
            at: Instant,
        ): LiveSourceCursorState = LiveSourceCursorState(
            revision = 1L,
            sourceId = sourceId,
            connectorIdentityFingerprint = connectorIdentityFingerprint,
            cursor = null,
            bootstrapped = false,
            baselineFingerprint = null,
            lastObservationRevision = 0L,
            updatedAt = at,
        )
    }
}

sealed interface LiveSourceCursorLoadResult {
    data object Missing : LiveSourceCursorLoadResult
    data class Loaded(val state: LiveSourceCursorState) : LiveSourceCursorLoadResult
    data class Unreadable(val message: String) : LiveSourceCursorLoadResult {
        init { require(message.isNotBlank()) }
    }
}

sealed interface LiveSourceCursorWriteResult {
    data class Saved(val state: LiveSourceCursorState) : LiveSourceCursorWriteResult
    data class Conflict(val actualRevision: Long?) : LiveSourceCursorWriteResult
    data class UnreadableExisting(val message: String) : LiveSourceCursorWriteResult {
        init { require(message.isNotBlank()) }
    }
}

interface LiveSourceCursorRepository {
    suspend fun load(sourceId: LiveSourceId): LiveSourceCursorLoadResult

    suspend fun compareAndSet(
        sourceId: LiveSourceId,
        expectedRevision: Long?,
        next: LiveSourceCursorState,
    ): LiveSourceCursorWriteResult
}

interface LiveSourceConnector {
    val adapter: LiveSourceAdapter
    val streamKind: LiveDataStreamKind
    val connectorVersion: String
    val priority: LiveSourcePriority
        get() = LiveSourcePriority.NORMAL

    suspend fun accountObservation(): LiveDataAccountObservation

    suspend fun project(delta: SourceDelta): LiveDataDelta?
}

interface LiveDataHubAuthority {
    suspend fun observeAccount(observation: LiveDataAccountObservation): Photon
    suspend fun ingest(delta: LiveDataDelta): LiveDataIngestResult

    companion object {
        fun from(hub: LiveDataHub): LiveDataHubAuthority = object : LiveDataHubAuthority {
            override suspend fun observeAccount(observation: LiveDataAccountObservation): Photon =
                hub.observeAccount(observation)

            override suspend fun ingest(delta: LiveDataDelta): LiveDataIngestResult =
                hub.ingest(delta)
        }
    }
}

sealed interface LiveSourceSyncResult {
    val sourceId: LiveSourceId

    data class PermissionBlocked(
        override val sourceId: LiveSourceId,
        val reasons: List<String>,
        val state: LiveSourceCursorState,
    ) : LiveSourceSyncResult {
        init { require(reasons.isNotEmpty() && reasons.none { it.isBlank() }) }
    }

    data class IdentityChanged(
        override val sourceId: LiveSourceId,
        val durableIdentityFingerprint: String,
        val observedIdentityFingerprint: String,
    ) : LiveSourceSyncResult

    data class StateUnreadable(
        override val sourceId: LiveSourceId,
        val message: String,
    ) : LiveSourceSyncResult

    data class StateConflict(
        override val sourceId: LiveSourceId,
        val actualRevision: Long?,
    ) : LiveSourceSyncResult

    data class SourceUnavailable(
        override val sourceId: LiveSourceId,
        val message: String,
        val state: LiveSourceCursorState?,
    ) : LiveSourceSyncResult {
        init { require(message.isNotBlank()) }
    }

    data class CapacityBlocked(
        override val sourceId: LiveSourceId,
        val distinctObjectCount: Int,
        val capacity: Int,
        val state: LiveSourceCursorState,
    ) : LiveSourceSyncResult {
        init {
            require(distinctObjectCount > capacity)
            require(capacity > 0)
        }
    }

    data class Bootstrapped(
        override val sourceId: LiveSourceId,
        val inventoryItemCount: Int,
        val state: LiveSourceCursorState,
    ) : LiveSourceSyncResult

    data class IdleWithoutIncrementalCursor(
        override val sourceId: LiveSourceId,
        val state: LiveSourceCursorState,
    ) : LiveSourceSyncResult

    data class Advanced(
        override val sourceId: LiveSourceId,
        val observedDeltaCount: Int,
        val coalescedDeltaCount: Int,
        val acceptedDeltaCount: Int,
        val state: LiveSourceCursorState,
    ) : LiveSourceSyncResult

    data class DeltaBlocked(
        override val sourceId: LiveSourceId,
        val deltaId: String,
        val reasons: List<String>,
        val state: LiveSourceCursorState,
    ) : LiveSourceSyncResult {
        init {
            require(deltaId.isNotBlank())
            require(reasons.isNotEmpty() && reasons.none { it.isBlank() })
        }
    }
}

data class LiveSourceSyncSnapshot(
    val results: List<LiveSourceSyncResult>,
) {
    init {
        require(results == results.sortedBy { it.sourceId.value })
        require(results.map { it.sourceId }.distinct().size == results.size)
    }
}

/**
 * Operational connector runner for M01.
 *
 * Payload truth remains canonical Photons owned by LiveDataHub. This class persists only restart
 * metadata through [LiveSourceCursorRepository]: source cursor, baseline fingerprint and source
 * observation revision. Permission/account truth remains the revisioned account Photon.
 */
class LiveSourceDeltaCoordinator(
    connectors: Collection<LiveSourceConnector>,
    private val cursors: LiveSourceCursorRepository,
    private val hub: LiveDataHubAuthority,
    private val maxInventoryItems: Int = DEFAULT_MAX_INVENTORY_ITEMS,
    private val maxRawDeltas: Int = DEFAULT_MAX_RAW_DELTAS,
    private val coalescedCapacity: Int = DEFAULT_COALESCED_CAPACITY,
    private val health: LiveSourceHealthReporter = LiveSourceHealthReporter.NONE,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private val connectors = connectors
        .sortedBy { it.adapter.sourceId.value }
        .associateBy { it.adapter.sourceId }

    init {
        require(connectors.isNotEmpty())
        require(this.connectors.size == connectors.size) {
            "Only one live connector may own a source id"
        }
        require(maxInventoryItems in 1..HARD_MAX_INVENTORY_ITEMS)
        require(maxRawDeltas in 1..HARD_MAX_RAW_DELTAS)
        require(coalescedCapacity in 1..maxRawDeltas)
        this.connectors.values.forEach {
            require(it.connectorVersion.isNotBlank())
            require(it.connectorVersion.length <= 160)
        }
    }

    suspend fun syncAll(): LiveSourceSyncSnapshot = mutex.withLock {
        val ordered = connectors.values.sortedWith(
            compareByDescending<LiveSourceConnector> { it.priority.rank }
                .thenBy { it.adapter.sourceId.value }
        )
        LiveSourceSyncSnapshot(
            ordered.map { syncLocked(it) }.sortedBy { it.sourceId.value }
        )
    }

    suspend fun sync(sourceId: LiveSourceId): LiveSourceSyncResult = mutex.withLock {
        val connector = requireNotNull(connectors[sourceId]) {
            "Unknown live source: " + sourceId.value
        }
        syncLocked(connector)
    }

    private suspend fun syncLocked(connector: LiveSourceConnector): LiveSourceSyncResult {
        val sourceId = connector.adapter.sourceId
        val loaded = cursors.load(sourceId)
        if (loaded is LiveSourceCursorLoadResult.Unreadable) {
            return LiveSourceSyncResult.StateUnreadable(sourceId, loaded.message)
        }

        val rawObservation = try {
            connector.accountObservation()
        } catch (error: Exception) {
            val message = error.message ?: error::class.simpleName ?: "account observation failed"
            health.failed(
                sourceId,
                now(),
                app.lifeos.core.runtime.RuntimeFailureCategory.UNKNOWN,
                message,
                true,
            )
            return LiveSourceSyncResult.SourceUnavailable(sourceId, message, null)
        }
        val observedIdentity = connectorIdentity(connector, rawObservation)
        var state = when (loaded) {
            LiveSourceCursorLoadResult.Missing -> {
                val initial = LiveSourceCursorState.initial(sourceId, observedIdentity, now())
                when (val write = cursors.compareAndSet(sourceId, null, initial)) {
                    is LiveSourceCursorWriteResult.Saved -> write.state
                    is LiveSourceCursorWriteResult.Conflict ->
                        return LiveSourceSyncResult.StateConflict(sourceId, write.actualRevision)
                    is LiveSourceCursorWriteResult.UnreadableExisting ->
                        return LiveSourceSyncResult.StateUnreadable(sourceId, write.message)
                }
            }
            is LiveSourceCursorLoadResult.Loaded -> loaded.state
            is LiveSourceCursorLoadResult.Unreadable -> error("handled above")
        }

        if (state.connectorIdentityFingerprint != observedIdentity) {
            hub.observeAccount(rawObservation.copy(sourceCursor = null))
            health.failed(
                sourceId,
                now(),
                app.lifeos.core.runtime.RuntimeFailureCategory.INVARIANT,
                "live source connector/account identity changed",
                false,
            )
            return LiveSourceSyncResult.IdentityChanged(
                sourceId = sourceId,
                durableIdentityFingerprint = state.connectorIdentityFingerprint,
                observedIdentityFingerprint = observedIdentity,
            )
        }

        val observation = rawObservation.copy(sourceCursor = state.cursor?.value)
        hub.observeAccount(observation)

        val permissionReasons = permissionReasons(observation, connector.streamKind)
        if (permissionReasons.isNotEmpty()) {
            health.healthy(
                sourceId,
                now(),
                "source reachable; permission/capability policy blocks ingestion",
            )
            return LiveSourceSyncResult.PermissionBlocked(sourceId, permissionReasons, state)
        }

        if (!state.bootstrapped) {
            return bootstrap(connector, rawObservation, state)
        }

        val cursor = state.cursor
            ?: return LiveSourceSyncResult.IdleWithoutIncrementalCursor(sourceId, state)
        return advance(connector, rawObservation, state, cursor)
    }

    private suspend fun bootstrap(
        connector: LiveSourceConnector,
        rawObservation: LiveDataAccountObservation,
        state: LiveSourceCursorState,
    ): LiveSourceSyncResult {
        val inventory = try {
            connector.adapter.inventory()
        } catch (error: Exception) {
            val message = error.message ?: error::class.simpleName ?: "source inventory failed"
            health.failed(
                connector.adapter.sourceId,
                now(),
                app.lifeos.core.runtime.RuntimeFailureCategory.UNKNOWN,
                message,
                true,
            )
            return LiveSourceSyncResult.SourceUnavailable(connector.adapter.sourceId, message, state)
        }
        require(inventory.items.size <= maxInventoryItems) {
            "Live source inventory exceeded bounded capacity"
        }
        require(inventory.items.map { it.externalKey }.distinct().size == inventory.items.size) {
            "Live source inventory contains duplicate external keys"
        }
        val baseline = StableCognitiveIds.fingerprint(
            "live-source-baseline/v1",
            connector.adapter.sourceId.value,
            *inventory.items.sortedBy { it.externalKey }.flatMap {
                listOf(it.externalKey, it.fingerprint.orEmpty(), it.privacyZone.name)
            }.toTypedArray(),
        )
        val next = state.bootstrap(inventory.cursor, baseline, now())
        val durable = persistState(state, next)
            ?: return currentConflict(connector.adapter.sourceId)

        // Raw cursor remains in the encrypted operational repository. LiveDataHub persists only its
        // fingerprint inside the account Photon.
        hub.observeAccount(rawObservation.copy(sourceCursor = durable.cursor?.value))
        health.healthy(
            connector.adapter.sourceId,
            now(),
            "source inventory bootstrapped at cursor revision " + durable.revision,
        )
        return LiveSourceSyncResult.Bootstrapped(
            sourceId = connector.adapter.sourceId,
            inventoryItemCount = inventory.items.size,
            state = durable,
        )
    }

    private suspend fun advance(
        connector: LiveSourceConnector,
        rawObservation: LiveDataAccountObservation,
        state: LiveSourceCursorState,
        cursor: SourceCursor,
    ): LiveSourceSyncResult {
        val changes = try {
            connector.adapter.changesAfter(cursor)
        } catch (error: Exception) {
            val message = error.message ?: error::class.simpleName ?: "source delta read failed"
            health.failed(
                connector.adapter.sourceId,
                now(),
                app.lifeos.core.runtime.RuntimeFailureCategory.UNKNOWN,
                message,
                true,
            )
            return LiveSourceSyncResult.SourceUnavailable(connector.adapter.sourceId, message, state)
        }
        require(changes.deltas.size <= maxRawDeltas) {
            "Live source change set exceeded bounded capacity"
        }
        require(changes.deltas.map { it.deltaId }.distinct().size == changes.deltas.size) {
            "Live source change set contains duplicate delta ids"
        }
        require(changes.deltas.all { it.sourceId == connector.adapter.sourceId }) {
            "Live source change set contains foreign source deltas"
        }

        val fresh = changes.deltas.filter {
            it.observationRevision > state.lastObservationRevision
        }
        val coalesced = try {
            SourceDeltaCoalescer(coalescedCapacity).coalesce(fresh)
        } catch (overflow: SourceDeltaCapacityExceededException) {
            health.failed(
                connector.adapter.sourceId,
                now(),
                app.lifeos.core.runtime.RuntimeFailureCategory.UNKNOWN,
                overflow.message ?: "live source delta capacity exceeded",
                true,
            )
            return LiveSourceSyncResult.CapacityBlocked(
                sourceId = connector.adapter.sourceId,
                distinctObjectCount = overflow.distinctObjectCount,
                capacity = overflow.capacity,
                state = state,
            )
        }
        var accepted = 0

        for (sourceDelta in coalesced) {
            val projected = connector.project(sourceDelta) ?: continue
            validateProjection(connector, rawObservation, sourceDelta, projected)
            when (val result = hub.ingest(projected)) {
                is LiveDataIngestResult.Accepted -> accepted += 1
                is LiveDataIngestResult.Blocked -> {
                    return LiveSourceSyncResult.DeltaBlocked(
                        sourceId = connector.adapter.sourceId,
                        deltaId = sourceDelta.deltaId,
                        reasons = result.reasons,
                        state = state,
                    )
                }
            }
        }

        val nextObservationRevision = maxOf(
            state.lastObservationRevision,
            changes.deltas.maxOfOrNull { it.observationRevision } ?: state.lastObservationRevision,
        )

        if (
            changes.nextCursor == state.cursor &&
            nextObservationRevision == state.lastObservationRevision
        ) {
            return LiveSourceSyncResult.Advanced(
                sourceId = connector.adapter.sourceId,
                observedDeltaCount = changes.deltas.size,
                coalescedDeltaCount = coalesced.size,
                acceptedDeltaCount = accepted,
                state = state,
            )
        }

        val next = state.advance(changes.nextCursor, nextObservationRevision, now())
        val durable = persistState(state, next)
            ?: return currentConflict(connector.adapter.sourceId)
        hub.observeAccount(rawObservation.copy(sourceCursor = durable.cursor?.value))
        health.healthy(
            connector.adapter.sourceId,
            now(),
            "source delta batch committed through cursor revision " + durable.revision,
        )

        return LiveSourceSyncResult.Advanced(
            sourceId = connector.adapter.sourceId,
            observedDeltaCount = changes.deltas.size,
            coalescedDeltaCount = coalesced.size,
            acceptedDeltaCount = accepted,
            state = durable,
        )
    }

    private suspend fun persistState(
        previous: LiveSourceCursorState,
        next: LiveSourceCursorState,
    ): LiveSourceCursorState? {
        return when (
            val write = cursors.compareAndSet(
                sourceId = previous.sourceId,
                expectedRevision = previous.revision,
                next = next,
            )
        ) {
            is LiveSourceCursorWriteResult.Saved -> write.state
            is LiveSourceCursorWriteResult.UnreadableExisting -> null
            is LiveSourceCursorWriteResult.Conflict -> {
                when (val current = cursors.load(previous.sourceId)) {
                    is LiveSourceCursorLoadResult.Loaded -> current.state.takeIf {
                        it.connectorIdentityFingerprint == next.connectorIdentityFingerprint &&
                            it.bootstrapped == next.bootstrapped &&
                            it.cursor == next.cursor &&
                            it.lastObservationRevision >= next.lastObservationRevision
                    }
                    else -> null
                }
            }
        }
    }

    private suspend fun currentConflict(sourceId: LiveSourceId): LiveSourceSyncResult =
        when (val current = cursors.load(sourceId)) {
            is LiveSourceCursorLoadResult.Loaded ->
                LiveSourceSyncResult.StateConflict(sourceId, current.state.revision)
            LiveSourceCursorLoadResult.Missing ->
                LiveSourceSyncResult.StateConflict(sourceId, null)
            is LiveSourceCursorLoadResult.Unreadable ->
                LiveSourceSyncResult.StateUnreadable(sourceId, current.message)
        }

    private fun connectorIdentity(
        connector: LiveSourceConnector,
        observation: LiveDataAccountObservation,
    ): String = StableCognitiveIds.fingerprint(
        "live-source-connector-identity/v1",
        connector.adapter.sourceId.value,
        connector.connectorVersion,
        connector.streamKind.name,
        observation.connectorId.value,
        observation.accountFingerprint,
    )

    private fun permissionReasons(
        observation: LiveDataAccountObservation,
        kind: LiveDataStreamKind,
    ): List<String> = buildList {
        if (kind.requiredCapability !in observation.capabilities) {
            add("capability-unavailable:" + kind.requiredCapability.name.lowercase())
        }
        if (observation.permissions[kind.requiredPermission] != LiveDataPermissionState.GRANTED) {
            add("permission-not-granted:" + kind.requiredPermission.name.lowercase())
        }
    }.sorted()

    private fun validateProjection(
        connector: LiveSourceConnector,
        observation: LiveDataAccountObservation,
        sourceDelta: SourceDelta,
        projected: LiveDataDelta,
    ) {
        require(projected.connectorId == observation.connectorId)
        require(projected.accountKey == observation.accountKey)
        require(projected.kind == connector.streamKind)
        require(projected.externalId == sourceDelta.externalKey) {
            "Projected live-data external id must preserve exact source identity"
        }
    }

    private companion object {
        const val DEFAULT_MAX_INVENTORY_ITEMS = 4096
        const val DEFAULT_MAX_RAW_DELTAS = 4096
        const val DEFAULT_COALESCED_CAPACITY = 512
        const val HARD_MAX_INVENTORY_ITEMS = 16_384
        const val HARD_MAX_RAW_DELTAS = 16_384
    }
}
