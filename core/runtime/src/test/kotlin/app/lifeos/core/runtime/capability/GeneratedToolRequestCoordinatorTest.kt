package app.lifeos.core.runtime.capability

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GeneratedToolRequestCoordinatorTest {
    private val requestedAt = Instant.parse("2026-09-10T20:00:00Z")

    @Test
    fun `request is non activating and exactly matches its capability gap`() {
        val gap = gap()
        val request = request(gap)

        assertFalse(request.activationAllowed)
        assertTrue(request.matches(gap))
        assertFalse(
            request.matches(
                gap.copy(
                    requirement = gap.requirement.copy(requiredOutputs = setOf("other-output")),
                )
            )
        )
    }

    @Test
    fun `approval cannot predate request and remains non activating`() {
        val request = request(gap())

        assertFailsWith<IllegalArgumentException> {
            ToolGenerationApproval.issueForExplicitUserAction(
                request = request,
                approverId = "user",
                approvedAt = requestedAt.minusSeconds(1),
            )
        }

        val approval = approval(request)
        assertFalse(approval.activationAllowed)
        assertTrue(approval.matches(request))
    }

    @Test
    fun `changed gap blocks before genesis`() = runTest {
        var genesisCalls = 0
        val gap = gap()
        val request = request(gap)
        val coordinator = GeneratedToolRequestCoordinator { generatedGap ->
            genesisCalls += 1
            rejectedGenesis(generatedGap)
        }

        val changed = gap.copy(
            requirement = gap.requirement.copy(requiredInputs = setOf("different-input")),
        )
        val result = coordinator.generateApproved(request, approval(request), changed)

        assertEquals(
            GeneratedToolRequestExecutionResult.Blocked(request.id, "request-gap-mismatch"),
            result,
        )
        assertEquals(0, genesisCalls)
    }

    @Test
    fun `approval for another request blocks before genesis`() = runTest {
        var genesisCalls = 0
        val gap = gap()
        val request = request(gap)
        val otherRequest = GeneratedToolRequest.fromGap(
            gap = gap,
            requestPhotonId = PhotonId("request-photon-other"),
            requestedBy = "user",
            requestedAt = requestedAt,
        )
        val coordinator = GeneratedToolRequestCoordinator { generatedGap ->
            genesisCalls += 1
            rejectedGenesis(generatedGap)
        }

        val result = coordinator.generateApproved(request, approval(otherRequest), gap)

        assertEquals(
            GeneratedToolRequestExecutionResult.Blocked(request.id, "approval-request-mismatch"),
            result,
        )
        assertEquals(0, genesisCalls)
    }

    @Test
    fun `same approved request is process idempotent`() = runTest {
        var genesisCalls = 0
        val gap = gap()
        val request = request(gap)
        val approval = approval(request)
        val coordinator = GeneratedToolRequestCoordinator { generatedGap ->
            genesisCalls += 1
            rejectedGenesis(generatedGap)
        }

        val first = coordinator.generateApproved(request, approval, gap)
        val second = coordinator.generateApproved(request, approval, gap)

        assertTrue(first is GeneratedToolRequestExecutionResult.Completed)
        assertFalse(first.duplicate)
        assertTrue(second is GeneratedToolRequestExecutionResult.Completed)
        assertTrue(second.duplicate)
        assertEquals(first.genesis, second.genesis)
        assertEquals(1, genesisCalls)
    }

    private fun gap() = CapabilityGap(
        requirement = CapabilityRequirement(
            capabilityId = CapabilityId("example.missing"),
            severity = GapSeverity.BLOCKING,
            requiredInputs = setOf("goal-photon"),
            requiredOutputs = setOf("answer-photon"),
        ),
        type = CapabilityGapType.CAPABILITY_MISSING,
        candidateProviderIds = listOf("candidate-a"),
    )

    private fun request(gap: CapabilityGap) = GeneratedToolRequest.fromGap(
        gap = gap,
        requestPhotonId = PhotonId("request-photon"),
        requestedBy = "user",
        requestedAt = requestedAt,
    )

    private fun approval(request: GeneratedToolRequest) =
        ToolGenerationApproval.issueForExplicitUserAction(
            request = request,
            approverId = "user",
            approvedAt = requestedAt.plusSeconds(1),
        )

    private fun rejectedGenesis(gap: CapabilityGap): GeneratedToolGenesisResult {
        val record = GeneratedToolRecord(
            manifest = GeneratedToolManifest(
                toolId = "generated-test",
                sourceCapability = gap.requirement.capabilityId,
                sourceHash = "source-hash",
                buildHash = null,
                permissions = emptySet(),
                generatedAt = requestedAt,
                requiredInputs = gap.requirement.requiredInputs,
                requiredOutputs = gap.requirement.requiredOutputs,
            ),
            state = GeneratedToolState.REJECTED,
            lastMessage = "test-rejected",
        )
        return GeneratedToolGenesisResult.Rejected(record, listOf("test-rejected"))
    }
}
