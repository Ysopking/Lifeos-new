package app.lifeos.core.runtime.self

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.runtime.SnapshotVerifier
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatus
import app.lifeos.core.runtime.health.HealthSnapshot
import app.lifeos.core.runtime.health.HealthState
import app.lifeos.core.runtime.health.SelfHealingIncidentSnapshot
import app.lifeos.core.runtime.life.DurableLifeMemorySnapshot
import app.lifeos.core.runtime.resource.HardwareStateSnapshot
import app.lifeos.core.runtime.topology.LifeOsRuntimeTopologySnapshot
import app.lifeos.core.runtime.topology.LifeOsSubsystemState
import java.time.Instant

sealed interface SelfObservationSource<out T> {
    data class Available<T>(val value: T) : SelfObservationSource<T>

    data class Unavailable(val reason: String) : SelfObservationSource<Nothing> {
        init { require(reason.isNotBlank()) }
    }

    data class Failed(val reason: String) : SelfObservationSource<Nothing> {
        init { require(reason.isNotBlank()) }
    }
}

enum class SelfObservationIssueKind {
    UNAVAILABLE,
    FAILED,
    CORRUPT,
}

data class SelfObservationIssue(
    val domain: SelfObservationDomain,
    val kind: SelfObservationIssueKind,
    val detail: String,
) {
    init { require(detail.isNotBlank()) }
}

data class SelfLiveSourceProjectionInput(
    val sourceIds: Set<String>,
    val healthySourceIds: Set<String>,
    val blockedSourceIds: Set<String>,
    val failedSourceIds: Set<String>,
    val sourceStateFingerprint: String,
) {
    init {
        listOf(sourceIds, healthySourceIds, blockedSourceIds, failedSourceIds).forEach { values ->
            require(values.none { it.isBlank() })
        }
        require(sourceStateFingerprint.isNotBlank())
        require(healthySourceIds.intersect(blockedSourceIds).isEmpty())
        require(healthySourceIds.intersect(failedSourceIds).isEmpty())
        require(blockedSourceIds.intersect(failedSourceIds).isEmpty())
        require(healthySourceIds + blockedSourceIds + failedSourceIds == sourceIds) {
            "Every live source must have exactly one self-observation classification"
        }
    }
}

data class SelfStateProjectionInputs(
    val capturedAt: Instant,
    val photonIndex: SelfObservationSource<PhotonIndexReport>,
    val memory: SelfObservationSource<DurableLifeMemorySnapshot>,
    val authorities: SelfObservationSource<SelfObservationAuthoritySnapshot>,
    val runtimeTopology: SelfObservationSource<LifeOsRuntimeTopologySnapshot>,
    val health: SelfObservationSource<HealthSnapshot>,
    val resources: SelfObservationSource<HardwareStateSnapshot>,
    val recovery: SelfObservationSource<List<SelfHealingIncidentSnapshot>>,
    val tools: SelfObservationSource<GeneratedToolRuntimeStatus>,
    val liveSources: SelfObservationSource<SelfLiveSourceProjectionInput>,
    val runtimeTelemetry: SelfObservationSource<RuntimeTelemetrySnapshot> =
        SelfObservationSource.Unavailable("runtime-telemetry-not-configured"),
)

data class SelfStateProjectionResult(
    val snapshot: LifeOsSelfStateSnapshot,
    val issues: List<SelfObservationIssue>,
) {
    init {
        require(issues == issues.sortedWith(compareBy<SelfObservationIssue> { it.domain.name }.thenBy { it.kind.name }.thenBy { it.detail }))
    }
}

