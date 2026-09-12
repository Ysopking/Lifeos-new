package app.lifeos.core.runtime.cognition

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
        if (store.load(id) == null) {
            store.save(cognitionJournalPhoton(CognitionJournalKind.OUTCOME, key, outcome.recordedAt, content))
        }
        loadCognitionJournalPhotons(store, CognitionJournalKind.OUTCOME).size.toLong()
    }

    override suspend fun latest(limit: Int): List<CognitiveOutcome> = emptyList()
}
