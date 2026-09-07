package app.lifeos.core.runtime

import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CognitiveRuntimeTest {
    @Test fun processesPhoton() = runTest {
        val runtime = CognitiveRuntime(this, emptyList())
        runtime.start()
        runtime.ingest(Photon(content = "test", provenance = Provenance("test", "test")))
        testScheduler.advanceUntilIdle()
        assertEquals(1, runtime.state.value.processed)
        runtime.stop()
        assertEquals(false, runtime.state.value.running)
    }
}

class RuntimeResilienceTest {
    @Test fun failingFieldDoesNotSkipHealthyField() = runTest {
        var reached = false
        val runtime = CognitiveRuntime(backgroundScope, listOf(
            ForceField { error("Broken module") },
            ForceField { reached = true; null },
        ))
        runtime.start()
        runtime.ingest(Photon(content = "test", provenance = Provenance("test", "user")))
        testScheduler.runCurrent()
        assertEquals(true, reached)
        assertEquals(1, runtime.state.value.failed)
        assertEquals(0, runtime.state.value.processed)
        runtime.stop()
    }

    @Test fun cancellationStopsWorkerAndRestartProcessesQueuedPhoton() = runTest {
        var first = true
        val runtime = CognitiveRuntime(backgroundScope, listOf(ForceField {
            if (first) { first = false; throw kotlinx.coroutines.CancellationException("Stop") }
            null
        }))
        val photon = Photon(content = "test", provenance = Provenance("test", "user"))
        runtime.start()
        runtime.ingest(photon)
        testScheduler.runCurrent()
        assertEquals(false, runtime.state.value.running)
        assertEquals(0, runtime.state.value.failed)
        runtime.ingest(photon)
        runtime.start()
        runtime.start()
        testScheduler.runCurrent()
        assertEquals(1, runtime.state.value.processed)
        runtime.stop()
    }
}
