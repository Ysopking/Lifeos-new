package app.lifeos.core.runtime.web

import app.lifeos.core.runtime.capability.CapabilityRequirement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

enum class WebApiDiscoverySignal {
    OPENAPI_DOCUMENT,
    SWAGGER_DOCUMENT,
    GRAPHQL_REFERENCE,
    API_DOCUMENTATION,
    ENDPOINT_REFERENCE,
}

data class WebApiDiscoveryEvidence(
    val source: WebResourceIdentity,
    val acquisitionReceiptFingerprint: String,
    val payloadSha256: String,
    val contentType: String,
    val publicText: String,
    val fingerprint: String,
) {
    init {
        require(acquisitionReceiptFingerprint.matches(SHA_256_REGEX))
        require(payloadSha256.matches(SHA_256_REGEX))
        require(contentType.isNotBlank() && contentType.length <= MAX_CONTENT_TYPE_CHARS)
        require(publicText.length <= MAX_DISCOVERY_TEXT_CHARS)
        require(publicText.none { it == '\u0000' })
        require(
            fingerprint == evidenceFingerprint(
                source = source,
                acquisitionReceiptFingerprint = acquisitionReceiptFingerprint,
                payloadSha256 = payloadSha256,
                contentType = contentType,
                publicText = publicText,
            )
        )
    }

    val trustAuthority: Boolean get() = false
    val truthAuthority: Boolean get() = false
    val networkAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun create(
            source: WebResourceIdentity,
            acquisitionReceiptFingerprint: String,
            payloadSha256: String,
            contentType: String,
            publicText: String,
        ): WebApiDiscoveryEvidence =
            WebApiDiscoveryEvidence(
                source = source,
                acquisitionReceiptFingerprint = acquisitionReceiptFingerprint,
                payloadSha256 = payloadSha256,
                contentType = contentType.trim().lowercase(Locale.ROOT).take(MAX_CONTENT_TYPE_CHARS),
                publicText = publicText,
                fingerprint = evidenceFingerprint(
                    source = source,
                    acquisitionReceiptFingerprint = acquisitionReceiptFingerprint,
                    payloadSha256 = payloadSha256,
                    contentType = contentType.trim().lowercase(Locale.ROOT).take(MAX_CONTENT_TYPE_CHARS),
                    publicText = publicText,
                ),
            )
    }
}

