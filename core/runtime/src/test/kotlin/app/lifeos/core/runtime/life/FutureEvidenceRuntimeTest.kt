package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FutureEvidenceRuntimeTest {
    @Test
    fun deadlineProducesPressureAndPreemptiveCandidate() {
        val photon = Photon(
            id = PhotonId("deadline-fact"),
            content = "kind=DEADLINE\nvalue=18.09.2026\nconfidence=0.8\nevidence=Frist",
            mimeType = "application/vnd.lifeos.domain-fact+text",
            provenance = Provenance("test", "lifeos"),
            tags = setOf("structured-domain-evidence", "fact:deadline"),
        )

        val scenarios = FutureEvidenceEngine().project(photon)

        assertEquals(2, scenarios.size)
        assertTrue(scenarios.any { it.type == FutureScenarioType.INACTION_PRESSURE && !it.allowed })
        assertTrue(scenarios.any { it.type == FutureScenarioType.PREEMPTIVE_ACTION && it.allowed })
    }

    @Test
    fun onlyAdmissibleFutureEvidenceBecomesPlannerCandidate() {
        val photon = Photon(
            id = PhotonId("debt-fact"),
            content = "kind=DEBT\nvalue=Mahnung\nconfidence=0.8\nevidence=Mahnung",
            mimeType = "application/vnd.lifeos.domain-fact+text",
            provenance = Provenance("test", "lifeos"),
            tags = setOf("structured-domain-evidence", "fact:debt"),
        )
        val candidates = FutureEvidenceEngine().plannerCandidates(listOf(photon))

        assertEquals(1, candidates.size)
        assertTrue(candidates.single().allowed)
        assertFalse(candidates.single().stateDelta.isEmpty())
    }

    @Test
    fun sameEvidenceProducesStableScenarioIdentity() {
        val photon = Photon(
            id = PhotonId("opportunity-fact"),
            content = "kind=BUSINESS_OPPORTUNITY\nvalue=Kunde\nconfidence=0.7\nevidence=Kunde",
            mimeType = "application/vnd.lifeos.domain-fact+text",
            provenance = Provenance("test", "lifeos"),
        )
        val engine = FutureEvidenceEngine()

        assertEquals(engine.project(photon), engine.project(photon))
    }
}
