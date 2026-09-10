package app.lifeos.core.runtime.context

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRepository
import java.time.Instant

class ConversationContextStore(
    repository: PhotonRepository,
    val id: ConversationContextId,
) {
    private val delegate = PhotonBackedContextStore(
        repository = repository,
        scope = ContextScope.CONVERSATION,
        scopeId = id.value,
        actor = "ConversationContextStore",
    )

    suspend fun record(
        target: Photon,
        kind: String = ContextKindClassifier.classify(target),
        tags: Set<String> = target.tags,
        contentTerms: Set<String> = ContextKindClassifier.contentTerms(target),
        active: Boolean = true,
        confidence: Double = target.confidence,
        recordedAt: Instant = Instant.now(),
    ): ContextWriteResult = delegate.record(
        target = target,
        kind = kind,
        tags = tags,
        contentTerms = contentTerms,
        active = active,
        confidence = confidence,
        recordedAt = recordedAt,
    )

    suspend fun setActive(
        targetPhotonId: PhotonId,
        active: Boolean,
        recordedAt: Instant = Instant.now(),
    ): ContextWriteResult = delegate.setActive(targetPhotonId, active, recordedAt)

    suspend fun loadReport(): ContextLoadReport = delegate.loadReport()

    suspend fun loadAll(): List<ContextEntry> = delegate.loadAll()
}
