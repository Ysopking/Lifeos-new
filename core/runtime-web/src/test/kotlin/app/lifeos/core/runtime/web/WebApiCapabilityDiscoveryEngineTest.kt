package app.lifeos.core.runtime.web

import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GapSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebApiCapabilityDiscoveryEngineTest {
    private val requirement = CapabilityRequirement(
        capabilityId = CapabilityId("calendar.sync"),
        severity = GapSeverity.BLOCKING,
        requiredInputs = setOf("calendar event"),
        requiredOutputs = setOf("sync receipt"),
    )

    @Test
    fun openapi_document_is_discovered_but_never_becomes_authority() {
        val evidence = evidence(
            "https://api.example.com/docs",
            """{"openapi":"3.1.0","info":{"title":"Calendar API"},"paths":{}}""",
            "application/json",
        )

        val report = WebApiCapabilityDiscoveryEngine().discover(
            requirement,
            listOf(evidence),
        )

        val candidate = report.candidates.single()
        assertEquals(WebApiDiscoverySignal.OPENAPI_DOCUMENT, candidate.signal)
        assertEquals(evidence.source, candidate.resource)
        assertFalse(candidate.providerAuthority)
        assertFalse(candidate.toolCandidateAuthority)
        assertFalse(candidate.activationAuthority)
        assertFalse(candidate.trustAuthority)
        assertFalse(candidate.executionAuthority)
        assertFalse(report.networkAuthority)
    }

    @Test
    fun embedded_https_api_links_are_canonicalized_and_deduplicated() {
        val evidence = evidence(
            "https://docs.example.com/calendar",
            """
            Calendar sync API reference:
            https://API.example.com:443/v1/calendar/events
            https://api.example.com/v1/calendar/events
            """.trimIndent(),
            "text/html",
        )

        val report = WebApiCapabilityDiscoveryEngine().discover(
            requirement,
            listOf(evidence),
        )

        val endpoint = report.candidates.single {
            it.signal == WebApiDiscoverySignal.ENDPOINT_REFERENCE
        }
        assertEquals(
            "https://api.example.com/v1/calendar/events",
            endpoint.resource.canonicalUrl,
        )
    }

    @Test
    fun plain_unrelated_web_text_does_not_create_api_candidate() {
        val report = WebApiCapabilityDiscoveryEngine().discover(
            requirement,
            listOf(
                evidence(
                    "https://example.com/article",
                    "A plain article about gardening without software interfaces.",
                    "text/html",
                )
            ),
        )

        assertTrue(report.candidates.isEmpty())
    }

    @Test
    fun evidence_input_order_and_exact_duplicates_do_not_change_report_identity() {
        val first = evidence(
            "https://example.com/openapi.json",
            """{"openapi":"3.0.0","info":{"title":"Calendar"}}""",
            "application/json",
            seed = 'a',
        )
        val second = evidence(
            "https://example.com/developer",
            "Calendar API reference https://example.com/api/v1/calendar",
            "text/html",
            seed = 'b',
        )
        val engine = WebApiCapabilityDiscoveryEngine()

        val left = engine.discover(requirement, listOf(first, second, first))
        val right = engine.discover(requirement, listOf(second, first))

        assertEquals(left, right)
        assertEquals(2, left.evidenceFingerprints.size)
    }

    @Test
    fun non_https_links_are_not_discovered_as_resources() {
        val report = WebApiCapabilityDiscoveryEngine().discover(
            requirement,
            listOf(
                evidence(
                    "https://docs.example.com/api",
                    "Calendar API reference http://insecure.example.com/v1/calendar",
                    "text/html",
                )
            ),
        )

        assertTrue(
            report.candidates.none {
                it.resource.canonicalUrl.startsWith("http://")
            }
        )
    }

    private fun evidence(
        url: String,
        text: String,
        contentType: String,
        seed: Char = 'a',
    ): WebApiDiscoveryEvidence =
        WebApiDiscoveryEvidence.create(
            source = WebResourceIdentity.parse(url),
            acquisitionReceiptFingerprint = seed.toString().repeat(64),
            payloadSha256 = seed.lowercaseChar().toString().repeat(64),
            contentType = contentType,
            publicText = text,
        )
}
