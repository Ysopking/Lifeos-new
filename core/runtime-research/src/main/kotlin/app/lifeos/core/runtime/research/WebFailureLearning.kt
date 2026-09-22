package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.web.WebAcquisitionReceipt
import app.lifeos.core.runtime.web.WebResourceId
import app.lifeos.core.runtime.web.WebResourceIdentity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

enum class WebFailureKind {
    AUTHENTICATION_REQUIRED,
    AUTHORIZATION_DENIED,
    HTTP_CLIENT_FAILURE,
    HTTP_SERVER_FAILURE,
    LOGIN_REDIRECT,
    REDIRECT_TARGET_CHANGED,
    RESPONSE_SCHEMA_CHANGED,
}

data class WebFailureExpectation(
    val expectedFinalResourceId: WebResourceId? = null,
    val expectedResponseSchemaFingerprint: String? = null,
) {
    init {
        require(
            expectedResponseSchemaFingerprint == null ||
                expectedResponseSchemaFingerprint.matches(SHA_256_REGEX_B417)
        )
    }
}

data class WebFailureObservation(
    val receipt: WebAcquisitionReceipt,
    val finalResource: WebResourceIdentity,
    val observedResponseSchemaFingerprint: String? = null,
) {
    init {
        require(receipt.finalResourceId == finalResource.id)
        require(
            observedResponseSchemaFingerprint == null ||
                observedResponseSchemaFingerprint.matches(SHA_256_REGEX_B417)
        )
    }

    val truthAuthority: Boolean get() = false
    val causalAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

data class WebFailureLearningCandidate(
    val kind: WebFailureKind,
    val receiptFingerprint: String,
    val requestedResourceId: WebResourceId,
    val finalResourceId: WebResourceId,
    val statusCode: Int,
    val evidenceFingerprint: String,
    val nextCycleEligible: Boolean,
    val fingerprint: String,
) {
    init {
        require(receiptFingerprint.matches(SHA_256_REGEX_B417))
        require(statusCode in 100..599)
        require(evidenceFingerprint.matches(SHA_256_REGEX_B417))
        require(nextCycleEligible)
        require(
            fingerprint == candidateFingerprint(
                kind,
                receiptFingerprint,
                requestedResourceId,
                finalResourceId,
                statusCode,
                evidenceFingerprint,
            )
        )
    }

    val truthAuthority: Boolean get() = false
    val causalAuthority: Boolean get() = false
    val policyAuthority: Boolean get() = false
    val credentialAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

data class WebFailureLearningReport(
    val receiptFingerprint: String,
    val candidates: List<WebFailureLearningCandidate>,
    val reasonCode: String,
    val fingerprint: String,
) {
    init {
        require(receiptFingerprint.matches(SHA_256_REGEX_B417))
        require(reasonCode.isNotBlank())
        require(candidates == candidates.distinctBy { it.fingerprint }.sortedBy { it.fingerprint })
        require(candidates.size <= MAX_FAILURE_CANDIDATES)
        require(
            fingerprint == reportFingerprint(
                receiptFingerprint = receiptFingerprint,
                candidates = candidates,
                reasonCode = reasonCode,
            )
        )
    }

    val automaticRetryAllowed: Boolean get() = false
    val automaticCredentialUseAllowed: Boolean get() = false
    val currentCycleMutationAllowed: Boolean get() = false
}

/**
 * B417 learns only bounded next-cycle failure hypotheses from exact B392 Web acquisition evidence.
 *
 * A 401/403, login-shaped redirect, changed redirect target or explicit schema-fingerprint mismatch
 * may become a candidate for later planning. None of those observations proves root cause, grants
 * credentials, changes Owner Policy, retries the request, or mutates the current plan.
 */
class WebFailureLearningEngine {
    fun evaluate(
        observation: WebFailureObservation,
        expectation: WebFailureExpectation = WebFailureExpectation(),
    ): WebFailureLearningReport {
        val receipt = observation.receipt
        val evidenceFingerprint = evidenceFingerprint(observation, expectation)
        val kinds = linkedSetOf<WebFailureKind>()

        when (receipt.statusCode) {
            401 -> kinds += WebFailureKind.AUTHENTICATION_REQUIRED
            403 -> kinds += WebFailureKind.AUTHORIZATION_DENIED
            in 400..499 -> kinds += WebFailureKind.HTTP_CLIENT_FAILURE
            in 500..599 -> kinds += WebFailureKind.HTTP_SERVER_FAILURE
        }

        val finalPath = observation.finalResource.path.lowercase(Locale.ROOT)
        if (
            receipt.redirects.isNotEmpty() &&
            LOGIN_PATH_TOKENS.any(finalPath::contains)
        ) {
            kinds += WebFailureKind.LOGIN_REDIRECT
        }

        if (
            receipt.redirects.isNotEmpty() &&
            expectation.expectedFinalResourceId != null &&
            expectation.expectedFinalResourceId != observation.finalResource.id
        ) {
            kinds += WebFailureKind.REDIRECT_TARGET_CHANGED
        }

        if (
            expectation.expectedResponseSchemaFingerprint != null &&
            observation.observedResponseSchemaFingerprint != null &&
            expectation.expectedResponseSchemaFingerprint !=
                observation.observedResponseSchemaFingerprint
        ) {
            kinds += WebFailureKind.RESPONSE_SCHEMA_CHANGED
        }

        val candidates = kinds
            .map { kind ->
                WebFailureLearningCandidate(
                    kind = kind,
                    receiptFingerprint = receipt.fingerprint,
                    requestedResourceId = receipt.requestedResourceId,
                    finalResourceId = receipt.finalResourceId,
                    statusCode = receipt.statusCode,
                    evidenceFingerprint = evidenceFingerprint,
                    nextCycleEligible = true,
                    fingerprint = candidateFingerprint(
                        kind,
                        receipt.fingerprint,
                        receipt.requestedResourceId,
                        receipt.finalResourceId,
                        receipt.statusCode,
                        evidenceFingerprint,
                    ),
                )
            }
            .distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }
            .take(MAX_FAILURE_CANDIDATES)

        val reasonCode = if (candidates.isEmpty()) {
            "no-actionable-web-failure-evidence"
        } else {
            "bounded-web-failure-evidence"
        }
        return WebFailureLearningReport(
            receiptFingerprint = receipt.fingerprint,
            candidates = candidates,
            reasonCode = reasonCode,
            fingerprint = reportFingerprint(
                receiptFingerprint = receipt.fingerprint,
                candidates = candidates,
                reasonCode = reasonCode,
            ),
        )
    }
}

private fun evidenceFingerprint(
    observation: WebFailureObservation,
    expectation: WebFailureExpectation,
): String = b417Fingerprint(
    "web-failure-learning-evidence/v1",
    observation.receipt.fingerprint,
    observation.finalResource.id.value,
    observation.observedResponseSchemaFingerprint.orEmpty(),
    expectation.expectedFinalResourceId?.value.orEmpty(),
    expectation.expectedResponseSchemaFingerprint.orEmpty(),
)

private fun candidateFingerprint(
    kind: WebFailureKind,
    receiptFingerprint: String,
    requestedResourceId: WebResourceId,
    finalResourceId: WebResourceId,
    statusCode: Int,
    evidenceFingerprint: String,
): String = b417Fingerprint(
    "web-failure-learning-candidate/v1",
    kind.name,
    receiptFingerprint,
    requestedResourceId.value,
    finalResourceId.value,
    statusCode.toString(),
    evidenceFingerprint,
)

private fun reportFingerprint(
    receiptFingerprint: String,
    candidates: List<WebFailureLearningCandidate>,
    reasonCode: String,
): String = b417Fingerprint(
    "web-failure-learning-report/v1",
    receiptFingerprint,
    reasonCode,
    *candidates.map { candidate -> candidate.fingerprint }.toTypedArray(),
)

private fun b417Fingerprint(domain: String, vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            )
        )
        digest.update(bytes)
    }
    update(domain)
    parts.forEach(::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private val SHA_256_REGEX_B417 = Regex("[0-9a-f]{64}")
private val LOGIN_PATH_TOKENS = listOf("/login", "/sign-in", "/signin", "/oauth", "/authorize")
private const val MAX_FAILURE_CANDIDATES = 8
