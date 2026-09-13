package app.lifeos.next.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.lifeos.core.image.ImageAssetDescriptor
import app.lifeos.core.image.ImagePhotonFactory
import app.lifeos.core.model.Photon
import app.lifeos.next.kernel.LifeOsKernel
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PhotonImagePreview(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val rendererId: String,
)

sealed interface PhotonImagePreviewState {
    data object Loading : PhotonImagePreviewState
    data class Ready(val preview: PhotonImagePreview) : PhotonImagePreviewState
    data class Failed(val message: String) : PhotonImagePreviewState
}

/**
 * Shared fail-closed UI loader for encrypted LIFEOS image-reference Photons.
 *
 * This class never reads files directly. The kernel verifies and loads the encrypted BinaryAssetStore
 * entry first; the UI then independently checks the descriptor byte count, SHA-256 and decoded size
 * before admitting a Bitmap to the bounded in-memory cache.
 */
class PhotonImagePreviewLoader(
    private val kernel: LifeOsKernel,
) {
    private val previewCache = object : LruCache<String, Bitmap>(IMAGE_PREVIEW_CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    suspend fun load(photon: Photon): PhotonImagePreviewState {
        if (photon.mimeType != ImagePhotonFactory.IMAGE_REFERENCE_MIME) {
            return PhotonImagePreviewState.Failed("Photon ist keine Bildreferenz.")
        }
        val descriptor = runCatching { ImageAssetDescriptor.decode(photon.content) }.getOrElse {
            return PhotonImagePreviewState.Failed("Bildreferenz ist beschädigt.")
        }
        val cacheKey = "${photon.id.value}:${photon.revision}:${descriptor.asset.sha256}"
        previewCache.get(cacheKey)?.let { bitmap ->
            return PhotonImagePreviewState.Ready(
                PhotonImagePreview(
                    bitmap = bitmap,
                    width = descriptor.width,
                    height = descriptor.height,
                    rendererId = descriptor.rendererId,
                )
            )
        }

        return try {
            val bytes = kernel.loadImageAsset(photon)
                ?: return PhotonImagePreviewState.Failed(
                    "Verschlüsseltes Bild-Asset fehlt oder ist nicht lesbar."
                )
            if (bytes.size.toLong() != descriptor.asset.byteCount) {
                return PhotonImagePreviewState.Failed("Bild-Asset stimmt nicht mit der Referenz überein.")
            }
            if (sha256(bytes) != descriptor.asset.sha256) {
                return PhotonImagePreviewState.Failed("Bild-Asset hat die Integritätsprüfung nicht bestanden.")
            }
            val bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                )
            } ?: return PhotonImagePreviewState.Failed("PNG konnte lokal nicht dekodiert werden.")

            if (bitmap.width != descriptor.width || bitmap.height != descriptor.height) {
                bitmap.recycle()
                return PhotonImagePreviewState.Failed(
                    "Bildabmessungen stimmen nicht mit der Photon-Referenz überein."
                )
            }
            previewCache.put(cacheKey, bitmap)
            PhotonImagePreviewState.Ready(
                PhotonImagePreview(
                    bitmap = bitmap,
                    width = descriptor.width,
                    height = descriptor.height,
                    rendererId = descriptor.rendererId,
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PhotonImagePreviewState.Failed("Bild konnte nicht aus dem lokalen Asset-Vault geladen werden.")
        }
    }

    fun clear() {
        previewCache.evictAll()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val IMAGE_PREVIEW_CACHE_KIB = 16 * 1024
    }
}
