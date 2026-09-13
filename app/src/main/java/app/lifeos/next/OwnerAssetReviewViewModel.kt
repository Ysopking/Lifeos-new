package app.lifeos.next

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.image.ImagePhotonFactory
import app.lifeos.core.runtime.artifact.ArtifactKind
import app.lifeos.core.runtime.artifact.OwnerAssetReviewCandidateId
import app.lifeos.core.runtime.artifact.OwnerAssetReviewDecision
import app.lifeos.core.runtime.artifact.OwnerAssetReviewRecord
import app.lifeos.next.kernel.PrivateOwnerPolicyBaseline
import app.lifeos.next.ui.components.PhotonImagePreviewLoader
import app.lifeos.next.ui.components.PhotonImagePreviewState
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AssetReviewFilter {
    PENDING,
    APPROVED,
    FEEDBACK,
}

data class OwnerAssetReviewUiState(
    val records: List<OwnerAssetReviewRecord> = emptyList(),
    val filter: AssetReviewFilter = AssetReviewFilter.PENDING,
    val feedbackDrafts: Map<String, String> = emptyMap(),
    val previewStates: Map<String, PhotonImagePreviewState> = emptyMap(),
    val busyCandidateIds: Set<String> = emptySet(),
    val error: String? = null,
) {
    val visibleRecords: List<OwnerAssetReviewRecord>
        get() = records.filter { record ->
            when (filter) {
                AssetReviewFilter.PENDING -> record.decision == null
                AssetReviewFilter.APPROVED ->
                    record.decision?.decision == OwnerAssetReviewDecision.APPROVED
                AssetReviewFilter.FEEDBACK ->
                    record.decision?.decision == OwnerAssetReviewDecision.CHANGES_REQUESTED ||
                        record.decision?.decision == OwnerAssetReviewDecision.REJECTED
            }
        }.sortedWith(
            compareByDescending<OwnerAssetReviewRecord> { it.candidate.createdAt }
                .thenBy { it.candidate.id.value }
        )

    val pendingCount: Int get() = records.count { it.decision == null }
    val approvedCount: Int get() = records.count {
        it.decision?.decision == OwnerAssetReviewDecision.APPROVED
    }
    val feedbackCount: Int get() = records.count {
        it.decision?.decision == OwnerAssetReviewDecision.CHANGES_REQUESTED ||
            it.decision?.decision == OwnerAssetReviewDecision.REJECTED
    }
}

class OwnerAssetReviewViewModel(application: Application) : AndroidViewModel(application) {
    private val owner = application as LifeOsApplication
    private val reviews = requireNotNull(owner.photonIngress.ownerAssetReview) {
        "Owner asset review runtime is unavailable"
    }
    private val imagePreviewLoader = PhotonImagePreviewLoader(owner.kernel)
    private val mutableState = MutableStateFlow(OwnerAssetReviewUiState())

    val state = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun selectFilter(filter: AssetReviewFilter) {
        mutableState.update { it.copy(filter = filter) }
    }

    fun editFeedback(candidateId: OwnerAssetReviewCandidateId, value: String) {
        mutableState.update { current ->
            current.copy(
                feedbackDrafts = current.feedbackDrafts + (candidateId.value to value.take(MAX_FEEDBACK_CHARS)),
            )
        }
    }

    fun dismissError() {
        mutableState.update { it.copy(error = null) }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val snapshot = reviews.snapshot()
                mutableState.update { current ->
                    current.copy(records = snapshot, error = null)
                }
                snapshot.forEach { record ->
                    if (record.candidate.kind == ArtifactKind.IMAGE) {
                        loadPreview(record)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(error = error.message ?: error::class.simpleName ?: "Asset-Freigaben konnten nicht geladen werden.")
                }
            }
        }
    }

    fun approve(candidateId: OwnerAssetReviewCandidateId) {
        decide(candidateId, OwnerAssetReviewDecision.APPROVED, feedback = null)
    }

    fun requestChanges(candidateId: OwnerAssetReviewCandidateId) {
        val feedback = mutableState.value.feedbackDrafts[candidateId.value]?.trim().orEmpty()
        if (feedback.isBlank()) {
            mutableState.update { it.copy(error = "Bitte beschreibe zuerst die gewünschten Änderungen.") }
            return
        }
        decide(candidateId, OwnerAssetReviewDecision.CHANGES_REQUESTED, feedback)
    }

    fun reject(candidateId: OwnerAssetReviewCandidateId) {
        val feedback = mutableState.value.feedbackDrafts[candidateId.value]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        decide(candidateId, OwnerAssetReviewDecision.REJECTED, feedback)
    }

    private fun decide(
        candidateId: OwnerAssetReviewCandidateId,
        decision: OwnerAssetReviewDecision,
        feedback: String?,
    ) {
        if (candidateId.value in mutableState.value.busyCandidateIds) return
        mutableState.update { current ->
            current.copy(
                busyCandidateIds = current.busyCandidateIds + candidateId.value,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                reviews.decide(
                    candidateId = candidateId,
                    decision = decision,
                    ownerActorId = PrivateOwnerPolicyBaseline.ownerActorId.value,
                    feedback = feedback,
                    decidedAt = Instant.now(),
                )
                val snapshot = reviews.snapshot()
                mutableState.update { current ->
                    current.copy(
                        records = snapshot,
                        busyCandidateIds = current.busyCandidateIds - candidateId.value,
                        feedbackDrafts = current.feedbackDrafts - candidateId.value,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { current ->
                    current.copy(
                        busyCandidateIds = current.busyCandidateIds - candidateId.value,
                        error = error.message ?: error::class.simpleName ?: "Asset-Entscheidung ist fehlgeschlagen.",
                    )
                }
            }
        }
    }

    private fun loadPreview(record: OwnerAssetReviewRecord) {
        val id = record.candidate.id.value
        if (id in mutableState.value.previewStates) return
        val imagePhoton = record.candidate.stagedPhotons.firstOrNull {
            it.mimeType == ImagePhotonFactory.IMAGE_REFERENCE_MIME
        } ?: return
        mutableState.update { current ->
            current.copy(previewStates = current.previewStates + (id to PhotonImagePreviewState.Loading))
        }
        viewModelScope.launch {
            val result = try {
                imagePreviewLoader.load(imagePhoton)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PhotonImagePreviewState.Failed("Bildvorschau konnte nicht geladen werden.")
            }
            mutableState.update { current ->
                current.copy(previewStates = current.previewStates + (id to result))
            }
        }
    }

    override fun onCleared() {
        imagePreviewLoader.clear()
        super.onCleared()
    }

    private companion object {
        const val MAX_FEEDBACK_CHARS = 8_192
    }
}
