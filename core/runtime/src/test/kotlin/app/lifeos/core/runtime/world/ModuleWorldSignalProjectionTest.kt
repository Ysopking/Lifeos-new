package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.runtime.informationasset.InformationAssetFingerprints
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionId
import app.lifeos.core.runtime.module.ModuleAuthorityProfile
import app.lifeos.core.runtime.module.ModuleDomainKnowledgeState
import app.lifeos.core.runtime.module.ModuleEpistemicState
import app.lifeos.core.runtime.module.ModuleExpertiseState
import app.lifeos.core.runtime.module.ModuleKnowledgeBinding
import app.lifeos.core.runtime.module.ModuleResourceProfile
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleWorldSignalProjectionTest {
    private val domain = StableFieldIds.domain("legal")
    private val at = Instant.parse("2026-09-15T12:00:00Z")

    @Test
    fun `module state projects separate authority knowledge expertise and calibration dimensions`() {
        val state = state()
        val projection = ModuleWorldSignalProjector().project(state, domain)

        assertEquals(WorldNodeKind.MODULE, projection.target.kind)
        assertEquals(state.authority.effectiveAuthority, projection.vector[WorldSignalDimension.AUTHORITY]?.value)
        assertEquals(0.70, projection.vector[WorldSignalDimension.KNOWLEDGE_STRENGTH]?.value)
        assertEquals(0.85, projection.vector[WorldSignalDimension.EXPERTISE]?.value)
        assertEquals(0.90, projection.vector[WorldSignalDimension.CALIBRATION]?.value)
        assertNotNull(projection.vector[WorldSignalDimension.EXPERIENCE_DEPTH])
        assertEquals(0.88, projection.vector[WorldSignalDimension.MODULE_CONFIDENCE]?.value)
        assertTrue(projection.sourceSnapshotFingerprint.isNotBlank())
    }

    @Test
    fun `unknown domain stays sparse rather than inventing knowledge`() {
        val projection = ModuleWorldSignalProjector().project(
            state(),
            StableFieldIds.domain("unknown-domain"),
        )

        assertNotNull(projection.vector[WorldSignalDimension.AUTHORITY])
        assertNotNull(projection.vector[WorldSignalDimension.MODULE_CONFIDENCE])
        assertNull(projection.vector[WorldSignalDimension.KNOWLEDGE_STRENGTH])
        assertNull(projection.vector[WorldSignalDimension.EXPERTISE])
        assertNull(projection.vector[WorldSignalDimension.CALIBRATION])
    }

    private fun state(): ModuleEpistemicState {
        val binding = ModuleKnowledgeBinding(
            domainId = domain,
            semanticKey = "legal.norm",
            assetId = InformationAssetFingerprints.asset("module-world-test", "legal"),
            assetRevisionId = InformationAssetRevisionId("d".repeat(64)),
            assetStateHash = "legal-state-hash",
            confidence = 0.95,
            observedAt = at,
        )
        return ModuleEpistemicState.initial(
            identity = ModuleIdentity(
                moduleId = "legal",
                version = "1",
                implementationHash = "legal-impl",
            ),
            authority = ModuleAuthorityProfile(
                architecturalFloor = 0.70,
                architecturalBaseline = 0.80,
                architecturalCeiling = 0.95,
            ),
            knowledge = listOf(
                ModuleDomainKnowledgeState(
                    domainId = domain,
                    knowledgeDepth = 0.80,
                    evidenceCoverage = 0.70,
                    confidence = 0.95,
                    bindings = listOf(binding),
                )
            ),
            expertise = listOf(
                ModuleExpertiseState(
                    domainId = domain,
                    baselineExpertise = 0.80,
                    learnedAdjustment = 0.05,
                    maxLearnedAdjustment = 0.20,
                    calibration = 0.90,
                    observedOutcomes = 64,
                    successfulOutcomeFraction = 0.85,
                    learningEvidenceFingerprints = setOf("legal-outcome-history"),
                )
            ),
            currentConfidence = 0.88,
            resourceProfile = ModuleResourceProfile.LIGHT,
            updatedAt = at,
        )
    }
}
