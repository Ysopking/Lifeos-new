package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.CausalCognitionEngine
import app.lifeos.core.runtime.capability.CapabilityId
import java.time.Instant

/** Deterministic in-process validation used by Block H before Product Gold evidence is trusted. */
class LifeOsSelfValidation(
    private val suite: LifeOsIntegratedCognitionSuite,
) {
    suspend fun validate(): Pair<LifeOsReadinessSnapshot, ChaosVerificationReport> {
        val states = linkedMapOf<LifeOsBlock, ReadinessState>()
        val details = linkedMapOf<LifeOsBlock, String>()

        fun record(block: LifeOsBlock, detail: String, check: () -> Boolean) {
            val ok = runCatching(check).getOrDefault(false)
            states[block] = if (ok) ReadinessState.READY else ReadinessState.BLOCKED
            details[block] = detail
        }

        record(LifeOsBlock.A, "causal-runtime-contract") {
            suite.domainModules.map { it.descriptor.identity.stableFingerprint }.distinct().size == suite.domainModules.size
        }
        record(LifeOsBlock.B, "deterministic-perception-fusion") {
            val signal = PerceptionSignal(
                PerceptionSource.APP_EVENT,
                "self-test",
                Instant.EPOCH,
                "perception",
            )
            suite.perception.fuse(listOf(signal)).batchFingerprint == suite.perception.fuse(listOf(signal, signal)).batchFingerprint
        }
        record(LifeOsBlock.C, "boot-life-memory-rebuild") {
            val photon = Photon(
                id = PhotonId("self-memory"),
                content = "memory",
                provenance = Provenance("self-test", "lifeos", Instant.EPOCH),
            )
            suite.lifeMemory.rehydrate(listOf(photon)).snapshot.contains(photon.id)
        }
        record(LifeOsBlock.D, "sein-future-delta-ranking") {
            val state = LifeStateVector(
                SeinDimension.entries.associateWith { if (it == SeinDimension.FRICTION) 0.5 else 0.5 }
            )
            val candidate = FutureDeltaCandidate(
                id = "self-improve",
                stateDelta = mapOf(SeinDimension.SELF_ALIGNMENT to 0.1, SeinDimension.FRICTION to -0.1),
                resourceCost = 0.1,
                allowed = true,
                explanation = "self-validation",
            )
            suite.lifePlanner.rank(state, listOf(candidate)).single().expectedImprovement > 0.0
        }
        record(LifeOsBlock.E, "domain-module-inventory") {
            suite.domainModules.map { it.descriptor.identity.moduleId }.toSet().containsAll(
                setOf("domain.curiosity", "domain.legal", "domain.debt", "domain.business-advisory")
            )
        }
        record(LifeOsBlock.F, "guarded-capability-expansion") {
            val plan = suite.creativeCapabilities.plan(
                CreativeCapabilityRequest(
                    capabilityId = CapabilityId("self.missing"),
                    description = "self validation gap",
                    existingProviderReady = false,
                    sourceCanBeGeneratedLocally = false,
                )
            )
            !plan.activationAllowed && plan.stages.last() == CapabilityExpansionStage.OWNER_PROMOTION
        }
        record(LifeOsBlock.G, "memory-integrity-and-retention") {
            val photon = Photon(
                id = PhotonId("self-child"),
                content = "child",
                provenance = Provenance(
                    "self-test",
                    "lifeos",
                    Instant.EPOCH,
                    parentIds = setOf(PhotonId("missing-parent")),
                ),
            )
            !suite.memoryIntegrity.verify(listOf(photon)).healthy &&
                suite.memoryCompactor.plan(listOf(photon)).decisions.size == 1
        }

        val chaos = runChaosProbes()
        states[LifeOsBlock.H] = if (chaos.passed) ReadinessState.READY else ReadinessState.BLOCKED
        details[LifeOsBlock.H] = "deterministic-chaos-containment"
        return suite.readiness.snapshot(states, details) to chaos
    }

    private suspend fun runChaosProbes(): ChaosVerificationReport {
        val results = mutableListOf<ChaosProbeResult>()

        val memoryPhoton = Photon(
            id = PhotonId("chaos-memory"),
            content = "memory",
            provenance = Provenance("chaos", "lifeos", Instant.EPOCH),
        )
        val firstBoot = suite.lifeMemory.rehydrate(listOf(memoryPhoton)).snapshot.fingerprint
        val secondBoot = suite.lifeMemory.rehydrate(listOf(memoryPhoton)).snapshot.fingerprint
        results += ChaosProbeResult(ChaosScenario.COLD_RESTART, firstBoot == secondBoot, "rehydration fingerprint stable")

        val signal = PerceptionSignal(PerceptionSource.TOOL_RESULT, "chaos", Instant.EPOCH, "duplicate")
        val duplicateBatch = suite.perception.fuse(listOf(signal, signal))
        results += ChaosProbeResult(ChaosScenario.DUPLICATE_INPUT, duplicateBatch.photons.size == 1, "duplicate perception deduplicated")

        val orphan = memoryPhoton.copy(
            id = PhotonId("chaos-orphan"),
            provenance = memoryPhoton.provenance.copy(parentIds = setOf(PhotonId("absent"))),
        )
        results += ChaosProbeResult(
            ChaosScenario.MISSING_PARENT,
            !suite.memoryIntegrity.verify(listOf(orphan)).healthy,
            "missing lineage contained as integrity issue",
        )

        val missingProvider = suite.creativeCapabilities.plan(
            CreativeCapabilityRequest(
                capabilityId = CapabilityId("chaos.provider"),
                description = "provider unavailable",
                existingProviderReady = false,
                sourceCanBeGeneratedLocally = true,
            )
        )
        results += ChaosProbeResult(
            ChaosScenario.PROVIDER_UNAVAILABLE,
            !missingProvider.activationAllowed,
            "gap remains non-activating",
        )

        val current = LifeStateVector(
            SeinDimension.entries.associateWith { if (it == SeinDimension.FRICTION) 0.5 else 0.5 }
        )
        val cheap = FutureDeltaCandidate(
            "cheap",
            mapOf(SeinDimension.AGENCY to 0.1),
            0.1,
            true,
            "bounded",
        )
        val expensive = cheap.copy(id = "expensive", resourceCost = 1.0)
        val ranked = suite.lifePlanner.rank(current, listOf(expensive, cheap))
        results += ChaosProbeResult(
            ChaosScenario.RESOURCE_PRESSURE,
            ranked.firstOrNull()?.candidate?.id == "cheap",
            "lower cost wins equal improvement tie",
        )

        val quarantinePlan = suite.creativeCapabilities.plan(
            CreativeCapabilityRequest(
                capabilityId = CapabilityId("chaos.generated"),
                description = "generated candidate",
                existingProviderReady = false,
                sourceCanBeGeneratedLocally = true,
            )
        )
        results += ChaosProbeResult(
            ChaosScenario.GENERATED_TOOL_QUARANTINE,
            !quarantinePlan.activationAllowed && CapabilityExpansionStage.CANARY_REVIEW in quarantinePlan.stages,
            "generated capability cannot skip canary",
        )

        return suite.chaosVerifier.verify(results)
    }
}
