package app.lifeos.core.runtime.personal

import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageLearningFeedbackKind
import app.lifeos.core.language.LanguageOwnerFeedback
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.language.LinguisticLexiconSnapshot
import app.lifeos.core.language.VersionedLanguageRuntime
import app.lifeos.core.language.WorldGroundedLanguageLearningEvidence
import app.lifeos.core.model.StableCognitiveIds
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LanguageOutcomeLearningGoldTest {
    @Test
    fun ownerConfirmedCandidatePromotesThroughExistingShadowGateAndRollsBackExactly() = runTest {
        val repository = InMemoryLanguageRuntimeStateRepository()
        val languageRuntime = VersionedLanguageRuntime()
        val durable = DurableLanguageRuntimeCoordinator(
            languageRuntime,
            repository,
        )
        val baseline = durable.rehydrate().lexicon
        val target = baseline.concepts.first {
            IntentType.CONTINUE in it.intentBias
        }

        val candidate = PersonalLanguageCandidate.create(
            surface = "weiterso",
            targetConceptId = target.id,
            observations = (1..5).map { index ->
                PersonalLanguageObservation(
                    conversationId = "conversation-$index",
                    surface = "weiterso",
                    targetConceptId = target.id,
                    accepted = true,
                    sourceRef = null,
                )
            },
        )

        val understanding = LanguageUnderstandingEngine().understand("weiter")
        val ownerEvidence =
            WorldGroundedLanguageLearningEvidence.ownerFeedback(
                result = understanding,
                feedback = LanguageOwnerFeedback.create(
                    kind = LanguageLearningFeedbackKind.OWNER_CONFIRMATION,
                    sourceFingerprint = StableCognitiveIds.fingerprint(
                        "b476-owner-confirmation/v1",
                        "weiterso",
                    ),
                ),
                sourceCycleId = "gold-cycle",
            )
        val guarded = OutcomeGuardedPersonalLanguagePromotionCoordinator(
            DurablePersonalLanguagePromotionCoordinator(durable)
        )

        val result = guarded.promote(
            candidate = candidate,
            evidence = listOf(
                PersonalLanguageOutcomeEvidenceBinding.bind(
                    candidate,
                    ownerEvidence,
                )
            ),
        )

        val promoted = assertIs<OutcomeGuardedPersonalLanguagePromotionResult.Promoted>(
            result
        )
        assertTrue(promoted.evidenceFingerprint.isNotBlank())
        val promotedSnapshot = promoted.delegate.snapshot
        assertNotEquals(baseline.fingerprint, promotedSnapshot.fingerprint)
        assertEquals(
            IntentType.CONTINUE,
            languageRuntime.current().understanding
                .understand("weiterso")
                .goal.intent,
        )

        val rolledBack = durable.rollback(baseline.fingerprint)
        assertEquals(baseline.fingerprint, rolledBack.lexicon.fingerprint)
        assertEquals(
            promotedSnapshot,
            repository.loadSnapshot(promotedSnapshot.fingerprint),
        )
    }

    @Test
    fun negativeOwnerEvidenceVetoesPromotionBeforeDurableHeadMoves() = runTest {
        val repository = InMemoryLanguageRuntimeStateRepository()
        val runtime = VersionedLanguageRuntime()
        val durable = DurableLanguageRuntimeCoordinator(runtime, repository)
        val baseline = durable.rehydrate().lexicon
        val target = baseline.concepts.first {
            IntentType.CONTINUE in it.intentBias
        }
        val candidate = PersonalLanguageCandidate.create(
            surface = "weiterso",
            targetConceptId = target.id,
            observations = (1..5).map { index ->
                PersonalLanguageObservation(
                    conversationId = "c$index",
                    surface = "weiterso",
                    targetConceptId = target.id,
                    accepted = true,
                    sourceRef = null,
                )
            },
        )
        val understanding = LanguageUnderstandingEngine().understand("weiter")
        val confirmation = WorldGroundedLanguageLearningEvidence.ownerFeedback(
            understanding,
            LanguageOwnerFeedback.create(
                kind = LanguageLearningFeedbackKind.OWNER_CONFIRMATION,
                sourceFingerprint = "a".repeat(64),
            ),
            "cycle-positive",
        )
        val rejection = WorldGroundedLanguageLearningEvidence.ownerFeedback(
            understanding,
            LanguageOwnerFeedback.create(
                kind = LanguageLearningFeedbackKind.OWNER_REJECTION,
                sourceFingerprint = "b".repeat(64),
            ),
            "cycle-negative",
        )

        val result = OutcomeGuardedPersonalLanguagePromotionCoordinator(
            DurablePersonalLanguagePromotionCoordinator(durable)
        ).promote(
            candidate,
            listOf(
                PersonalLanguageOutcomeEvidenceBinding.bind(candidate, confirmation),
                PersonalLanguageOutcomeEvidenceBinding.bind(candidate, rejection),
            ),
        )

        val rejected =
            assertIs<OutcomeGuardedPersonalLanguagePromotionResult.Rejected>(result)
        assertEquals("negative-owner-evidence", rejected.reason)
        assertEquals(baseline.fingerprint, durable.current().lexicon.fingerprint)
        assertEquals(1L, repository.loadHead()?.headRevision)
    }

    private class InMemoryLanguageRuntimeStateRepository :
        LanguageRuntimeStateRepository {
        private val snapshots =
            linkedMapOf<String, LinguisticLexiconSnapshot>()
        private var head: DurableLanguageRuntimeHead? = null

        override suspend fun saveSnapshot(snapshot: LinguisticLexiconSnapshot) {
            val existing = snapshots[snapshot.fingerprint]
            if (existing == null) snapshots[snapshot.fingerprint] = snapshot
            else require(existing == snapshot)
        }

        override suspend fun loadSnapshot(
            fingerprint: String,
        ): LinguisticLexiconSnapshot? = snapshots[fingerprint]

        override suspend fun loadHead(): DurableLanguageRuntimeHead? = head

        override suspend fun compareAndSetHead(
            expectedRevision: Long?,
            next: DurableLanguageRuntimeHead,
        ): Boolean {
            if (head?.headRevision != expectedRevision) return false
            require(next.headRevision == (expectedRevision ?: 0L) + 1L)
            require(
                next.previousActiveSnapshotFingerprint ==
                    head?.activeSnapshotFingerprint
            )
            requireNotNull(snapshots[next.activeSnapshotFingerprint])
            head = next
            return true
        }
    }
}
