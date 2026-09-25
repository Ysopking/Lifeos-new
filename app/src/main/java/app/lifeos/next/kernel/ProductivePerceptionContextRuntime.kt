package app.lifeos.next.kernel

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.life.AppSensorRegistry
import app.lifeos.core.runtime.life.AppSensorRegistrySnapshot
import app.lifeos.core.runtime.life.SensorHealthState
import app.lifeos.core.runtime.life.SensorId
import app.lifeos.core.runtime.policy.OwnerObservationPolicyLedger
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet
import app.lifeos.core.runtime.world.PersonalContextBootBinding
import app.lifeos.core.runtime.world.PersonalContextSnapshot
import app.lifeos.core.runtime.world.SensorAttentionCoverageProfile
import app.lifeos.core.runtime.world.SensorAttentionDecision
import app.lifeos.core.runtime.world.SensorAttentionDemand
import app.lifeos.core.runtime.world.SensorAttentionRuntime
import app.lifeos.core.runtime.world.SensorWorldGapAttentionCompiler
import app.lifeos.core.runtime.world.SensorWorldGapAttentionPlan
import app.lifeos.core.runtime.world.WorldGap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface ProductiveWorldGapAttentionSink {
    suspend fun update(gaps: Collection<WorldGap>)
}

internal object ProductiveWorldGapAttentionRuntimeRegistry {
    @Volatile
    private var sink: ProductiveWorldGapAttentionSink? = null

    fun install(value: ProductiveWorldGapAttentionSink) {
        sink = value
    }

    suspend fun update(gaps: Collection<WorldGap>) {
        sink?.update(gaps)
    }

    internal fun clearForTests() {
        sink = null
    }
}

data class ProductiveSensorAttentionUpdate(
    val plan: SensorWorldGapAttentionPlan,
    val decisions: List<SensorAttentionDecision>,
) {
    val observationGrantAuthority: Boolean
        get() = false

    val effectAuthority: Boolean
        get() = false
}

/**
 * Productive process-local perception composition.
 *
 * It owns only sensor scheduling/lifecycle metadata and the exact PersonalContext boot binding.
 * Observation permission remains in OwnerObservationPolicyLedger; effect authority remains outside
 * this runtime.
 */
fun interface PersonalContextBootBindingSource {
    suspend fun freeze(
        workingSet: ThoughtGraphWorkingSet,
    ): PersonalContextBootBinding

    suspend fun matchesCurrent(
        expected: PersonalContextBootBinding,
    ): Boolean = true
}

