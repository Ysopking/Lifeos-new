package app.lifeos.core.runtime.life

import app.lifeos.core.runtime.policy.OwnerObservationType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppObservationIngressTest {
    private val sensorId = SensorId("android-usage-stats")
    private val descriptor = SensorDescriptor(
        sensorId = sensorId,
        sensorClass = SensorClass.APP_USAGE,
        adapterVersion = "1",
        observationType = OwnerObservationType.APP_USAGE,
        resourcePrefix = "android-usage:",
        supportedSurfaces = setOf(ObservationSurfaceKind.APP_USAGE),
        defaultMode = SensorAttentionMode.PERIODIC,
    )
    private val factory = AppUsageObservationFactory(sensorId)

    @Test
    fun batchCanonicalizesReplayCopiesAndPreservesCursorMonotonicity() {
        val first = factory.create(
            AppUsageEvent(
                packageName = "com.example.bank",
                foregroundSince = Instant.parse("2026-09-24T12:00:00Z"),
                backgroundAt = Instant.parse("2026-09-24T12:02:00Z"),
                observedAt = Instant.parse("2026-09-24T12:02:01Z"),
                eventType = AppUsageEventType.FOREGROUND_INTERVAL,
                sourceRevision = "event-1",
            )
        )
        val second = factory.create(
            AppUsageEvent(
                packageName = "com.example.chat",
                foregroundSince = Instant.parse("2026-09-24T12:03:00Z"),
                backgroundAt = null,
                observedAt = Instant.parse("2026-09-24T12:03:00Z"),
                eventType = AppUsageEventType.FOREGROUND_ENTER,
                sourceRevision = "event-2",
            )
        )
        val cursor = AppSensorCursor(sensorId, revision = 0L)
        val batch = AppObservationBatch.create(
            sensorId = sensorId,
            observations = listOf(second, first, first),
            nextCursor = AppSensorCursor(sensorId, revision = 1L, sourcePosition = "2"),
            exhausted = false,
        )

        val validated = AppObservationIngress.validate(
            descriptor = descriptor,
            cursor = cursor,
            budget = AppSensorBudget(maxObservations = 8, maxPayloadChars = 16_384),
            batch = batch,
        )

        assertEquals(2, validated.observations.size)
        assertEquals(first.id, validated.observations.first().id)
        assertEquals(1L, validated.nextCursor.revision)
    }

    @Test
    fun adapterCannotSelfAuthorizeOwnerObservationPolicy() {
        val event = factory.create(
            AppUsageEvent(
                packageName = "com.example.bank",
                foregroundSince = Instant.parse("2026-09-24T12:00:00Z"),
                backgroundAt = null,
                observedAt = Instant.parse("2026-09-24T12:00:00Z"),
                eventType = AppUsageEventType.FOREGROUND_ENTER,
                sourceRevision = "event-1",
            )
        ).authorizedBy("forged-adapter-grant")
        val cursor = AppSensorCursor(sensorId, 0L)
        val batch = AppObservationBatch.create(
            sensorId,
            listOf(event),
            AppSensorCursor(sensorId, 1L),
            exhausted = true,
        )

        assertFailsWith<IllegalArgumentException> {
            AppObservationIngress.validate(
                descriptor,
                cursor,
                AppSensorBudget(),
                batch,
            )
        }
    }

    @Test
    fun usageContextIsProjectedBehaviorEvidenceNotAppContentOrIntent() {
        val observation = factory.create(
            AppUsageEvent(
                packageName = "com.example.bank",
                foregroundSince = Instant.parse("2026-09-24T12:00:00Z"),
                backgroundAt = Instant.parse("2026-09-24T12:12:00Z"),
                observedAt = Instant.parse("2026-09-24T12:12:01Z"),
                eventType = AppUsageEventType.FOREGROUND_INTERVAL,
                sourceRevision = "usage-42",
            )
        )

        assertEquals(ObservationSurfaceKind.APP_USAGE, observation.surface)
        assertEquals(RepresentationLevel.PROJECTED, observation.realization.representation)
        assertEquals(ObservationAuthorityClass.PLATFORM_PROVIDER, observation.authority)
        assertEquals("720000", observation.metadata["durationMillis"])
        assertNull(observation.observationGrantId)
        assertTrue("behavior-context" in observation.tags)
        assertTrue("title=" !in observation.payload)
        assertTrue("intent=" !in observation.payload)
    }
}
