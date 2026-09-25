package app.lifeos.next.kernel

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.runtime.life.AppSensorRegistry
import app.lifeos.core.runtime.life.ControlStatus
import app.lifeos.core.runtime.life.EpistemicStatus
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import app.lifeos.core.runtime.life.ObservationPrivacyClass
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.RealizationDescriptor
import app.lifeos.core.runtime.life.RepresentationLevel
import app.lifeos.core.runtime.life.SensorAttentionMode
import app.lifeos.core.runtime.life.SensorClass
import app.lifeos.core.runtime.life.SensorDescriptor
import app.lifeos.core.runtime.life.SensorHealthState
import app.lifeos.core.runtime.life.SensorId
import app.lifeos.core.runtime.life.SensorRuntimeState
import app.lifeos.core.runtime.life.TemporalStatus
import app.lifeos.core.runtime.policy.OwnerObservationPolicyEvent
import app.lifeos.core.runtime.policy.OwnerObservationPolicyLedger
import app.lifeos.core.runtime.policy.OwnerObservationPolicyRepository
import app.lifeos.core.runtime.policy.OwnerObservationPolicyRepositoryLoadReport
import app.lifeos.core.runtime.policy.OwnerObservationType
import app.lifeos.core.runtime.thought.ThoughtGraphAttentionPolicy
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet
import app.lifeos.core.runtime.world.SensorAttentionCoverageProfile
import app.lifeos.core.runtime.world.SensorAttentionDemand
import app.lifeos.core.runtime.world.SensorStateDimensionSelector
import app.lifeos.core.runtime.world.SensorStateDimensionSelectorType
import app.lifeos.core.runtime.world.StateDimensionId
import app.lifeos.core.runtime.world.WorldGap
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProductivePerceptionContextRuntimeTest {
    private val sensorId = SensorId("test-app-sensor")
    private val descriptor = SensorDescriptor(
        sensorId = sensorId,
        sensorClass = SensorClass.APP_CONTENT,
        adapterVersion = "1",
        observationType = OwnerObservationType.APP_CONTENT,
        resourcePrefix = "test-app:",
        supportedSurfaces = setOf(ObservationSurfaceKind.CONTENT_PROVIDER),
        defaultMode = SensorAttentionMode.EVENT_DRIVEN,
    )

    @Test
    fun freezeBindsExactRegistryAndDurableObservationPolicyRevision() = runTest {
        val registry = AppSensorRegistry(listOf(SensorRuntimeState(descriptor)))
        val runtime = ProductivePerceptionContextRuntime(
            ownerObservationPolicy = OwnerObservationPolicyLedger(EmptyPolicyRepository()),
            sensorRegistry = registry,
        )
        val workingSet = workingSet()

        val first = runtime.freeze(workingSet)
        val second = runtime.freeze(workingSet)
        val sensorFingerprint = registry.snapshot().fingerprint()

        assertEquals(first, second)
        assertTrue(first.personalContextSnapshotId.startsWith("personal-context:"))
        assertEquals(sensorFingerprint, first.sensorRegistryFingerprint)
        assertEquals(0L, first.ownerObservationPolicyRevision)

        registry.updateMode(sensorId, SensorAttentionMode.PERIODIC)
        val changed = runtime.freeze(workingSet)
        assertNotEquals(first.sensorRegistryFingerprint, changed.sensorRegistryFingerprint)
        assertNotEquals(first.personalContextSnapshotId, changed.personalContextSnapshotId)
    }

    @Test
    fun liveBindingValidationDetectsSensorRegistryDrift() = runTest {
        val registry = AppSensorRegistry(listOf(SensorRuntimeState(descriptor)))
        val runtime = ProductivePerceptionContextRuntime(
            ownerObservationPolicy = OwnerObservationPolicyLedger(EmptyPolicyRepository()),
            sensorRegistry = registry,
        )
        val frozen = runtime.freeze(workingSet())

        assertTrue(runtime.matchesCurrent(frozen))

        registry.updateMode(sensorId, SensorAttentionMode.PERIODIC)

        assertFalse(runtime.matchesCurrent(frozen))
    }

    @Test
    fun attentionRuntimeChangesRegistryModeWithoutMintingAuthority() = runTest {
        val registry = AppSensorRegistry(listOf(SensorRuntimeState(descriptor)))
        val runtime = ProductivePerceptionContextRuntime(
            ownerObservationPolicy = OwnerObservationPolicyLedger(EmptyPolicyRepository()),
            sensorRegistry = registry,
        )

        val decision = runtime.applyAttention(
            listOf(
                SensorAttentionDemand(
                    sensorId = sensorId,
                    informationGainMicros = 700_000L,
                    goalRelevanceMicros = 800_000L,
                    verificationValueMicros = 0L,
                    energyCostMicros = 10_000L,
                    privacyCostMicros = 10_000L,
                    latencyCostMicros = 10_000L,
                    resourceCostMicros = 10_000L,
                    blockingGapCount = 1,
                    stateDimensions = setOf(StateDimensionId("test.dimension")),
                )
            )
        ).single()

        assertEquals(SensorAttentionMode.FOCUSED, decision.mode)
        assertEquals(SensorAttentionMode.FOCUSED, registry.state(sensorId)?.mode)
        assertEquals(false, decision.observationGrantAuthority)
        assertEquals(false, decision.effectAuthority)
    }

    @Test
    fun worldGapsFlowThroughCompilerIntoExistingAttentionRuntime() = runTest {
        val registry = AppSensorRegistry(listOf(SensorRuntimeState(descriptor)))
        val runtime = ProductivePerceptionContextRuntime(
            ownerObservationPolicy = OwnerObservationPolicyLedger(EmptyPolicyRepository()),
            sensorRegistry = registry,
        )
        val gap = WorldGap.Perception(
            domain = FieldDomainId("device"),
            missingDimensions = setOf(StateDimensionId("device.motion.current")),
            reason = "motion-state-missing",
        )

        val update = runtime.applyWorldGaps(
            gaps = listOf(gap),
            coverage = listOf(
                SensorAttentionCoverageProfile(
                    sensorId = sensorId,
                    stateDimensions = listOf(
                        SensorStateDimensionSelector(
                            SensorStateDimensionSelectorType.PREFIX,
                            "device.motion.",
                        )
                    ),
                    informationGainMicros = 700_000L,
                    goalRelevanceMicros = 800_000L,
                    verificationValueMicros = 800_000L,
                    energyCostMicros = 100_000L,
                    privacyCostMicros = 100_000L,
                    latencyCostMicros = 100_000L,
                    resourceCostMicros = 100_000L,
                )
            ),
        )

        assertEquals(listOf(gap.id), update.plan.worldGapIds)
        assertEquals(SensorAttentionMode.FOCUSED, update.decisions.single().mode)
        assertEquals(SensorAttentionMode.FOCUSED, registry.state(sensorId)?.mode)
        assertEquals(false, update.observationGrantAuthority)
        assertEquals(false, update.effectAuthority)
    }

    @Test
    fun installedWorldGapSinkUsesRegisteredCoverageAndPreservesAuthoritySeparation() = runTest {
        val registry = AppSensorRegistry(listOf(SensorRuntimeState(descriptor)))
        val runtime = ProductivePerceptionContextRuntime(
            ownerObservationPolicy = OwnerObservationPolicyLedger(EmptyPolicyRepository()),
            sensorRegistry = registry,
        )
        runtime.registerCoverage(
            SensorAttentionCoverageProfile(
                sensorId = sensorId,
                stateDimensions = listOf(
                    SensorStateDimensionSelector(
                        SensorStateDimensionSelectorType.PREFIX,
                        "device.motion.",
                    )
                ),
                informationGainMicros = 700_000L,
                goalRelevanceMicros = 800_000L,
                verificationValueMicros = 800_000L,
                energyCostMicros = 100_000L,
                privacyCostMicros = 100_000L,
                latencyCostMicros = 100_000L,
                resourceCostMicros = 100_000L,
            )
        )
        val gap = WorldGap.Perception(
            domain = FieldDomainId("device"),
            missingDimensions = setOf(StateDimensionId("device.motion.current")),
            reason = "motion-state-missing",
        )

        try {
            ProductiveWorldGapAttentionRuntimeRegistry.install(runtime)
            ProductiveWorldGapAttentionRuntimeRegistry.update(listOf(gap))

            assertEquals(SensorAttentionMode.FOCUSED, registry.state(sensorId)?.mode)
            assertEquals(listOf(sensorId), runtime.coverageSnapshot().map { it.sensorId })
        } finally {
            ProductiveWorldGapAttentionRuntimeRegistry.clearForTests()
        }
    }

    @Test
    fun appUsageBridgeRegistersCoverageAndReplansWhenOwnerGrantsPlatformAccess() = runTest {
        val registry = AppSensorRegistry()
        val runtime = ProductivePerceptionContextRuntime(
            ownerObservationPolicy = OwnerObservationPolicyLedger(EmptyPolicyRepository()),
            sensorRegistry = registry,
        )
        var granted = false
        val source = object : AppUsageEventSource {
            override fun isAccessGranted(): Boolean = granted

            override fun queryEvents(
                beginMillis: Long,
                endMillis: Long,
                maxEvents: Int,
            ): List<PlatformAppUsageEvent> = emptyList()
        }
        val bridge = AndroidAppUsageSensorBridge(
            source = source,
            commitBatch = AppUsageBatchCommitter { _, _, _, _ -> Unit },
            scope = this,
        )

        runtime.attachAppUsageBridge(bridge)

        assertEquals(
            SensorHealthState.UNAVAILABLE,
            registry.state(bridge.descriptor.sensorId)?.health,
        )
        assertEquals(
            SensorAttentionMode.SUSPENDED,
            bridge.descriptor.defaultMode,
        )
        assertTrue(
            runtime.coverageSnapshot().any { it.sensorId == bridge.descriptor.sensorId }
        )

        granted = true
        bridge.refreshAvailability()

        assertEquals(
            SensorHealthState.HEALTHY,
            registry.state(bridge.descriptor.sensorId)?.health,
        )
        assertEquals(
            SensorAttentionMode.SUSPENDED,
            registry.state(bridge.descriptor.sensorId)?.mode,
        )

        val update = runtime.applyWorldGaps(
            listOf(
                WorldGap.Perception(
                    domain = FieldDomainId("app"),
                    missingDimensions = setOf(StateDimensionId("app.usage.current")),
                    reason = "app-usage-context-missing",
                )
            )
        )

        assertEquals(
            SensorAttentionMode.FOCUSED,
            registry.state(bridge.descriptor.sensorId)?.mode,
        )
        assertEquals(false, update.observationGrantAuthority)
        assertEquals(false, update.effectAuthority)
    }

    @Test
    fun notificationBridgeBuildsCanonicalSensorBatchAndHonorsSuspension() = runTest {
        val revisions = mutableListOf<Pair<Long, Long>>()
        val committed = mutableListOf<InformationObservation>()
        val bridge = LiveNotificationSensorBridge(
            LiveNotificationBatchCommitter { descriptor, cursor, _, batch ->
                assertEquals(
                    PrivateOwnerObservationPolicyBaseline.NOTIFICATION_SENSOR_ID,
                    descriptor.sensorId.value,
                )
                revisions += cursor.revision to batch.nextCursor.revision
                committed += batch.observations.single()
            }
        )

        val first = notificationObservation(
            revision = "notification-revision-1",
            observedAt = Instant.parse("2026-09-25T00:01:00Z"),
        )
        bridge.ingest(first)
        bridge.applyAttention(SensorAttentionMode.SUSPENDED)
        bridge.ingest(
            notificationObservation(
                revision = "notification-revision-2",
                observedAt = Instant.parse("2026-09-25T00:02:00Z"),
            )
        )

        assertEquals(listOf(0L to 1L), revisions)
        assertEquals(listOf(first), committed)
        assertEquals(null, committed.single().observationGrantId)
    }

    @Test
    fun notificationLifecycleReplansRegisteredAttentionWithoutMintingAuthority() = runTest {
        val registry = AppSensorRegistry()
        val runtime = ProductivePerceptionContextRuntime(
            ownerObservationPolicy = OwnerObservationPolicyLedger(EmptyPolicyRepository()),
            sensorRegistry = registry,
        )
        val bridge = LiveNotificationSensorBridge(
            LiveNotificationBatchCommitter { _, _, _, _ -> Unit }
        )

        runtime.attachNotificationBridge(bridge)

        assertEquals(
            SensorHealthState.UNAVAILABLE,
            registry.state(bridge.descriptor.sensorId)?.health,
        )
        assertTrue(
            runtime.coverageSnapshot().any { it.sensorId == bridge.descriptor.sensorId }
        )

        bridge.connected()

        assertEquals(
            SensorHealthState.HEALTHY,
            registry.state(bridge.descriptor.sensorId)?.health,
        )
        assertEquals(
            SensorAttentionMode.EVENT_DRIVEN,
            registry.state(bridge.descriptor.sensorId)?.mode,
        )

        val gap = WorldGap.Perception(
            domain = FieldDomainId("app"),
            missingDimensions = setOf(StateDimensionId("app.notification.current")),
            reason = "notification-state-missing",
        )
        val update = runtime.applyWorldGaps(listOf(gap))

        assertEquals(
            SensorAttentionMode.FOCUSED,
            registry.state(bridge.descriptor.sensorId)?.mode,
        )
        assertEquals(false, update.observationGrantAuthority)
        assertEquals(false, update.effectAuthority)

        bridge.disconnected()

        assertEquals(
            SensorHealthState.UNAVAILABLE,
            registry.state(bridge.descriptor.sensorId)?.health,
        )
        assertEquals(
            SensorAttentionMode.SUSPENDED,
            registry.state(bridge.descriptor.sensorId)?.mode,
        )
    }

    private fun notificationObservation(
        revision: String,
        observedAt: Instant,
    ) = InformationObservation(
        sourceId = PrivateOwnerObservationPolicyBaseline.NOTIFICATION_SENSOR_ID,
        sourceResource =
            PrivateOwnerObservationPolicyBaseline.NOTIFICATION_RESOURCE_PREFIX +
                "example.app:key",
        surface = ObservationSurfaceKind.NOTIFICATION,
        observedAt = observedAt,
        sourceTimestamp = observedAt,
        sourceRevision = revision,
        mimeType = "application/vnd.lifeos.android-notification+text",
        payload = "title=example",
        realization = RealizationDescriptor(
            representation = RepresentationLevel.PROJECTED,
            epistemicStatus = EpistemicStatus.OBSERVED,
            temporalStatus = TemporalStatus.CURRENT,
            controlStatus = ControlStatus.PASSIVE,
        ),
        authority = ObservationAuthorityClass.PLATFORM_NOTIFICATION,
        privacy = ObservationPrivacyClass.PERSONAL,
        confidence = 1.0,
    )

    private fun workingSet() = ThoughtGraphWorkingSet(
        sourceSnapshotId = "goal-thought-snapshot:test",
        sourceRevision = 7L,
        sourceHistoryFingerprint = "history-fingerprint",
        asOf = Instant.parse("2026-09-25T00:00:00Z"),
        policy = ThoughtGraphAttentionPolicy(),
        entries = emptyList(),
        nodes = emptyList(),
        edges = emptyList(),
        conflicts = emptyList(),
    )

    private class EmptyPolicyRepository : OwnerObservationPolicyRepository {
        override suspend fun loadReport(): OwnerObservationPolicyRepositoryLoadReport =
            OwnerObservationPolicyRepositoryLoadReport(emptyList())

        override suspend fun append(
            expectedRevision: Long,
            event: OwnerObservationPolicyEvent,
        ): Boolean = false
    }
}
