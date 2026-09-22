package app.lifeos.core.runtime.web

import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.ToolPermission
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

enum class OpenApiHttpMethod {
    GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS,
}

enum class OpenApiAuthenticationMode {
    NONE_DECLARED,
    REQUIRED_UNRESOLVED,
    OPTIONAL_UNRESOLVED,
}

data class OpenApiOperationDescriptor(
    val method: OpenApiHttpMethod,
    val path: String,
    val operationId: String?,
    val requestContracts: List<String>,
    val responseContracts: List<String>,
    val authenticationMode: OpenApiAuthenticationMode,
    val fingerprint: String,
) {
    init {
        require(path.startsWith('/'))
        require(path.length <= MAX_OPENAPI_PATH_CHARS)
        require(path.none { it == '\r' || it == '\n' || it == '\u0000' })
        require(operationId == null || operationId.matches(OPERATION_ID_REGEX))
        require(requestContracts == canonicalContracts(requestContracts))
        require(responseContracts == canonicalContracts(responseContracts))
        require(requestContracts.size <= MAX_CONTRACTS_PER_OPERATION)
        require(responseContracts.size <= MAX_CONTRACTS_PER_OPERATION)
        require(
            fingerprint == operationFingerprint(
                method, path, operationId, requestContracts, responseContracts, authenticationMode
            )
        )
    }

    companion object {
        fun create(
            method: OpenApiHttpMethod,
            path: String,
            operationId: String? = null,
            requestContracts: Collection<String> = emptyList(),
            responseContracts: Collection<String> = emptyList(),
            authenticationMode: OpenApiAuthenticationMode =
                OpenApiAuthenticationMode.REQUIRED_UNRESOLVED,
        ): OpenApiOperationDescriptor {
            val canonicalPath = path.trim()
            val canonicalOperationId = operationId?.trim()?.takeIf(String::isNotBlank)
            val requests = canonicalContracts(requestContracts)
            val responses = canonicalContracts(responseContracts)
            return OpenApiOperationDescriptor(
                method = method,
                path = canonicalPath,
                operationId = canonicalOperationId,
                requestContracts = requests,
                responseContracts = responses,
                authenticationMode = authenticationMode,
                fingerprint = operationFingerprint(
                    method, canonicalPath, canonicalOperationId, requests, responses, authenticationMode
                ),
            )
        }
    }
}

data class NormalizedOpenApiDocument(
    val resource: WebResourceIdentity,
    val discoveryCandidateFingerprint: String,
    val specPayloadSha256: String,
    val openApiVersion: String,
    val operations: List<OpenApiOperationDescriptor>,
    val fingerprint: String,
) {
    init {
        require(discoveryCandidateFingerprint.matches(SHA_256_REGEX))
        require(specPayloadSha256.matches(SHA_256_REGEX))
        require(openApiVersion.matches(OPENAPI_VERSION_REGEX))
        require(operations.isNotEmpty())
        require(operations.size <= MAX_OPENAPI_OPERATIONS)
        require(operations == operations.distinctBy { it.fingerprint }.sortedBy { it.fingerprint })
        require(
            fingerprint == documentFingerprint(
                resource, discoveryCandidateFingerprint, specPayloadSha256, openApiVersion, operations
            )
        )
    }

    val trustAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun create(
            resource: WebResourceIdentity,
            discoveryCandidateFingerprint: String,
            specPayloadSha256: String,
            openApiVersion: String,
            operations: Collection<OpenApiOperationDescriptor>,
        ): NormalizedOpenApiDocument {
            val canonicalOperations = operations.distinctBy { it.fingerprint }.sortedBy { it.fingerprint }
            val version = openApiVersion.trim()
            return NormalizedOpenApiDocument(
                resource = resource,
                discoveryCandidateFingerprint = discoveryCandidateFingerprint,
                specPayloadSha256 = specPayloadSha256,
                openApiVersion = version,
                operations = canonicalOperations,
                fingerprint = documentFingerprint(
                    resource, discoveryCandidateFingerprint, specPayloadSha256, version, canonicalOperations
                ),
            )
        }
    }
}

