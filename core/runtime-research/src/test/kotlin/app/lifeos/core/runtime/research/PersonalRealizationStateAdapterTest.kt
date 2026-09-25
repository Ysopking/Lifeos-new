package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.reasoning.RealizationComponentKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalRealizationStateAdapterTest {
    private val t0 = Instant.parse("2026-09-25T13:00:00Z")

    @Test
    fun `adapter binds owner personal context without granting authority`() {
        val snapshot = snapshot()
        val component = PersonalRealizationStateAdapter.component(snapshot)

        assertEquals(RealizationComponentKind.PERSONAL_CONTEXT, component.kind)
        assertEquals(
            "owner-personal-context:${snapshot.fingerprint}",
            component.representationId,
        )
        assertEquals(snapshot.verifiedOutcomeFingerprints, component.provenanceFingerprints)
        assertTrue(component.semanticFingerprint.isNotBlank())
    }

    @Test
    fun `adapting same frozen snapshot is deterministic`() {
        val snapshot = snapshot()

        assertEquals(
            PersonalRealizationStateAdapter.component(snapshot),
            PersonalRealizationStateAdapter.component(snapshot),
        )
    }

    @Test
    fun `source owner context remains non authoritative`() {
        val snapshot = snapshot()

        assertFalse(snapshot.factualWorldAuthority)
        assertFalse(snapshot.policyAuthority)
        assertFalse(snapshot.executionAuthority)
    }

    private fun snapshot(): OwnerPersonalContextSnapshot {
        val world = PersonalWorldMaterializer().materialize(
            semanticProjections = emptyList(),
            temporalEpisodes = emptyList(),
            asOf = t0,
            revision = 1L,
        )
        val objective = OwnerObjectiveSnapshot.create(
            activeGoalPlanIds = emptyList(),
            objectiveFingerprints = emptyList(),
            constraintFingerprints = emptyList(),
            asOf = t0,
        )
        val agency = OwnerAgencySnapshot.create(
            objective = objective,
            personalWorld = world,
            signals = emptyList(),
            asOf = t0,
        )
        val subjective = SubjectiveStateHypothesis.create(
            personalWorld = world,
            ownerAgency = agency,
            evidence = emptyList(),
            asOf = t0,
        )
        return OwnerPersonalContextSnapshot.create(
            personalWorld = world,
            objective = objective,
            agency = agency,
            subjectiveState = subjective,
            verifiedOutcomes = emptyList(),
            asOf = t0,
        )
    }
}