data class WebApiCapabilityCandidate(
    val capabilityId: String,
    val resource: WebResourceIdentity,
    val signal: WebApiDiscoverySignal,
    val sourceEvidenceFingerprint: String,
    val matchedCapabilityTerms: List<String>,
    val fingerprint: String,
) {
    init {
        require(capabilityId.isNotBlank())
        require(sourceEvidenceFingerprint.matches(SHA_256_REGEX))
        require(matchedCapabilityTerms == matchedCapabilityTerms.distinct().sorted())
        require(matchedCapabilityTerms.size <= MAX_MATCHED_TERMS)
        require(
            fingerprint == candidateFingerprint(
                capabilityId = capabilityId,
                resource = resource,
                signal = signal,
                sourceEvidenceFingerprint = sourceEvidenceFingerprint,
                matchedCapabilityTerms = matchedCapabilityTerms,
            )
        )
    }

    /** B412 discovery is evidence only. B413+ own candidate translation and later safety gates. */
    val providerAuthority: Boolean get() = false
    val toolCandidateAuthority: Boolean get() = false
    val activationAuthority: Boolean get() = false
    val trustAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

data class WebApiCapabilityDiscoveryReport(
    val requirementFingerprint: String,
    val evidenceFingerprints: List<String>,
    val candidates: List<WebApiCapabilityCandidate>,
    val fingerprint: String,
) {
    init {
        require(requirementFingerprint.matches(SHA_256_REGEX))
        require(evidenceFingerprints == evidenceFingerprints.distinct().sorted())
        require(candidates == candidates.distinctBy { it.fingerprint }.sortedBy { it.fingerprint })
        require(candidates.size <= MAX_DISCOVERY_CANDIDATES)
        require(
            fingerprint == reportFingerprint(
                requirementFingerprint = requirementFingerprint,
                evidenceFingerprints = evidenceFingerprints,
                candidates = candidates,
            )
        )
    }

    val networkAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
    val activationAuthority: Boolean get() = false
}

/**
 * B412 performs bounded, read-only discovery over already acquired public Web evidence.
 *
 * It never performs network access, authenticates, executes an endpoint, creates an active provider,
 * or treats documentation as trusted truth. B413 may later translate an exact discovered OpenAPI
 * resource into a non-activating ToolCandidate under separate validation.
 */
class WebApiCapabilityDiscoveryEngine {
    fun discover(
        requirement: CapabilityRequirement,
        evidence: Collection<WebApiDiscoveryEvidence>,
    ): WebApiCapabilityDiscoveryReport {
        val canonicalEvidence = canonicalizeEvidence(evidence)
        val requirementFingerprint = requirementFingerprint(requirement)
        val terms = capabilityTerms(requirement)

        val candidates = buildList {
            canonicalEvidence.forEach { item ->
                val lower = item.publicText.lowercase(Locale.ROOT)
                val matched = terms.filter(lower::contains).take(MAX_MATCHED_TERMS).sorted()

                classifyDocument(item, lower)?.let { signal ->
                    add(candidate(requirement, item.source, signal, item, matched))
                }

                extractHttpsUrls(item.publicText)
                    .asSequence()
                    .mapNotNull { raw -> runCatching { WebResourceIdentity.parse(raw) }.getOrNull() }
                    .distinctBy { it.id }
                    .take(MAX_LINKS_PER_EVIDENCE)
                    .forEach { resource ->
                        val signal = classifyResource(resource)
                        if (signal != null && (matched.isNotEmpty() || isStrongApiSignal(signal))) {
                            add(candidate(requirement, resource, signal, item, matched))
                        }
                    }
            }
        }
            .distinctBy { it.fingerprint }
            .sortedWith(
                compareBy<WebApiCapabilityCandidate>({ it.resource.canonicalUrl }, { it.signal.name })
                    .thenBy { it.fingerprint }
            )
            .take(MAX_DISCOVERY_CANDIDATES)
            .sortedBy { it.fingerprint }

        val evidenceFingerprints = canonicalEvidence.map { it.fingerprint }.sorted()
        return WebApiCapabilityDiscoveryReport(
            requirementFingerprint = requirementFingerprint,
            evidenceFingerprints = evidenceFingerprints,
            candidates = candidates,
            fingerprint = reportFingerprint(
                requirementFingerprint = requirementFingerprint,
                evidenceFingerprints = evidenceFingerprints,
                candidates = candidates,
            ),
        )
    }

    private fun canonicalizeEvidence(
        evidence: Collection<WebApiDiscoveryEvidence>,
    ): List<WebApiDiscoveryEvidence> {
        require(evidence.size <= MAX_DISCOVERY_EVIDENCE) {
            "B412 discovery evidence exceeds bounded size"
        }
        val grouped = evidence.groupBy { it.fingerprint }
        grouped.forEach { (fingerprint, items) ->
            require(items.all { it == items.first() }) {
                "Conflicting B412 evidence identity: $fingerprint"
            }
        }
        return grouped.values.map { it.first() }.sortedBy { it.fingerprint }
    }

    private fun classifyDocument(
        evidence: WebApiDiscoveryEvidence,
        lower: String,
    ): WebApiDiscoverySignal? {
        val path = evidence.source.path.lowercase(Locale.ROOT)
        val type = evidence.contentType.substringBefore(';').trim()
        return when {
            OPENAPI_MARKERS.any(lower::contains) ||
                path.endsWith("/openapi.json") ||
                path.endsWith("/openapi.yaml") ||
                path.endsWith("/openapi.yml") ->
                WebApiDiscoverySignal.OPENAPI_DOCUMENT
            SWAGGER_MARKERS.any(lower::contains) ||
                path.endsWith("/swagger.json") ||
                path.endsWith("/swagger.yaml") ->
                WebApiDiscoverySignal.SWAGGER_DOCUMENT
            GRAPHQL_MARKERS.any(lower::contains) ->
                WebApiDiscoverySignal.GRAPHQL_REFERENCE
            type == "text/html" && API_DOC_MARKERS.any(lower::contains) ->
                WebApiDiscoverySignal.API_DOCUMENTATION
            else -> null
        }
    }

    private fun classifyResource(resource: WebResourceIdentity): WebApiDiscoverySignal? {
        val path = resource.path.lowercase(Locale.ROOT)
        return when {
            path.endsWith("/openapi.json") ||
                path.endsWith("/openapi.yaml") ||
                path.endsWith("/openapi.yml") ->
                WebApiDiscoverySignal.OPENAPI_DOCUMENT
            path.endsWith("/swagger.json") || path.endsWith("/swagger.yaml") ->
                WebApiDiscoverySignal.SWAGGER_DOCUMENT
            "/graphql" in path ->
                WebApiDiscoverySignal.GRAPHQL_REFERENCE
            "/api/" in path || API_VERSION_PATH.containsMatchIn(path) ->
                WebApiDiscoverySignal.ENDPOINT_REFERENCE
            "/docs" in path || "/developer" in path || "/reference" in path ->
                WebApiDiscoverySignal.API_DOCUMENTATION
            else -> null
        }
    }

    private fun isStrongApiSignal(signal: WebApiDiscoverySignal): Boolean =
        signal == WebApiDiscoverySignal.OPENAPI_DOCUMENT ||
            signal == WebApiDiscoverySignal.SWAGGER_DOCUMENT ||
            signal == WebApiDiscoverySignal.GRAPHQL_REFERENCE

    private fun candidate(
        requirement: CapabilityRequirement,
        resource: WebResourceIdentity,
        signal: WebApiDiscoverySignal,
        evidence: WebApiDiscoveryEvidence,
        matched: List<String>,
    ): WebApiCapabilityCandidate {
        val capabilityId = requirement.capabilityId.value
        return WebApiCapabilityCandidate(
            capabilityId = capabilityId,
            resource = resource,
            signal = signal,
            sourceEvidenceFingerprint = evidence.fingerprint,
            matchedCapabilityTerms = matched,
            fingerprint = candidateFingerprint(
                capabilityId = capabilityId,
                resource = resource,
                signal = signal,
                sourceEvidenceFingerprint = evidence.fingerprint,
                matchedCapabilityTerms = matched,
            ),
        )
    }

    private fun capabilityTerms(requirement: CapabilityRequirement): List<String> =
        (
            tokenize(requirement.capabilityId.value) +
                requirement.requiredInputs.flatMap(::tokenize) +
                requirement.requiredOutputs.flatMap(::tokenize)
            )
            .filter { it.length >= 3 }
            .distinct()
            .sorted()
            .take(MAX_MATCHED_TERMS)

    private fun tokenize(value: String): List<String> =
        value.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter(String::isNotBlank)

    private fun extractHttpsUrls(text: String): List<String> =
        HTTPS_URL.findAll(text)
            .map { match -> match.value.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'') }
            .distinct()
            .take(MAX_LINKS_PER_EVIDENCE)
            .toList()
}

