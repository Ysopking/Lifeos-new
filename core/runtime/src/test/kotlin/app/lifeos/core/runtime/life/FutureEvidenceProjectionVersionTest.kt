package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FutureEvidenceProjectionVersionTest {
    @Test
    fun projectionVersionIsPartOfScenarioIdentity() {
        val photon = Photon(
            id = PhotonId("versioned-opportunity-fact"),
            content = "kind=BUSINESS_OPPORTUNITY\nvalue=Kunde\nconfidence=0.7\nevidence=Kunde",
            mimeType = "application/vnd.lifeos.domain-fact+text",
            provenance = Provenance("test", "lifeos"),
        )

        val v2 = FutureEvidenceEngine("future-evidence/v2").project(photon).single()
        val v3 = FutureEvidenceEngine("future-evidence/v3").project(photon).single()

        assertNotEquals(v2.id, v3.id)
        assertEquals("future-evidence/v2", v2.projectionVersion)
        assertEquals("future-evidence/v3", v3.projectionVersion)
    }
}
