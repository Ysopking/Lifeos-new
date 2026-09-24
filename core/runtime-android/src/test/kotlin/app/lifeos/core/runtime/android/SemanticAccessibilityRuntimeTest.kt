package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.life.ObservationAuthorityClass
import app.lifeos.core.runtime.life.RepresentationLevel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemanticAccessibilityRuntimeTest {
    private val boundary = SemanticAccessibilityBoundary()
    private val snapshot = SemanticUiSnapshot.create(
        packageName = "example.app",
        windowRevision = "42",
        capturedAt = Instant.parse("2026-09-24T12:00:00Z"),
        nodes = listOf(
            SemanticUiNodeSnapshot(
                nodeKey = "compose",
                role = SemanticUiRole.TEXT_FIELD,
                labelFingerprint = "a".repeat(64),
                valueFingerprint = "b".repeat(64),
                enabled = true,
                visible = true,
                supportedActions = setOf(
                    SemanticUiActionKind.ACTIVATE,
                    SemanticUiActionKind.SET_TEXT,
                ),
            )
        ),
    )

    @Test
    fun semanticUiObservationRemainsProjectedAndLowAuthority() {
        val observation = boundary.observe(
            snapshot,
            "owner-observation-grant:" + "c".repeat(64),
        )

        assertEquals(RepresentationLevel.PROJECTED, observation.realization.representation)
        assertEquals(ObservationAuthorityClass.UI_OBSERVATION, observation.authority)
        assertTrue("untrusted-ui-projection" in observation.tags)
    }

    @Test
    fun actionCandidateCarriesNoExecutionPolicyOrCoordinateAuthority() {
        val candidate = boundary.actionCandidate(
            snapshot = snapshot,
            targetNodeKey = "compose",
            action = SemanticUiActionKind.SET_TEXT,
            payloadFingerprint = "d".repeat(64),
        )

        assertFalse(candidate.executionAuthority)
        assertFalse(candidate.ownerPolicyAuthority)
        assertFalse(candidate.coordinateFallbackAllowed)
        assertEquals(snapshot.fingerprint, candidate.preconditionSnapshotFingerprint)
    }

    @Test
    fun missingOrUnsupportedSemanticTargetFailsClosed() {
        assertFailsWith<IllegalArgumentException> {
            boundary.actionCandidate(
                snapshot = snapshot,
                targetNodeKey = "missing",
                action = SemanticUiActionKind.ACTIVATE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            boundary.actionCandidate(
                snapshot = snapshot,
                targetNodeKey = "compose",
                action = SemanticUiActionKind.SCROLL_FORWARD,
            )
        }
    }
}
