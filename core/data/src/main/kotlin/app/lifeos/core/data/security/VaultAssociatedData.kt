package app.lifeos.core.data.security

import java.io.File

internal object VaultAssociatedData {
    fun forPath(
        domain: String,
        root: File,
        file: File,
    ): ByteArray {
        require(domain.isNotBlank()) { "Vault AAD domain must not be blank" }
        val relative = file.relativeTo(root).invariantSeparatorsPath
        require(relative.isNotBlank() && !relative.startsWith("../")) {
            "Vault AAD file must remain inside its root"
        }
        return "$domain:$relative".toByteArray(Charsets.UTF_8)
    }
}
