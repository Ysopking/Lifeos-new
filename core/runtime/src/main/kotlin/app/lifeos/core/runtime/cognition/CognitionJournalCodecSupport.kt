package app.lifeos.core.runtime.cognition

import java.nio.charset.StandardCharsets
import java.util.Base64

internal object CognitionJournalCodecSupport {
    private const val MAX_FIELDS = 8192
    private const val MAX_CONTENT_CHARS = 1_500_000
    private const val MAX_FIELD_BYTES = 256 * 1024

    fun encode(fields: List<String?>): String {
        require(fields.size <= MAX_FIELDS) { "Too many cognition journal fields" }
        return fields.joinToString("\n") { field ->
            if (field == null) "0" else {
                val bytes = field.toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= MAX_FIELD_BYTES) { "Cognition journal field too large" }
                "1" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            }
        }
    }

    fun decode(content: String): List<String?> {
        require(content.length <= MAX_CONTENT_CHARS) { "Cognition journal payload too large" }
        val tokens = content.split('\n')
        require(tokens.size <= MAX_FIELDS) { "Too many cognition journal fields" }
        return tokens.map { token ->
            if (token == "0") null else {
                require(token.startsWith("1")) { "Invalid cognition journal token" }
                val bytes = Base64.getUrlDecoder().decode(token.drop(1))
                require(bytes.size <= MAX_FIELD_BYTES) { "Cognition journal field too large" }
                String(bytes, StandardCharsets.UTF_8)
            }
        }
    }
}
