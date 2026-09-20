package app.lifeos.core.runtime.boot

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BootPerformanceRecorderTest {
    @Test
    fun `measure records successful and failed phases in finally`() = runTest {
        var nanos = 0L
        val recorder = BootPerformanceRecorder {
            nanos += 2_000_000L
            nanos
        }

        recorder.measure(BootPhaseId.RUNTIME_BOOTSTRAP) { "ready" }
        assertFailsWith<IllegalStateException> {
            recorder.measure(BootPhaseId.STORE_VERIFY) {
                error("verification-failed")
            }
        }

        val snapshot = recorder.snapshot()
        assertEquals(
            listOf(
                BootPhaseId.RUNTIME_BOOTSTRAP.stableId,
                BootPhaseId.STORE_VERIFY.stableId,
            ),
            snapshot.timings.map { it.phase },
        )
        assertEquals(listOf(2L, 2L), snapshot.timings.map { it.elapsedMillis })
        assertEquals(4L, snapshot.totalMeasuredMillis)
    }

    @Test
    fun `reset starts a fresh boot timing epoch`() = runTest {
        var nanos = 0L
        val recorder = BootPerformanceRecorder { ++nanos }

        recorder.measure(BootPhaseId.RUNTIME_BOOTSTRAP) { Unit }
        assertEquals(1, recorder.snapshot().timings.size)

        recorder.reset()

        assertEquals(BootPerformanceSnapshot.EMPTY, recorder.snapshot())
        recorder.measure(BootPhaseId.STORE_VERIFY) { Unit }
        assertEquals(
            listOf(BootPhaseId.STORE_VERIFY.stableId),
            recorder.snapshot().timings.map { it.phase },
        )
    }
}
