package app.lifeos.core.runtime.workers

import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthState

/**
 * Explicit process-level worker topology. Task leases remain in TaskRepository and health truth
 * remains in HealthGraph; this registry only owns worker identity/version/runtime projections.
 */
class WorkerRegistry(
    private val healthGraph: HealthGraph? = null,
) {
    private val lock = Any()
    private val entries = linkedMapOf<WorkerId, WorkerRegistryEntry>()
    private var revision: Long = 0L

    fun register(
        descriptor: WorkerDescriptor,
        initialState: WorkerRuntimeState = WorkerRuntimeState.REGISTERED,
    ): WorkerRegistrationResult = synchronized(lock) {
        val current = entries[descriptor.workerId]
        if (current == null) {
            val registered = entryFor(descriptor, initialState)
            entries[descriptor.workerId] = registered
            revision += 1
            return@synchronized WorkerRegistrationResult.Registered(registered)
        }

        if (current.descriptor == descriptor) {
            return@synchronized WorkerRegistrationResult.AlreadyRegistered(current)
        }

        val versionComparison = descriptor.version.compareTo(current.descriptor.version)
        if (versionComparison < 0) {
            return@synchronized WorkerRegistrationResult.Rejected(
                entry = current,
                conflict = WorkerRegistrationConflict.STALE_VERSION,
                requestedVersion = descriptor.version,
            )
        }
        if (versionComparison == 0) {
            return@synchronized WorkerRegistrationResult.Rejected(
                entry = current,
                conflict = WorkerRegistrationConflict.VERSION_CONFLICT,
                requestedVersion = descriptor.version,
            )
        }
        if (current.state != WorkerRuntimeState.STOPPED) {
            return@synchronized WorkerRegistrationResult.Rejected(
                entry = current,
                conflict = WorkerRegistrationConflict.REPLACEMENT_REQUIRES_STOP,
                requestedVersion = descriptor.version,
            )
        }

        val replacement = entryFor(descriptor, initialState)
        entries[descriptor.workerId] = replacement
        revision += 1
        WorkerRegistrationResult.Registered(
            entry = replacement,
            replacedStoppedVersion = current.descriptor.version,
        )
    }

    fun updateState(workerId: WorkerId, state: WorkerRuntimeState): WorkerRegistryEntry = synchronized(lock) {
        val current = requireEntry(workerId)
        if (current.state == state) return@synchronized current
        val updated = current.copy(state = state)
        entries[workerId] = updated
        revision += 1
        updated
    }

    fun updateLoad(workerId: WorkerId, activeWork: Int): WorkerRegistryEntry = synchronized(lock) {
        val current = requireEntry(workerId)
        val nextLoad = WorkerLoad(
            activeWork = activeWork,
            capacity = current.descriptor.maxConcurrency,
        )
        if (current.load == nextLoad) return@synchronized current
        val updated = current.copy(load = nextLoad)
        entries[workerId] = updated
        revision += 1
        updated
    }

    fun removeStopped(workerId: WorkerId): WorkerRegistryEntry? = synchronized(lock) {
        val current = entries[workerId] ?: return@synchronized null
        require(current.state == WorkerRuntimeState.STOPPED) {
            "Worker ${workerId.value} must be stopped before registry removal"
        }
        entries.remove(workerId)
        revision += 1
        current
    }

    fun entry(workerId: WorkerId): WorkerRegistryEntry? = synchronized(lock) {
        entries[workerId]
    }

    fun all(): List<WorkerRegistryEntry> = synchronized(lock) {
        entries.values.sortedWith(entryOrdering())
    }

    suspend fun query(query: WorkerCandidateQuery): WorkerCandidateQueryResult {
        val currentEntries = all()
        val candidates = mutableListOf<WorkerCandidate>()
        val excluded = mutableListOf<WorkerExcludedCandidate>()

        for (entry in currentEntries) {
            val health = resolveHealth(entry.descriptor)
            val reasons = buildList {
                if (query.capabilityId !in entry.descriptor.capabilities) {
                    add(WorkerExclusionReason.CAPABILITY_MISSING)
                }
                if (entry.state != WorkerRuntimeState.READY && entry.state != WorkerRuntimeState.BUSY) {
                    add(WorkerExclusionReason.STATE_NOT_RUNNABLE)
                }
                if (entry.load.saturated) {
                    add(WorkerExclusionReason.SATURATED)
                }
                if (!healthEligible(health.state, query)) {
                    add(WorkerExclusionReason.HEALTH_NOT_ELIGIBLE)
                }
            }.distinct().sortedBy { it.ordinal }

            if (reasons.isEmpty()) {
                candidates += WorkerCandidate(entry = entry, health = health)
            } else {
                excluded += WorkerExcludedCandidate(
                    entry = entry,
                    health = health,
                    reasons = reasons,
                )
            }
        }

        return WorkerCandidateQueryResult(
            candidates = candidates.sortedWith(candidateOrdering()),
            excluded = excluded.sortedWith(
                compareBy<WorkerExcludedCandidate> { it.entry.descriptor.workerId.value }
                    .thenByDescending { it.entry.descriptor.version }
            ),
        )
    }

    fun snapshot(): WorkerRegistrySnapshot = synchronized(lock) {
        WorkerRegistrySnapshot(
            revision = revision,
            entries = entries.values.sortedWith(entryOrdering()),
        )
    }

    /** Rehydration is intentionally allowed only into an empty registry to prevent live overwrite. */
    fun rehydrate(snapshot: WorkerRegistrySnapshot) = synchronized(lock) {
        require(entries.isEmpty()) { "Worker registry must be empty before rehydration" }
        val canonicalEntries = snapshot.entries.sortedWith(entryOrdering())
        canonicalEntries.forEach { entry ->
            require(entry.load.capacity == entry.descriptor.maxConcurrency) {
                "Worker ${entry.descriptor.workerId.value} load capacity does not match descriptor"
            }
            entries[entry.descriptor.workerId] = entry
        }
        revision = snapshot.revision
    }

    private suspend fun resolveHealth(descriptor: WorkerDescriptor): WorkerHealth {
        val state = healthGraph
            ?.node(descriptor.healthNodeId)
            ?.state
            ?: HealthState.UNKNOWN
        return WorkerHealth(nodeId = descriptor.healthNodeId, state = state)
    }

    private fun healthEligible(state: HealthState, query: WorkerCandidateQuery): Boolean = when (state) {
        HealthState.HEALTHY -> true
        HealthState.DEGRADED -> query.allowDegradedHealth
        HealthState.UNKNOWN -> query.allowUnknownHealth
        HealthState.RECOVERING,
        HealthState.UNHEALTHY,
        HealthState.QUARANTINED,
        HealthState.DISABLED -> false
    }

    private fun entryFor(
        descriptor: WorkerDescriptor,
        state: WorkerRuntimeState,
    ): WorkerRegistryEntry = WorkerRegistryEntry(
        descriptor = descriptor,
        state = state,
        load = WorkerLoad(activeWork = 0, capacity = descriptor.maxConcurrency),
    )

    private fun requireEntry(workerId: WorkerId): WorkerRegistryEntry = requireNotNull(entries[workerId]) {
        "Worker ${workerId.value} is not registered"
    }

    private fun entryOrdering(): Comparator<WorkerRegistryEntry> =
        compareBy<WorkerRegistryEntry> { it.descriptor.workerId.value }
            .thenByDescending { it.descriptor.version }

    private fun candidateOrdering(): Comparator<WorkerCandidate> =
        compareBy<WorkerCandidate> { healthRank(it.health.state) }
            .thenBy { it.entry.load.utilization }
            .thenBy { it.entry.load.activeWork }
            .thenBy { it.entry.descriptor.workerId.value }
            .thenByDescending { it.entry.descriptor.version }

    private fun healthRank(state: HealthState): Int = when (state) {
        HealthState.HEALTHY -> 0
        HealthState.DEGRADED -> 1
        HealthState.UNKNOWN -> 2
        HealthState.RECOVERING -> 3
        HealthState.UNHEALTHY -> 4
        HealthState.QUARANTINED -> 5
        HealthState.DISABLED -> 6
    }
}
