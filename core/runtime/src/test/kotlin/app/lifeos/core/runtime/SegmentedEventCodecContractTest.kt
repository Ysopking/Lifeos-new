package app.lifeos.core.runtime

import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.HotSwapEvent
import app.lifeos.core.runtime.capability.HotSwapEventLogCodec
import app.lifeos.core.runtime.capability.HotSwapEventType
import app.lifeos.core.runtime.capability.HotSwapTransactionId
import app.lifeos.core.runtime.capability.ToolWorkshopJobDefinition
import app.lifeos.core.runtime.capability.ToolWorkshopJobEvent
import app.lifeos.core.runtime.capability.ToolWorkshopJobEventLogCodec
import app.lifeos.core.runtime.capability.ToolWorkshopJobId
import app.lifeos.core.runtime.capability.ToolWorkshopJobState
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionEvent
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionEventLogCodec
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionEventType
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionId
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.SelfHealingEvent
import app.lifeos.core.runtime.health.SelfHealingEventLogCodec
import app.lifeos.core.runtime.health.SelfHealingEventType
import app.lifeos.core.runtime.health.SelfHealingIncidentId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SegmentedEventCodecContractTest {
    private val at = Instant.parse("2026-09-18T15:10:00Z")

    @Test
    fun hotSwapSegmentPreservesNonInitialRevision() {
        val capability = CapabilityId("segment.hot-swap")
        val transaction = HotSwapTransactionId.create(
            capability, "old", "new", "old-evidence", "new-evidence"
        )
        val event = HotSwapEvent(
            revision = 7,
            transactionId = transaction,
            capabilityId = capability,
            previousToolId = "old",
            candidateToolId = "new",
            previousPromotionEvidenceId = "old-evidence",
            candidatePromotionEvidenceId = "new-evidence",
            type = HotSwapEventType.PREPARED,
            recordedAt = at,
        )
        val bytes = HotSwapEventLogCodec.encodeSegment(event)
        assertEquals(event, HotSwapEventLogCodec.decodeSegment(bytes))
        assertFailsWith<IllegalArgumentException> { HotSwapEventLogCodec.decode(bytes) }
    }

    @Test
    fun toolWorkshopSegmentPreservesNonInitialRevision() {
        val capability = CapabilityId("segment.tool")
        val id = ToolWorkshopJobId.create(
            sourcePhotonId = "source-photon",
            sourceRevision = 1,
            capabilityId = capability,
            severity = GapSeverity.BLOCKING,
            gapType = CapabilityGapType.CAPABILITY_MISSING,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("result"),
            candidateProviderIds = emptyList(),
            policyVersion = "policy-v1",
            workshopVersion = "workshop-v1",
        )
        val definition = ToolWorkshopJobDefinition(
            id = id,
            sourceRequestId = "request-1",
            sourcePhotonId = "source-photon",
            sourceRevision = 1,
            capabilityId = capability,
            severity = GapSeverity.BLOCKING,
            gapType = CapabilityGapType.CAPABILITY_MISSING,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("result"),
            candidateProviderIds = emptyList(),
            policyVersion = "policy-v1",
            workshopVersion = "workshop-v1",
            createdAt = at,
        )
        val event = ToolWorkshopJobEvent(
            revision = 7,
            definition = definition,
            state = ToolWorkshopJobState.REQUESTED,
            recordedAt = at,
        )
        val bytes = ToolWorkshopJobEventLogCodec.encodeSegment(event)
        assertEquals(event, ToolWorkshopJobEventLogCodec.decodeSegment(bytes))
        assertFailsWith<IllegalArgumentException> { ToolWorkshopJobEventLogCodec.decode(bytes) }
    }

    @Test
    fun deepSearchSegmentPreservesNonInitialRevision() {
        val event = DeepSearchMissionEvent(
            revision = 7,
            missionId = DeepSearchMissionId(DeepSearchMissionId.PREFIX + "0".repeat(64)),
            type = DeepSearchMissionEventType.EXPLORATION_STARTED,
            recordedAt = at,
        )
        val bytes = DeepSearchMissionEventLogCodec.encodeSegment(event)
        assertEquals(event, DeepSearchMissionEventLogCodec.decodeSegment(bytes))
        assertFailsWith<IllegalArgumentException> { DeepSearchMissionEventLogCodec.decode(bytes) }
    }

    @Test
    fun selfHealingSegmentPreservesNonInitialRevision() {
        val node = HealthNodeId("segment-node")
        val incident = SelfHealingIncidentId.create(
            node, "incident-fingerprint", "plan-fingerprint"
        )
        val event = SelfHealingEvent(
            revision = 7,
            incidentId = incident,
            nodeId = node,
            planFingerprint = "plan-fingerprint",
            type = SelfHealingEventType.OPENED,
            recordedAt = at,
        )
        val bytes = SelfHealingEventLogCodec.encodeSegment(event)
        assertEquals(event, SelfHealingEventLogCodec.decodeSegment(bytes))
        assertFailsWith<IllegalArgumentException> { SelfHealingEventLogCodec.decode(bytes) }
    }
}