class SelfStateProjector {
    fun project(inputs: SelfStateProjectionInputs): SelfStateProjectionResult {
        val issues = mutableListOf<SelfObservationIssue>()

        val photonReport = value(SelfObservationDomain.PHOTON, inputs.photonIndex, issues)
        val photonCorrupt = photonReport?.unreadableRevisionFiles?.isNotEmpty() == true
        if (photonCorrupt) {
            issues += SelfObservationIssue(
                domain = SelfObservationDomain.PHOTON,
                kind = SelfObservationIssueKind.CORRUPT,
                detail = "photon-index-unreadable-revisions:" +
                    photonReport!!.unreadableRevisionFiles.sorted().joinToString(","),
            )
        }
        val photon = SelfPhotonState(
            latestRevisionCount = photonReport?.let {
                (it.livePhotonCount + it.tombstonedPhotonCount).toLong()
            },
            livePhotonCount = photonReport?.livePhotonCount?.toLong(),
            tombstonedPhotonCount = photonReport?.tombstonedPhotonCount?.toLong(),
            indexFingerprint = photonReport?.takeUnless { photonCorrupt }?.let(::photonIndexIdentityFingerprint),
            headFingerprint = photonReport?.takeUnless { photonCorrupt }?.let(::photonIndexHeadFingerprint),
        )

        val memorySnapshot = value(SelfObservationDomain.MEMORY, inputs.memory, issues)
        val memory = SelfMemoryState(
            authoritativePhotonCount = memorySnapshot?.authoritativePhotonCount?.toLong(),
            graphNodeCount = memorySnapshot?.graph?.let {
                (it.entities.size + it.events.size).toLong()
            },
            graphEdgeCount = memorySnapshot?.graph?.relationships?.size?.toLong(),
            memoryFingerprint = memorySnapshot?.fingerprint,
        )

        val authority = value(SelfObservationDomain.WORLD, inputs.authorities, issues)
        val world = SelfWorldState(
            worldHeadRevision = authority?.productiveWorldHead?.revision,
            worldHeadFingerprint = authority?.productiveWorldHead?.fingerprint,
            worldEquationRevision = authority?.worldEquationHead?.revision,
            worldEquationVersion = authority?.worldEquationHead?.activeEquationVersion,
            worldEquationFingerprint = authority?.worldEquationHead?.fingerprint,
            bootCycleId = authority?.committedBootCycle?.cycleId?.value,
            bootCycleFingerprint = authority?.committedBootCycle?.fingerprint,
            cognitiveSnapshotFingerprint = authority?.cognitiveSnapshot?.let(::cognitiveSnapshotFingerprint),
        )

        val topology = value(SelfObservationDomain.RUNTIME, inputs.runtimeTopology, issues)
        val runtimeTelemetry = value(SelfObservationDomain.RUNTIME, inputs.runtimeTelemetry, issues)
        val runtime = SelfRuntimeState(
            topologyFingerprint = topology?.manifestFingerprint,
            registeredSubsystems = topology?.subsystems?.mapTo(linkedSetOf()) { it.descriptor.id },
            operationalSubsystems = topology?.subsystems
                ?.filter { it.state == LifeOsSubsystemState.ACTIVE }
                ?.mapTo(linkedSetOf()) { it.descriptor.id },
            degradedSubsystems = topology?.subsystems
                ?.filter { it.state == LifeOsSubsystemState.DEGRADED }
                ?.mapTo(linkedSetOf()) { it.descriptor.id },
            unavailableSubsystems = topology?.subsystems
                ?.filter { it.state == LifeOsSubsystemState.UNAVAILABLE }
                ?.mapTo(linkedSetOf()) { it.descriptor.id },
            unboundSubsystems = topology?.subsystems
                ?.filter { it.state == LifeOsSubsystemState.REGISTERED }
                ?.mapTo(linkedSetOf()) { it.descriptor.id },
            telemetry = runtimeTelemetry,
        )

        val hardware = value(SelfObservationDomain.RESOURCE, inputs.resources, issues)
        val resource = SelfResourceState(
            hardwareFingerprint = hardware?.fingerprint(),
            memoryHeadroom = hardware?.memoryHeadroom(),
            storageHeadroom = hardware?.storageHeadroom(),
            thermalHeadroom = hardware?.thermalHeadroom(),
            energyAvailability = hardware?.energyAvailability(),
            capabilityReadiness = hardware?.capabilityReadiness(),
        )

        val healthSnapshot = value(SelfObservationDomain.HEALTH, inputs.health, issues)
        // The self-observation controller's own derived health node is output evidence, not input
        // evidence. Excluding it prevents a DEGRADED/CRITICAL assessment from feeding itself back
        // into the next assessment while every other productive HealthGraph node remains visible.
        val observedHealthNodes = healthSnapshot?.nodes
            ?.filterNot { it.id.value == SELF_OBSERVATION_HEALTH_NODE_ID }
        val health = SelfHealthState(
            healthy = observedHealthNodes?.count { it.state == HealthState.HEALTHY },
            degraded = observedHealthNodes?.count { it.state == HealthState.DEGRADED },
            unhealthy = observedHealthNodes?.count { it.state == HealthState.UNHEALTHY },
            recovering = observedHealthNodes?.count { it.state == HealthState.RECOVERING },
            quarantined = observedHealthNodes?.count { it.state == HealthState.QUARANTINED },
            disabled = observedHealthNodes?.count { it.state == HealthState.DISABLED },
            unknown = observedHealthNodes?.count { it.state == HealthState.UNKNOWN },
        )

        val repairs = value(SelfObservationDomain.RECOVERY, inputs.recovery, issues)
        val recovery = SelfRecoveryState(
            activeRepairIds = repairs?.mapTo(linkedSetOf()) { it.incidentId.value },
            recoveryStateFingerprint = repairs?.let(::recoveryFingerprint),
        )

        val toolStatus = value(SelfObservationDomain.TOOLS, inputs.tools, issues)
        val tools = SelfToolState(
            totalTools = toolStatus?.totalTools,
            activeTools = toolStatus?.activeTools,
            trialTools = toolStatus?.trialTools,
            quarantinedTools = toolStatus?.quarantinedTools,
            rejectedTools = toolStatus?.rejectedTools,
        )

        val sourceProjection = value(SelfObservationDomain.LIVE_SOURCES, inputs.liveSources, issues)
        val liveSources = SelfLiveSourceState(
            sourceCount = sourceProjection?.sourceIds?.size,
            healthyCount = sourceProjection?.healthySourceIds?.size,
            blockedCount = sourceProjection?.blockedSourceIds?.size,
            failedCount = sourceProjection?.failedSourceIds?.size,
            sourceStateFingerprint = sourceProjection?.sourceStateFingerprint,
        )

        val snapshot = LifeOsSelfStateSnapshot(
            capturedAt = inputs.capturedAt,
            photon = photon,
            memory = memory,
            world = world,
            runtime = runtime,
            resource = resource,
            health = health,
            recovery = recovery,
            tools = tools,
            liveSources = liveSources,
        )
        return SelfStateProjectionResult(
            snapshot = snapshot,
            issues = issues.sortedWith(
                compareBy<SelfObservationIssue> { it.domain.name }
                    .thenBy { it.kind.name }
                    .thenBy { it.detail }
            ),
        )
    }