internal class ProductivePerceptionContextRuntime(
    private val ownerObservationPolicy: OwnerObservationPolicyLedger,
    private val sensorRegistry: AppSensorRegistry = AppSensorRegistry(),
) : PersonalContextBootBindingSource, ProductiveWorldGapAttentionSink {
    private val attentionRuntime = SensorAttentionRuntime(sensorRegistry)
    private val gapAttentionCompiler = SensorWorldGapAttentionCompiler()
    private val coverageMutex = Mutex()
    private val coverageProfiles = linkedMapOf<String, SensorAttentionCoverageProfile>()
    private var latestWorldGaps: List<WorldGap> = emptyList()
    private var hardwareBridge: AndroidHardwareSensorBridge? = null
    private var notificationBridge: LiveNotificationSensorBridge? = null
    private var appUsageBridge: AndroidAppUsageSensorBridge? = null

    /**
     * Registers the concrete hardware bridge contract without starting physical acquisition.
     * Productive acquisition begins only after kernel readiness via [start].
     */
    suspend fun attachHardwareBridge(
        bridge: AndroidHardwareSensorBridge,
    ) {
        val current = hardwareBridge
        require(current == null || current === bridge) {
            "Productive perception runtime cannot replace an attached hardware bridge"
        }
        sensorRegistry.register(bridge.descriptor)
        registerCoverage(bridge.attentionCoverage)
        hardwareBridge = bridge
    }

    /**
     * B483 registers notifications as an ordinary event-driven sensor. The listener starts as
     * UNAVAILABLE until Android reports a connected NotificationListenerService lifecycle.
     */
    suspend fun attachNotificationBridge(
        bridge: LiveNotificationSensorBridge,
    ) {
        val current = notificationBridge
        if (current === bridge) return
        require(current == null) {
            "Productive perception runtime cannot replace an attached notification bridge"
        }
        sensorRegistry.register(bridge.descriptor)
        registerCoverage(bridge.attentionCoverage)
        sensorRegistry.updateHealth(
            bridge.descriptor.sensorId,
            SensorHealthState.UNAVAILABLE,
            "notification-listener-not-connected",
        )
        bridge.bindHealthReporter { health, failure ->
            updateSensorHealth(
                sensorId = bridge.descriptor.sensorId,
                health = health,
                failure = failure,
            )
        }
        notificationBridge = bridge
        LiveNotificationPhotonIngress.install(bridge)
    }

    /**
     * B484 registers UsageStats as a bounded periodic context sensor. Android special access only
     * controls source availability; Owner Observation Policy remains the persistence authority.
     */
    suspend fun attachAppUsageBridge(
        bridge: AndroidAppUsageSensorBridge,
    ) {
        val current = appUsageBridge
        if (current === bridge) return
        require(current == null) {
            "Productive perception runtime cannot replace an attached app-usage bridge"
        }
        sensorRegistry.register(bridge.descriptor)
        registerCoverage(bridge.attentionCoverage)
        bridge.bindHealthReporter { health, failure ->
            updateSensorHealth(
                sensorId = bridge.descriptor.sensorId,
                health = health,
                failure = failure,
            )
        }
        val initialHealth = bridge.currentHealth()
        sensorRegistry.updateHealth(
            bridge.descriptor.sensorId,
            initialHealth,
            if (initialHealth == SensorHealthState.HEALTHY) {
                null
            } else {
                "usage-access-not-granted"
            },
        )
        appUsageBridge = bridge
    }

    /**
     * Applies registry defaults after the kernel is ready. Unavailable sensors fail closed to
     * SUSPENDED until their lifecycle reports HEALTHY.
     */
    suspend fun start(): List<SensorAttentionDecision> {
        requireNotNull(hardwareBridge) {
            "Productive perception runtime requires the hardware bridge before start"
        }
        val snapshot = coverageMutex.withLock {
            latestWorldGaps.toList() to
                coverageProfiles.values.sortedBy { it.sensorId.value }
        }
        val decisions = applyWorldGaps(
            gaps = snapshot.first,
            coverage = snapshot.second,
        ).decisions
        appUsageBridge?.start()
        return decisions
    }

    /**
     * B459 remains the only scheduler. Decisions update process-local registry metadata first and
     * are then applied to concrete bridges. They cannot create observation grants.
     */
    suspend fun applyAttention(
        demands: Collection<SensorAttentionDemand>,
    ): List<SensorAttentionDecision> {
        val decisions = attentionRuntime.apply(demands)

        hardwareBridge?.let { bridge ->
            decisions
                .firstOrNull { it.sensorId == bridge.descriptor.sensorId }
                ?.let { decision -> bridge.applyAttention(decision.mode) }
        }
        notificationBridge?.let { bridge ->
            decisions
                .firstOrNull { it.sensorId == bridge.descriptor.sensorId }
                ?.let { decision -> bridge.applyAttention(decision.mode) }
        }
        appUsageBridge?.let { bridge ->
            decisions
                .firstOrNull { it.sensorId == bridge.descriptor.sensorId }
                ?.let { decision -> bridge.applyAttention(decision.mode) }
        }
        return decisions
    }

    suspend fun registerCoverage(
        profile: SensorAttentionCoverageProfile,
    ) {
        require(sensorRegistry.state(profile.sensorId) != null) {
            "Sensor attention coverage requires a registered sensor: ${profile.sensorId}"
        }
        coverageMutex.withLock {
            coverageProfiles[profile.sensorId.value] = profile
        }
    }

    suspend fun coverageSnapshot(): List<SensorAttentionCoverageProfile> =
        coverageMutex.withLock {
            coverageProfiles.values.sortedBy { it.sensorId.value }
        }

    /**
     * Converts explicit world-state gaps through B480's pure compiler and immediately applies the
     * resulting B459 attention plan. Unmatched observation gaps remain visible in the returned plan.
     */
    suspend fun applyWorldGaps(
        gaps: Collection<WorldGap>,
        coverage: Collection<SensorAttentionCoverageProfile>,
    ): ProductiveSensorAttentionUpdate {
        val plan = gapAttentionCompiler.compile(
            sensors = sensorRegistry.snapshot(),
            gaps = gaps,
            coverage = coverage,
        )
        return ProductiveSensorAttentionUpdate(
            plan = plan,
            decisions = applyAttention(plan.demands),
        )
    }

    suspend fun applyWorldGaps(
        gaps: Collection<WorldGap>,
    ): ProductiveSensorAttentionUpdate {
        val canonicalGaps = gaps.sortedBy { it.id }
        val coverage = coverageMutex.withLock {
            latestWorldGaps = canonicalGaps
            coverageProfiles.values.sortedBy { it.sensorId.value }
        }
        return applyWorldGaps(
            gaps = canonicalGaps,
            coverage = coverage,
        )
    }

    override suspend fun update(gaps: Collection<WorldGap>) {
        applyWorldGaps(gaps)
    }

    /**
     * Sensor lifecycle changes are perception-context changes. Re-running the last final WorldGap
     * plan restores the correct attention mode when an unavailable event source becomes healthy.
     */
    internal suspend fun updateSensorHealth(
        sensorId: SensorId,
        health: SensorHealthState,
        failure: String? = null,
    ) {
        sensorRegistry.updateHealth(sensorId, health, failure)
        val snapshot = coverageMutex.withLock {
            latestWorldGaps.toList() to
                coverageProfiles.values.sortedBy { it.sensorId.value }
        }
        applyWorldGaps(
            gaps = snapshot.first,
            coverage = snapshot.second,
        )
    }

    suspend fun sensorRegistrySnapshot(): AppSensorRegistrySnapshot =
        sensorRegistry.snapshot()

    /**
     * Freezes the actual cycle-local perception lineage into B479's BootEngine binding.
     *
     * No placeholder authority is manufactured: the sensor fingerprint comes from the live
     * registry and the policy revision comes from the durable Owner Observation Policy head.
     */
    override suspend fun matchesCurrent(
        expected: PersonalContextBootBinding,
    ): Boolean {
        val sensors = sensorRegistry.snapshot()
        val policy = ownerObservationPolicy.snapshot()
        return expected.sensorRegistryFingerprint == sensors.fingerprint() &&
            expected.ownerObservationPolicyRevision == policy.revision
    }

    override suspend fun freeze(
        workingSet: ThoughtGraphWorkingSet,
    ): PersonalContextBootBinding {
        val sensors = sensorRegistry.snapshot()
        val policy = ownerObservationPolicy.snapshot()

        val observationHeadFingerprint = StableFieldIds.fingerprint(
            "personal-context-observation-head/v1",
            workingSet.sourceSnapshotId,
            workingSet.sourceRevision.toString(),
            workingSet.sourceHistoryFingerprint,
        )
        val evidenceHeadFingerprint = StableFieldIds.fingerprint(
            "personal-context-evidence-head/v1",
            workingSet.sourceHistoryFingerprint,
            workingSet.sourceSnapshotId,
        )
        val sensorFingerprint = sensors.fingerprint()

        val context = PersonalContextSnapshot.create(
            appObservationHeadFingerprint = observationHeadFingerprint,
            sensorProjectionFingerprint = sensorFingerprint,
            evidenceHeadFingerprint = evidenceHeadFingerprint,
            ownerObservationPolicyRevision = policy.revision,
            createdAt = workingSet.asOf,
        )

        return PersonalContextBootBinding(
            personalContextSnapshotId = context.id,
            sensorRegistryFingerprint = sensorFingerprint,
            ownerObservationPolicyRevision = policy.revision,
        )
    }
}
