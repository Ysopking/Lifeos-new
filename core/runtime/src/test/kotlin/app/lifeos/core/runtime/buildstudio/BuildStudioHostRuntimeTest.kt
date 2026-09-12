package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.topology.LifeOsRuntimeBindingRegistry
import app.lifeos.core.runtime.topology.LifeOsRuntimeBindingState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class BuildStudioHostRuntimeTest {
    @AfterTest
    fun cleanup() = runTest {
        BuildStudioHostProcessRegistry.uninstall()
        LifeOsRuntimeBindingRegistry.clearForTests()
    }

    @Test
    fun `ready host is the only source of active buildstudio capability`() = runTest {
        val capabilities = CapabilityRegistry()
        assertNull(capabilities.providersFor(BuildStudioHostProcessRegistry.CAPABILITY_ID).singleOrNull())

        BuildStudioHostProcessRegistry.install(
            host = FakeHost(BuildStudioHostState.READY),
            capabilities = capabilities,
        )

        val provider = assertNotNull(
            capabilities.providersFor(BuildStudioHostProcessRegistry.CAPABILITY_ID).singleOrNull()
        )
        assertEquals(ProviderState.ACTIVE, provider.state)
        assertEquals("buildstudio-host:test-host", provider.providerId)
        assertEquals(
            LifeOsRuntimeBindingState.ACTIVE,
            LifeOsRuntimeBindingRegistry.current(BuildStudioHostProcessRegistry.SUBSYSTEM_ID)?.state,
        )
    }

    @Test
    fun `degraded and quarantined host health is reflected in shared routing state`() = runTest {
        val capabilities = CapabilityRegistry()
        val host = FakeHost(BuildStudioHostState.DEGRADED)
        BuildStudioHostProcessRegistry.install(host, capabilities)

        assertEquals(
            ProviderState.DEGRADED,
            capabilities.providersFor(BuildStudioHostProcessRegistry.CAPABILITY_ID).single().state,
        )

        host.state = BuildStudioHostState.QUARANTINED
        BuildStudioHostProcessRegistry.refresh()

        assertNull(capabilities.providersFor(BuildStudioHostProcessRegistry.CAPABILITY_ID).singleOrNull())
        assertEquals(
            ProviderState.QUARANTINED,
            capabilities.providersFor(
                BuildStudioHostProcessRegistry.CAPABILITY_ID,
                includeUnavailable = true,
            ).single().state,
        )
        assertEquals(
            LifeOsRuntimeBindingState.QUARANTINED,
            LifeOsRuntimeBindingRegistry.current(BuildStudioHostProcessRegistry.SUBSYSTEM_ID)?.state,
        )
    }

    @Test
    fun `uninstall removes buildstudio provider instead of leaving stale active capability`() = runTest {
        val capabilities = CapabilityRegistry()
        BuildStudioHostProcessRegistry.install(FakeHost(BuildStudioHostState.READY), capabilities)

        BuildStudioHostProcessRegistry.uninstall()

        assertNull(
            capabilities.providersFor(
                BuildStudioHostProcessRegistry.CAPABILITY_ID,
                includeUnavailable = true,
            ).singleOrNull()
        )
        assertEquals(
            LifeOsRuntimeBindingState.STOPPED,
            LifeOsRuntimeBindingRegistry.current(BuildStudioHostProcessRegistry.SUBSYSTEM_ID)?.state,
        )
    }

    private class FakeHost(
        var state: BuildStudioHostState,
    ) : BuildStudioHostAdapter {
        override val id: String = "test-host"

        override suspend fun status(): BuildStudioHostStatus = BuildStudioHostStatus(state)

        override suspend fun run(spec: BuildSpec): BuildStudioResult =
            BuildStudioResult.Failed("test", "not-executed")
    }
}
