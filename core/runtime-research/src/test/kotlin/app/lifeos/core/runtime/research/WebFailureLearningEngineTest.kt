package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.web.WebAcquisitionRequest
import app.lifeos.core.runtime.web.WebAcquisitionRuntime
import app.lifeos.core.runtime.web.WebAcquisitionTransport
import app.lifeos.core.runtime.web.WebAcquisitionTransportResponse
import app.lifeos.core.runtime.web.WebResourceIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebFailureLearningEngineTest {
    @Test
    fun http_401_becomes_authentication_learning_candidate_without_credential_authority() = runTest {
        val result = acquire(
            "https://api.example.com/v1/items",
            WebAcquisitionTransportResponse(statusCode = 401),
        )

        val report = WebFailureLearningEngine().evaluate(
            WebFailureObservation(result.receipt, result.finalResource)
        )

        assertEquals(
            listOf(WebFailureKind.AUTHENTICATION_REQUIRED),
            report.candidates.map { it.kind },
        )
        assertFalse(report.candidates.single().credentialAuthority)
        assertFalse(report.automaticCredentialUseAllowed)
        assertFalse(report.automaticRetryAllowed)
    }

    @Test
    fun http_403_becomes_authorization_learning_candidate() = runTest {
        val result = acquire(
            "https://api.example.com/v1/items",
            WebAcquisitionTransportResponse(statusCode = 403),
        )

        val report = WebFailureLearningEngine().evaluate(
            WebFailureObservation(result.receipt, result.finalResource)
        )

        assertEquals(
            listOf(WebFailureKind.AUTHORIZATION_DENIED),
            report.candidates.map { it.kind },
        )
    }

    @Test
    fun redirect_to_login_becomes_login_flow_candidate_without_execution() = runTest {
        val start = WebResourceIdentity.parse("https://api.example.com/v1/items")
        val runtime = WebAcquisitionRuntime(
            WebAcquisitionTransport { request ->
                if (request.resource.id == start.id) {
                    WebAcquisitionTransportResponse(
                        statusCode = 302,
                        location = "/login",
                    )
                } else {
                    WebAcquisitionTransportResponse(statusCode = 200)
                }
            }
        )
        val result = runtime.acquire(WebAcquisitionRequest.create(start))

        val report = WebFailureLearningEngine().evaluate(
            WebFailureObservation(result.receipt, result.finalResource)
        )

        assertTrue(report.candidates.any { it.kind == WebFailureKind.LOGIN_REDIRECT })
        assertFalse(report.candidates.first().executionAuthority)
    }

    @Test
    fun explicit_schema_mismatch_becomes_next_cycle_candidate() = runTest {
        val result = acquire(
            "https://api.example.com/v1/items",
            WebAcquisitionTransportResponse(
                statusCode = 200,
                contentType = "application/json",
                body = "{}".toByteArray(),
            ),
        )

        val report = WebFailureLearningEngine().evaluate(
            observation = WebFailureObservation(
                receipt = result.receipt,
                finalResource = result.finalResource,
                observedResponseSchemaFingerprint = "b".repeat(64),
            ),
            expectation = WebFailureExpectation(
                expectedResponseSchemaFingerprint = "a".repeat(64),
            ),
        )

        assertTrue(report.candidates.any { it.kind == WebFailureKind.RESPONSE_SCHEMA_CHANGED })
        assertFalse(report.currentCycleMutationAllowed)
    }

    @Test
    fun successful_stable_response_does_not_create_failure_learning() = runTest {
        val resource = WebResourceIdentity.parse("https://api.example.com/v1/items")
        val result = acquire(
            resource.canonicalUrl,
            WebAcquisitionTransportResponse(statusCode = 200),
        )

        val report = WebFailureLearningEngine().evaluate(
            WebFailureObservation(result.receipt, result.finalResource),
            WebFailureExpectation(expectedFinalResourceId = resource.id),
        )

        assertTrue(report.candidates.isEmpty())
        assertEquals("no-actionable-web-failure-evidence", report.reasonCode)
    }

    private suspend fun acquire(
        url: String,
        response: WebAcquisitionTransportResponse,
    ) = WebAcquisitionRuntime(
        WebAcquisitionTransport { response }
    ).acquire(
        WebAcquisitionRequest.create(WebResourceIdentity.parse(url))
    )
}
