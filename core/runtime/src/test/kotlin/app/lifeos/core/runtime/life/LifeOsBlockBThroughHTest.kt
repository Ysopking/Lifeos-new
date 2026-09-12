package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.CausalCognitionEngine
import app.lifeos.core.runtime.capability.CapabilityId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifeOsBlockBThroughHTest {
    @Test
    fun perceptionFusionIsDeterministic() {
        val signal = PerceptionSignal(
            source = PerceptionSource.CHAT,
            sourceId = "user",
            observedAt = Instant.EPOCH,
            payload = "hello",
            tags = setOf("question"),
        )
        val engine = PerceptionFusionEngine()
        assertEquals(engine.fuse(listOf(signal)), engine.fuse(listOf(signal, signal)))
    }

    @Test
    fun lifeMemoryRebuildUsesLatestRevisionAndStableTiers() {
        val older = Photon(
            id = PhotonId("p"),
            revision = 1,
            content = "old",
            semanticMass = 0.1,
            provenance = Provenance("test", "user", Instant.EPOCH),
        )
        val latest = older.copy(revision = 2, content = "new", tags = setOf("goal"))
        val report = BootLifeMemoryRehydrator().rehydrate(listOf(older, latest))
        assertEquals(1, report.snapshot.entries.size)
        assertEquals(2, report.snapshot.entries.single().revision)
        assertEquals(LifeMemoryTier.HOT, report.snapshot.entries.single().tier)
    }

    @Test
    fun lifePlannerRejectsDisallowedAndRanksSeinImprovement() {
        val current = LifeStateVector(
            mapOf(
                SeinDimension.SELF_ALIGNMENT to 0.5,
                SeinDimension.PRESENT_COHERENCE to 0.5,
                SeinDimension.AGENCY to 0.5,
                SeinDimension.CONTINUITY to 0.5,
                SeinDimension.FRICTION to 0.5,
            )
        )
        val improve = FutureDeltaCandidate(
            id = "improve",
            stateDelta = mapOf(SeinDimension.SELF_ALIGNMENT to 0.2, SeinDimension.FRICTION to -0.2),
            resourceCost = 0.2,
            allowed = true,
            explanation = "closer to target",
        )
        val blocked = improve.copy(id = "blocked", allowed = false)
        val ranked = LifePlanner().rank(current, listOf(blocked, improve))
        assertEquals(listOf("improve"), ranked.map { it.candidate.id })
        assertTrue(ranked.single().expectedImprovement > 0.0)
    }

    @Test
    fun legalDomainModuleProducesDerivedAnalysisPhoton() = runTest {
        val source = Photon(
            id = PhotonId("legal-root"),
            content = "contract question",
            tags = setOf("legal", "contract"),
            provenance = Provenance("test", "user", Instant.EPOCH),
        )
        val result = CausalCognitionEngine().process(source, listOf(DomainCognitionModules.legal()))
        assertFalse(result.replayed)
        assertTrue(result.emittedPhotons.any { it.mimeType == "application/vnd.lifeos.domain-note+text" })
    }

    @Test
    fun creativeExpansionNeverActivatesGeneratedCandidateDirectly() {
        val plan = CreativeCapabilityOrchestrator().plan(
            CreativeCapabilityRequest(
                capabilityId = CapabilityId("creative.missing"),
                description = "new private capability",
                existingProviderReady = false,
                sourceCanBeGeneratedLocally = false,
            )
        )
        assertFalse(plan.activationAllowed)
        assertTrue(CapabilityExpansionStage.BUILDSTUDIO_CANDIDATE in plan.stages)
        assertEquals(CapabilityExpansionStage.OWNER_PROMOTION, plan.stages.last())
    }

    @Test
    fun memoryIntegrityDetectsMissingCausalParentWithoutDeletingAnything() {
        val photon = Photon(
            id = PhotonId("child"),
            content = "child",
            provenance = Provenance(
                source = "test",
                actor = "lifeos",
                createdAt = Instant.EPOCH,
                parentIds = setOf(PhotonId("missing")),
            ),
        )
        val report = MemoryIntegrityVerifier().verify(listOf(photon))
        assertFalse(report.healthy)
        assertTrue(report.issues.single().message.startsWith("missing-parent:"))
        assertEquals(RetentionClass.ARCHIVE, CognitiveMemoryCompactor().plan(listOf(photon)).decisions.single().retentionClass)
    }

    @Test
    fun hReadinessRequiresEveryBlockAndAllChaosProbesContained() {
        val readiness = LifeOsCompletionReadiness().snapshot(
            LifeOsBlock.entries.associateWith { ReadinessState.READY },
            LifeOsBlock.entries.associateWith { "verified" },
        )
        assertTrue(readiness.complete)
        val chaos = LifeOsChaosVerifier().verify(
            ChaosScenario.entries.map { ChaosProbeResult(it, contained = true, detail = "contained") }
        )
        assertTrue(chaos.passed)
    }
}