data class OpenApiToolCandidate(
    val capabilityId: String,
    val discoveryCandidateFingerprint: String,
    val documentFingerprint: String,
    val specResource: WebResourceIdentity,
    val specPayloadSha256: String,
    val selectedOperations: List<OpenApiOperationDescriptor>,
    val requiredInputs: List<String>,
    val requiredOutputs: List<String>,
    val requestedPermissions: Set<ToolPermission>,
    val unresolvedAuthentication: Boolean,
    val requiresOwnerPolicy: Boolean,
    val fingerprint: String,
) {
    init {
        require(capabilityId.isNotBlank())
        require(discoveryCandidateFingerprint.matches(SHA_256_REGEX))
        require(documentFingerprint.matches(SHA_256_REGEX))
        require(specPayloadSha256.matches(SHA_256_REGEX))
        require(selectedOperations.isNotEmpty())
        require(selectedOperations.size <= MAX_SELECTED_OPERATIONS)
        require(selectedOperations == selectedOperations.distinctBy { it.fingerprint }.sortedBy { it.fingerprint })
        require(requiredInputs == canonicalContracts(requiredInputs))
        require(requiredOutputs == canonicalContracts(requiredOutputs))
        require(requestedPermissions == setOf(ToolPermission.NETWORK_ACCESS))
        require(requiresOwnerPolicy)
        require(
            fingerprint == toolCandidateFingerprint(
                capabilityId,
                discoveryCandidateFingerprint,
                documentFingerprint,
                specResource,
                specPayloadSha256,
                selectedOperations,
                requiredInputs,
                requiredOutputs,
                requestedPermissions,
                unresolvedAuthentication,
                requiresOwnerPolicy,
            )
        )
    }

    val permissionAuthority: Boolean get() = false
    val authenticationAuthority: Boolean get() = false
    val activationAuthority: Boolean get() = false
    val providerAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
    val trustAuthority: Boolean get() = false
}

/**
 * B413 translates an exact normalized B412 OpenAPI/Swagger discovery into a non-activating
 * candidate. Raw syntax parsing is kept outside this authority boundary; a parser must first bind
 * exact resource, discovery fingerprint and payload hash into NormalizedOpenApiDocument.
 */
class OpenApiToolCandidateTranslator {
    fun translate(
        requirement: CapabilityRequirement,
        discovery: WebApiCapabilityCandidate,
        document: NormalizedOpenApiDocument,
    ): OpenApiToolCandidate? {
        require(
            discovery.signal == WebApiDiscoverySignal.OPENAPI_DOCUMENT ||
                discovery.signal == WebApiDiscoverySignal.SWAGGER_DOCUMENT
        ) {
            "B413 requires an exact OpenAPI/Swagger B412 discovery candidate"
        }
        require(discovery.capabilityId == requirement.capabilityId.value) {
            "B413 discovery candidate belongs to another capability"
        }
        require(document.resource == discovery.resource) {
            "B413 OpenAPI resource was substituted after discovery"
        }
        require(document.discoveryCandidateFingerprint == discovery.fingerprint) {
            "B413 normalized OpenAPI document does not bind the exact B412 candidate"
        }

        val terms = requirementTerms(requirement)
        val selected = document.operations
            .map { it to operationMatchScore(it, terms) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<OpenApiOperationDescriptor, Int>> { it.second }
                    .thenBy { it.first.fingerprint }
            )
            .take(MAX_SELECTED_OPERATIONS)
            .map { it.first }
            .sortedBy { it.fingerprint }

        if (selected.isEmpty()) return null

        val inputs = canonicalContracts(requirement.requiredInputs + selected.flatMap { it.requestContracts })
        val outputs = canonicalContracts(requirement.requiredOutputs + selected.flatMap { it.responseContracts })
        val unresolvedAuth = selected.any {
            it.authenticationMode != OpenApiAuthenticationMode.NONE_DECLARED
        }
        val permissions = setOf(ToolPermission.NETWORK_ACCESS)
        val capabilityId = requirement.capabilityId.value

        return OpenApiToolCandidate(
            capabilityId = capabilityId,
            discoveryCandidateFingerprint = discovery.fingerprint,
            documentFingerprint = document.fingerprint,
            specResource = document.resource,
            specPayloadSha256 = document.specPayloadSha256,
            selectedOperations = selected,
            requiredInputs = inputs,
            requiredOutputs = outputs,
            requestedPermissions = permissions,
            unresolvedAuthentication = unresolvedAuth,
            requiresOwnerPolicy = true,
            fingerprint = toolCandidateFingerprint(
                capabilityId,
                discovery.fingerprint,
                document.fingerprint,
                document.resource,
                document.specPayloadSha256,
                selected,
                inputs,
                outputs,
                permissions,
                unresolvedAuth,
                true,
            ),
        )
    }

