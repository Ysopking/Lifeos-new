package app.lifeos.core.runtime.agency

data class ExternalEndpoint(
    val uri: String,
) {
    init {
        require(uri.matches(Regex("[a-z][a-z0-9+.-]*://[^\\s]+"))) {
            "External endpoint must be an absolute scheme URI"
        }
    }

    val scheme: String get() = uri.substringBefore("://")
}
