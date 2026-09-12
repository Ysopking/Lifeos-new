package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.StableCognitiveIds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhotonBackedCognitiveOutcomeJournal(private val store: PhotonRepository) : CognitiveOutcomeJournal {
    private val lock = Mutex()

    override suspend fun record(outcome: CognitiveOutcome): Long = lock.withLock {
        val content = CognitionOutcomeCodec.encode(outcome)
        val key = StableCognitiveIds.fingerprint("cognition-outcome/v1", outcome.taskId.value, content)
        val id = CognitionJournalIdentity.photonId(CognitionJournalKind.OUTCOME.tag, key)
        store.load(id)?.let {
            check(decode(it) == outcome) { "Cognitive outcome identity conflict" }
        } ?: store.save(
            cognitionJournalPhoton(
                CognitionJournalKind.OUTCOME,
                key,
                outcome.recordedAt,
                content,
            )
        )
        outcomes().size.toLong()
    }

    override suspend fun latest(limit: Int): List<CognitiveOutcome> = lock.withLock {
        require(limit > 0) { "Outcome limit must be positive" }
        outcomes()
            .sortedWith(compareBy<CognitiveOutcome> { it.recordedAt }.thenBy { it.taskId.value })
            .asReversed()
            .take(limit)
    }

    private suspend fun outcomes(): List<CognitiveOutcome> =
        loadCognitionJournalPhotons(store, CognitionJournalKind.OUTCOME).map(::decode)

    private fun decode(photon: Photon): CognitiveOutcome = try {
        require(photon.mimeType == COGNITION_JOURNAL_MIME)
        CognitionOutcomeCodec.decode(photon.content)
    } catch (error: Exception) {
        throw CognitionJournalCorruptionException(
            "Unreadable outcome journal ${photon.id.value}",
            error,
        )
    }
}
