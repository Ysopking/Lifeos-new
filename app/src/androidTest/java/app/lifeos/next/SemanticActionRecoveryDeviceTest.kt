package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.next.kernel.KernelBootstrapState
import app.lifeos.next.kernel.KernelBootstrapStatus
import app.lifeos.next.kernel.LocalCommunicationExecutionResult
import app.lifeos.next.kernel.SemanticNodeExecutionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticActionRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: LifeOsApplication
        get() = instrumentation.targetContext.applicationContext as LifeOsApplication

    @Test
    fun seedSemanticActionDataflow() = runBlocking {
        assertTrue(awaitBoot().ready)
        val user = Photon(
            content = "Merke dir die Semantic-Recovery-Notiz und sende sie mir anschließend.",
            provenance = Provenance(
                source = "semantic-action-recovery-device-test",
                actor = "user",
            ),
            tags = setOf(
                "chat",
                "chat:user",
                "conversation:default",
                USER_SENTINEL_TAG,
            ),
        )

        val submission = app.kernel.persistUserUtterance(user)
        val execution = requireNotNull(submission.actionGraphExecution)
        val executionDiagnostic = execution.executions.joinToString(";") { node ->
            node.intent.name + ":" + node.state.name + ":" + node.reason.orEmpty()
        }
        assertTrue(
            "Semantic action graph must complete; blocked=${execution.blockedReason}; " +
                "executions=$executionDiagnostic",
            execution.completed,
        )
        assertEquals(2, execution.executions.size)
        assertTrue(
            "Every semantic node must execute; executions=$executionDiagnostic",
            execution.executions.all { it.state == SemanticNodeExecutionState.EXECUTED },
        )

        val upstreamRef = requireNotNull(execution.executions.first().outputRef)
        val communication = execution.executions.last().dispatch?.localCommunication
        val prepared = communication as? LocalCommunicationExecutionResult.Prepared
        assertNotNull("Second action must prepare communication", prepared)
        prepared!!
        assertEquals(upstreamRef.photonId, prepared.share.target.id)
        assertEquals(upstreamRef.revision, prepared.share.target.revision)
    }

    @Test
    fun recoverSemanticActionDataflowAfterColdStart() = runBlocking {
        assertTrue(awaitBoot().ready)
        val photons = app.kernel.photonStore.loadAll()
        val user = photons.singleOrNull { USER_SENTINEL_TAG in it.tags }
        assertNotNull("Semantic recovery user Photon missing", user)
        user!!

        val goal = photons.singleOrNull {
            it.mimeType == GOAL_MIME && user.id in it.provenance.parentIds
        }
        assertNotNull("Semantic recovery goal/v4 missing", goal)
        goal!!
        assertTrue(goal.content.startsWith("goal/v4\n"))
        assertTrue(goal.content.contains("USES_RESULT_OF"))

        val memories = photons.filter {
            "memory" in it.tags &&
                user.id in it.provenance.parentIds
        }
        assertEquals("Upstream memory action must not duplicate across restart", 1, memories.size)
        val memory = memories.single()

        val preparations = photons.filter {
            "share-preparation" in it.tags &&
                goal.id in it.provenance.parentIds
        }
        assertEquals(
            "Downstream communication preparation must remain singular",
            1,
            preparations.size,
        )
        assertTrue(memory.id in preparations.single().provenance.parentIds)
    }

    private suspend fun awaitBoot(): KernelBootstrapState = withTimeout(BOOT_TIMEOUT_MS) {
        val processStartup = app.startupState.first { state ->
            state.phase == LifeOsProcessStartupPhase.READY ||
                state.phase == LifeOsProcessStartupPhase.FAILED
        }
        if (processStartup.phase == LifeOsProcessStartupPhase.FAILED) {
            error(
                "Process startup failed during semantic action recovery: " +
                    (processStartup.failure ?: "unknown")
            )
        }
        app.kernel.bootstrapState.first { state ->
            state.status == KernelBootstrapStatus.READY ||
                state.status == KernelBootstrapStatus.DEGRADED ||
                state.status == KernelBootstrapStatus.FAILED
        }.also { state ->
            if (state.status == KernelBootstrapStatus.FAILED) {
                error(
                    "Kernel boot failed during semantic action recovery: " +
                        (state.failureMessage ?: "unknown")
                )
            }
        }
    }

    companion object {
        private const val BOOT_TIMEOUT_MS = 20_000L
        private const val USER_SENTINEL_TAG = "semantic-action-recovery:user"
        private const val GOAL_MIME = "application/vnd.lifeos.goal+text"
    }
}
