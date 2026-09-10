package app.lifeos.core.runtime.workers

import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthState

/** Ordered implementation version for one logical worker identity. */
data class WorkerVersion(
    val major: Int,
    val minor: Int = 0,
    val patch: Int = 0,
) : Comparable<WorkerVersion> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) {
            "Worker version components must not be negative"
        }
    }

    override fun compareTo(other: WorkerVersion): Int {
        val majorResult = major.compareTo(other.major)
        if (majorResult != 0) return majorResult
        val minorResult = minor.compareTo(other.minor)
        if (minorResult != 0) return minorResult
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"
}

enum class WorkerRuntimeState {
    REGISTERED,
    READY,
    BUSY,
    DRAINING,
    STOPPED,
}

/** Runtime load projection. Durable task leases remain authoritative for actual task ownership. */
data class WorkerLoad(
    val activeWork: Int,
    val capacity: Int,
) {
    init {
        require(activeWork >= 0) { "Active worker load must not be negative" }
        require(capacity > 0) { "Worker capacity must be positive" }
    }

    val utilization: Double
        get() = activeWork.toDouble() / capacity.toDouble()

    val saturated: Boolean
        get() = activeWork >= capacity
}

/** Read-only health projection sourced from HealthGraph; the registry never owns health truth. */
data class WorkerHealth(
    val nodeId: HealthNodeId,
    val state: HealthState,
)

data class WorkerDescriptor(
    val workerId: WorkerId,
    val version: WorkerVersion,
    val capabilities: Set<CapabilityId>,
    val maxConcurrency: Int,
    val implementationFingerprint: String,
    val healthNodeId: HealthNodeId = HealthNodeId("worker:${workerId.value}"),
) {
    init {
        require(capabilities.isNotEmpty()) { "Worker must declare at least one capability" }
        require(maxConcurrency > 0) { "Worker max concurrency must be positive" }
        require(implementationFingerprint.isNotBlank()) {
            "Worker implementation fingerprint must not be blank"
        }
        require(implementationFingerprint.length <= 256) {
            "Worker implementation fingerprint too long"
        }
    }
}

data class WorkerRegistryEntry(
    val descriptor: WorkerDescriptor,
    val state: WorkerRuntimeState,
    val load: WorkerLoad,
) {
    init {
        require(load.capacity == descriptor.maxConcurrency) {
            "Worker load capacity must match descriptor max concurrency"
        }
    }
}

data class WorkerView(
    val entry: WorkerRegistryEntry,
    val health: WorkerHealth,
)

enum class WorkerRegistrationConflict {
    STALE_VERSION,
    VERSION_CONFLICT,
    REPLACEMENT_REQUIRES_STOP,
}

sealed interface WorkerRegistrationResult {
    val entry: WorkerRegistryEntry

    data class Registered(
        override val entry: WorkerRegistryEntry,
        val replacedStoppedVersion: WorkerVersion? = null,
    ) : WorkerRegistrationResult

    data class AlreadyRegistered(
        override val entry: WorkerRegistryEntry,
    ) : WorkerRegistrationResult

    data class Rejected(
        override val entry: WorkerRegistryEntry,
        val conflict: WorkerRegistrationConflict,
        val requestedVersion: WorkerVersion,
    ) : WorkerRegistrationResult
}

data class WorkerCandidateQuery(
    val capabilityId: CapabilityId,
    val allowDegradedHealth: Boolean = true,
    val allowUnknownHealth: Boolean = true,
)

enum class WorkerExclusionReason {
    CAPABILITY_MISSING,
    STATE_NOT_RUNNABLE,
    SATURATED,
    HEALTH_NOT_ELIGIBLE,
}

data class WorkerCandidate(
    val entry: WorkerRegistryEntry,
    val health: WorkerHealth,
)

data class WorkerExcludedCandidate(
    val entry: WorkerRegistryEntry,
    val health: WorkerHealth,
    val reasons: List<WorkerExclusionReason>,
) {
    init {
        require(reasons.isNotEmpty()) { "Excluded worker must have at least one reason" }
        require(reasons == reasons.distinct().sortedBy { it.ordinal }) {
            "Worker exclusion reasons must be unique and deterministically ordered"
        }
    }
}

data class WorkerCandidateQueryResult(
    val candidates: List<WorkerCandidate>,
    val excluded: List<WorkerExcludedCandidate>,
)

data class WorkerRegistrySnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val revision: Long,
    val entries: List<WorkerRegistryEntry>,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported worker registry snapshot schema: $schemaVersion"
        }
        require(revision >= 0L) { "Worker registry revision must not be negative" }
        require(entries.map { it.descriptor.workerId }.distinct().size == entries.size) {
            "Worker registry snapshot contains duplicate worker ids"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
