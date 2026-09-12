package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhotonBackedCognitiveOutcomeJournal(private val store: PhotonRepository) : CognitiveOutcomeJournal {
    private val lock = Mutex()

    override suspend fun record(outcome: CognitiveOutcome): Long = lock.withLock {
        val persisted = outcomes()
        if (persisted.any { normalize(it) == normalize(outcome) }) {
            return@withLock persisted.size.toLong()
        }

        val key = outcomeKey(outcome)
        val id = CognitionJournalIdentity.photonId(CognitionJournalKind.OUTCOME.tag, key)
        store.load(id)?.let { existing ->
            check(normalize(decode(existing)) == normalize(outcome)) { "Cognitive outcome identity conflict" }
        } ?: store.save(
            cognitionJournalPhoton(
                CognitionJournalKind.OUTCOME,
                key,
                outcome.recordedAt,
                CognitionOutcomeCodec.encode(outcome),
            )
        )
        outcomes().size.toLong()
    }

    override suspend fun latest(limit: Int): List<CognitiveOutcome> = lock.withLock {
        require(limit > 0) { "Outcome limit must be positive" }
        outcomes().asReversed().take(limit)
    }

    private suspend fun outcomes(): List<CognitiveOutcome> =
        loadCognitionJournalPhotons(store, CognitionJournalKind.OUTCOME)
            .map(::decode)
            .sortedWith(compareBy<CognitiveOutcome> { it.recordedAt }.thenBy { outcomeKey(it) })

    private fun decode(photon: Photon): CognitiveOutcome = try {
        require(photon.mimeType == COGNITION_JOURNAL_MIME)
        CognitionOutcomeCodec.decode(photon.content)
    } catch (error: Exception) {
        throw CognitionJournalCorruptionException("Unreadable outcome journal ${photon.id.value}", error)
    }

    internal fun outcomeKey(outcome: CognitiveOutcome): String = StableCognitiveIds.fingerprint(
        "cognition-outcome/v2",
        outcome.taskId.value,
        CognitionOutcomeCodec.encode(normalize(outcome)),
    )

    internal fun legacyOutcomeKey(outcome: CognitiveOutcome): String = StableCognitiveIds.fingerprint(
        "cognition-outcome/v1",
        outcome.taskId.value,
        CognitionOutcomeCodec.encode(outcome),
    )

    private fun normalize(outcome: CognitiveOutcome): CognitiveOutcome = outcome.copy(recordedAt = Instant.EPOCH)
}
