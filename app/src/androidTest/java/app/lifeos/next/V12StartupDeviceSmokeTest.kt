package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.runtime.RuntimeSupervisorProcessRegistry
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionRuntimeRegistry
import app.lifeos.core.runtime.health.HealthGraphProcessRegistry
import app.lifeos.next.kernel.KernelBootstrapStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android-process smoke coverage for the V12 startup composition and recovery registries. */
@RunWith(AndroidJUnit4::class)
class V12StartupDeviceSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: LifeOsApplication
        get() = instrumentation.targetContext.applicationContext as LifeOsApplication

    @Test
    fun coldStartInstallsDeepSearchAndSelfHealingBeforeKernelBecomesReady() = runBlocking {
        val boot = withTimeout(BOOT_TIMEOUT_MS) {
            app.kernel.bootstrapState.first { state ->
                state.status == KernelBootstrapStatus.READY ||
                    state.status == KernelBootstrapStatus.DEGRADED ||
                    state.status == KernelBootstrapStatus.FAILED
            }
        }

        assertTrue(
            "V12 cold start must reach a usable kernel state; failure=${boot.failureMessage}",
            boot.status == KernelBootstrapStatus.READY || boot.status == KernelBootstrapStatus.DEGRADED,
        )
        assertNotNull(
            "DeepSearch mission runtime must be installed before productive startup",
            DeepSearchMissionRuntimeRegistry.currentOrNull(),
        )
        assertNotNull(
            "Kernel factory must expose the shared HealthGraph before self-healing starts",
            HealthGraphProcessRegistry.current(),
        )
        assertNotNull(
            "Kernel factory must expose the RuntimeSupervisor before self-healing starts",
            RuntimeSupervisorProcessRegistry.current(),
        )
        app.selfHealingRuntime.verifyLedgerIntegrity()
    }

    private companion object {
        const val BOOT_TIMEOUT_MS = 30_000L
    }
}
