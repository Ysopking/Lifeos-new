package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.android.SemanticAccessibilityBoundary
import app.lifeos.core.runtime.android.SemanticUiNodeSnapshot
import app.lifeos.core.runtime.android.SemanticUiRole
import app.lifeos.core.runtime.android.SemanticUiSnapshot
import app.lifeos.core.runtime.life.PerceptionFusionEngine
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthorizedObservationSemanticProjectionRuntimeTest {
    @Test
    fun projectionPersistsDerivedPhotonWithExactOriginLineage() = runTest {
        val observation = SemanticAccessibilityBoundary().project(
            SemanticUiSnapshot.create(
                packageName = "example.bank",
                windowRevision = "window-1",
                capturedAt = NOW,
                nodes = listOf(
                    SemanticUiNodeSnapshot(
                        nodeKey = "root",
                        role = SemanticUiRole.TEXT,
                        labelFingerprint = "a".repeat(64),
                        valueFingerprint = null,
                        enabled = true,
                        visible = true,
                        supportedActions = emptySet(),
                    )
                ),
            )
        ).authorizedBy("grant-1")

        val source = PerceptionFusionEngine()
            .fuse(listOf(observation.toPerceptionSignal()))
            .photons
            .single()
        val persisted = mutableListOf<Photon>()
        val runtime = AuthorizedObservationSemanticProjectionRuntime(
            persistDerived = { persisted += it },
        )

        val output = runtime.project(observation, source).single()

        assertEquals(listOf(output), persisted)
        assertEquals(setOf(source.id), output.provenance.parentIds)
        assertTrue("semantic-projection" in output.tags)
        assertTrue("domain:app" in output.tags)
        assertTrue(
            output.tags.any { it == "state-dimension:app.ui.example.bank.current" }
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T05:00:00Z")
    }
}
