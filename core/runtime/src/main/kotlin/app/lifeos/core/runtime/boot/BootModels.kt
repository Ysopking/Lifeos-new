package app.lifeos.core.runtime.boot

import java.time.Instant

enum class BootState {
    NOT_STARTED,
    INITIALIZING,
    VERIFYING_STORES,
    RESTORING_RUNTIME,
    RESTORING_PHOTONS,
    RESTORING_MODULES,
    ANALYZING_DELTAS,
    WARMING_COGNITION,
    VALIDATING,
    READY,
    DEGRADED,
    RECOVERING,
    FAILED,
}

enum class StoreState {
    HEALTHY,
    STALE,
    PARTIALLY_RECOVERABLE,
    CORRUPTED,
    LOCKED,
    UNAVAILABLE,
    VERSION_MISMATCH,
}

data class StoreStatus(
    val storeId: String,
    val state: StoreState,
    val message: String? = null,
) {
    init {
        require(storeId.isNotBlank()) { "Store id must not be blank" }
    }
}

data class StoreVerificationResult(
    val stores: List<StoreStatus>,
    val canBootNormally: Boolean,
    val requiresRecovery: Boolean,
)

data class RehydratedRuntimeState(
    val checkpointId: String? = null,
    val recoverableTaskIds: List<String> = emptyList(),
    val interruptedWorkerIds: List<String> = emptyList(),
    val expiredLeaseIds: List<String> = emptyList(),
    val previousEpoch: Long = 0,
) {
    init {
        require(previousEpoch >= 0) { "Previous runtime epoch must not be negative" }
    }
}

data class ModuleRestoreSummary(
    val restored: Int = 0,
    val degraded: Int = 0,
    val failed: Int = 0,
) {
    init {
        require(restored >= 0 && degraded >= 0 && failed >= 0) {
            "Module restore counts must not be negative"
        }
    }
}

data class ThoughtMatrixWarmupResult(
    val snapshotId: String? = null,
    val appliedDeltaCount: Long = 0,
    val degraded: Boolean = false,
) {
    init {
        require(appliedDeltaCount >= 0) { "Applied delta count must not be negative" }
    }
}

data class CapabilityWarmupResult(
    val availableCapabilities: Int = 0,
    val degradedCapabilities: Int = 0,
) {
    init {
        require(availableCapabilities >= 0 && degradedCapabilities >= 0) {
            "Capability counts must not be negative"
        }
    }
}

data class BootSnapshot(
    val bootId: String,
    val startedAt: Instant,
    val state: BootState,
    val previousShutdownId: String? = null,
    val lastCheckpointId: String? = null,
    val restoredPhotonCount: Long = 0,
    val restoredModuleCount: Int = 0,
    val detectedDeltaCount: Long = 0,
    val phaseTimings: List<BootPhaseTiming> = emptyList(),
    val warnings: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
) {
    init {
        require(bootId.isNotBlank()) { "Boot id must not be blank" }
        require(restoredPhotonCount >= 0) { "Restored photon count must not be negative" }
        require(restoredModuleCount >= 0) { "Restored module count must not be negative" }
        require(detectedDeltaCount >= 0) { "Detected delta count must not be negative" }
        require(phaseTimings.map { it.phase }.distinct().size == phaseTimings.size) {
            "Boot phase timings must not contain duplicate phases"
        }
    }
}

data class BootContext(
    val stores: StoreVerificationResult,
    val runtimeState: RehydratedRuntimeState,
    val photons: PhotonRehydrationResult,
    val modules: ModuleRestoreSummary,
    val thoughtMatrix: ThoughtMatrixWarmupResult,
    val capabilities: CapabilityWarmupResult,
)

sealed interface BootValidationResult {
    data object Ready : BootValidationResult

    data class Degraded(
        val limitations: Set<String>,
    ) : BootValidationResult

    data class RecoveryRequired(
        val failures: List<String>,
    ) : BootValidationResult

    data class Fatal(
        val reason: String,
    ) : BootValidationResult
}

sealed interface BootRunResult {
    val snapshot: BootSnapshot

    data class Ready(
        override val snapshot: BootSnapshot,
        val context: BootContext,
    ) : BootRunResult

    data class Degraded(
        override val snapshot: BootSnapshot,
        val context: BootContext,
    ) : BootRunResult

    data class RecoveryRequired(
        override val snapshot: BootSnapshot,
        val context: BootContext,
    ) : BootRunResult

    data class Failed(
        override val snapshot: BootSnapshot,
        val cause: Throwable,
    ) : BootRunResult
}
