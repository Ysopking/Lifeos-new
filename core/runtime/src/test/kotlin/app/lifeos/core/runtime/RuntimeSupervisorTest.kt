package app.lifeos.core.runtime

import app.lifeos.core.model.Photon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeSupervisorTest {
    @Test fun startAndStopAreIdempotent() = runTest {
        val runtime = FakeRuntime()
        val supervisor = RuntimeSupervisor(runtime)

        supervisor.start()
        supervisor.start()
        assertEquals(1, runtime.startCalls)
        assertEquals(RuntimeStatus.RUNNING, runtime.state.value.status)

        supervisor.stop()
        supervisor.stop()
        assertEquals(1, runtime.stopCalls)
        assertEquals(RuntimeStatus.STOPPED, runtime.state.value.status)
    }

    @Test fun restartPerformsOneStopAndOneStart() = runTest {
        val runtime = FakeRuntime().apply { start() }
        val supervisor = RuntimeSupervisor(runtime)

        supervisor.restart()

        assertEquals(2, runtime.startCalls)
        assertEquals(1, runtime.stopCalls)
        assertEquals(RuntimeStatus.RUNNING, runtime.state.value.status)
    }

    private class FakeRuntime : LifeOsRuntime {
        private val mutableState = MutableStateFlow(RuntimeState())
        override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

        var startCalls = 0
            private set
        var stopCalls = 0
            private set

        override fun start() {
            startCalls++
            mutableState.value = mutableState.value.copy(status = RuntimeStatus.RUNNING)
        }

        override fun stop() {
            stopCalls++
            mutableState.value = mutableState.value.copy(status = RuntimeStatus.STOPPED)
        }

        override suspend fun ingest(photon: Photon) = Unit
    }
}
