package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.PhotonRevisionRef
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhotonBackedCognitiveOutcomeJournal(
    private val store: PhotonRepository,
    private val journalIndex: CognitionJournalIndex? = null,
) : CognitiveOutcomeJournal {
    private val lock = Mutex()

    override suspend fun record(outcome: CognitiveOutcome): Long = lock.withLock {
        val key = outcomeKey(outcome)
        val legacyKey = legacyOutcomeKey(outcome)
        val existing = store.load(
            CognitionJournalIdentity.photonId(CognitionJournalKind.OUTCOME.tag, key)
        ) ?: store.load(
            CognitionJournalIdentity.photonId(CognitionJournalKind.OUTCOME.tag, legacyKey)
        )
        existing?.let {
            check(normalize(decode(it)) == normalize(outcome)) {
                "Cognitive outcome identity conflict"
            }
            return@withLock journalIndex?.size(CognitionJournalKind.OUTCOME)
                ?: outcomes().size.toLong()
        }

        if (journalIndex == null) {
            val persisted = outcomes()
            if (persisted.any { normalize(it) == normalize(outcome) }) {
                return@withLock persisted.size.toLong()
            }
            store.save(
                cognitionJournalPhoton(
                    CognitionJournalKind.OUTCOME,
                    key,
                    outcome.recordedAt,
                    CognitionOutcomeCodec.encode(outcome),
                )
            )
            return@withLock outcomes().size.toLong()
        }

        val reservation = journalIndex.reserveNext(CognitionJournalKind.OUTCOME, key)
        val photon = cognitionJournalPhoton(
            CognitionJournalKind.OUTCOME,
            key,
            outcome.recordedAt,
            CognitionOutcomeCodec.encode(outcome),
        )
        store.save(photon)
        journalIndex.commit(
            reservation = reservation,
            photonRef = PhotonRevisionRef(photon.id, photon.revision),
            recordedAt = outcome.recordedAt,
        )
        reservation.sequence
    }

    override suspend fun latest(limit: Int): List<CognitiveOutcome> = lock.withLock {
        require(limit > 0) { "Outcome limit must be positive" }
        if (journalIndex == null) {
            return@withLock outcomes().asReversed().take(limit)
        }
        val entries = journalIndex.entries(CognitionJournalKind.OUTCOME)
            .asReversed()
            .take(limit)
        loadCognitionJournalPhotons(store, entries.map { it.photonRef }).map(::decode)
    }

    private suspend fun outcomes(): List<CognitiveOutcome> =
        loadCognitionJournalPhotons(store, CognitionJournalKind.OUTCOME)
            .map(::decode)
            .sortedWith(compareBy<CognitiveOutcome> { it.recordedAt }.thenBy { outcomeKey(it) })

    private fun decode(photon: Photon): CognitiveOutcome = try {
        require(photon.mimeType == COGNITION_JOURNAL_MIME)
        CognitionOutcomeCodec.decode(photon.content)
    } catch (error: Exception) {
        throw CognitionJournalCorruptionException(
            "Unreadable outcome journal ${photon.id.value}",
            error,
        )
    }

    internal fun outcomeKey(outcome: CognitiveOutcome): String =
        cognitionOutcomeStableId(outcome)

    internal fun legacyOutcomeKey(outcome: CognitiveOutcome): String =
        cognitionOutcomeLegacyStableId(outcome)

    private fun normalize(outcome: CognitiveOutcome): CognitiveOutcome =
        outcome.copy(recordedAt = Instant.EPOCH)
}