private fun evidenceFingerprint(
    source: WebResourceIdentity,
    acquisitionReceiptFingerprint: String,
    payloadSha256: String,
    contentType: String,
    publicText: String,
): String = discoveryFingerprint(
    "web-api-discovery-evidence/v1",
    source.id.value,
    acquisitionReceiptFingerprint,
    payloadSha256,
    contentType,
    sha256(publicText),
)

private fun requirementFingerprint(
    requirement: CapabilityRequirement,
): String = discoveryFingerprint(
    "web-api-discovery-requirement/v1",
    requirement.capabilityId.value,
    requirement.severity.name,
    *requirement.requiredInputs.sorted().map { "input:$it" }.toTypedArray(),
    *requirement.requiredOutputs.sorted().map { "output:$it" }.toTypedArray(),
)

private fun candidateFingerprint(
    capabilityId: String,
    resource: WebResourceIdentity,
    signal: WebApiDiscoverySignal,
    sourceEvidenceFingerprint: String,
    matchedCapabilityTerms: List<String>,
): String = discoveryFingerprint(
    "web-api-capability-candidate/v1",
    capabilityId,
    resource.id.value,
    signal.name,
    sourceEvidenceFingerprint,
    *matchedCapabilityTerms.toTypedArray(),
)

private fun reportFingerprint(
    requirementFingerprint: String,
    evidenceFingerprints: List<String>,
    candidates: List<WebApiCapabilityCandidate>,
): String = discoveryFingerprint(
    "web-api-capability-discovery-report/v1",
    requirementFingerprint,
    evidenceFingerprints.joinToString("\u001f"),
    *candidates.map { it.fingerprint }.toTypedArray(),
)

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun discoveryFingerprint(
    domain: String,
    vararg parts: String,
): String {
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

private val SHA_256_REGEX = Regex("[0-9a-f]{64}")
private val HTTPS_URL = Regex("""https://[^\s<>"']+""", RegexOption.IGNORE_CASE)
private val API_VERSION_PATH = Regex("/v[0-9]+(?:/|$)")

private val OPENAPI_MARKERS = listOf(
    "\"openapi\"",
    "openapi:",
    "openapi specification",
)
private val SWAGGER_MARKERS = listOf(
    "\"swagger\"",
    "swagger:",
    "swagger ui",
)
private val GRAPHQL_MARKERS = listOf(
    "graphql",
    "__schema",
    "introspection",
)
private val API_DOC_MARKERS = listOf(
    "api reference",
    "developer api",
    "rest api",
    "endpoint",
    "authentication",
)

private const val MAX_CONTENT_TYPE_CHARS = 255
private const val MAX_DISCOVERY_TEXT_CHARS = 256 * 1024
private const val MAX_DISCOVERY_EVIDENCE = 64
private const val MAX_LINKS_PER_EVIDENCE = 128
private const val MAX_DISCOVERY_CANDIDATES = 128
private const val MAX_MATCHED_TERMS = 32
