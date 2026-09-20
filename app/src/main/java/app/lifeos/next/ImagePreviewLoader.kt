package app.lifeos.next

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.lifeos.core.image.ImageAssetDescriptor
import app.lifeos.core.image.ImagePhotonFactory
import app.lifeos.core.model.Photon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ImagePreviewLoader(
    private val loadAsset: suspend (Photon) -> ByteArray?,
    cacheKiB: Int = 16 * 1024,
) {
    private val previewCache = object : LruCache<String, Bitmap>(cacheKiB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    suspend fun load(photon: Photon): ImagePreviewState {
        if (photon.mimeType != ImagePhotonFactory.IMAGE_REFERENCE_MIME) {
            return ImagePreviewState.Failed("Photon ist keine Bildreferenz.")
        }
        val descriptor = runCatching {
            ImageAssetDescriptor.decode(photon.content)
        }.getOrElse {
            return ImagePreviewState.Failed("Bildreferenz ist beschädigt.")
        }
        val cacheKey =
            "${photon.id.value}:${photon.revision}:${descriptor.asset.sha256}"
        previewCache.get(cacheKey)?.let { bitmap ->
            return ImagePreviewState.Ready(
                ImagePreview(
                    bitmap,
                    descriptor.width,
                    descriptor.height,
                    descriptor.rendererId,
                ),
            )
        }

        return try {
            val bytes = loadAsset(photon)
                ?: return ImagePreviewState.Failed(
                    "Verschlüsseltes Bild-Asset fehlt oder ist nicht lesbar."
                )
            val bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                )
            } ?: return ImagePreviewState.Failed(
                "PNG konnte lokal nicht dekodiert werden."
            )

            if (
                bitmap.width != descriptor.width ||
                bitmap.height != descriptor.height
            ) {
                bitmap.recycle()
                return ImagePreviewState.Failed(
                    "Bildabmessungen stimmen nicht mit der Photon-Referenz überein."
                )
            }
            previewCache.put(cacheKey, bitmap)
            ImagePreviewState.Ready(
                ImagePreview(
                    bitmap,
                    descriptor.width,
                    descriptor.height,
                    descriptor.rendererId,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ImagePreviewState.Failed(
                "Bild konnte nicht aus dem lokalen Asset-Vault geladen werden."
            )
        }
    }

    fun clear() {
        previewCache.evictAll()
    }
}
