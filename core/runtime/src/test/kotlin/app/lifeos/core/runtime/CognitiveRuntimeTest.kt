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
    }
}
