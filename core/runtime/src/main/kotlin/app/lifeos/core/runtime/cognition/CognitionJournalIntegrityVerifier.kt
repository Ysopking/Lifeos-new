package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant

/**
 * Forces a bounded, schema-aware decode of every internal cognition-journal Photon.
 * The encrypted PhotonRepository remains the authority; this verifier only validates
 * journal payloads and deterministic identities before productive runtime start.
 */
class CognitionJournalIntegrityVerifier(
    private val repository: PhotonRepository,
) {
    suspend fun verify(): CognitionJournalIntegrityReport {
        val eventCount = verifyKind(CognitionJournalKind.EVENT) { photon ->
            val value = RuntimeEventJournalCodec.decode(photon.content)
            requireIdentity(photon, CognitionJournalKind.EVENT, value.event.eventId)
        }
        val transactionCount = verifyKind(CognitionJournalKind.TRANSACTION) { photon ->
            val value = CognitionTransactionCodec.decode(photon.content)
            requireIdentity(photon, CognitionJournalKind.TRANSACTION, value.transactionId)
        }
        val outcomeCount = verifyKind(CognitionJournalKind.OUTCOME) { photon ->
            val value = CognitionOutcomeCodec.decode(photon.content)
            val normalized = value.copy(recordedAt = Instant.EPOCH)
            val key = StableCognitiveIds.fingerprint(
                "cognition-outcome/v2",
                value.taskId.value,
                CognitionOutcomeCodec.encode(normalized),
            )
            requireIdentity(photon, CognitionJournalKind.OUTCOME, key)
        }
        val triggerCount = verifyKind(CognitionJournalKind.TRIGGER) { photon ->
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

    private suspend fun verifyKind(
        kind: CognitionJournalKind,
        verifyPhoton: (Photon) -> Unit,
    ): Int {
        val photons = loadCognitionJournalPhotons(repository, kind)
        photons.forEach { photon ->
            try {
                require(photon.mimeType == COGNITION_JOURNAL_MIME) { "Invalid cognition journal MIME" }
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
    ) {
        val expected = CognitionJournalIdentity.photonId(kind.tag, stableId)
        require(photon.id == expected) {
            "Cognition journal identity mismatch: expected ${expected.value}, found ${photon.id.value}"
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
