package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.PhotonRevisionRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhotonBackedCognitiveTriggerSink(
    private val store: PhotonRepository,
    private val journalIndex: CognitionJournalIndex? = null,
) : CognitiveTriggerSink {
    private val lock = Mutex()

    override suspend fun emit(trigger: CognitiveTrigger): Boolean = lock.withLock {
        val id = CognitionJournalIdentity.photonId(CognitionJournalKind.TRIGGER.tag, trigger.id)
        store.load(id)?.let { existing ->
            check(decode(existing) == trigger) { "Cognitive trigger id conflict" }
            return@withLock false
        }

        if (journalIndex == null) {
            store.save(
                cognitionJournalPhoton(
                    CognitionJournalKind.TRIGGER,
                    trigger.id,
                    trigger.createdAt,
                    CognitionTriggerCodec.encode(trigger),
                )
            )
            return@withLock true
        }

        val reservation = journalIndex.reserveNext(CognitionJournalKind.TRIGGER, trigger.id)
        val photon = cognitionJournalPhoton(
            CognitionJournalKind.TRIGGER,
            trigger.id,
            trigger.createdAt,
            CognitionTriggerCodec.encode(trigger),
        )
        store.save(photon)
        journalIndex.commit(
            reservation = reservation,
            photonRef = PhotonRevisionRef(photon.id, photon.revision),
            recordedAt = trigger.createdAt,
        )
        true
    }

    override suspend fun snapshot(): List<CognitiveTrigger> = lock.withLock {
        if (journalIndex == null) {
            return@withLock loadCognitionJournalPhotons(store, CognitionJournalKind.TRIGGER)
                .map(::decode)
                .sortedWith(compareBy<CognitiveTrigger> { it.createdAt }.thenBy { it.id })
        }

        val entries = journalIndex.entries(CognitionJournalKind.TRIGGER)
        loadCognitionJournalPhotons(store, entries.map { it.photonRef }).map(::decode)
    }

    private fun decode(photon: Photon): CognitiveTrigger = try {
        require(photon.mimeType == COGNITION_JOURNAL_MIME)
        CognitionTriggerCodec.decode(photon.content)
    } catch (error: Exception) {
        throw CognitionJournalCorruptionException(
            "Unreadable cognitive trigger journal ${photon.id.value}",
            error,
        )
    }
}
