package app.lifeos.core.runtime.web

import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.ToolPermission
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class WebToolDocumentationEvidence(
    val source: WebResourceIdentity,
    val acquisitionReceiptFingerprint: String,
    val payloadSha256: String,
    val excerpt: String,
    val fingerprint: String,
) {
    init {
        require(acquisitionReceiptFingerprint.matches(SHA_256_REGEX_B414))
        require(payloadSha256.matches(SHA_256_REGEX_B414))
        require(excerpt.isNotBlank())
        require(excerpt.length <= MAX_WEB_TOOL_EXCERPT_CHARS)
        require(excerpt.none { it == '\u0000' })
        require(
            fingerprint == documentationFingerprint(
                source,
                acquisitionReceiptFingerprint,
                payloadSha256,
                excerpt,
            )
        )
    }

    val trustAuthority: Boolean get() = false
    val truthAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun create(
            source: WebResourceIdentity,
            acquisitionReceiptFingerprint: String,
            payloadSha256: String,
            excerpt: String,
        ): WebToolDocumentationEvidence {
            val bounded = excerpt
                .replace('\u0000', ' ')
                .trim()
                .take(MAX_WEB_TOOL_EXCERPT_CHARS)
            require(bounded.isNotBlank())
            return WebToolDocumentationEvidence(
                source = source,
                acquisitionReceiptFingerprint = acquisitionReceiptFingerprint,
                payloadSha256 = payloadSha256,
                excerpt = bounded,
                fingerprint = documentationFingerprint(
                    source,
                    acquisitionReceiptFingerprint,
                    payloadSha256,
                    bounded,
                ),
            )
        }
    }
}

data class WebAssistedToolWorkshopBrief(
    val requirement: CapabilityRequirement,
    val toolCandidateFingerprint: String,
    val specResource: WebResourceIdentity,
    val specPayloadSha256: String,
    val selectedOperationFingerprints: List<String>,
    val documentationEvidence: List<WebToolDocumentationEvidence>,
    val requestedPermissions: Set<ToolPermission>,
    val unresolvedAuthentication: Boolean,
    val implementationConstraints: List<String>,
    val fingerprint: String,
) {
    init {
        require(toolCandidateFingerprint.matches(SHA_256_REGEX_B414))
        require(specPayloadSha256.matches(SHA_256_REGEX_B414))
        require(selectedOperationFingerprints.isNotEmpty())
        require(
            selectedOperationFingerprints ==
                selectedOperationFingerprints.distinct().sorted()
        )
        require(selectedOperationFingerprints.all { it.matches(SHA_256_REGEX_B414) })
        require(documentationEvidence.size <= MAX_WEB_TOOL_DOCUMENTS)
        require(
            documentationEvidence ==
                documentationEvidence.distinctBy { it.fingerprint }.sortedBy { it.fingerprint }
        )
        require(requestedPermissions == setOf(ToolPermission.NETWORK_ACCESS))
        require(implementationConstraints == implementationConstraints.distinct().sorted())
        require(REQUIRED_CONSTRAINTS.all(implementationConstraints::contains))
        require(
            fingerprint == briefFingerprint(
                requirement,
                toolCandidateFingerprint,
                specResource,
                specPayloadSha256,
                selectedOperationFingerprints,
                documentationEvidence,
                requestedPermissions,
                unresolvedAuthentication,
                implementationConstraints,
            )
        )
    }

    /** B414 creates design evidence only. Existing ToolWorkshop/OwnerPolicy/promotion remain authority. */
    val generationAuthority: Boolean get() = false
    val credentialAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false
    val providerAuthority: Boolean get() = false
    val activationAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
    val truthAuthority: Boolean get() = false
}

/**
 * B414 converts a B413 OpenAPI ToolCandidate plus already-acquired documentation/examples into an
 * immutable design brief for the existing ToolWorkshop/BuildStudio pipeline.
 *
 * No network request is performed here. Documentation never grants trust, credentials, permission,
 * provider registration, activation or execution. Authentication remains unresolved metadata until
 * an explicit later owner-authorized integration supplies a credential mechanism.
 */
class WebAssistedToolWorkshopBriefBuilder {
    fun build(
        requirement: CapabilityRequirement,
        candidate: OpenApiToolCandidate,
        documentation: Collection<WebToolDocumentationEvidence>,
    ): WebAssistedToolWorkshopBrief {
        require(candidate.capabilityId == requirement.capabilityId.value) {
            "B414 candidate belongs to another capability"
        }
        require(candidate.requestedPermissions == setOf(ToolPermission.NETWORK_ACCESS))
        require(candidate.requiresOwnerPolicy)
        require(documentation.size <= MAX_WEB_TOOL_DOCUMENTS) {
            "B414 documentation evidence exceeds bounded size"
        }

        val canonicalDocs = documentation
            .distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }

