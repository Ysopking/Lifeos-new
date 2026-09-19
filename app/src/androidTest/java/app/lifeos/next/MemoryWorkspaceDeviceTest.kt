package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.runtime.life.MemoryStage
import app.lifeos.next.kernel.KernelBootstrapState
import app.lifeos.next.kernel.KernelBootstrapStatus
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

        // READY intentionally means the productive supervisor is already running. It may append
        // legitimate runtime evidence concurrently with this read-only projection, so whole-store
        // equality is racy and can report a false F5 write. The projection must still preserve every
        // exact Photon revision that existed before it ran; additions from the live runtime are allowed.
        val missingOrChanged = durableBefore.filter { photon ->
            app.kernel.photonStore.load(
                PhotonRevisionRef(photon.id, photon.revision)
            ) != photon
        }
        assertTrue(
            "F5 projection must preserve every pre-existing Photon revision; changed=${missingOrChanged.map { "${it.id.value}@${it.revision}" }}",
            missingOrChanged.isEmpty(),
        )
    }

    private suspend fun awaitBoot(): KernelBootstrapState {
        val processStartup = withTimeout(BOOT_TIMEOUT_MS) {
            app.startupState.first { state ->
                state.phase == LifeOsProcessStartupPhase.READY ||
                    state.phase == LifeOsProcessStartupPhase.FAILED
            }
        }
        if (processStartup.phase == LifeOsProcessStartupPhase.FAILED) {
            error("Process startup failed during F5 memory proof: ${processStartup.failure ?: "unknown"}")
        }
        return withTimeout(BOOT_TIMEOUT_MS) {
            app.kernel.bootstrapState.first { state ->
                state.status == KernelBootstrapStatus.READY ||
                    state.status == KernelBootstrapStatus.DEGRADED ||
                    state.status == KernelBootstrapStatus.FAILED
            }
        }.also { state ->
            if (state.status == KernelBootstrapStatus.FAILED) {
                error("Kernel boot failed during F5 memory proof: ${state.failureMessage ?: "unknown"}")
            }
        }
    }

    private companion object {
        const val BOOT_TIMEOUT_MS = 30_000L
    }
}
