package app.lifeos.next.kernel

import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.SensorAttentionMode
import app.lifeos.core.runtime.life.SensorClass
import app.lifeos.core.runtime.life.SensorHealthState
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidAppUsageSensorBridgeTest {
    @Test
    fun `usage events become bounded canonical app usage observations without authority`() = runTest {
        val now = Instant.parse("2026-09-25T01:00:30Z")
        val source = FakeUsageSource(
            granted = true,
            events = listOf(
                PlatformAppUsageEvent(
                    packageName = "com.example.app",
                    timestampMillis = Instant.parse("2026-09-25T01:00:10Z").toEpochMilli(),
                    kind = PlatformAppUsageEventKind.FOREGROUND,
                ),
                PlatformAppUsageEvent(
                    packageName = "com.example.app",
                    timestampMillis = Instant.parse("2026-09-25T01:00:20Z").toEpochMilli(),
                    kind = PlatformAppUsageEventKind.BACKGROUND,
                ),
            ),
        )
        val revisions = mutableListOf<Pair<Long, Long>>()
        val committed = mutableListOf<InformationObservation>()
        val bridge = AndroidAppUsageSensorBridge(
            source = source,
            commitBatch = AppUsageBatchCommitter { descriptor, cursor, budget, batch ->
                assertEquals(SensorClass.APP_USAGE, descriptor.sensorClass)
                assertEquals(64, budget.maxObservations)
                revisions += cursor.revision to batch.nextCursor.revision
                committed += batch.observations
            },
            clock = Clock.fixed(now, ZoneOffset.UTC),
            scope = this,
        )
        val health = mutableListOf<SensorHealthState>()
        bridge.bindHealthReporter { state, _ -> health += state }
        bridge.applyAttention(SensorAttentionMode.PERIODIC)

        val count = bridge.pollOnce()

        assertEquals(2, count)
        assertEquals(listOf(0L to 1L), revisions)
        assertEquals(2, committed.size)
        assertTrue(committed.all { it.surface == ObservationSurfaceKind.APP_USAGE })
        assertTrue(committed.all { it.sourceResource == "android-usage:com.example.app" })
        assertTrue(committed.all { it.observationGrantId == null })
        assertEquals(
            setOf("FOREGROUND_ENTER", "FOREGROUND_INTERVAL"),
            committed.mapNotNull { it.metadata["eventType"] }.toSet(),
        )
        assertEquals(listOf(SensorHealthState.HEALTHY), health)

        bridge.applyAttention(SensorAttentionMode.SUSPENDED)
        assertEquals(0, bridge.pollOnce())
        assertEquals(listOf(0L to 1L), revisions)
    }

    @Test
    fun `missing usage access fails closed before adapter query or commit`() = runTest {
        val source = FakeUsageSource(
            granted = false,
            events = emptyList(),
        )
        var commits = 0
        val bridge = AndroidAppUsageSensorBridge(
            source = source,
            commitBatch = AppUsageBatchCommitter { _, _, _, _ -> commits += 1 },
            clock = Clock.fixed(
                Instant.parse("2026-09-25T01:00:30Z"),
                ZoneOffset.UTC,
            ),
            scope = this,
        )
        var failure: String? = null
        bridge.bindHealthReporter { state, reason ->
            assertEquals(SensorHealthState.UNAVAILABLE, state)
            failure = reason
        }

        assertEquals(0, bridge.pollOnce())

        assertEquals(0, source.queryCount)
        assertEquals(0, commits)
        assertEquals("usage-access-not-granted", failure)
        assertEquals(SensorHealthState.UNAVAILABLE, bridge.currentHealth())
        assertNull(null)
    }

    private class FakeUsageSource(
        var granted: Boolean,
        private val events: List<PlatformAppUsageEvent>,
    ) : AppUsageEventSource {
        var queryCount: Int = 0

        override fun isAccessGranted(): Boolean = granted

        override fun queryEvents(
            beginMillis: Long,
            endMillis: Long,
            maxEvents: Int,
        ): List<PlatformAppUsageEvent> {
            queryCount += 1
            return events
                .filter { it.timestampMillis in beginMillis..endMillis }
                .take(maxEvents)
        }
    }
}
