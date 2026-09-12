package app.lifeos.core.runtime.cognition

import java.nio.charset.StandardCharsets
import java.util.Base64

internal object CognitionJournalCodecSupport {
    fun encode(fields: List<String?>): String = fields.joinToString("\n") { field ->
        if (field == null) "0" else "1" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(field.toByteArray(StandardCharsets.UTF_8))
    }

    fun decode(content: String): List<String?> = content.split('\n').map { token ->
        if (token == "0") null else {
            require(token.startsWith("1")) { "Invalid cognition journal token" }
            String(Base64.getUrlDecoder().decode(token.drop(1)), StandardCharsets.UTF_8)
        }
    }
}