    private fun <T> value(
        domain: SelfObservationDomain,
        source: SelfObservationSource<T>,
        issues: MutableList<SelfObservationIssue>,
    ): T? = when (source) {
        is SelfObservationSource.Available -> source.value
        is SelfObservationSource.Unavailable -> {
            issues += SelfObservationIssue(domain, SelfObservationIssueKind.UNAVAILABLE, source.reason)
            null
        }
        is SelfObservationSource.Failed -> {
            issues += SelfObservationIssue(domain, SelfObservationIssueKind.FAILED, source.reason)
            null
        }
    }

    /**
     * Restart-stable identity of the canonical Photon population.
     *
     * Revision numbers and total historical entry count are deliberately excluded: productive
     * startup readers can legitimately re-observe the same canonical Photon identities and advance
     * their revisions after process death. Exact head-state drift is captured separately by
     * [photonIndexHeadFingerprint].
     */
    private fun photonIndexIdentityFingerprint(report: PhotonIndexReport): String =
        StableFieldIds.fingerprint(
            "lifeos-self-photon-index-identity/v1",
            report.formatVersion.toString(),
            report.livePhotonCount.toString(),
            report.tombstonedPhotonCount.toString(),
            *report.latestRefs.keys
                .map { it.value }
                .sorted()
                .map { "id:" + it }
                .toTypedArray(),
        )

    /** Exact current Photon index head, including historical entry count and live revisions. */
    private fun photonIndexHeadFingerprint(report: PhotonIndexReport): String =
        StableFieldIds.fingerprint(
            "lifeos-self-photon-index-head/v1",
            report.formatVersion.toString(),
            report.entryCount.toString(),
            report.livePhotonCount.toString(),
            report.tombstonedPhotonCount.toString(),
            *report.latestRefs.entries
                .sortedBy { it.key.value }
                .map { entry ->
                    "head:" + entry.key.value + ":" + entry.value.revision.toString()
                }
                .toTypedArray(),
        )

    private fun cognitiveSnapshotFingerprint(
        snapshot: app.lifeos.core.runtime.CognitiveSnapshot,
    ): String {
        val manifest = SnapshotVerifier().manifest(snapshot)
        return StableFieldIds.fingerprint(
            "lifeos-self-cognitive-snapshot/v1",
            snapshot.schemaVersion.toString(),
            snapshot.projectionVersion.toString(),
            snapshot.worldRevision.toString(),
            snapshot.eventSequence.toString(),
            snapshot.worldRoot,
            snapshot.dependencyIndexFingerprint,
            snapshot.memoryIndexFingerprint,
            manifest.payloadSha256,
        )
    }

    private fun recoveryFingerprint(repairs: List<SelfHealingIncidentSnapshot>): String =
        StableFieldIds.fingerprint(
            "lifeos-self-recovery-state/v1",
            *repairs.sortedBy { it.incidentId.value }
                .flatMap { repair ->
                    listOf(
                        "incident:" + repair.incidentId.value,
                        "node:" + repair.nodeId.value,
                        "plan:" + repair.planFingerprint,
                        "state:" + repair.state.name,
                        "next:" + repair.nextActionIndex.toString(),
                        "ledger:" + repair.ledgerRevision.toString(),
                    )
                }
                .toTypedArray(),
        )
}


const val SELF_OBSERVATION_HEALTH_NODE_ID: String = "self-observation"
const val SELF_OBSERVATION_HEALTH_SOURCE: String = "lifeos-self-observation"
