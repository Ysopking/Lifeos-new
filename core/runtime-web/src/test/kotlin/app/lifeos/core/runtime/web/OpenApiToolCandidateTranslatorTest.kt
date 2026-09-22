package app.lifeos.core.runtime.web

import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.ToolPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenApiToolCandidateTranslatorTest {
    private val requirement = CapabilityRequirement(
        capabilityId = CapabilityId("calendar.sync"),
        severity = GapSeverity.BLOCKING,
        requiredInputs = setOf("calendar event"),
        requiredOutputs = setOf("sync receipt"),
    )

    @Test
    fun exact_openapi_discovery_translates_to_non_activating_candidate() {
        val discovery = discovery()
        val document = document(
            discovery,
            listOf(
                operation(
                    OpenApiHttpMethod.POST,
                    "/v1/calendar/events/sync",
                    "syncCalendarEvent",
                    request = listOf("calendar event"),
                    response = listOf("sync receipt"),
                )
            ),
        )

        val candidate = requireNotNull(
            OpenApiToolCandidateTranslator().translate(requirement, discovery, document)
        )

        assertEquals(setOf(ToolPermission.NETWORK_ACCESS), candidate.requestedPermissions)
        assertTrue(candidate.requiresOwnerPolicy)
        assertTrue(candidate.unresolvedAuthentication)
        assertFalse(candidate.permissionAuthority)
        assertFalse(candidate.authenticationAuthority)
        assertFalse(candidate.activationAuthority)
        assertFalse(candidate.providerAuthority)
        assertFalse(candidate.executionAuthority)
        assertFalse(candidate.trustAuthority)
    }

    @Test
    fun resource_substitution_fails_closed() {
        val discovery = discovery()
        val other = discovery("https://api.example.com/other-openapi.json")
        val substituted = document(
            other,
            listOf(operation(OpenApiHttpMethod.GET, "/v1/calendar/sync", "syncCalendar")),
        )

        assertFailsWith<IllegalArgumentException> {
            OpenApiToolCandidateTranslator().translate(requirement, discovery, substituted)
        }
    }

    @Test
    fun non_openapi_b412_signal_is_rejected() {
        val openApi = discovery()
        val endpoint = endpointDiscovery()

        assertFailsWith<IllegalArgumentException> {
            OpenApiToolCandidateTranslator().translate(
                requirement,
                endpoint,
                document(
                    openApi,
                    listOf(operation(OpenApiHttpMethod.GET, "/calendar", "calendarSync")),
                ),
            )
        }
    }

    @Test
    fun unrelated_operations_do_not_become_tool_candidate() {
        val discovery = discovery()
        val document = document(
            discovery,
            listOf(
                operation(
                    OpenApiHttpMethod.GET,
                    "/v1/weather/forecast",
                    "getWeatherForecast",
                    response = listOf("weather forecast"),
                )
            ),
        )

        assertNull(OpenApiToolCandidateTranslator().translate(requirement, discovery, document))
    }

    @Test
    fun operation_order_is_deterministic_and_duplicates_are_idempotent() {
        val discovery = discovery()
        val one = operation(
            OpenApiHttpMethod.GET,
            "/v1/calendar/events",
            "listCalendarEvents",
            response = listOf("calendar event"),
        )
        val two = operation(
            OpenApiHttpMethod.POST,
            "/v1/calendar/sync",
            "syncCalendar",
            request = listOf("calendar event"),
            response = listOf("sync receipt"),
        )
        val left = document(discovery, listOf(two, one, two))
        val right = document(discovery, listOf(one, two))

        assertEquals(left, right)
        val translator = OpenApiToolCandidateTranslator()
        assertEquals(
            translator.translate(requirement, discovery, left),
            translator.translate(requirement, discovery, right),
        )
    }

    @Test
    fun authentication_stays_unresolved_and_never_becomes_authority() {
        val discovery = discovery()
        val document = document(
            discovery,
            listOf(
                operation(
                    OpenApiHttpMethod.GET,
                    "/v1/calendar/events",
                    "listCalendarEvents",
                    auth = OpenApiAuthenticationMode.REQUIRED_UNRESOLVED,
                )
            ),
        )

        val candidate = requireNotNull(
            OpenApiToolCandidateTranslator().translate(requirement, discovery, document)
        )

        assertTrue(candidate.unresolvedAuthentication)
        assertFalse(candidate.authenticationAuthority)
        assertFalse(candidate.permissionAuthority)
    }

    private fun discovery(
        url: String = "https://api.example.com/openapi.json",
    ): WebApiCapabilityCandidate {
        val source = WebResourceIdentity.parse(url)
        return WebApiCapabilityDiscoveryEngine().discover(
            requirement,
            listOf(
                WebApiDiscoveryEvidence.create(
                    source = source,
                    acquisitionReceiptFingerprint = "a".repeat(64),
                    payloadSha256 = "b".repeat(64),
                    contentType = "application/json",
                    publicText = """{"openapi":"3.1.0","info":{"title":"calendar sync api"}}""",
                )
            ),
        ).candidates.single { it.signal == WebApiDiscoverySignal.OPENAPI_DOCUMENT }
    }

    private fun endpointDiscovery(): WebApiCapabilityCandidate =
        WebApiCapabilityDiscoveryEngine().discover(
            requirement,
            listOf(
                WebApiDiscoveryEvidence.create(
                    source = WebResourceIdentity.parse("https://docs.example.com/reference"),
                    acquisitionReceiptFingerprint = "c".repeat(64),
                    payloadSha256 = "d".repeat(64),
                    contentType = "text/html",
                    publicText =
                        "calendar sync api reference https://api.example.com/api/v1/calendar/sync",
                )
            ),
        ).candidates.single { it.signal == WebApiDiscoverySignal.ENDPOINT_REFERENCE }

    private fun document(
        discovery: WebApiCapabilityCandidate,
        operations: Collection<OpenApiOperationDescriptor>,
    ): NormalizedOpenApiDocument =
        NormalizedOpenApiDocument.create(
            resource = discovery.resource,
            discoveryCandidateFingerprint = discovery.fingerprint,
            specPayloadSha256 = "e".repeat(64),
            openApiVersion = "3.1.0",
            operations = operations,
        )

    private fun operation(
        method: OpenApiHttpMethod,
        path: String,
        operationId: String,
        request: List<String> = emptyList(),
        response: List<String> = emptyList(),
        auth: OpenApiAuthenticationMode = OpenApiAuthenticationMode.REQUIRED_UNRESOLVED,
    ): OpenApiOperationDescriptor =
        OpenApiOperationDescriptor.create(
            method = method,
            path = path,
            operationId = operationId,
            requestContracts = request,
            responseContracts = response,
            authenticationMode = auth,
        )
}
