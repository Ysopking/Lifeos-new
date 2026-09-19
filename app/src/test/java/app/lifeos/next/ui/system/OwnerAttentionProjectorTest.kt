package app.lifeos.next.ui.system

import app.lifeos.next.ui.components.RuntimeHealthLevel
import app.lifeos.next.ui.layout.attentionBadgeLabel
import app.lifeos.next.ui.layout.systemActionDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerAttentionProjectorTest {
    @Test
    fun readySystemWithoutHiddenOwnerWorkStaysQuiet() {
        val state = OwnerAttentionProjector.projectCounts(
            pendingAssetReviews = 0,
            toolActions = 0,
            runtimeLevel = RuntimeHealthLevel.READY,
        )

        assertEquals(0, state.totalCount)
        assertFalse(state.hasAttention)
        assertFalse(state.runtimeNeedsAttention)
        assertEquals("System öffnen", systemActionDescription(state.totalCount))
    }

    @Test
    fun hiddenOwnerWorkIsCountedWithoutTreatingVerificationAsFailure() {
        val state = OwnerAttentionProjector.projectCounts(
            pendingAssetReviews = 2,
            toolActions = 3,
            runtimeLevel = RuntimeHealthLevel.VERIFYING,
        )

        assertEquals(5, state.totalCount)
        assertTrue(state.hasAttention)
        assertFalse(state.runtimeNeedsAttention)
        assertEquals(
            "System öffnen, 5 Punkte brauchen dich",
            systemActionDescription(state.totalCount),
        )
    }

    @Test
    fun degradedOrFailedRuntimeAddsOneAttentionPointAndBadgeCapsAtNinePlus() {
        val degraded = OwnerAttentionProjector.projectCounts(
            pendingAssetReviews = 1,
            toolActions = 1,
            runtimeLevel = RuntimeHealthLevel.DEGRADED,
        )
        val failed = OwnerAttentionProjector.projectCounts(
            pendingAssetReviews = 9,
            toolActions = 1,
            runtimeLevel = RuntimeHealthLevel.FAILED,
        )

        assertEquals(3, degraded.totalCount)
        assertTrue(degraded.runtimeNeedsAttention)
        assertEquals(11, failed.totalCount)
        assertTrue(failed.runtimeNeedsAttention)
        assertEquals("9+", attentionBadgeLabel(failed.totalCount))
    }
}
