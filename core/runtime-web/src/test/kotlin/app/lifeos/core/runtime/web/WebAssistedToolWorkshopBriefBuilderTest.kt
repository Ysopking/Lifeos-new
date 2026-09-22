package app.lifeos.core.runtime.web

import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.ToolPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebAssistedToolWorkshopBriefBuilderTest {
    @Test
    fun brief_preserves_exact_candidate_lineage_without_authority() {
        val requirement = requirement()
        val candidate = candidate(requirement)
        val documentation = listOf(
            WebToolDocumentationEvidence.create(
                source = WebResourceIdentity.parse("https://docs.example.com/calendar"),
                acquisitionReceiptFingerprint = "c".repeat(64),
                payloadSha256 = "d".repeat(64),
                excerpt = "Create calendar events with POST /v1/events.",
            )
        )

        val brief = WebAssistedToolWorkshopBriefBuilder().build(
            requirement,
            candidate,
            documentation,
        )

        assertEquals(candidate.fingerprint, brief.toolCandidateFingerprint)
        assertEquals(candidate.specPayloadSha256, brief.specPayloadSha256)
        assertEquals(setOf(ToolPermission.NETWORK_ACCESS), brief.requestedPermissions)
        assertTrue("no-owner-policy-bypass" in brief.implementationConstraints)
        assertTrue("sandbox-build-required" in brief.implementationConstraints)
        assertFalse(brief.generationAuthority)
        assertFalse(brief.credentialAuthority)
        assertFalse(brief.permissionAuthority)
        assertFalse(brief.providerAuthority)
        assertFalse(brief.activationAuthority)
        assertFalse(brief.executionAuthority)
        assertFalse(brief.truthAuthority)
    }

    @Test
    fun documentation_input_order_and_duplicates_are_deterministic() {
        val requirement = requirement()
        val candidate = candidate(requirement)
        val first = document("https://docs.example.com/a", 'e', "Example A")
        val second = document("https://docs.example.com/b", 'f', "Example B")
        val builder = WebAssistedToolWorkshopBriefBuilder()

        val left = builder.build(requirement, candidate, listOf(second, first, first))
        val right = builder.build(requirement, candidate, listOf(first, second))

        assertEquals(left, right)
        assertEquals(2, left.documentationEvidence.size)
    }

    @Test
    fun unresolved_authentication_is_carried_as_constraint_not_permission() {
        val requirement = requirement()
        val candidate = candidate(requirement)

        val brief = WebAssistedToolWorkshopBriefBuilder().build(
            requirement,
            candidate,
            emptyList(),
        )

        assertTrue(brief.unresolvedAuthentication)
        assertTrue("authentication-unresolved" in brief.implementationConstraints)
        assertTrue("no-credential-inference" in brief.implementationConstraints)
        assertFalse(brief.credentialAuthority)
        assertFalse(brief.permissionAuthority)
    }

    private fun requirement(): CapabilityRequirement =
        CapabilityRequirement(
            capabilityId = CapabilityId("calendar.sync"),
            severity = GapSeverity.BLOCKING,
            requiredInputs = setOf("calendar event"),
            requiredOutputs = setOf("sync receipt"),
        )

    private fun candidate(requirement: CapabilityRequirement): OpenApiToolCandidate {
        val discoveryEvidence = WebApiDiscoveryEvidence.create(
            source = WebResourceIdentity.parse("https://api.example.com/openapi.json"),
            acquisitionReceiptFingerprint = "a".repeat(64),
            payloadSha256 = "b".repeat(64),
            contentType = "application/json",
            publicText = """{"openapi":"3.1.0","calendar":true}""",
        )
        val discovery = WebApiCapabilityDiscoveryEngine()
            .discover(requirement, listOf(discoveryEvidence))
            .candidates
            .single()
        val operation = OpenApiOperationDescriptor.create(
            method = OpenApiHttpMethod.POST,
            path = "/v1/calendar/events",
            operationId = "createCalendarEvent",
            requestContracts = listOf("calendar event"),
            responseContracts = listOf("sync receipt"),
            authenticationMode = OpenApiAuthenticationMode.REQUIRED_UNRESOLVED,
        )
        val normalized = NormalizedOpenApiDocument.create(
            resource = discovery.resource,
            discoveryCandidateFingerprint = discovery.fingerprint,
            specPayloadSha256 = "9".repeat(64),
            openApiVersion = "3.1.0",
            operations = listOf(operation),
        )
        return requireNotNull(
            OpenApiToolCandidateTranslator().translate(
                requirement,
                discovery,
                normalized,
            )
        )
    }

    private fun document(
        url: String,
        seed: Char,
        excerpt: String,
    ): WebToolDocumentationEvidence =
        WebToolDocumentationEvidence.create(
            source = WebResourceIdentity.parse(url),
            acquisitionReceiptFingerprint = seed.toString().repeat(64),
            payloadSha256 = seed.toString().repeat(64),
            excerpt = excerpt,
        )
}
