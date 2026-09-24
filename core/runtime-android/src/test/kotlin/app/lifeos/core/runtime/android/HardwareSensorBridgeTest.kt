package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.life.AppSensorBudget
import app.lifeos.core.runtime.life.AppSensorCursor
import app.lifeos.core.runtime.life.AppSensorRegistry
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import app.lifeos.core.runtime.life.ObservationPrivacyClass
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.OwnerAuthorizedAppObservationIngress
import app.lifeos.core.runtime.life.SensorClass
import app.lifeos.core.runtime.life.SensorDescriptor
import app.lifeos.core.runtime.life.SensorHealthState
import app.lifeos.core.runtime.life.SensorId
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerObservationGrant
import app.lifeos.core.runtime.policy.OwnerObservationPolicyEvent
import app.lifeos.core.runtime.policy.OwnerObservationPolicyLedger
import app.lifeos.core.runtime.policy.OwnerObservationPolicyRepository
import app.lifeos.core.runtime.policy.OwnerObservationPolicyRepositoryLoadReport
import app.lifeos.core.runtime.policy.OwnerObservationType
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HardwareSensorBridgeTest {
    private val now = Instant.parse("2026-09-25T00:30:00Z")
    private val sensorId = SensorId("hardware-imu")
    private val descriptor = SensorDescriptor(
        sensorId = sensorId,
        sensorClass = SensorClass.EXTERNAL,
        adapterVersion = "1",
        observationType = OwnerObservationType.EXTERNAL_SENSOR,
        resourcePrefix = "sensor://imu/",
        supportedSurfaces = setOf(ObservationSurfaceKind.SENSOR),
    )

    @Test
    fun authorizedHardwareObservationCommitsThenAdvancesCheckpoint() = runTest {
        val policy = policyWithGrant()
        val registry = AppSensorRegistry()
        val committed = mutableListOf<app.lifeos.core.runtime.life.InformationObservation>()
        val runtime = runtime(
            policy = policy,
            registry = registry,
            commit = committed::add,
        )

        val result = assertNotNull(
            runtime.run(
                cursor = AppSensorCursor(sensorId, revision = 0L),
                budget = AppSensorBudget(maxObservations = 4),
            )
        )

        assertEquals(1, result.observedCount)
        assertEquals(1, result.authorizedCount)
        assertEquals(0, result.blockedCount)
        assertEquals(1, result.committedCount)
        assertTrue(!result.effectAuthority)
        assertEquals(1, committed.size)
        assertNotNull(committed.single().observationGrantId)
        assertEquals(1L, registry.state(sensorId)?.checkpoint?.revision)
        assertNotNull(registry.state(sensorId)?.checkpoint?.lastObservationId)
    }

    @Test
    fun blockedHardwareObservationAdvancesCursorWithoutCommit() = runTest {
        val policy = OwnerObservationPolicyLedger(InMemoryObservationPolicyRepository()) { now }
        val registry = AppSensorRegistry()
        var commits = 0
        val runtime = runtime(
            policy = policy,
            registry = registry,
            commit = { commits += 1 },
        )

        val result = assertNotNull(
            runtime.run(AppSensorCursor(sensorId, revision = 0L))
        )

        assertEquals(0, result.authorizedCount)
        assertEquals(1, result.blockedCount)
        assertEquals(0, commits)
        val checkpoint = assertNotNull(registry.state(sensorId)?.checkpoint)
        assertEquals(1L, checkpoint.revision)
        assertNull(checkpoint.lastObservationId)
        assertNull(checkpoint.lastObservationFingerprint)
    }

    @Test
    fun commitFailureLeavesCursorUnadvancedAndMarksSensorDegraded() = runTest {
        val policy = policyWithGrant()
        val registry = AppSensorRegistry()
        val runtime = runtime(
            policy = policy,
            registry = registry,
            commit = { error("commit-failed") },
        )

        var failed = false
        try {
            runtime.run(AppSensorCursor(sensorId, revision = 0L))
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        val state = assertNotNull(registry.state(sensorId))
        assertNull(state.checkpoint)
        assertEquals(SensorHealthState.DEGRADED, state.health)
        assertEquals("commit-failed", state.lastFailure)
    }

    @Test
    fun unavailableHardwareSourceDoesNotPollOrAdvanceCursor() = runTest {
        val registry = AppSensorRegistry()
        val source = FakeHardwareSource(health = SensorHealthState.UNAVAILABLE)
        val runtime = HardwareSensorObservationRuntime(
            adapter = HardwareSensorBridgeAdapter(source),
            registry = registry,
            authorization = OwnerAuthorizedAppObservationIngress(
                OwnerObservationPolicyLedger(InMemoryObservationPolicyRepository()) { now },
                OwnerActorId("owner"),
                "hardware-context",
            ),
            commitAuthorized = { error("must-not-commit") },
            now = { now },
        )

        val result = runtime.run(AppSensorCursor(sensorId, revision = 0L))

        assertNull(result)
        val state = assertNotNull(registry.state(sensorId))
        assertEquals(SensorHealthState.UNAVAILABLE, state.health)
        assertNull(state.checkpoint)
        assertEquals(0, source.readCount)
    }

    private fun runtime(
        policy: OwnerObservationPolicyLedger,
        registry: AppSensorRegistry,
        commit: suspend (app.lifeos.core.runtime.life.InformationObservation) -> Unit,
    ) = HardwareSensorObservationRuntime(
        adapter = HardwareSensorBridgeAdapter(FakeHardwareSource()),
        registry = registry,
        authorization = OwnerAuthorizedAppObservationIngress(
            observationPolicy = policy,
            actorId = OwnerActorId("owner"),
            scope = "hardware-context",
        ),
        commitAuthorized = commit,
        now = { now },
    )

    private suspend fun policyWithGrant(): OwnerObservationPolicyLedger {
        val policy = OwnerObservationPolicyLedger(InMemoryObservationPolicyRepository()) { now }
        policy.grant(
            OwnerObservationGrant.create(
                actorId = OwnerActorId("owner"),
                observationType = OwnerObservationType.EXTERNAL_SENSOR,
                resource = OwnerResourceSelector(
                    OwnerResourceSelectorType.PREFIX,
                    "sensor://imu/",
                ),
                scope = "hardware-context",
                sensorId = sensorId.value,
                validFrom = now.minusSeconds(60),
            )
        )
        return policy
    }

    private inner class FakeHardwareSource(
        private val health: SensorHealthState = SensorHealthState.HEALTHY,
    ) : HardwareSensorSource {
        override val descriptor: SensorDescriptor = this@HardwareSensorBridgeTest.descriptor
        override val authority = ObservationAuthorityClass.PLATFORM_PROVIDER
        override val privacy = ObservationPrivacyClass.PERSONAL
        override val transportId: String = "test-transport"
        var readCount: Int = 0

        override suspend fun availability(): SensorHealthState = health

        override suspend fun read(
            cursor: AppSensorCursor,
            budget: AppSensorBudget,
        ): HardwareSensorRead {
            readCount += 1
            return HardwareSensorRead(
                samples = listOf(
                    HardwareSensorSample(
                        eventId = "sample-1",
                        resource = "sensor://imu/device/1",
                        observedAt = now,
                        sourceRevision = "1",
                        values = mapOf(
                            "accel.x" to 1.0,
                            "accel.y" to -0.5,
                        ),
                    )
                ),
                nextRevision = cursor.revision + 1L,
                sourcePosition = "sample-1",
                exhausted = true,
            )
        }
    }

    private class InMemoryObservationPolicyRepository :
        OwnerObservationPolicyRepository {
        private val events = mutableListOf<OwnerObservationPolicyEvent>()

        override suspend fun loadReport() =
            OwnerObservationPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(
            expectedRevision: Long,
            event: OwnerObservationPolicyEvent,
        ): Boolean {
            val head = events.maxOfOrNull { it.revision } ?: 0L
            if (head != expectedRevision) return false
            events += event
            return true
        }
    }
}
