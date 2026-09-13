package app.lifeos.next.ui.chat

import app.lifeos.core.model.Photon
import app.lifeos.next.kernel.LifeOsKernel
import app.lifeos.next.ui.components.PhotonImagePreviewLoader
import app.lifeos.next.ui.components.PhotonImagePreviewState

typealias ChatImagePreview = app.lifeos.next.ui.components.PhotonImagePreview

sealed interface ChatImagePreviewState {
    data object Loading : ChatImagePreviewState
    data class Ready(val preview: ChatImagePreview) : ChatImagePreviewState
    data class Failed(val message: String) : ChatImagePreviewState
}

/**
 * F4 source-compatible adapter. The verification/decoding implementation now lives in the shared
 * PhotonImagePreviewLoader so chat and memory cannot drift into different asset-integrity policies.
 */
class ChatImagePreviewLoader(
    kernel: LifeOsKernel,
) {
    private val delegate = PhotonImagePreviewLoader(kernel)

    suspend fun load(photon: Photon): ChatImagePreviewState = when (val state = delegate.load(photon)) {
        PhotonImagePreviewState.Loading -> ChatImagePreviewState.Loading
        is PhotonImagePreviewState.Ready -> ChatImagePreviewState.Ready(state.preview)
        is PhotonImagePreviewState.Failed -> ChatImagePreviewState.Failed(state.message)
    }

    fun clear() {
        delegate.clear()
    }
}
