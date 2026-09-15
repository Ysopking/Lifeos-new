package app.lifeos.core.runtime.module

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.runtime.informationasset.InformationAssetFingerprints
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ModuleEpistemicStateTest {
    private val domain = StableFieldIds.domain("science")
    private val at = Instant.parse("2026-09-15T12:00:00Z")
    private val identity = ModuleIdentity(
        moduleId = "science",
        version = "1",
        implementationHash = "science-impl-v1",
        capabilityIds = setOf("research"),
    )

    @Test
    fun `architectural authority floor cannot be learned away`() {
        val profile = ModuleAuthorityProfile(
            architecturalFloor = 0.70,
            architecturalBaseline = 0.80,
            architecturalCeiling = 0.95,
            learnedAdjustment = -0.20,
            maxLearnedAdjustment = 0.20,
            learningEvidenceFingerprints = setOf("outcome-1"),
        )

        assertEquals(0.70, profile.effectiveAuthority)
    }

    @Test
    fun `exact information asset revision changes module knowledge fingerprint`() {
        val first = initialState(revisionDigest = "a".repeat(64))
        val second = initialState(revisionDigest = "b".repeat(64))

        assertNotEquals(first.stateFingerprint, second.stateFingerprint)
        assertNotEquals(
            first.knowledge.single().bindings.single().stableKey,
            second.knowledge.single().bindings.single().stableKey,
        )
    }

    @Test
    fun `next revision binds exact predecessor`() {
        val first = initialState(revisionDigest = "a".repeat(64))
        val second = first.next(
            currentConfidence = 0.91,
            updatedAt = at.plusSeconds(60),
        )

        assertEquals(2L, second.revision)
        assertEquals(first.stateFingerprint, second.previousStateFingerprint)
        assertEquals(first.identity, second.identity)
        assertNotEquals(first.stateFingerprint, second.stateFingerprint)
    }

    @Test
    fun `bounded codec restores exact module epistemic state`() {
        val state = initialState(revisionDigest = "c".repeat(64))

        val decoded = ModuleEpistemicStateCodec.decode(ModuleEpistemicStateCodec.encode(state))

        assertEquals(state, decoded)
        assertEquals(state.stateFingerprint, decoded.stateFingerprint)
    }

    @Test
    fun `experience grows without becoming unbounded`() {
        val low = expertise(outcomes = 1)
        val high = expertise(outcomes = 1_000)

        assertTrue(high.experienceDepth > low.experienceDepth)
        assertTrue(high.experienceDepth < 1.0)
    }

    private fun initialState(revisionDigest: String): ModuleEpistemicState {
        val assetId = InformationAssetFingerprints.asset("module-test", "science-knowledge")
        val binding = ModuleKnowledgeBinding(
            domainId = domain,
            semanticKey = "evidence.synthesis",
            assetId = assetId,
            assetRevisionId = InformationAssetRevisionId(revisionDigest),
            assetStateHash = "state-$revisionDigest",
            confidence = 0.90,
            observedAt = at,
        )
        return ModuleEpistemicState.initial(
            identity = identity,
            authority = ModuleAuthorityProfile(
                architecturalFloor = 0.60,
                architecturalBaseline = 0.75,
                architecturalCeiling = 0.95,
            ),
            knowledge = listOf(
                ModuleDomainKnowledgeState(
                    domainId = domain,
                    knowledgeDepth = 0.80,
                    evidenceCoverage = 0.70,
                    confidence = 0.90,
                    bindings = listOf(binding),
                )
            ),
            expertise = listOf(expertise(outcomes = 10)),
            currentConfidence = 0.85,
            resourceProfile = ModuleResourceProfile(
                cpuIntensity = 0.40,
                memoryIntensity = 0.30,
                ioIntensity = 0.20,
                networkIntensity = 0.10,
                thermalIntensity = 0.25,
                maxParallelism = 3,
            ),
            updatedAt = at,
        )
    }

    private fun expertise(outcomes: Long): ModuleExpertiseState = ModuleExpertiseState(
        domainId = domain,
        baselineExpertise = 0.75,
        learnedAdjustment = 0.05,
        maxLearnedAdjustment = 0.20,
        calibration = 0.90,
        observedOutcomes = outcomes,
        successfulOutcomeFraction = 0.80,
        learningEvidenceFingerprints = setOf("outcome-evidence-$outcomes"),
    )
}
