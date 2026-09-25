package app.lifeos.next.kernel

import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.SensorAttentionMode
import app.lifeos.core.runtime.life.SensorClass
import app.lifeos.core.runtime.life.SensorHealthState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `owner special access refresh updates productive app usage health`() = runTest {
        val source = FakeUsageSource(
            granted = false,
            events = emptyList(),
        )
        val health = mutableListOf<SensorHealthState>()
        val bridge = AndroidAppUsageSensorBridge(
            source = source,
            commitBatch = AppUsageBatchCommitter { _, _, _, _ -> Unit },
            clock = Clock.fixed(
                Instant.parse("2026-09-25T01:00:30Z"),
                ZoneOffset.UTC,
            ),
            scope = this,
        )
        bridge.bindHealthReporter { state, _ -> health += state }
        ProductiveAppUsageSensorRuntimeRegistry.install(bridge)
        try {
            ProductiveAppUsageSensorRuntimeRegistry.refreshAvailability()
            source.granted = true
            ProductiveAppUsageSensorRuntimeRegistry.refreshAvailability()

            assertEquals(
                listOf(
                    SensorHealthState.UNAVAILABLE,
                    SensorHealthState.HEALTHY,
                ),
                health,
            )
        } finally {
            ProductiveAppUsageSensorRuntimeRegistry.clearForTests()
        }
    }

    @Test
    fun `suspended runtime owns no idle app usage polling job`() = runTest {
        val source = FakeUsageSource(
            granted = true,
            events = emptyList(),
        )
        val bridge = AndroidAppUsageSensorBridge(
            source = source,
            commitBatch = AppUsageBatchCommitter { _, _, _, _ -> Unit },
            clock = Clock.fixed(
                Instant.parse("2026-09-25T01:00:30Z"),
                ZoneOffset.UTC,
            ),
            scope = this,
        )
        bridge.bindHealthReporter { _, _ -> Unit }

        bridge.start()
        runCurrent()
        assertEquals(0, source.queryCount)

        bridge.applyAttention(SensorAttentionMode.PERIODIC)
        runCurrent()
        assertEquals(1, source.queryCount)

        bridge.applyAttention(SensorAttentionMode.SUSPENDED)
        bridge.stop()
    }

    @Test
    fun `self usage is excluded to prevent recursive context churn`() = runTest {
        val now = Instant.parse("2026-09-25T01:00:30Z")
        val source = FakeUsageSource(
            granted = true,
            events = listOf(
                PlatformAppUsageEvent(
                    packageName = "app.lifeos.next",
                    timestampMillis = Instant.parse("2026-09-25T01:00:05Z").toEpochMilli(),
                    kind = PlatformAppUsageEventKind.FOREGROUND,
                ),
                PlatformAppUsageEvent(
                    packageName = "com.example.external",
                    timestampMillis = Instant.parse("2026-09-25T01:00:10Z").toEpochMilli(),
                    kind = PlatformAppUsageEventKind.FOREGROUND,
                ),
            ),
        )
        val committed = mutableListOf<InformationObservation>()
        val bridge = AndroidAppUsageSensorBridge(
            source = source,
            commitBatch = AppUsageBatchCommitter { _, _, _, batch ->
                committed += batch.observations
            },
            excludedPackageNames = setOf("app.lifeos.next"),
            clock = Clock.fixed(now, ZoneOffset.UTC),
            scope = this,
        )
        bridge.bindHealthReporter { _, _ -> Unit }
        bridge.applyAttention(SensorAttentionMode.PERIODIC)

        assertEquals(1, bridge.pollOnce())
        assertEquals(
            listOf("android-usage:com.example.external"),
            committed.map { it.sourceResource },
        )
    }

    @Test
    fun `saturated usage window drains without skipping unread platform events`() = runTest {
        val now = Instant.parse("2026-09-25T01:01:00Z")
        val baseMillis = Instant.parse("2026-09-25T01:00:10Z").toEpochMilli()
        val source = FakeUsageSource(
            granted = true,
            events = (0 until 65).map { index ->
                PlatformAppUsageEvent(
                    packageName = "com.example.app$index",
                    timestampMillis = baseMillis + index * 500L,
                    kind = PlatformAppUsageEventKind.FOREGROUND,
                )
            },
        )
        val committed = mutableListOf<InformationObservation>()
        val exhausted = mutableListOf<Boolean>()
        val bridge = AndroidAppUsageSensorBridge(
            source = source,
            commitBatch = AppUsageBatchCommitter { _, _, _, batch ->
                committed += batch.observations
                exhausted += batch.exhausted
            },
            clock = Clock.fixed(now, ZoneOffset.UTC),
            scope = this,
        )
        bridge.bindHealthReporter { _, _ -> Unit }
        bridge.applyAttention(SensorAttentionMode.PERIODIC)

        val first = bridge.pollOnce()
        val second = bridge.pollOnce()

        assertTrue(first in 1..64)
        assertEquals(65, first + second)
        assertEquals(65, committed.map { it.id }.distinct().size)
        assertEquals(listOf(false, true), exhausted)
        assertTrue(source.queryCount >= 3)
    }

    @Test
    fun `failed productive commit replays identical usage observation identities`() = runTest {
        var now = Instant.parse("2026-09-25T01:00:30Z")
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = now
        }
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
        val attempts = mutableListOf<List<String>>()
        var firstAttempt = true
        val bridge = AndroidAppUsageSensorBridge(
            source = source,
            commitBatch = AppUsageBatchCommitter { _, _, _, batch ->
                attempts += batch.observations.map { it.id.value }
                if (firstAttempt) {
                    firstAttempt = false
                    error("synthetic-commit-failure")
                }
            },
            clock = clock,
            scope = this,
        )
        bridge.bindHealthReporter { _, _ -> Unit }
        bridge.applyAttention(SensorAttentionMode.PERIODIC)

        var failed = false
        try {
            bridge.pollOnce()
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)

        now = Instant.parse("2026-09-25T01:00:40Z")
        assertEquals(2, bridge.pollOnce())

        assertEquals(2, attempts.size)
        assertEquals(attempts.first(), attempts.last())
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
        bridge.applyAttention(SensorAttentionMode.PERIODIC)

        assertEquals(0, bridge.pollOnce())

        assertEquals(0, source.queryCount)
        assertEquals(0, commits)
        assertEquals("usage-access-not-granted", failure)
        assertEquals(SensorHealthState.UNAVAILABLE, bridge.currentHealth())
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
