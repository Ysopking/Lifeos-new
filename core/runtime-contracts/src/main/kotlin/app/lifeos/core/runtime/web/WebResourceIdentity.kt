package app.lifeos.core.runtime.web

import java.net.IDN
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class WebResourceId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid WebResourceId prefix" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid WebResourceId digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "web-resource:"
    }
}

@JvmInline
value class WebOriginId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid WebOriginId prefix" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid WebOriginId digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "web-origin:"
    }
}

data class WebOriginIdentity(
    val id: WebOriginId,
    val scheme: String,
    val host: String,
    val port: Int?,
    val canonicalOrigin: String,
) {
    init {
        require(scheme == HTTPS_SCHEME)
        require(host.isNotBlank())
        require(port == null || port in 1..65535)
        require(canonicalOrigin == canonicalOrigin(scheme, host, port))
        require(id == expectedId())
    }

    val networkAuthority: Boolean
        get() = false

    val permissionAuthority: Boolean
        get() = false

    fun fingerprint(): String = webFingerprint(
        "web-origin-identity/v1",
        canonicalOrigin,
    )

    private fun expectedId(): WebOriginId =
        WebOriginId(WebOriginId.PREFIX + fingerprint())
}

data class WebResourceIdentity(
    val id: WebResourceId,
    val canonicalUrl: String,
    val origin: WebOriginIdentity,
    val path: String,
    val query: String?,
) {
    init {
        require(canonicalUrl.isNotBlank())
        require(path.startsWith('/'))
        require(query == null || !query.contains('#'))
        require(canonicalUrl == canonicalUrl(origin, path, query))
        require(id == expectedId())
    }

    val fragment: String?
        get() = null

    val contentAuthority: Boolean
        get() = false

    val trustAuthority: Boolean
        get() = false

    val networkAuthority: Boolean
        get() = false

    val permissionAuthority: Boolean
        get() = false

    fun fingerprint(): String = webFingerprint(
        "web-resource-identity/v1",
        canonicalUrl,
    )

    private fun expectedId(): WebResourceId =
        WebResourceId(WebResourceId.PREFIX + fingerprint())

    companion object {
        fun parse(rawUrl: String): WebResourceIdentity =
            WebResourceIdentityCanonicalizer.canonicalize(rawUrl)
    }
}

/**
 * B391 pure identity canonicalizer for HTTPS Web resources.
 *
 * It performs no DNS lookup, network request, permission decision, source-reliability judgment,
 * content trust decision, or redirect following. Fragments are deliberately excluded from resource
 * identity. Query order and duplicate query parameters are deliberately preserved because servers
 * may assign semantics to their order.
 */
object WebResourceIdentityCanonicalizer {
    fun canonicalize(rawUrl: String): WebResourceIdentity {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotBlank()) { "Web resource URL must not be blank" }
        val parsed = URI(trimmed)
        require(parsed.isAbsolute) { "Web resource URL must be absolute" }
        require(parsed.scheme.equals(HTTPS_SCHEME, ignoreCase = true)) {
            "Web resource identity requires HTTPS"
        }
        require(parsed.rawUserInfo == null && parsed.rawAuthority?.contains('@') != true) {
            "Web resource identity does not allow user-info"
        }

        val authority = parseAuthority(
            requireNotNull(parsed.rawAuthority) { "Web resource URL requires an authority" }
        )
        val host = canonicalHost(authority.host)
        val port = authority.port?.takeUnless { it == DEFAULT_HTTPS_PORT }
        val origin = createOrigin(host, port)

        val rawPath = parsed.rawPath.orEmpty().ifEmpty { "/" }
        val interim = canonicalUrl(
            origin = origin,
            path = rawPath,
            query = parsed.rawQuery,
        )
        val ascii = URI(interim).toASCIIString()
        val asciiUri = URI(ascii)
        val canonicalPath = normalizePath(asciiUri.rawPath.orEmpty().ifEmpty { "/" })
        val canonicalQuery = asciiUri.rawQuery?.let(::canonicalizePercentEncoding)
        val canonical = canonicalUrl(origin, canonicalPath, canonicalQuery)
        val fingerprint = webFingerprint("web-resource-identity/v1", canonical)

