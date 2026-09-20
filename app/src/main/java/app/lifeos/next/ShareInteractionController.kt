package app.lifeos.next

import android.content.Intent
import app.lifeos.core.runtime.goal.LocalSharePreparation
import app.lifeos.next.kernel.LifeOsKernel
import app.lifeos.next.kernel.LocalShareIntentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ShareInteractionController(
    private val kernel: LifeOsKernel,
    private val intentFactory: LocalShareIntentFactory,
    private val state: MutableStateFlow<LifeOsState>,
    private val scope: CoroutineScope,
) {
    suspend fun createIntent(share: LocalSharePreparation): Intent =
        intentFactory.create(share)

    fun opened(share: LocalSharePreparation) {
        if (state.value.pendingShare != share) return
        state.update {
            it.copy(
                pendingShare = null,
                shareStatus = "Android-Teilen wurde geöffnet.",
            )
        }
        scope.launch {
            val receipt = kernel.recordCommunicationHandoff(share)
            if (!receipt.processingQueued) {
                state.update {
                    it.copy(
                        shareStatus =
                            "Android-Teilen wurde geöffnet; der lokale Handoff-Beleg konnte aber nicht vollständig eingereiht werden."
                    )
                }
            }
        }
    }

    fun failed(share: LocalSharePreparation) {
        if (state.value.pendingShare != share) return
        state.update {
            it.copy(
                pendingShare = null,
                error = "Das Android-Teilen konnte nicht geöffnet werden.",
            )
        }
    }

    fun dismissStatus() {
        state.update { it.copy(shareStatus = null) }
    }
}
