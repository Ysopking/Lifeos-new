package app.lifeos.next.kernel

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.runtime.android.RelevantAppSurfaceProfile
import app.lifeos.core.runtime.android.SemanticAccessibilityBoundary
import app.lifeos.core.runtime.android.SemanticUiNodeSnapshot
import app.lifeos.core.runtime.android.SemanticUiRole
import app.lifeos.core.runtime.android.SemanticUiSnapshot
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.SensorAttentionMode
import app.lifeos.core.runtime.world.SensorStateDimensionSelector
import app.lifeos.core.runtime.world.SensorStateDimensionSelectorType
import app.lifeos.core.runtime.world.StateDimensionId
import app.lifeos.core.runtime.world.WorldGap
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals(emptyList(), committed)

        bridge.registerRelevantSurface(
            RelevantAppSurfaceProfile(
                packageName = "example.app",
                surfaceKey = "semantic-ui",
                stateDimensions = listOf(
                    SensorStateDimensionSelector(
                        SensorStateDimensionSelectorType.PREFIX,
                        "app.ui.",
                    )
                ),
                sourceFingerprint = "b".repeat(64),
            )
        )
        val plan = bridge.updateWorldGaps(
            listOf(
                WorldGap.Perception(
                    domain = FieldDomainId("app"),
                    missingDimensions = setOf(StateDimensionId("app.ui.current")),
                    reason = "ui-state-missing",
                )
            )
        )
        assertEquals(listOf("example.app"), plan.focusedPackages)
        assertTrue(bridge.shouldObserve("example.app"))
        assertFalse(bridge.shouldObserve("other.app"))

        bridge.ingest(observation)

        assertEquals(listOf(0L to 1L), revisions)
        assertEquals(listOf(observation), committed)
        assertNull(committed.single().observationGrantId)
    }
}
