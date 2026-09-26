package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.level7.EvidenceActionKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MetaRealizationShadowEvaluatorTest {
    private val profile = profile()
    private val snapshot = shadowSnapshot(profile)
    private val evaluator = MetaRealizationShadowEvaluator()

    @Test
    fun `intervention separable shadow advances only to awaiting observation`() {
        val analysis = evaluator.evaluate(
            snapshot,
            evidence(
                informationCandidates = listOf(
                    InformationActionCandidate.create(
                        interventionId = "ask-owner",
                        kind = EvidenceActionKind.ASK_USER,
                        expectedInformationGainMicros = 800_000L,
                        privacyCostMicros = 100_000L,
                        rationale = "Resolve candidate ambiguity",
                    )
                ),
                allowedActionKinds = setOf(EvidenceActionKind.ASK_USER),
            ),
        )

        assertEquals(MetaRealizationCycleState.AWAITING_OBSERVATION, analysis.cycle.state)
        assertNotNull(analysis.informationPlan)
        assertEquals(1, analysis.informationPlan?.items?.size)
        assertFalse(analysis.truthAuthority)
        assertFalse(analysis.causalAuthority)
        assertFalse(analysis.executionAuthority)
    }

    @Test
    fun `policy-disallowed action cannot advance cycle`() {
        val analysis = evaluator.evaluate(
            snapshot,
            evidence(
                informationCandidates = listOf(
                    InformationActionCandidate.create(
                        interventionId = "sandbox",
                        kind = EvidenceActionKind.SAFE_SANDBOX_EXPERIMENT,
                        expectedInformationGainMicros = 1_000_000L,
                        rationale = "Would discriminate candidates",
                    )
                ),
                allowedActionKinds = emptySet(),
            ),
        )

        assertEquals(MetaRealizationCycleState.INFORMATION_REQUIRED, analysis.cycle.state)
        assertNotNull(analysis.informationPlan)
        assertEquals(emptyList(), analysis.informationPlan?.items)
        assertNull(analysis.cycle.informationPlanFingerprint)
    }

    @Test
    fun `observational separation creates no information plan`() {
        val input = evidence().copy(
            candidateSignatures = listOf(
                candidate("candidate:a", "observed:a", "ask-owner" to "law:a"),
                candidate("candidate:b", "observed:b", "ask-owner" to "law:b"),
            )
        )

        val analysis = evaluator.evaluate(snapshot, input)

        assertEquals(IdentifiabilityStatus.OBSERVATIONALLY_DISTINCT, analysis.identifiability.status)
        assertEquals(MetaRealizationCycleState.UNRESOLVED, analysis.cycle.state)
        assertNull(analysis.informationPlan)
    }

    @Test
    fun `cross-profile evidence is rejected`() {
        val input = evidence().copy(
            predictiveHistories = listOf(
                PredictiveHistory(
                    realizationProfileFingerprint = "profile:other",
                    historyFingerprint = "history:a",
                    futureLaw = law(),
                )
            )
        )

        assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate(snapshot, input)
        }
    }

    @Test
    fun `input ordering does not change analysis fingerprint`() {
        val forward = evidence()
        val reverse = forward.copy(
            predictiveHistories = forward.predictiveHistories.reversed(),
            candidateSignatures = forward.candidateSignatures.reversed(),
        )

        assertEquals(
            evaluator.evaluate(snapshot, forward).fingerprint,
            evaluator.evaluate(snapshot, reverse).fingerprint,
        )
    }

    private fun evidence(
        informationCandidates: List<InformationActionCandidate> = emptyList(),
        allowedActionKinds: Set<EvidenceActionKind> = emptySet(),
    ): MetaRealizationShadowEvidence {
        val futureLaw = law()
        return MetaRealizationShadowEvidence(
            predictiveHistories = listOf(
                PredictiveHistory(profile.fingerprint, "history:a", futureLaw),
                PredictiveHistory(profile.fingerprint, "history:b", futureLaw),
            ),
            candidateSignatures = listOf(
                candidate("candidate:a", "observed:same", "ask-owner" to "law:a"),
                candidate("candidate:b", "observed:same", "ask-owner" to "law:b"),
            ),
            informationCandidates = informationCandidates,
            allowedActionKinds = allowedActionKinds,
            budgetFingerprint = "budget:shadow",
        )
    }

    private fun candidate(
        id: String,
        observational: String,
        intervention: Pair<String, String>,
    ): PredictiveCandidateSignature =
        PredictiveCandidateSignature.create(
            realizationProfileFingerprint = profile.fingerprint,
            candidateId = id,
            observationalLawFingerprint = observational,
            interventionalLawFingerprints = mapOf(intervention),
        )

    private fun law(): DiscreteFutureLaw =
        DiscreteFutureLaw.create(mapOf("continue" to 700_000L, "pause" to 300_000L))

    private fun shadowSnapshot(
        profile: RealizationTransferProfile,
    ): MetaRealizationShadowSnapshot {
        val realization = CanonicalRealizationState.create(
            revision = 1L,
            asOf = T0,
            components = listOf(
                component(RealizationComponentKind.PRODUCTIVE_WORLD, "world"),
                component(RealizationComponentKind.COGNITIVE_STATE, "cognition"),
                component(RealizationComponentKind.RESOURCE_STATE, "resource"),
                component(RealizationComponentKind.SENSOR_STATE, "sensor"),
            ),
        )
        val cycle = MetaRealizationCycle.start(realization, profile)
        val fingerprint = StableFieldIds.fingerprint(
            "meta-realization-shadow-snapshot/v1",
            "self:state",
            "self:issues",
            realization.representationFingerprint,
            cycle.fingerprint,
        )
        return MetaRealizationShadowSnapshot(
            sourceSelfStateFingerprint = "self:state",
            sourceIssueFingerprint = "self:issues",
            realization = realization,
            cycle = cycle,
            fingerprint = fingerprint,
        )
    }

    private fun component(
        kind: RealizationComponentKind,
        key: String,
    ) = RealizationComponentRef(
        kind = kind,
        representationId = "$key:r1",
        semanticFingerprint = "$key:s1",
    )

    private fun profile(): RealizationTransferProfile =
        RealizationTransferProfile.create(
            version = "shadow-evaluator-test",
            requiredComponents = listOf(
                RealizationComponentKind.PRODUCTIVE_WORLD,
                RealizationComponentKind.COGNITIVE_STATE,
                RealizationComponentKind.RESOURCE_STATE,
                RealizationComponentKind.SENSOR_STATE,
            ),
            projectionRegistryFingerprint = "projection:test",
            stateContractFingerprint = "state:test",
            observableIds = listOf("self.world"),
            invariantIds = listOf("truth-not-permission"),
            failureCriterionIds = listOf("underidentified"),
            frozenAt = T0,
        )

    private companion object {
        val T0: Instant = Instant.parse("2026-09-25T16:00:00Z")
    }
}
