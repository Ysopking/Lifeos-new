package app.lifeos.core.runtime.life

import app.lifeos.core.runtime.policy.OwnerObservationType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppSensorRegistryTest {
    private val descriptor = SensorDescriptor(
        sensorId = SensorId("android-notification-listener"),
        sensorClass = SensorClass.NOTIFICATION,
        adapterVersion = "1",
        observationType = OwnerObservationType.NOTIFICATION,
        resourcePrefix = "android-notification:",
        supportedSurfaces = setOf(ObservationSurfaceKind.NOTIFICATION),
    )

    @Test
    fun registrySnapshotIsCanonicalAndDeterministic() = runTest {
        val registry = AppSensorRegistry()
        registry.register(descriptor)
        registry.register(
            descriptor.copy(
                sensorId = SensorId("z-sensor"),
                resourcePrefix = "z:",
            )
        )

        val first = registry.snapshot()
        val second = registry.snapshot()

        assertEquals(first, second)
        assertEquals(
            listOf("android-notification-listener", "z-sensor"),
            first.sensors.map { it.descriptor.sensorId.value },
        )
    }

    @Test
    fun checkpointNeverMovesBackwards() = runTest {
        val registry = AppSensorRegistry()
        registry.register(descriptor)
        registry.checkpoint(
            SensorCheckpoint(
                sensorId = descriptor.sensorId,
                revision = 2L,
                committedAt = Instant.parse("2026-09-24T12:00:00Z"),
            )
        )

        assertFailsWith<IllegalArgumentException> {
            registry.checkpoint(
                SensorCheckpoint(
                    sensorId = descriptor.sensorId,
                    revision = 1L,
                    committedAt = Instant.parse("2026-09-24T12:00:01Z"),
                )
            )
        }
    }

    @Test
    fun healthAndAttentionAreRuntimeMetadataNotObservationAuthority() = runTest {
        val registry = AppSensorRegistry()
        registry.register(descriptor)

        registry.updateMode(descriptor.sensorId, SensorAttentionMode.SUSPENDED)
        val degraded = registry.updateHealth(
            sensorId = descriptor.sensorId,
            health = SensorHealthState.DEGRADED,
            failure = "listener-disconnected",
        )

        assertEquals(SensorAttentionMode.SUSPENDED, degraded.mode)
        assertEquals(SensorHealthState.DEGRADED, degraded.health)
        assertEquals("listener-disconnected", degraded.lastFailure)
    }
}