        val operationFingerprints = candidate.selectedOperations
            .map { it.fingerprint }
            .distinct()
            .sorted()

        val constraints = buildSet {
            addAll(REQUIRED_CONSTRAINTS)
            add("exact-openapi-candidate:" + candidate.fingerprint)
            add("exact-spec-payload:" + candidate.specPayloadSha256)
            if (candidate.unresolvedAuthentication) {
                add("authentication-unresolved")
                add("no-credential-inference")
            }
            candidate.selectedOperations.forEach { operation ->
                add(
                    "operation:" +
                        operation.method.name +
                        ":" +
                        operation.path +
                        ":" +
                        operation.fingerprint
                )
            }
            canonicalDocs.forEach { evidence ->
                add("documentation-evidence:" + evidence.fingerprint)
            }
        }.toList().sorted()

        return WebAssistedToolWorkshopBrief(
            requirement = requirement.copy(
                requiredInputs = (requirement.requiredInputs + candidate.requiredInputs).toSet(),
                requiredOutputs = (requirement.requiredOutputs + candidate.requiredOutputs).toSet(),
            ),
            toolCandidateFingerprint = candidate.fingerprint,
            specResource = candidate.specResource,
            specPayloadSha256 = candidate.specPayloadSha256,
            selectedOperationFingerprints = operationFingerprints,
            documentationEvidence = canonicalDocs,
            requestedPermissions = candidate.requestedPermissions,
            unresolvedAuthentication = candidate.unresolvedAuthentication,
            implementationConstraints = constraints,
            fingerprint = briefFingerprint(
                requirement = requirement.copy(
                    requiredInputs = (requirement.requiredInputs + candidate.requiredInputs).toSet(),
                    requiredOutputs = (requirement.requiredOutputs + candidate.requiredOutputs).toSet(),
                ),
                toolCandidateFingerprint = candidate.fingerprint,
                specResource = candidate.specResource,
                specPayloadSha256 = candidate.specPayloadSha256,
                selectedOperationFingerprints = operationFingerprints,
                documentationEvidence = canonicalDocs,
                requestedPermissions = candidate.requestedPermissions,
                unresolvedAuthentication = candidate.unresolvedAuthentication,
                implementationConstraints = constraints,
            ),
        )
    }
}

private fun documentationFingerprint(
    source: WebResourceIdentity,
    acquisitionReceiptFingerprint: String,
    payloadSha256: String,
    excerpt: String,
): String = b414Fingerprint(
    "web-tool-documentation-evidence/v1",
    source.id.value,
    acquisitionReceiptFingerprint,
    payloadSha256,
    sha256B414(excerpt),
)

private fun briefFingerprint(
    requirement: CapabilityRequirement,
    toolCandidateFingerprint: String,
    specResource: WebResourceIdentity,
    specPayloadSha256: String,
    selectedOperationFingerprints: List<String>,
    documentationEvidence: List<WebToolDocumentationEvidence>,
    requestedPermissions: Set<ToolPermission>,
    unresolvedAuthentication: Boolean,
    implementationConstraints: List<String>,
): String = b414Fingerprint(
    "web-assisted-tool-workshop-brief/v1",
    requirement.capabilityId.value,
    requirement.severity.name,
    toolCandidateFingerprint,
    specResource.id.value,
    specPayloadSha256,
    unresolvedAuthentication.toString(),
    requirement.requiredInputs.sorted().joinToString("\u001f"),
    requirement.requiredOutputs.sorted().joinToString("\u001f"),
    selectedOperationFingerprints.joinToString("\u001f"),
    documentationEvidence.joinToString("\u001f") { it.fingerprint },
    requestedPermissions.map { it.name }.sorted().joinToString("\u001f"),
    implementationConstraints.joinToString("\u001f"),
)

private fun sha256B414(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun b414Fingerprint(domain: String, vararg parts: String): String {
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

private val REQUIRED_CONSTRAINTS = listOf(
    "no-credential-inference",
    "no-direct-endpoint-execution",
    "no-owner-policy-bypass",
    "no-provider-activation",
    "sandbox-build-required",
)

private val SHA_256_REGEX_B414 = Regex("[0-9a-f]{64}")
private const val MAX_WEB_TOOL_EXCERPT_CHARS = 4096
private const val MAX_WEB_TOOL_DOCUMENTS = 16
