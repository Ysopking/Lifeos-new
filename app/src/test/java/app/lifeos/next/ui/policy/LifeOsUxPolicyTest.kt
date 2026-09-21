package app.lifeos.next.ui.policy

import app.lifeos.core.runtime.life.LifeOsReadinessSnapshot
import app.lifeos.next.kernel.InitialCognitiveContextPhase
import app.lifeos.next.kernel.InitialCognitiveContextReadiness
import app.lifeos.next.ui.chat.ChatTurnProcessingState
import app.lifeos.next.ui.components.RuntimeHealthLevel
import app.lifeos.next.ui.components.RuntimeHealthUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LifeOsUxPolicyTest {
    @Test
    fun healthyAndVerifyingRuntimeStayQuiet() {
        assertNull(LifeOsUxPolicy.runtimeNotice(runtime(RuntimeHealthLevel.READY)))
        assertNull(LifeOsUxPolicy.runtimeNotice(runtime(RuntimeHealthLevel.VERIFYING)))
    }

    @Test
    fun degradedAndFailedRuntimeSurfaceOnlyHumanMeaning() {
        val degraded = requireNotNull(LifeOsUxPolicy.runtimeNotice(runtime(RuntimeHealthLevel.DEGRADED)))
        val failed = requireNotNull(LifeOsUxPolicy.runtimeNotice(runtime(RuntimeHealthLevel.FAILED)))

        assertEquals(LifeOsUxPriority.ACTION_REQUIRED, degraded.priority)
        assertEquals(LifeOsUxPriority.BLOCKING, failed.priority)
        assertTrue("topologie" !in degraded.message.lowercase())
        assertTrue("readiness" !in failed.message.lowercase())
    }

    @Test
    fun activeProcessingStaysQuietButFailureIsExplained() {
        assertNull(
            LifeOsUxPolicy.processingNotice(
                ChatTurnProcessingState.submitting("turn-1")
            )
        )
        val failed = requireNotNull(
            LifeOsUxPolicy.processingNotice(
                ChatTurnProcessingState.failed(
                    turnId = "turn-1",
                    userTurnPersisted = true,
                    message = "backend-detail",
                )
            )
        )
        assertEquals(LifeOsUxPriority.ACTION_REQUIRED, failed.priority)
        assertTrue("backend-detail" !in failed.message)
    }

    @Test
    fun permissionWaitIsVisibleWithoutBackendJargon() {
        val notice = requireNotNull(
            LifeOsUxPolicy.contextNotice(
                context(InitialCognitiveContextPhase.WAITING_FOR_PERMISSIONS)
            )
        )
        assertEquals(LifeOsUxPriority.CONTEXT, notice.priority)
        assertTrue("permission" !in notice.message.lowercase())
        assertTrue("context" !in notice.message.lowercase())
    }

    private fun runtime(level: RuntimeHealthLevel) = RuntimeHealthUiModel(
        level = level,
        compactLabel = "technical",
        summary = "technical",
        bootLabel = "technical",
        readinessSummary = "technical",
        topologySummary = "technical",
        detailsAvailable = true,
    )

    private fun context(phase: InitialCognitiveContextPhase) =
        InitialCognitiveContextReadiness(
            phase = phase,
            contextReady = phase == InitialCognitiveContextPhase.READY ||
                phase == InitialCognitiveContextPhase.PARTIAL,
            sources = emptyList(),
            authorizedSources = 0,
            unauthorizedSources = if (phase == InitialCognitiveContextPhase.PARTIAL) 1 else 0,
            unavailableSources = 0,
            importedPhotonCount = 0,
            indexedNodeCount = 0,
            projectionRevision = null,
            failure = if (phase == InitialCognitiveContextPhase.FAILED) "backend-detail" else null,
        )
}
