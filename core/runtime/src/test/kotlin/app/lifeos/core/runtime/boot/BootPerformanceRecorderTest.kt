package app.lifeos.core.runtime.boot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class BootPerformanceRecorderTest {
    @Test
    fun `records deterministic phase duration and wall clock span`() = runTest {
        val ticks = ArrayDeque(
            listOf(
                1_000_000L,
                4_000_000L,
                6_000_000L,
                10_000_000L,
            )
        )
        val recorder = BootPerformanceRecorder { ticks.removeFirst() }

        recorder.measure(BootPhaseId.RUNTIME_BOOTSTRAP) { Unit }
        recorder.measure(BootPhaseId.STORE_VERIFY) { Unit }

        val snapshot = recorder.snapshot()
        assertEquals(
            listOf(
                BootPhaseId.RUNTIME_BOOTSTRAP,
                BootPhaseId.STORE_VERIFY,
            ),
            snapshot.timings.map { it.phase },
        )
        assertEquals(3L, snapshot.timings[0].elapsedMillis)
        assertEquals(4L, snapshot.timings[1].elapsedMillis)
        assertEquals(9L, snapshot.wallClockMillis)
    }

    @Test
    fun `failed phase is still timed and duplicate measurement fails closed`() = runTest {
        var tick = 0L
        val recorder = BootPerformanceRecorder {
            tick += 1_000_000L
            tick
        }

        assertFailsWith<IllegalStateException> {
            recorder.measure(BootPhaseId.STATE_REHYDRATE) {
                error("restore failed")
            }
        }
        assertEquals(
            listOf(BootPhaseId.STATE_REHYDRATE),
            recorder.snapshot().timings.map { it.phase },
        )

        assertFailsWith<IllegalStateException> {
            recorder.measure(BootPhaseId.STATE_REHYDRATE) { Unit }
        }
    }

    @Test
    fun `reset isolates subsequent boot run`() = runTest {
        var tick = 0L
        val recorder = BootPerformanceRecorder {
            tick += 1_000_000L
            tick
        }
        recorder.measure(BootPhaseId.RUNTIME_BOOTSTRAP) { Unit }
        recorder.reset()
        recorder.measure(BootPhaseId.VALIDATE) { Unit }

        assertEquals(
            listOf(BootPhaseId.VALIDATE),
            recorder.snapshot().timings.map { it.phase },
        )
    }
}
