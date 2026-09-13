package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.runtime.life.MemoryStage
import app.lifeos.next.kernel.KernelBootstrapState
import app.lifeos.next.ui.memory.MemoryWorkspaceProjector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android proof that F5 reads the productive memory snapshot without rebuilding or rewriting it. */
@RunWith(AndroidJUnit4::class)
class MemoryWorkspaceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: LifeOsApplication
        get() = instrumentation.targetContext.applicationContext as LifeOsApplication

    @Test
    fun productiveSnapshotProjectsWithoutMemoryMutation() = runBlocking {
        val boot = awaitBoot()
        assertTrue("Kernel must complete boot before F5 memory proof", boot.ready)

        val snapshot = app.lifeMemoryRuntime.current()
        assertNotNull("Productive DurableLifeMemoryRuntime snapshot must exist", snapshot)
        snapshot!!
        val fingerprintBefore = snapshot.fingerprint
        val durableBefore = app.kernel.photonStore.loadAll()

        val workspace = MemoryWorkspaceProjector.project(
            snapshot = snapshot,
            photons = boot.photons,
        )

        assertTrue(workspace.projectionAvailable)
        assertEquals(snapshot.memory.evaluatedAt, workspace.projectionEvaluatedAt)
        assertEquals(snapshot.authoritativePhotonCount, workspace.authoritativePhotonCount)

        val stageById = snapshot.memory.decisions.associate { it.photonId to it.toStage }
        workspace.now.forEach { source ->
            val projectedStage = stageById[source.photonId]
            if (source.isNew) {
                assertNull("NEW evidence must not receive an invented memory stage", source.stage)
                assertNull(projectedStage)
            } else {
                assertEquals(projectedStage, source.stage)
                assertTrue(
                    "Jetzt may expose only productive HOT/WARM stages",
                    source.stage == MemoryStage.HOT || source.stage == MemoryStage.WARM,
                )
            }
        }

        assertEquals(
            "F5 projection must not rebuild or replace the productive memory snapshot",
            fingerprintBefore,
            app.lifeMemoryRuntime.current()?.fingerprint,
        )
        assertEquals(
            "F5 projection must not write Photon state",
            durableBefore,
            app.kernel.photonStore.loadAll(),
        )
    }

    private suspend fun awaitBoot(): KernelBootstrapState = withTimeout(BOOT_TIMEOUT_MS) {
        app.kernel.bootstrapState.first { state -> state.ready || state.failureMessage != null }
    }

    private companion object {
        const val BOOT_TIMEOUT_MS = 30_000L
    }
}
