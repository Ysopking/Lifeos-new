package app.lifeos.core.runtime.capability

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GeneratedToolUserActionCoordinatorTest {
    private val requestedAt = Instant.parse("2026-09-10T22:30:00Z")

    @Test
    fun `explicit action persists exact request and approval before genesis`() = runTest {
        val stored = linkedMapOf<PhotonId, Photon>()
        val persistedMimes = mutableListOf<String>()
        var genesisCalls = 0
        val gap = gap()
        val coordinator = GeneratedToolUserActionCoordinator(
            requests = GeneratedToolRequestCoordinator { generatedGap ->
                genesisCalls += 1
                rejectedGenesis(generatedGap)
            },
            persist = { photon ->
                persistedMimes += photon.mimeType
                stored[photon.id] = photon
            },
            load = stored::get,
            now = sequenceClock(requestedAt, requestedAt.plusSeconds(1)),
        )

        val result = coordinator.generateExplicitlyApproved(gap)

        assertEquals(
            listOf(
                GeneratedToolRequestPhotonCodec.REQUEST_MIME,
                GeneratedToolRequestPhotonCodec.APPROVAL_MIME,
            ),
            persistedMimes,
        )
        assertEquals(1, genesisCalls)
        val execution = result.execution as GeneratedToolRequestExecutionResult.Completed
        assertFalse(execution.duplicate)

        val request = GeneratedToolRequestPhotonCodec.decodeRequest(result.requestPhoton)
        val approval = GeneratedToolRequestPhotonCodec.decodeApproval(result.approvalPhoton, request)
        assertTrue(request.matches(gap))
        assertTrue(approval.matches(request))
        assertFalse(request.activationAllowed)
        assertFalse(approval.activationAllowed)
        assertEquals(setOf("capability-gap", "tool-request", "user-requested"), result.requestPhoton.tags)
        assertEquals(setOf("tool-generation-approval", "user-approved"), result.approvalPhoton.tags)
    }

    @Test
    fun `approval persistence failure blocks genesis`() = runTest {
        val stored = linkedMapOf<PhotonId, Photon>()
        var genesisCalls = 0
        val coordinator = GeneratedToolUserActionCoordinator(
            requests = GeneratedToolRequestCoordinator { generatedGap ->
                genesisCalls += 1
                rejectedGenesis(generatedGap)
            },
            persist = { photon ->
                if (photon.mimeType == GeneratedToolRequestPhotonCodec.APPROVAL_MIME) {
                    error("approval-write-failed")
                }
                stored[photon.id] = photon
            },
            load = stored::get,
            now = sequenceClock(requestedAt, requestedAt.plusSeconds(1)),
        )

        val error = assertFailsWith<IllegalStateException> {
            coordinator.generateExplicitlyApproved(gap())
        }

        assertEquals("approval-write-failed", error.message)
        assertEquals(0, genesisCalls)
        assertEquals(1, stored.size)
    }

    @Test
    fun `mutated request read back blocks genesis`() = runTest {
        val stored = linkedMapOf<PhotonId, Photon>()
        var genesisCalls = 0
        val coordinator = GeneratedToolUserActionCoordinator(
            requests = GeneratedToolRequestCoordinator { generatedGap ->
                genesisCalls += 1
                rejectedGenesis(generatedGap)
            },
            persist = { photon ->
                stored[photon.id] = if (photon.mimeType == GeneratedToolRequestPhotonCodec.REQUEST_MIME) {
                    photon.copy(tags = photon.tags + "tampered")
                } else {
                    photon
                }
            },
            load = stored::get,
            now = sequenceClock(requestedAt),
        )

        assertFailsWith<IllegalArgumentException> {
            coordinator.generateExplicitlyApproved(gap())
        }
        assertEquals(0, genesisCalls)
    }

    @Test
    fun `regressed approval clock is clamped to request time`() = runTest {
        val stored = linkedMapOf<PhotonId, Photon>()
        val coordinator = GeneratedToolUserActionCoordinator(
            requests = GeneratedToolRequestCoordinator { generatedGap -> rejectedGenesis(generatedGap) },
            persist = { photon -> stored[photon.id] = photon },
            load = stored::get,
            now = sequenceClock(requestedAt, requestedAt.minusSeconds(5)),
        )

        val result = coordinator.generateExplicitlyApproved(gap())
        val request = GeneratedToolRequestPhotonCodec.decodeRequest(result.requestPhoton)
        val approval = GeneratedToolRequestPhotonCodec.decodeApproval(result.approvalPhoton, request)

        assertEquals(requestedAt, approval.approvedAt)
        assertTrue(approval.matches(request))
    }

    private fun sequenceClock(vararg values: Instant): () -> Instant {
        var index = 0
        return {
            values[index.coerceAtMost(values.lastIndex)].also { index += 1 }
        }
    }

    private fun gap() = CapabilityGap(
        requirement = CapabilityRequirement(
            capabilityId = CapabilityId("text.uppercase.local"),
            severity = GapSeverity.BLOCKING,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("text"),
        ),
        type = CapabilityGapType.CAPABILITY_MISSING,
        candidateProviderIds = emptyList(),
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
