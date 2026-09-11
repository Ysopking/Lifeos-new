package app.lifeos.core.runtime.capability

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutonomousToolWorkshopPhotonTest {
    @Test
    fun `autonomous request id is deterministic and safe for encrypted photon vault`() {
        val source = sourcePhoton()
        val gap = missingGap()

        val first = AutonomousToolWorkshopRequestPhoton.create(gap, source)
        val second = AutonomousToolWorkshopRequestPhoton.create(gap, source)

        assertEquals(first, second)
        assertTrue(first.first.id.value.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        assertFalse(':' in first.first.id.value)
        assertEquals(first.second, AutonomousToolWorkshopRequestPhoton.decode(first.first, source))
        assertFalse(first.second.activationAllowed)
    }

    @Test
    fun `trial ready outcome is deterministic non activating evolution handoff`() {
        val source = sourcePhoton()
        val (_, request) = AutonomousToolWorkshopRequestPhoton.create(missingGap(), source)
        val definition = ToolWorkshopJobDefinition.fromRequest(
            request = request,
            sourceRevision = source.revision,
            policyVersion = "policy-v14",
            workshopVersion = "workshop-v11",
        )
        val snapshot = ToolWorkshopJobSnapshot(
            definition = definition,
            state = ToolWorkshopJobState.TRIAL_READY,
            stageFingerprint = "trial-ready-fingerprint",
            ledgerRevision = 9L,
        )

        val first = ToolWorkshopOutcomePhoton.create(snapshot)
        val second = ToolWorkshopOutcomePhoton.create(snapshot)

        assertEquals(first, second)
        assertEquals(ToolWorkshopEvolutionRoute.NOVEL_CANARY, ToolWorkshopOutcomePhoton.route(snapshot))
        assertTrue("evolution-handoff" in first.tags)
        assertTrue("non-activating" in first.tags)
        assertFalse("active" in first.tags)
        assertTrue(first.id.value.matches(Regex("[A-Za-z0-9_-]{1,128}")))
    }

    @Test
    fun `rejected workshop outcome becomes learning evidence not activation handoff`() {
        val source = sourcePhoton()
        val (_, request) = AutonomousToolWorkshopRequestPhoton.create(missingGap(), source)
        val definition = ToolWorkshopJobDefinition.fromRequest(
            request = request,
            sourceRevision = source.revision,
            policyVersion = "policy-v14",
            workshopVersion = "workshop-v11",
        )
        val snapshot = ToolWorkshopJobSnapshot(
            definition = definition,
            state = ToolWorkshopJobState.REJECTED,
            lastDetail = "security-rejected",
            ledgerRevision = 4L,
        )

        val photon = ToolWorkshopOutcomePhoton.create(snapshot)

        assertEquals(ToolWorkshopEvolutionRoute.NONE, ToolWorkshopOutcomePhoton.route(snapshot))
        assertTrue("learning-evidence" in photon.tags)
        assertFalse("evolution-handoff" in photon.tags)
    }

    private fun sourcePhoton() = Photon(
        id = PhotonId("source_goal_1"),
        revision = 3L,
        content = "normalize this text",
        provenance = Provenance(
            source = "test",
            actor = "user",
            createdAt = NOW,
        ),
        tags = setOf("chat"),
    )

    private fun missingGap() = CapabilityGap(
        requirement = CapabilityRequirement(
            capabilityId = CapabilityId("text.normalize.custom"),
            severity = GapSeverity.BLOCKING,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("text"),
        ),
        type = CapabilityGapType.CAPABILITY_MISSING,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T12:00:00Z")
    }
}
