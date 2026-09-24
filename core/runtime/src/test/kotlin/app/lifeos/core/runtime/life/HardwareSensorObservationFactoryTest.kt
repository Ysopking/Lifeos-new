package app.lifeos.core.runtime.life

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HardwareSensorObservationFactoryTest {
    private val sensorId = SensorId("android-hardware-sensor-manager")
    private val factory = HardwareSensorObservationFactory(sensorId)
    private val observedAt = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun rawHardwareSampleRemainsProjectedObservation() {
        val observation = factory.create(sample())

        assertEquals(sensorId.value, observation.sourceId)
        assertEquals(ObservationSurfaceKind.SENSOR, observation.surface)
        assertEquals(RepresentationLevel.PROJECTED, observation.realization.representation)
        assertEquals(EpistemicStatus.OBSERVED, observation.realization.epistemicStatus)
        assertEquals(TemporalStatus.CURRENT, observation.realization.temporalStatus)
        assertEquals(ControlStatus.PASSIVE, observation.realization.controlStatus)
        assertEquals(ObservationAuthorityClass.PLATFORM_PROVIDER, observation.authority)
        assertEquals(ObservationPrivacyClass.PERSONAL, observation.privacy)
        assertTrue(observation.sourceResource.startsWith("android-sensor:1/"))
        assertTrue("hardware-sensor" in observation.tags)
        assertTrue("device-context" in observation.tags)
    }

    @Test
    fun equalPlatformSampleHasStableObservationIdentity() {
        val first = factory.create(sample())
        val second = factory.create(sample())

        assertEquals(first.sourceRevision, second.sourceRevision)
        assertEquals(first.id, second.id)
        assertEquals(first.sourceObservationFingerprint, second.sourceObservationFingerprint)
    }

    @Test
    fun changedSensorValuesProduceDifferentSourceRevisionAndObservationIdentity() {
        val first = factory.create(sample())
        val second = factory.create(
            sample().copy(values = listOf(1.0, 2.0, 3.5))
        )

        assertNotEquals(first.sourceRevision, second.sourceRevision)
        assertNotEquals(first.id, second.id)
    }

    private fun sample() = HardwareSensorSample(
        sensorType = 1,
        sensorName = "Accelerometer / Primary",
        sensorVendor = "Device Vendor",
        sensorVersion = 3,
        accuracy = 3,
        eventTimestampNanos = 123456789L,
        observedAt = observedAt,
        values = listOf(1.0, 2.0, 3.0),
    )
}
