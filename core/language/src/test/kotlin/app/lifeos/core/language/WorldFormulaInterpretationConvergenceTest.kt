package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorldFormulaInterpretationConvergenceTest {
    @Test
    fun noWorldEvidencePreservesLanguageOnlyCandidateShape() {
        val first = LanguageUnderstandingEngine().understand(
            "Suche nach dem Dokument."
        )
        val lattice = first.goal.interpretationLattice

        assertEquals(null, lattice.worldEvidenceFingerprint)
        assertFalse(lattice.unresolvedDueToWorldState)
        assertTrue(lattice.candidates.all { it.worldSupport == 0.0 })
        assertTrue(lattice.candidates.all { it.worldConflict == 0.0 })
        assertTrue(lattice.candidates.all { it.worldStateSufficient })
    }

    @Test
    fun worldConflictBlocksOtherwiseLeadingInterpretation() {
        val engine = LanguageUnderstandingEngine()
        val initial = engine.understand(
            "Suche nach dem Dokument.",
            LanguageContext(),
        )
        val leading = assertNotNull(initial.goal.interpretationLattice.winner)
        val evidence = LanguageWorldInterpretationEvidence(
            intent = leading.intent,
            reference = leading.reference,
            support = 0.0,
            contradiction = 1.0,
            stateSufficient = true,
            sourceFingerprint = StableCognitiveIds.fingerprint(
                "world-test-evidence/v1",
                "conflict",
            ),
        )

        val refined = engine.understand(
            "Suche nach dem Dokument.",
            LanguageContext(),
            listOf(evidence),
        )
        val candidate = refined.goal.interpretationLattice.candidates
            .first { it.intent == leading.intent && it.reference == leading.reference }

        assertEquals(1.0, candidate.worldConflict)
        assertTrue("world-contradiction" in candidate.blockers)
        assertTrue(refined.goal.interpretationLattice.unresolvedDueToWorldState)
        assertFalse(refined.goal.interpretationLattice.converged)
        assertTrue(
            refined.goal.ambiguities.any { it.code == "world_evidence_conflict" }
        )
    }

    @Test
    fun insufficientWorldStateRemainsUnresolvedInsteadOfForcedWinner() {
        val engine = LanguageUnderstandingEngine()
        val initial = engine.understand(
            "Suche nach dem Dokument.",
            LanguageContext(),
        )
        val leading = assertNotNull(initial.goal.interpretationLattice.winner)

        val refined = engine.understand(
            "Suche nach dem Dokument.",
            LanguageContext(),
            listOf(
                LanguageWorldInterpretationEvidence(
                    intent = leading.intent,
                    reference = leading.reference,
                    support = 0.9,
                    contradiction = 0.0,
                    stateSufficient = false,
                    sourceFingerprint = StableCognitiveIds.fingerprint(
                        "world-test-evidence/v1",
                        "insufficient",
                    ),
                )
            ),
        )

        val winner = assertNotNull(refined.goal.interpretationLattice.winner)
        assertTrue("world-state-insufficient" in winner.blockers)
        assertFalse(refined.goal.interpretationLattice.converged)
        assertTrue(
            refined.goal.ambiguities.any { it.code == "world_state_insufficient" }
        )
    }

    @Test
    fun worldEvidenceOrderDoesNotChangeConvergence() {
        val engine = LanguageUnderstandingEngine()
        val initial = engine.understand("Suche nach dem Dokument.", LanguageContext())
        val leading = assertNotNull(initial.goal.interpretationLattice.winner)
        val a = LanguageWorldInterpretationEvidence(
            intent = leading.intent,
            reference = null,
            support = 0.4,
            contradiction = 0.1,
            stateSufficient = true,
            sourceFingerprint = StableCognitiveIds.fingerprint("world-test", "a"),
        )
        val b = LanguageWorldInterpretationEvidence(
            intent = leading.intent,
            reference = leading.reference,
            support = 0.6,
            contradiction = 0.2,
            stateSufficient = true,
            sourceFingerprint = StableCognitiveIds.fingerprint("world-test", "b"),
        )

        val first = engine.understand(
            "Suche nach dem Dokument.",
            LanguageContext(),
            listOf(a, b),
        ).goal.interpretationLattice
        val second = engine.understand(
            "Suche nach dem Dokument.",
            LanguageContext(),
            listOf(b, a),
        ).goal.interpretationLattice

        assertEquals(first, second)
        assertEquals(first.worldEvidenceFingerprint, second.worldEvidenceFingerprint)
    }
}
