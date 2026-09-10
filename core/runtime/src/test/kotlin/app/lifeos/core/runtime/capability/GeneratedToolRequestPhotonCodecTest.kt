package app.lifeos.core.runtime.capability

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GeneratedToolRequestPhotonCodecTest {
    private val t0 = Instant.parse("2026-09-10T20:00:00Z")
    private val gap = CapabilityGap(
        requirement = CapabilityRequirement(
            capabilityId = CapabilityId("text.normalize"),
            severity = GapSeverity.BLOCKING,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
        ),
        type = CapabilityGapType.CAPABILITY_MISSING,
        candidateProviderIds = listOf("candidate-a"),
    )

    @Test
    fun `request photon round trips exact gap and is non activating`() {
        val (photon, request) = GeneratedToolRequestPhotonCodec.createRequestPhoton(
            gap = gap,
            createdAt = t0,
            id = PhotonId("request-photon"),
        )

        val decoded = GeneratedToolRequestPhotonCodec.decodeRequest(photon)

        assertEquals(request, decoded)
        assertEquals(gap, GeneratedToolRequestPhotonCodec.toGap(decoded))
        assertFalse(decoded.activationAllowed)
    }

    @Test
    fun `approval photon binds exact request parent and fingerprint`() {
        val (_, request) = GeneratedToolRequestPhotonCodec.createRequestPhoton(
            gap = gap,
            createdAt = t0,
            id = PhotonId("request-photon"),
        )
        val (approvalPhoton, approval) = GeneratedToolRequestPhotonCodec.createApprovalPhoton(
            request = request,
            approvedAt = t0.plusSeconds(5),
            id = PhotonId("approval-photon"),
        )

        val decoded = GeneratedToolRequestPhotonCodec.decodeApproval(approvalPhoton, request)

        assertEquals(approval.id, decoded.id)
        assertEquals(request.id, decoded.requestId)
        assertFalse(decoded.activationAllowed)
    }

    @Test
    fun `ordinary request tag cannot be decoded as approval`() {
        val (requestPhoton, request) = GeneratedToolRequestPhotonCodec.createRequestPhoton(
            gap = gap,
            createdAt = t0,
            id = PhotonId("request-photon"),
        )
        val forged = requestPhoton.copy(tags = requestPhoton.tags + "user-approved")

        assertFailsWith<IllegalArgumentException> {
            GeneratedToolRequestPhotonCodec.decodeApproval(forged, request)
        }
    }

    @Test
    fun `changed approval payload is rejected`() {
        val (_, request) = GeneratedToolRequestPhotonCodec.createRequestPhoton(
            gap = gap,
            createdAt = t0,
            id = PhotonId("request-photon"),
        )
        val (approvalPhoton, _) = GeneratedToolRequestPhotonCodec.createApprovalPhoton(
            request = request,
            approvedAt = t0.plusSeconds(5),
            id = PhotonId("approval-photon"),
        )
        val forged = approvalPhoton.copy(
            content = approvalPhoton.content.replace(
                "capability=text.normalize",
                "capability=text.other",
            )
        )

        assertFailsWith<IllegalArgumentException> {
            GeneratedToolRequestPhotonCodec.decodeApproval(forged, request)
        }
    }

    @Test
    fun `request with foreign field is rejected`() {
        val (requestPhoton, _) = GeneratedToolRequestPhotonCodec.createRequestPhoton(
            gap = gap,
            createdAt = t0,
            id = PhotonId("request-photon"),
        )
        val forged = requestPhoton.copy(content = requestPhoton.content + "\napprovalId=foreign")

        assertFailsWith<IllegalArgumentException> {
            GeneratedToolRequestPhotonCodec.decodeRequest(forged)
        }
    }
}
