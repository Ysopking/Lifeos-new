package app.lifeos.next

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.image.ImageAssetDescriptor
import app.lifeos.core.image.ImagePhotonFactory
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.next.kernel.ImageGenerationResult
import app.lifeos.next.kernel.KernelBootstrapStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LifeOsState(
    val photons: List<Photon> = emptyList(),
    val draft: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
    val loadFailed: Boolean = false,
    val unreadable: Int = 0,
    val error: String? = null,
    val lastGoal: GoalFrame? = null,
)

data class ImagePreview(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val rendererId: String,
)

sealed interface ImagePreviewState {
    data object Loading : ImagePreviewState
    data class Ready(val preview: ImagePreview) : ImagePreviewState
    data class Failed(val message: String) : ImagePreviewState
}

class LifeOsViewModel(application: Application) : AndroidViewModel(application) {
    private val kernel = (application as LifeOsApplication).kernel
    private val mutableState = MutableStateFlow(LifeOsState())
    private val previewCache = object : LruCache<String, Bitmap>(IMAGE_PREVIEW_CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    val state = mutableState.asStateFlow()
    val runtimeState = kernel.runtime.state
    val matrixState = kernel.matrix.state

    init {
        observeKernel()
    }

    fun editDraft(text: String) {
        if (!mutableState.value.saving) mutableState.update { it.copy(draft = text) }
    }

    fun retryLoad() {
        val current = mutableState.value
        if (!current.loadFailed || current.loading) return

        mutableState.update {
            it.copy(
                loading = true,
                loadFailed = false,
                error = null,
            )
        }
        kernel.retryBootstrap()
    }

    fun dismissError() {
        mutableState.update { it.copy(error = null) }
    }

    fun saveDraft() {
        val current = mutableState.value
        if (current.loading || current.loadFailed || current.saving || current.draft.isBlank()) return

        val photon = Photon(
            content = current.draft.trim(),
            provenance = Provenance("local-chat", "user"),
            tags = setOf("chat"),
        )
        mutableState.update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            try {
                val result = kernel.persistUserUtterance(photon)
                mutableState.update {
                    it.copy(
                        draft = "",
                        lastGoal = result.understanding?.goal ?: it.lastGoal,
                        error = when {
                            result.languageFailure != null ->
                                "Gedanke wurde gespeichert, aber das lokale Sprachverständnis ist fehlgeschlagen."
                            !result.source.processingQueued ->
                                "Gedanke wurde gespeichert, konnte aber nicht zur Verarbeitung eingereiht werden."
                            result.goal?.processingQueued != true ->
                                "Gedanke wurde verstanden und gespeichert, das abgeleitete Ziel konnte aber nicht zur Verarbeitung eingereiht werden."
                            result.imageGeneration is ImageGenerationResult.Blocked ->
                                "Das Bildziel wurde verstanden, kann mit den lokalen Fähigkeiten aber noch nicht vollständig ausgeführt werden."
                            result.imageGeneration is ImageGenerationResult.Failed ->
                                "Das Bild konnte lokal nicht erzeugt werden: ${result.imageGeneration.message}"
                            else -> null
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        error = "Gedanke konnte nicht gespeichert werden. Die Eingabe bleibt im Textfeld.",
                    )
                }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }

    suspend fun loadImagePreview(photon: Photon): ImagePreviewState {
        if (photon.mimeType != ImagePhotonFactory.IMAGE_REFERENCE_MIME) {
            return ImagePreviewState.Failed("Photon ist keine Bildreferenz.")
        }
        val descriptor = runCatching { ImageAssetDescriptor.decode(photon.content) }.getOrElse {
            return ImagePreviewState.Failed("Bildreferenz ist beschädigt.")
        }
        val cacheKey = "${photon.id.value}:${photon.revision}:${descriptor.asset.sha256}"
        previewCache.get(cacheKey)?.let { bitmap ->
            return ImagePreviewState.Ready(
                ImagePreview(bitmap, descriptor.width, descriptor.height, descriptor.rendererId),
            )
        }

        return try {
            val bytes = kernel.loadImageAsset(photon)
                ?: return ImagePreviewState.Failed("Verschlüsseltes Bild-Asset fehlt oder ist nicht lesbar.")
            val bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                )
            } ?: return ImagePreviewState.Failed("PNG konnte lokal nicht dekodiert werden.")

            if (bitmap.width != descriptor.width || bitmap.height != descriptor.height) {
                bitmap.recycle()
                return ImagePreviewState.Failed("Bildabmessungen stimmen nicht mit der Photon-Referenz überein.")
            }
            previewCache.put(cacheKey, bitmap)
            ImagePreviewState.Ready(
                ImagePreview(bitmap, descriptor.width, descriptor.height, descriptor.rendererId),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ImagePreviewState.Failed("Bild konnte nicht aus dem lokalen Asset-Vault geladen werden.")
        }
    }

    private fun observeKernel() {
        viewModelScope.launch {
            kernel.bootstrapState.collect { bootstrap ->
                mutableState.update { current ->
                    val loadError = bootstrap.status == KernelBootstrapStatus.FAILED
                    current.copy(
                        photons = bootstrap.photons
                            .filter { it.isUserVisiblePhoton() }
                            .asReversed(),
                        loading = bootstrap.status == KernelBootstrapStatus.CREATED ||
                            bootstrap.status == KernelBootstrapStatus.LOADING,
                        loadFailed = loadError,
                        unreadable = bootstrap.unreadableFiles,
                        error = when {
                            loadError -> LOAD_ERROR_MESSAGE
                            current.error == LOAD_ERROR_MESSAGE -> null
                            else -> current.error
                        },
                    )
                }
            }
        }
    }

    override fun onCleared() {
        previewCache.evictAll()
        super.onCleared()
    }

    private fun Photon.isUserVisiblePhoton(): Boolean =
        "goal" !in tags && "scene-graph" !in tags

    private companion object {
        const val LOAD_ERROR_MESSAGE = "Speicher konnte nicht geladen werden. Bitte erneut versuchen."
        const val IMAGE_PREVIEW_CACHE_KIB = 16 * 1024
    }
}
