package app.lifeos.core.data

import app.lifeos.core.runtime.RuntimeFailureCategory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class LiveSourceM02ReliabilityTest {
    private val at = Instant.parse("2026-09-19T03:10:00Z")

    @Test
    fun coalescerFailsClosedInsteadOfDroppingDistinctObjects() {
        val source = LiveSourceId("m02-source")
        val deltas = listOf(
            SourceDelta("d1", source, "a", SourceDeltaKind.UPDATED, null, "1", 1),
            SourceDelta("d2", source, "b", SourceDeltaKind.UPDATED, null, "1", 2),
            SourceDelta("d3", source, "c", SourceDeltaKind.UPDATED, null, "1", 3),
        )

        val error = assertFailsWith<SourceDeltaCapacityExceededException> {
            SourceDeltaCoalescer(2).coalesce(deltas)
        }

        assertEquals(3, error.distinctObjectCount)
        assertEquals(2, error.capacity)
    }

    @Test
    fun repeatedObjectChangesStillCoalesceLosslesslyWithinCapacity() {
        val source = LiveSourceId("m02-source")
        val result = SourceDeltaCoalescer(2).coalesce(
            listOf(
                SourceDelta("d1", source, "a", SourceDeltaKind.UPDATED, "0", "1", 1),
                SourceDelta("d2", source, "a", SourceDeltaKind.UPDATED, "1", "2", 2),
                SourceDelta("d3", source, "b", SourceDeltaKind.CREATED, null, "1", 3),
            )
        )

        assertEquals(listOf("d2", "d3"), result.map { it.deltaId })
    }

    @Test
    fun healthGraphReporterProjectsIntoExistingHealthAuthority() = runBlocking {
        val graph = app.lifeos.core.runtime.health.HealthGraph(
            unhealthyAfterConsecutiveFailures = 2,
            now = { at },
        )
        val reporter = HealthGraphLiveSourceHealthReporter(graph)
        val source = LiveSourceId("calendar-live")

        reporter.failed(
            sourceId = source,
            observedAt = at,
            category = RuntimeFailureCategory.DEPENDENCY,
            message = "provider unavailable",
            recoverable = true,
        )
        val degraded = requireNotNull(graph.node(liveSourceHealthNodeId(source)))
        assertEquals(app.lifeos.core.runtime.health.HealthState.DEGRADED, degraded.state)
        assertEquals(app.lifeos.core.runtime.health.HealthScope.EXTERNAL_APP, degraded.scope)

        reporter.healthy(source, at.plusSeconds(1), "provider recovered")
        val healthy = requireNotNull(graph.node(liveSourceHealthNodeId(source)))
        assertEquals(app.lifeos.core.runtime.health.HealthState.HEALTHY, healthy.state)
        assertEquals(0, healthy.consecutiveFailures)
    }
}
