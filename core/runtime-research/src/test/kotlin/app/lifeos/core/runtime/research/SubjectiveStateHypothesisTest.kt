package app.lifeos.core.runtime.research

import app.lifeos.core.field.StableFieldIds
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubjectiveStateHypothesisTest {
    @Test
    fun ownerConfirmedStateOverridesStrongerDerivedInference() {
        val world = PersonalWorldSnapshot(
            revision = 1L,
            asOf = NOW,
            financialStateFingerprint = null,
            relationshipStateFingerprint = null,
            conversationStateFingerprint = null,
            lifeGraphFingerprint = "life",
            sourceEvidenceIds = emptyList(),
            temporalEpisodeIds = emptyList(),
            fingerprint = StableFieldIds.fingerprint(
                "personal-world-snapshot/v1",
                "1",
                NOW.toString(),
                "",
                "",
                "",
                "life",
            ),
        )
        val objective = OwnerObjectiveSnapshot.create(
            activeGoalPlanIds = emptyList(),
            objectiveFingerprints = emptyList(),
            constraintFingerprints = emptyList(),
            asOf = NOW,
        )
        val agency = OwnerAgencySnapshot.create(
            objective = objective,
            personalWorld = world,
            signals = emptyList(),
            asOf = NOW,
        )
        val derived = SubjectiveStateEvidence(
            dimension = SubjectiveStateDimension.PERCEIVED_THREAT,
            value = 0.9,
            confidence = 0.99,
            kind = SubjectiveStateEvidenceKind.DERIVED,
            sourceEvidenceIds = listOf("derived"),
        )
        val owner = SubjectiveStateEvidence(
            dimension = SubjectiveStateDimension.PERCEIVED_THREAT,
            value = 0.2,
            confidence = 0.6,
            kind = SubjectiveStateEvidenceKind.OWNER_CONFIRMED,
            sourceEvidenceIds = listOf("owner"),
        )

        val hypothesis = SeinEvidenceIntegrator().infer(
            personalWorld = world,
            ownerAgency = agency,
            evidence = listOf(derived, owner),
            asOf = NOW,
        )

        assertEquals(
            0.2,
            hypothesis.values.getValue(SubjectiveStateDimension.PERCEIVED_THREAT),
        )
        assertTrue(hypothesis.ownerCorrectionFingerprints.isNotEmpty())
        assertFalse(hypothesis.factualWorldAuthority)
        assertFalse(hypothesis.diagnosticAuthority)
        assertFalse(hypothesis.effectAuthority)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T07:00:00Z")
    }
}
