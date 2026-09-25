package app.lifeos.next.kernel

import app.lifeos.core.runtime.android.SemanticAccessibilityBoundary
import app.lifeos.core.runtime.android.SemanticUiNodeSnapshot
import app.lifeos.core.runtime.android.SemanticUiRole
import app.lifeos.core.runtime.android.SemanticUiSnapshot
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.SensorAttentionMode
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidSemanticAppContentSensorBridgeTest {
    private val observation = SemanticAccessibilityBoundary().project(
        SemanticUiSnapshot.create(
            packageName = "example.app",
            windowRevision = "window-1",
            capturedAt = Instant.parse("2026-09-25T04:00:00Z"),
            nodes = listOf(
                SemanticUiNodeSnapshot(
                    nodeKey = "root/0",
                    role = SemanticUiRole.TEXT,
                    labelFingerprint = "a".repeat(64),
                    valueFingerprint = null,
                    enabled = true,
                    visible = true,
                    supportedActions = emptySet(),
                )
            ),
        )
    )

    @Test
    fun projectedContentCarriesNoOwnerGrantBeforeCanonicalIngress() {
        assertEquals(
            PrivateOwnerObservationPolicyBaseline.APP_CONTENT_SENSOR_ID,
            observation.sourceId,
        )
        assertNull(observation.observationGrantId)
    }

    @Test
    fun suspendedBridgeDoesNotCommitAndFocusedBridgeUsesCanonicalCursor() = runTest {
        val committed = mutableListOf<InformationObservation>()
        val revisions = mutableListOf<Pair<Long, Long>>()
        val bridge = AndroidSemanticAppContentSensorBridge(
            AppContentBatchCommitter { descriptor, cursor, _, batch ->
                assertEquals(
                    PrivateOwnerObservationPolicyBaseline.APP_CONTENT_SENSOR_ID,
                    descriptor.sensorId.value,
                )
                revisions += cursor.revision to batch.nextCursor.revision
                committed += batch.observations.single()
            }
        )

        bridge.ingest(observation)
        assertEquals(emptyList(), committed)

        bridge.applyAttention(SensorAttentionMode.FOCUSED)
        bridge.ingest(observation)

        assertEquals(listOf(0L to 1L), revisions)
        assertEquals(listOf(observation), committed)
        assertNull(committed.single().observationGrantId)
    }
}