        return WebResourceIdentity(
            id = WebResourceId(WebResourceId.PREFIX + fingerprint),
            canonicalUrl = canonical,
            origin = origin,
            path = canonicalPath,
            query = canonicalQuery,
        )
    }

    private fun createOrigin(
        host: String,
        port: Int?,
    ): WebOriginIdentity {
        val canonical = canonicalOrigin(HTTPS_SCHEME, host, port)
        val fingerprint = webFingerprint("web-origin-identity/v1", canonical)
        return WebOriginIdentity(
            id = WebOriginId(WebOriginId.PREFIX + fingerprint),
            scheme = HTTPS_SCHEME,
            host = host,
            port = port,
            canonicalOrigin = canonical,
        )
    }

    private fun normalizePath(rawPath: String): String {
        val percentCanonical = canonicalizePercentEncoding(rawPath)
        val normalized = URI(
            HTTPS_SCHEME + "://identity.invalid" +
                if (percentCanonical.startsWith('/')) percentCanonical else "/$percentCanonical"
        ).normalize().rawPath
        return normalized.ifEmpty { "/" }
    }

    private fun canonicalHost(rawHost: String): String {
        require(rawHost.isNotBlank()) { "Web resource host must not be blank" }
        require('%' !in rawHost) { "Percent-encoded Web resource hosts are not accepted" }
        if (rawHost.startsWith('[')) {
            require(rawHost.endsWith(']')) { "Invalid bracketed IP host" }
            val literal = rawHost.substring(1, rawHost.length - 1)
            require(literal.isNotBlank() && ':' in literal) { "Invalid bracketed IP host" }
            return "[" + literal.lowercase() + "]"
        }
        val withoutRootDot = rawHost.removeSuffix(".")
        require(withoutRootDot.isNotBlank()) { "Web resource host must not be blank" }
        return IDN.toASCII(withoutRootDot, IDN.USE_STD3_ASCII_RULES).lowercase()
    }

    private fun parseAuthority(rawAuthority: String): ParsedAuthority {
        require(rawAuthority.isNotBlank()) { "Web resource authority must not be blank" }
        require('@' !in rawAuthority) { "Web resource identity does not allow user-info" }

        if (rawAuthority.startsWith('[')) {
            val close = rawAuthority.indexOf(']')
            require(close > 1) { "Invalid bracketed Web resource authority" }
            val host = rawAuthority.substring(0, close + 1)
            val tail = rawAuthority.substring(close + 1)
            val port = when {
                tail.isEmpty() -> null
                tail.startsWith(':') -> parsePort(tail.substring(1))
                else -> error("Invalid bracketed Web resource authority")
            }
            return ParsedAuthority(host, port)
        }

        require(rawAuthority.count { it == ':' } <= 1) {
            "IPv6 Web resource hosts must use brackets"
        }
        val colon = rawAuthority.lastIndexOf(':')
        if (colon < 0) return ParsedAuthority(rawAuthority, null)
        return ParsedAuthority(
            host = rawAuthority.substring(0, colon),
            port = parsePort(rawAuthority.substring(colon + 1)),
        )
    }

    private fun parsePort(rawPort: String): Int {
        require(rawPort.isNotBlank() && rawPort.all(Char::isDigit)) {
            "Invalid Web resource port"
        }
        return requireNotNull(rawPort.toIntOrNull()) {
            "Invalid Web resource port"
        }.also {
            require(it in 1..65535) { "Web resource port is outside the valid range" }
        }
    }

    private fun canonicalizePercentEncoding(raw: String): String {
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val current = raw[index]
            if (current != '%') {
                out.append(current)
                index += 1
                continue
            }
            require(index + 2 < raw.length) { "Invalid percent encoding in Web resource URL" }
            val high = raw[index + 1].digitToIntOrNull(16)
            val low = raw[index + 2].digitToIntOrNull(16)
            require(high != null && low != null) {
                "Invalid percent encoding in Web resource URL"
            }
            val value = high * 16 + low
            val decoded = value.toChar()
            if (decoded.isUnreservedAscii()) {
                out.append(decoded)
            } else {
                out.append('%')
                out.append(HEX[value ushr 4])
                out.append(HEX[value and 0x0f])
            }
            index += 3
        }
        return out.toString()
    }

    private fun Char.isUnreservedAscii(): Boolean =
        this in 'A'..'Z' ||
            this in 'a'..'z' ||
            this in '0'..'9' ||
            this == '-' ||
            this == '.' ||
            this == '_' ||
            this == '~'

    private data class ParsedAuthority(
        val host: String,
        val port: Int?,
    )

    private const val DEFAULT_HTTPS_PORT = 443
    private const val HEX = "0123456789ABCDEF"
}

private fun canonicalOrigin(
    scheme: String,
    host: String,
    port: Int?,
): String = buildString {
    append(scheme.lowercase())
    append("://")
    append(host.lowercase())
    if (port != null) append(':').append(port)
}

private fun canonicalUrl(
    origin: WebOriginIdentity,
    path: String,
    query: String?,
): String = buildString {
    append(origin.canonicalOrigin)
    append(path)
    if (query != null) append('?').append(query)
}

private fun webFingerprint(
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

private const val HTTPS_SCHEME = "https"
