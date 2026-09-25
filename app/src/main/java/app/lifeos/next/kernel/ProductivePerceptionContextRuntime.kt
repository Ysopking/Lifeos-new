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
    private val sensorTargets = linkedMapOf<SensorId, ProductiveSensorAttentionTarget>()
    private val sensorTargetOwners = linkedMapOf<SensorId, Any>()
    private var requiredHardwareSensorId: SensorId? = null

    /**
     * Registers one productive sensor target behind the generic B485 attention boundary.
     *
     * Source identity is retained only to preserve idempotent process composition. The target owns
     * no observation grant and no effect authority; those remain in the existing policy layers.
     */
    private suspend fun attachTarget(
        owner: Any,
        target: ProductiveSensorAttentionTarget,
    ): Boolean {
        val sensorId = target.descriptor.sensorId
        val currentOwner = sensorTargetOwners[sensorId]
        if (currentOwner === owner) return false
        require(currentOwner == null) {
            "Productive perception runtime cannot replace attached sensor target: $sensorId"
        }
        require(sensorTargets[sensorId] == null) {
            "Productive perception runtime already has sensor target: $sensorId"
        }

        sensorRegistry.register(target.descriptor)
        registerCoverage(target.coverage)
        sensorTargets[sensorId] = target
        sensorTargetOwners[sensorId] = owner
        return true
    }

    /**
     * Registers the concrete hardware bridge contract without starting physical acquisition.
     * Productive acquisition begins only after kernel readiness via [start].
     */
    suspend fun attachHardwareBridge(
        bridge: AndroidHardwareSensorBridge,
    ) {
        val attached = attachTarget(
            owner = bridge,
            target = ProductiveSensorAttentionTarget(
                descriptor = bridge.descriptor,
                coverage = bridge.attentionCoverage,
                applyAttention = { mode -> bridge.applyAttention(mode) },
            ),
        )
        if (attached) {
            requiredHardwareSensorId = bridge.descriptor.sensorId
        }
    }

    /**
     * B483 registers notifications as an ordinary event-driven sensor. The listener starts as
     * UNAVAILABLE until Android reports a connected NotificationListenerService lifecycle.
     */
    suspend fun attachNotificationBridge(
        bridge: LiveNotificationSensorBridge,
    ) {
        val attached = attachTarget(
            owner = bridge,
            target = ProductiveSensorAttentionTarget(
                descriptor = bridge.descriptor,
                coverage = bridge.attentionCoverage,
                applyAttention = bridge::applyAttention,
            ),
        )
        if (!attached) return

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
        LiveNotificationPhotonIngress.install(bridge)
    }

    /**
     * B484 registers UsageStats as a bounded periodic context sensor. Android special access only
     * controls source availability; Owner Observation Policy remains the persistence authority.
     */
    suspend fun attachAppUsageBridge(
        bridge: AndroidAppUsageSensorBridge,
    ) {
        val attached = attachTarget(
            owner = bridge,
            target = ProductiveSensorAttentionTarget(
                descriptor = bridge.descriptor,
                coverage = bridge.attentionCoverage,
                applyAttention = bridge::applyAttention,
                start = { bridge.start() },
                stop = { bridge.stop() },
            ),
        )
        if (!attached) return

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
    }

    /**
     * B485 registers semantic Accessibility UI as APP_CONTENT. Android accessibility enablement
     * controls source availability only; Owner Observation Policy still authorizes every batch.
     */
    suspend fun attachAppContentBridge(
        bridge: AndroidSemanticAppContentSensorBridge,
    ) {
        val attached = attachTarget(
            owner = bridge,
            target = ProductiveSensorAttentionTarget(
                descriptor = bridge.descriptor,
                coverage = bridge.attentionCoverage,
                applyAttention = bridge::applyAttention,
            ),
        )
        if (!attached) return

        sensorRegistry.updateHealth(
            bridge.descriptor.sensorId,
            SensorHealthState.UNAVAILABLE,
            "accessibility-service-not-connected",
        )
        bridge.bindHealthReporter { health, failure ->
            updateSensorHealth(
                sensorId = bridge.descriptor.sensorId,
                health = health,
                failure = failure,
            )
        }
        ProductiveSemanticAppContentIngress.install(bridge)
    }

    /**
     * Applies registry defaults after the kernel is ready. Unavailable sensors fail closed to
     * SUSPENDED until their lifecycle reports HEALTHY.
     */
    suspend fun start(): List<SensorAttentionDecision> {
        val hardwareSensorId = requireNotNull(requiredHardwareSensorId) {
            "Productive perception runtime requires the hardware bridge before start"
        }
        require(sensorTargets.containsKey(hardwareSensorId)) {
            "Required productive hardware sensor target is not registered"
        }

        val snapshot = coverageMutex.withLock {
            latestWorldGaps.toList() to
                coverageProfiles.values.sortedBy { it.sensorId.value }
        }
        val decisions = applyWorldGaps(
            gaps = snapshot.first,
            coverage = snapshot.second,
        ).decisions

        sensorTargets.values
            .sortedBy { it.descriptor.sensorId.value }
            .forEach { target -> target.start() }

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

        decisions.forEach { decision ->
            sensorTargets[decision.sensorId]
                ?.applyAttention(decision.mode)
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
