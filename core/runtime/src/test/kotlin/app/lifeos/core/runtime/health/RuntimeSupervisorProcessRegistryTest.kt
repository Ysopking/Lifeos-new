package app.lifeos.core.runtime.health

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.LifeOsRuntime
import app.lifeos.core.runtime.RuntimeState
import app.lifeos.core.runtime.RuntimeStatus
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.RuntimeSupervisorProcessRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertSame

class RuntimeSupervisorProcessRegistryTest {
    @Test
    fun `latest constructed supervisor is exposed as process instance`() {
        val supervisor = RuntimeSupervisor(FakeRuntime())
        assertSame(supervisor, RuntimeSupervisorProcessRegistry.current())
    }

    private class FakeRuntime : LifeOsRuntime {
        private val mutable = MutableStateFlow(RuntimeState(RuntimeStatus.CREATED))
        override val state: StateFlow<RuntimeState> = mutable
        override fun start() { mutable.value = RuntimeState(RuntimeStatus.RUNNING) }
        override fun stop() { mutable.value = RuntimeState(RuntimeStatus.STOPPED) }
        override suspend fun ingest(photon: Photon) = Unit
    }
}
