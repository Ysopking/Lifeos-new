package app.lifeos.core.runtime.self

import java.time.Instant

enum class SelfObservationDomain {
    PHOTON,
    MEMORY,
    WORLD,
    COGNITION,
    RUNTIME,
    RESOURCE,
    HEALTH,
    RECOVERY,
    TOOLS,
    LIVE_SOURCES,
}

data class SelfPhotonState(
    val latestRevisionCount: Long?,
    val livePhotonCount: Long?,
    val tombstonedPhotonCount: Long?,
    val indexFingerprint: String?,
    val headFingerprint: String? = null,
) {
    init {
        validateCount(latestRevisionCount, "latestRevisionCount")
        validateCount(livePhotonCount, "livePhotonCount")
        validateCount(tombstonedPhotonCount, "tombstonedPhotonCount")
        validateOptionalFingerprint(indexFingerprint, "indexFingerprint")
        validateOptionalFingerprint(headFingerprint, "headFingerprint")
    }
}

data class SelfMemoryState(
    val authoritativePhotonCount: Long?,
    val graphNodeCount: Long?,
    val graphEdgeCount: Long?,
    val memoryFingerprint: String?,
) {
    init {
        validateCount(authoritativePhotonCount, "authoritativePhotonCount")
        validateCount(graphNodeCount, "graphNodeCount")
        validateCount(graphEdgeCount, "graphEdgeCount")
        validateOptionalFingerprint(memoryFingerprint, "memoryFingerprint")
    }
}

data class SelfWorldState(
    val worldHeadRevision: Long?,
    val worldHeadFingerprint: String?,
    val worldEquationRevision: Long?,
    val worldEquationVersion: String?,
    val worldEquationFingerprint: String?,
    val bootCycleId: String?,
    val bootCycleFingerprint: String?,
    val cognitiveSnapshotFingerprint: String?,
) {
    init {
        validateCount(worldHeadRevision, "worldHeadRevision")
        validateCount(worldEquationRevision, "worldEquationRevision")
        validateOptionalFingerprint(worldHeadFingerprint, "worldHeadFingerprint")
        validateOptionalText(worldEquationVersion, "worldEquationVersion")
        validateOptionalText(bootCycleId, "bootCycleId")
        validateOptionalFingerprint(worldEquationFingerprint, "worldEquationFingerprint")
        validateOptionalFingerprint(bootCycleFingerprint, "bootCycleFingerprint")
        validateOptionalFingerprint(cognitiveSnapshotFingerprint, "cognitiveSnapshotFingerprint")
    }
}

data class SelfRuntimeState(
    val topologyFingerprint: String?,
    val registeredSubsystems: Set<String>?,
    val operationalSubsystems: Set<String>?,
    val degradedSubsystems: Set<String>?,
    val unavailableSubsystems: Set<String>?,
    val unboundSubsystems: Set<String>?,
    val telemetry: RuntimeTelemetrySnapshot? = null,
) {
    init {
        validateOptionalFingerprint(topologyFingerprint, "topologyFingerprint")
        validateNames(registeredSubsystems, "registeredSubsystems")
        validateNames(operationalSubsystems, "operationalSubsystems")
        validateNames(degradedSubsystems, "degradedSubsystems")
        validateNames(unavailableSubsystems, "unavailableSubsystems")
        validateNames(unboundSubsystems, "unboundSubsystems")
    }
}

data class SelfResourceState(
    val hardwareFingerprint: String?,
    val memoryHeadroom: Double?,
    val storageHeadroom: Double?,
    val thermalHeadroom: Double?,
    val energyAvailability: Double?,
    val capabilityReadiness: Double?,
) {
    init {
        validateOptionalFingerprint(hardwareFingerprint, "hardwareFingerprint")
        validateRatio(memoryHeadroom, "memoryHeadroom")
        validateRatio(storageHeadroom, "storageHeadroom")
        validateRatio(thermalHeadroom, "thermalHeadroom")
        validateRatio(energyAvailability, "energyAvailability")
        validateRatio(capabilityReadiness, "capabilityReadiness")
    }
}

data class SelfHealthState(
    val healthy: Int?,
    val degraded: Int?,
    val unhealthy: Int?,
    val recovering: Int?,
    val quarantined: Int?,
    val disabled: Int?,
    val unknown: Int? = null,
) {
    init {
        validateCount(healthy, "healthy")
        validateCount(degraded, "degraded")
        validateCount(unhealthy, "unhealthy")
        validateCount(recovering, "recovering")
        validateCount(quarantined, "quarantined")
        validateCount(disabled, "disabled")
        validateCount(unknown, "unknown")
    }
}

data class SelfRecoveryState(
    val activeRepairIds: Set<String>?,
    val recoveryStateFingerprint: String?,
) {
    init {
        validateNames(activeRepairIds, "activeRepairIds")
        validateOptionalFingerprint(recoveryStateFingerprint, "recoveryStateFingerprint")
    }

    val activeRepairs: Int?
        get() = activeRepairIds?.size
}

data class SelfToolState(
    val totalTools: Int?,
    val activeTools: Int?,
    val trialTools: Int?,
    val quarantinedTools: Int?,
    val rejectedTools: Int?,
) {
    init {
        validateCount(totalTools, "totalTools")
        validateCount(activeTools, "activeTools")
        validateCount(trialTools, "trialTools")
        validateCount(quarantinedTools, "quarantinedTools")
        validateCount(rejectedTools, "rejectedTools")
    }
}

data class SelfLiveSourceState(
    val sourceCount: Int?,
    val healthyCount: Int?,
    val blockedCount: Int?,
    val failedCount: Int?,
    val sourceStateFingerprint: String?,
) {
    init {
        validateCount(sourceCount, "sourceCount")
        validateCount(healthyCount, "healthyCount")
        validateCount(blockedCount, "blockedCount")
        validateCount(failedCount, "failedCount")
        validateOptionalFingerprint(sourceStateFingerprint, "sourceStateFingerprint")
    }
}

data class LifeOsSelfStateSnapshot(
    val capturedAt: Instant,
    val photon: SelfPhotonState,
    val memory: SelfMemoryState,
    val world: SelfWorldState,
    val runtime: SelfRuntimeState,
    val resource: SelfResourceState,
    val health: SelfHealthState,
    val recovery: SelfRecoveryState,
    val tools: SelfToolState,
    val liveSources: SelfLiveSourceState,
) {
    val authorityFingerprint: String
        get() = SelfStateFingerprint.authorityFingerprint(
            world = world,
        )

    val stateFingerprint: String
        get() = SelfStateFingerprint.stateFingerprint(this)
}

private fun validateCount(value: Long?, name: String) {
    require(value == null || value >= 0L) { "$name must be non-negative when known" }
}

private fun validateCount(value: Int?, name: String) {
    require(value == null || value >= 0) { "$name must be non-negative when known" }
}

private fun validateRatio(value: Double?, name: String) {
    require(value == null || value.isFinite() && value in 0.0..1.0) {
        "$name must be in 0..1 when known"
    }
}

private fun validateOptionalText(value: String?, name: String) {
    require(value == null || value.isNotBlank()) { "$name must not be blank when known" }
}

private fun validateOptionalFingerprint(value: String?, name: String) =
    validateOptionalText(value, name)

private fun validateNames(values: Set<String>?, name: String) {
    require(values == null || values.none { it.isBlank() }) { "$name must not contain blank ids" }
}
