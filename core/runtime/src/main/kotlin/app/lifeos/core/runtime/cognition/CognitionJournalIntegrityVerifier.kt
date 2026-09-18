package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant

/**
 * Forces a bounded, schema-aware decode of every internal cognition-journal Photon.
 * The encrypted PhotonRepository remains the authority; this verifier only validates
 * journal payloads and deterministic identities before productive runtime start.
 */
class CognitionJournalIntegrityVerifier(
    private val repository: PhotonRepository,
    private val journalIndex: CognitionJournalIndex? = null,
) {
    suspend fun verify(): CognitionJournalIntegrityReport {
        val photonsByKind = indexedPhotonsByKind()

        val eventCount = verifyKind(
            CognitionJournalKind.EVENT,
            photonsByKind.getValue(CognitionJournalKind.EVENT),
        ) { photon ->
            val value = RuntimeEventJournalCodec.decode(photon.content)
            requireIdentity(photon, CognitionJournalKind.EVENT, value.event.eventId)
        }
        val transactionCount = verifyKind(
            CognitionJournalKind.TRANSACTION,
            photonsByKind.getValue(CognitionJournalKind.TRANSACTION),
        ) { photon ->
            val value = CognitionTransactionCodec.decode(photon.content)
            requireIdentity(photon, CognitionJournalKind.TRANSACTION, value.transactionId)
        }
        val outcomeCount = verifyKind(
            CognitionJournalKind.OUTCOME,
            photonsByKind.getValue(CognitionJournalKind.OUTCOME),
        ) { photon ->
            val value = CognitionOutcomeCodec.decode(photon.content)
            val normalized = value.copy(recordedAt = Instant.EPOCH)
            val currentKey = StableCognitiveIds.fingerprint(
                "cognition-outcome/v2",
                value.taskId.value,
                CognitionOutcomeCodec.encode(normalized),
            )
            val legacyKey = StableCognitiveIds.fingerprint(
                "cognition-outcome/v1",
                value.taskId.value,
                CognitionOutcomeCodec.encode(value),
            )
            requireAnyIdentity(
                photon = photon,
                kind = CognitionJournalKind.OUTCOME,
                stableIds = listOf(currentKey, legacyKey),
            )
        }
        val triggerCount = verifyKind(
            CognitionJournalKind.TRIGGER,
            photonsByKind.getValue(CognitionJournalKind.TRIGGER),
        ) { photon ->
            val value = CognitionTriggerCodec.decode(photon.content)
            requireIdentity(photon, CognitionJournalKind.TRIGGER, value.id)
        }
        return CognitionJournalIntegrityReport(
            events = eventCount,
            transactions = transactionCount,
            outcomes = outcomeCount,
            triggers = triggerCount,
        )
    }

    private suspend fun indexedPhotonsByKind(): Map<CognitionJournalKind, List<Photon>> {
        val index = journalIndex
        if (index == null) {
            val photons = loadAllCognitionJournalPhotons(repository)
            return CognitionJournalKind.values().associateWith { kind ->
                photons.filter { "cognition-journal-kind:${kind.tag}" in it.tags }
            }
        }

        val snapshot = index.snapshot()
        val refs = snapshot.entries.map { it.photonRef }.distinct()
        val photonsByRef = loadCognitionJournalPhotons(repository, refs)
            .associateBy { PhotonRevisionRef(it.id, it.revision) }
        return CognitionJournalKind.values().associateWith { kind ->
            snapshot.entries(kind).map { entry ->
                photonsByRef[entry.photonRef]
                    ?: throw CognitionJournalCorruptionException(
                        "Missing indexed cognition journal Photon ${entry.photonRef.stableKey}"
                    )
            }
        }
    }

    private fun verifyKind(
        kind: CognitionJournalKind,
        photons: List<Photon>,
        verifyPhoton: (Photon) -> Unit,
    ): Int {
        photons.forEach { photon ->
            try {
                require(photon.mimeType == COGNITION_JOURNAL_MIME) {
                    "Invalid cognition journal MIME"
                }
                require(COGNITION_JOURNAL_ROOT_TAG in photon.tags) {
                    "Missing cognition journal root tag"
                }
                require("cognition-journal-kind:${kind.tag}" in photon.tags) {
                    "Cognition journal kind mismatch"
                }
                verifyPhoton(photon)
            } catch (error: Exception) {
                throw CognitionJournalCorruptionException(
                    "Unreadable ${kind.tag} journal ${photon.id.value}",
                    error,
                )
            }
        }
        return photons.size
    }

    private fun requireIdentity(
        photon: Photon,
        kind: CognitionJournalKind,
        stableId: String,
    ) = requireAnyIdentity(photon, kind, listOf(stableId))

    private fun requireAnyIdentity(
        photon: Photon,
        kind: CognitionJournalKind,
        stableIds: List<String>,
    ) {
        val expectedIds = stableIds.map { CognitionJournalIdentity.photonId(kind.tag, it) }
        require(photon.id in expectedIds) {
            "Cognition journal identity mismatch for ${photon.id.value}"
        }
    }
}

data class CognitionJournalIntegrityReport(
    val events: Int,
    val transactions: Int,
    val outcomes: Int,
    val triggers: Int,
) {
    init {
        require(events >= 0 && transactions >= 0 && outcomes >= 0 && triggers >= 0)
    }

    val total: Int get() = events + transactions + outcomes + triggers
}