    private fun operationMatchScore(
        operation: OpenApiOperationDescriptor,
        terms: Set<String>,
    ): Int {
        if (terms.isEmpty()) return 0
        val haystack = listOf(
            operation.path,
            operation.operationId.orEmpty(),
            operation.requestContracts.joinToString(" "),
            operation.responseContracts.joinToString(" "),
        ).joinToString(" ").lowercase(Locale.ROOT)
        return terms.count(haystack::contains)
    }

    private fun requirementTerms(requirement: CapabilityRequirement): Set<String> =
        (
            tokenize(requirement.capabilityId.value) +
                requirement.requiredInputs.flatMap(::tokenize) +
                requirement.requiredOutputs.flatMap(::tokenize)
        )
            .filter { it.length >= 3 }
            .toSortedSet()

    private fun tokenize(value: String): List<String> =
        value.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter(String::isNotBlank)
}

private fun canonicalContracts(values: Collection<String>): List<String> =
    values.map(String::trim)
        .filter(String::isNotBlank)
        .onEach {
            require(it.length <= MAX_CONTRACT_CHARS)
            require(it.none { ch -> ch == '\r' || ch == '\n' || ch == '\u0000' })
        }
        .distinct()
        .sorted()

private fun operationFingerprint(
    method: OpenApiHttpMethod,
    path: String,
    operationId: String?,
    requestContracts: List<String>,
    responseContracts: List<String>,
    authenticationMode: OpenApiAuthenticationMode,
): String = openApiFingerprint(
    "openapi-operation/v1",
    method.name,
    path,
    operationId.orEmpty(),
    requestContracts.joinToString("\u001f"),
    responseContracts.joinToString("\u001f"),
    authenticationMode.name,
)

private fun documentFingerprint(
    resource: WebResourceIdentity,
    discoveryCandidateFingerprint: String,
    specPayloadSha256: String,
    openApiVersion: String,
    operations: List<OpenApiOperationDescriptor>,
): String = openApiFingerprint(
    "normalized-openapi-document/v1",
    resource.id.value,
    discoveryCandidateFingerprint,
    specPayloadSha256,
    openApiVersion,
    *operations.map { it.fingerprint }.toTypedArray(),
)

private fun toolCandidateFingerprint(
    capabilityId: String,
    discoveryCandidateFingerprint: String,
    documentFingerprint: String,
    specResource: WebResourceIdentity,
    specPayloadSha256: String,
    selectedOperations: List<OpenApiOperationDescriptor>,
    requiredInputs: List<String>,
    requiredOutputs: List<String>,
    requestedPermissions: Set<ToolPermission>,
    unresolvedAuthentication: Boolean,
    requiresOwnerPolicy: Boolean,
): String = openApiFingerprint(
    "openapi-tool-candidate/v1",
    capabilityId,
    discoveryCandidateFingerprint,
    documentFingerprint,
    specResource.id.value,
    specPayloadSha256,
    selectedOperations.joinToString("\u001f") { it.fingerprint },
    requiredInputs.joinToString("\u001f"),
    requiredOutputs.joinToString("\u001f"),
    requestedPermissions.map { it.name }.sorted().joinToString("\u001f"),
    unresolvedAuthentication.toString(),
    requiresOwnerPolicy.toString(),
)

private fun openApiFingerprint(domain: String, vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(byteArrayOf(
            (bytes.size ushr 24).toByte(),
            (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(),
            bytes.size.toByte(),
        ))
        digest.update(bytes)
    }
    update(domain)
    parts.forEach(::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private val SHA_256_REGEX = Regex("[0-9a-f]{64}")
private val OPENAPI_VERSION_REGEX = Regex("[0-9]+(?:\\.[0-9]+){1,2}")
private val OPERATION_ID_REGEX = Regex("[A-Za-z0-9_.:-]{1,256}")

private const val MAX_OPENAPI_PATH_CHARS = 2048
private const val MAX_CONTRACT_CHARS = 512
private const val MAX_CONTRACTS_PER_OPERATION = 128
private const val MAX_OPENAPI_OPERATIONS = 512
private const val MAX_SELECTED_OPERATIONS = 32
