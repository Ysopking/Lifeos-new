package app.lifeos.core.runtime.resource

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ExecutionSampleTest {
    @Test
    fun fingerprintBindsExecutionDecisionContext() {
        val base = ExecutionSample(
            measurement = ExecutionMeasurement(
                operationId = "op-1",
                measuredAt = Instant.parse("2026-09-19T00:00:00Z"),
                elapsedMillis = 12,
                workUnits = 20,
                peakMemoryBytes = 100,
                ioBytes = 0,
                networkBytes = 0,
                candidates = 1,
                thermalState = HardwareThermalState.NOMINAL,
                batteryFraction = 0.8,
                outcomeCode = "ok",
                utility = 1.0,
            ),
            domain = ResourceBudgetDomain.COGNITION,
            operationKind = "COGNITIVE_FIELD",
            executionClass = HardwareExecutionClass.CPU_COMPUTE,
            workGraphId = "graph-1",
            workNodeId = "node-1",
            hardwareFingerprint = "hardware",
            executionPlanFingerprint = "plan",
            strategyFingerprint = "strategy",
            learningProfileFingerprint = "learning",
            queueWaitMillis = 2,
            cpuTimeMillis = 8,
            parallelism = 4,
            batchSize = 8,
            reused = false,
        )

        assertEquals(base.fingerprint(), base.copy().fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(parallelism = 2).fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(reused = true).fingerprint())
    }
}
