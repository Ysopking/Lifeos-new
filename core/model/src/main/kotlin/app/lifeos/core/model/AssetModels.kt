package app.lifeos.core.model

import java.util.UUID

@JvmInline
value class AssetId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid asset id" }
    }

    companion object {
        fun new(): AssetId = AssetId(UUID.randomUUID().toString())
    }
}

data class AssetRef(
    val id: AssetId,
    val mediaType: String,
    val byteCount: Long,
    val sha256: String,
) {
    init {
        require(mediaType.isNotBlank() && !mediaType.contains('\n') && !mediaType.contains('\r'))
        require(byteCount >= 0)
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "sha256 must be lowercase hexadecimal" }
    }
}

interface BinaryAssetStore {
    suspend fun save(bytes: ByteArray, mediaType: String): AssetRef
    suspend fun load(ref: AssetRef): ByteArray?
    suspend fun delete(id: AssetId)
}
