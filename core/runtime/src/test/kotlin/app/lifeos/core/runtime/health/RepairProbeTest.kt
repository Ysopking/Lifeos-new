package app.lifeos.core.runtime.health

import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepairProbeTest {
    private val t0 = Instant.parse("2026-09-08T09:00:00Z")

    @Test
    fun compositeEvidenceUsesCanonicalProbeAndFactOrdering() = runTest {
        val node = HealthNodeId("runtime:test")
        val field = FieldRepairProbe("z-field", HealthNodeId("field:test")) {
            RepairProbeObservation(
                status = RepairProbeStatus.HEALTHY,
                facts = listOf(
                    RepairProbeFact("version", "2"),
                    RepairProbeFact("domain", "runtime-shadow"),
                ),
            )
        }
        val store = StoreRepairProbe("a-store", HealthNodeId("store:test")) {
            RepairProbeObservation(RepairProbeStatus.HEALTHY)
        }

        val evidence = CompositeRepairProbe(listOf(field, store), now = { t0 }).collect(node)

        assertEquals(listOf("a-store", "z-field"), evidence.results.map { it.probeId })
        assertEquals(listOf("domain", "version"), evidence.results.last().facts.map { it.key })
        assertEquals(RepairProbeStatus.HEALTHY, evidence.worstStatus)
        assertTrue(evidence.verifiedHealthy)
    }

    @Test
    fun allComponentProbeKindsRemainExplicitInEvidence() = runTest {
        val node = HealthNodeId("runtime:test")
        val healthy: suspend () -> RepairProbeObservation = {
            RepairProbeObservation(RepairProbeStatus.HEALTHY)
        }
        val probes = listOf(
            StoreRepairProbe("store", HealthNodeId("store:test"), healthy),
            WorkerRepairProbe("worker", HealthNodeId("worker:test"), healthy),
            FieldRepairProbe("field", HealthNodeId("field:test"), healthy),
            CapabilityToolRepairProbe("capability-tool", HealthNodeId("tool:test"), healthy),
        )

        val evidence = CompositeRepairProbe(probes, now = { t0 }).collect(node)

        assertEquals(
            setOf(
                RepairProbeKind.STORE,
                RepairProbeKind.WORKER,
                RepairProbeKind.FIELD,
                RepairProbeKind.CAPABILITY_TOOL,
            ),
            evidence.results.map { it.kind }.toSet(),
        )
        assertTrue(evidence.verifiedHealthy)
    }

    @Test
    fun probeExceptionBecomesUnavailableEvidenceInsteadOfFalseHealth() = runTest {
        val node = HealthNodeId("worker:test")
        val failing = WorkerRepairProbe("worker", node) {
            error("boom")
        }

        val evidence = CompositeRepairProbe(listOf(failing), now = { t0 }).collect(node)

        assertFalse(evidence.verifiedHealthy)
        assertEquals(RepairProbeStatus.UNAVAILABLE, evidence.worstStatus)
        assertEquals("probe-exception:IllegalStateException", evidence.results.single().message)
    }

    @Test
    fun cancellationPropagatesAndIsNeverConvertedIntoProbeEvidence() = runTest {
        val node = HealthNodeId("worker:test")
        val cancelled = WorkerRepairProbe("worker", node) {
            throw CancellationException("cancel")
        }

        assertFailsWith<CancellationException> {
            CompositeRepairProbe(listOf(cancelled), now = { t0 }).collect(node)
        }
    }

    @Test
    fun degradedProbePreventsVerifiedHealthyComposite() = runTest {
        val node = HealthNodeId("runtime:test")
        val worker = WorkerRepairProbe("worker", HealthNodeId("worker:test")) {
            RepairProbeObservation(RepairProbeStatus.HEALTHY)
        }
        val store = StoreRepairProbe("store", HealthNodeId("store:test")) {
            RepairProbeObservation(
                RepairProbeStatus.DEGRADED,
                message = "readable-with-corrupt-entry",
            )
        }

        val evidence = CompositeRepairProbe(listOf(worker, store), now = { t0 }).collect(node)

        assertFalse(evidence.verifiedHealthy)
        assertEquals(RepairProbeStatus.DEGRADED, evidence.worstStatus)
    }
}
