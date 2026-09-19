package app.lifeos.core.runtime.informationasset.project

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectSemanticKeysM208Test {
    @Test
    fun crossSourceReferencesRemainOptionalAndBlockerIsOperationallyValidated() {
        val optional = setOf(
            ProjectSemanticKeys.DECISION,
            ProjectSemanticKeys.REQUIREMENT,
            ProjectSemanticKeys.DOCUMENT,
            ProjectSemanticKeys.CONVERSATION,
            ProjectSemanticKeys.REPOSITORY,
            ProjectSemanticKeys.ISSUE,
            ProjectSemanticKeys.PARTICIPANT,
            ProjectSemanticKeys.EVENT,
            ProjectSemanticKeys.ARTIFACT,
            ProjectSemanticKeys.OUTPUT,
            ProjectSemanticKeys.BLOCKER,
        )
        assertTrue(optional.none(ProjectSemanticKeys.CORE::contains))
        assertTrue(ProjectSemanticKeys.BLOCKER in ProjectInformationAssetValidationProfile.EVIDENCE_CRITICAL_KEYS)
        assertFalse(ProjectSemanticKeys.DOCUMENT in ProjectInformationAssetValidationProfile.EVIDENCE_CRITICAL_KEYS)
    }
}
