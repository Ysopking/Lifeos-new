package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldNodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class WorldEquationPackStructuralValidationTest {
    @Test
    fun exactShadowSupportedEvidenceProducesOnlyNonActivatingValidation() {
        val fixture = fixture()
        val record = supportedRecord(fixture)

        val bundle = WorldEquationPackStructuralValidationGate().validate(
            baseline = fixture.baseline,
            candidate = fixture.candidate,
            preflight = fixture.preflight,
            record = record,
        )

        assertEquals(fixture.baseline.fingerprint(), bundle.baselinePackFingerprint)
        assertEquals(
            fixture.candidate.candidate.fingerprint(),
            bundle.candidatePackFingerprint,
        )
        assertEquals(record.fingerprint, bundle.evidenceRecordFingerprint)
        assertEquals(record.latestAssessmentId, bundle.shadowAssessmentId)
        assertFalse(bundle.productiveActivationAllowed)
        assertFalse(bundle.productiveWorldMutationAllowed)
        assertFalse(bundle.promotionAdmissionAllowed)
        assertFalse(bundle.automaticEvolutionAllowed)
    }

    @Test
    fun structuralValidationRejectsEvidenceThatIsNotShadowSupported() {
        val fixture = fixture()
        val supported = supportedRecord(fixture)
        val shadowOnly = WorldEquationPackEvidenceRecord.create(
            revision = supported.revision,
            state = WorldEquationPackLifecycleState.SHADOW,
            evidence = supported.evidence,
            latestAssessmentId = supported.latestAssessmentId,
        )

        assertFailsWith<IllegalArgumentException> {
            WorldEquationPackStructuralValidationGate().validate(
                baseline = fixture.baseline,
                candidate = fixture.candidate,
                preflight = fixture.preflight,
                record = shadowOnly,
            )
        }
    }

    @Test
    fun structuralValidationCodecAndRepositoryPreserveImmutableIdentity() = runTest {
        val fixture = fixture()
        val bundle = WorldEquationPackStructuralValidationGate().validate(
            baseline = fixture.baseline,
            candidate = fixture.candidate,
            preflight = fixture.preflight,
            record = supportedRecord(fixture),
        )

        val decoded = WorldEquationPackStructuralValidationCodec.decode(
            WorldEquationPackStructuralValidationCodec.encode(bundle)
        )
        assertEquals(bundle, decoded)

        val repository = InMemoryWorldEquationPackStructuralValidationRepository()
        repository.putIfAbsent(bundle)
        repository.putIfAbsent(bundle)
        assertEquals(bundle, repository.load(bundle.candidatePackFingerprint))
        assertEquals(listOf(bundle), repository.loadReport().bundles)
    }

    @Test
    fun validationCoordinatorPersistsAndRecoversExactBundleIdempotently() = runTest {
        val fixture = fixture()
        val repository = InMemoryWorldEquationPackStructuralValidationRepository()
        val coordinator = WorldEquationPackStructuralValidationCoordinator(repository)
        val record = supportedRecord(fixture)

        val first = coordinator.validateAndPersist(
            baseline = fixture.baseline,
            candidate = fixture.candidate,
            preflight = fixture.preflight,
            evidenceRecord = record,
        )
        val second = coordinator.validateAndPersist(
            baseline = fixture.baseline,
            candidate = fixture.candidate,
            preflight = fixture.preflight,
            evidenceRecord = record,
        )

        assertEquals(first, second)
        assertEquals(
            first,
            coordinator.recover(fixture.candidate.candidate.fingerprint()),
        )
        assertFalse(first.productiveActivationAllowed)
        assertFalse(first.promotionAdmissionAllowed)
    }

    private fun supportedRecord(
        fixture: Fixture,
    ): WorldEquationPackEvidenceRecord {
        val protocol = WorldEquationPackEvaluationProtocol(
            version = "structural-validation-test-v1",
            minimumIndependentRuns = 2,
            minimumDistinctWorkloads = 2,
            minimumStructuralExerciseRuns = 2,
            minimumShadowRuns = 1,
            minimumHoldoutRuns = 1,
        )
        val evidence = WorldEquationPackEvidenceSet.empty(
            baseline = fixture.baseline,
            candidate = fixture.candidate,
            preflight = fixture.preflight,
            protocol = protocol,
        ).copy(
            observations = listOf(
                observation(
                    fixture = fixture,
                    runId = "run-shadow",
                    workloadId = "workload-a",
                    partition = WorldEquationPackEvidencePartition.SHADOW,
                    suffix = "shadow",
                ),
                observation(
                    fixture = fixture,
                    runId = "run-holdout",
                    workloadId = "workload-b",
                    partition = WorldEquationPackEvidencePartition.HOLDOUT,
                    suffix = "holdout",
                ),
            )
        )
        val assessment = WorldEquationPackEvidenceEvaluator().evaluate(evidence)
        assertEquals(WorldEquationPackShadowDecision.SHADOW_SUPPORTED, assessment.decision)
        return WorldEquationPackEvidenceRecord.create(
            revision = 4L,
            state = WorldEquationPackLifecycleState.SHADOW_SUPPORTED,
            evidence = evidence,
            latestAssessmentId = assessment.id,
        )
    }

    private fun observation(
        fixture: Fixture,
        runId: String,
        workloadId: String,
        partition: WorldEquationPackEvidencePartition,
        suffix: String,
    ): WorldEquationPackShadowObservation =
        WorldEquationPackShadowObservation(
            caseFingerprint = "case-" + suffix,
            runId = runId,
            workloadId = workloadId,
            partition = partition,
            baselinePackFingerprint = fixture.baseline.fingerprint(),
            candidatePackFingerprint = fixture.candidate.candidate.fingerprint(),
            baseline = metrics(
                materialization = "baseline-materialization-" + suffix,
                graph = "baseline-graph-" + suffix,
                state = "baseline-state-" + suffix,
            ),
            candidate = metrics(
                materialization = "candidate-materialization-" + suffix,
                graph = "candidate-graph-" + suffix,
                state = "candidate-state-" + suffix,
            ),
        )

    private fun metrics(
        materialization: String,
        graph: String,
        state: String,
    ): WorldEquationPackRunMetrics =
        WorldEquationPackRunMetrics(
            status = WorldFormulaStatus.CONVERGED,
            iterationCount = 2,
            conflictCount = 0,
            anomalyCount = 0,
            terminalDelta = 0.0,
            activeCoefficientIds = emptySet(),
            materializationFingerprint = materialization,
            graphFingerprint = graph,
            finalStateFingerprint = state,
        )

    private fun fixture(): Fixture {
        val equation = CognitiveWorldEquationProfile().spec
        val dimensions = equation.stableCoefficients()
            .flatMap { listOf(it.sourceDimension, it.targetDimension) }
            .toSet()
        val registry = WorldProjectionRegistrySnapshot.create(
            listOf(
                WorldProjectionDescriptor(
                    providerId = "validation-provider",
                    sourceKind = WorldProjectionSourceKind.FIELD,
                    nodeKinds = setOf(
                        WorldNodeKind.EVIDENCE,
                        WorldNodeKind.HYPOTHESIS,
                    ),
                    signalDimensions = dimensions,
                )
            )
        )
        val baseline = WorldEquationPack(
            version = "validation-pack-v1",
            equation = equation,
            interactionSchema = WorldInteractionSchema(
                version = "validation-interaction-v1",
                entries = equation.stableCoefficients().map {
                    WorldInteractionSchemaEntry(
                        coefficientId = it.id,
                        sourceNodeKinds = setOf(WorldNodeKind.EVIDENCE),
                        targetNodeKinds = setOf(WorldNodeKind.EVIDENCE),
                    )
                },
            ),
            projectionContract = WorldProjectionContractSnapshot.create(
                registry = registry,
                requiredProviderIds = setOf("validation-provider"),
            ),
            requiredDimensions = dimensions,
            requiredNodeKinds = setOf(WorldNodeKind.EVIDENCE),
        )
        val firstId = baseline.interactionSchema.stableEntries().first().coefficientId
        val candidatePack = baseline.copy(
            version = "validation-pack-v2",
            interactionSchema = baseline.interactionSchema.copy(
                version = "validation-interaction-v2",
                entries = baseline.interactionSchema.entries.map { entry ->
                    if (entry.coefficientId == firstId) {
                        entry.copy(
                            targetNodeKinds = setOf(
                                WorldNodeKind.EVIDENCE,
                                WorldNodeKind.HYPOTHESIS,
                            )
                        )
                    } else {
                        entry
                    }
                },
            ),
            requiredNodeKinds = setOf(
                WorldNodeKind.EVIDENCE,
                WorldNodeKind.HYPOTHESIS,
            ),
        )
        val candidate = WorldEquationPackCandidate.create(baseline, candidatePack)
        val preflight = WorldEquationPackStructuralEvaluator().evaluate(
            baseline = baseline,
            candidate = candidate,
            registry = registry,
        )
        assertEquals(WorldEquationPackStructuralStatus.SHADOW_ELIGIBLE, preflight.status)
        return Fixture(
            baseline = baseline,
            candidate = candidate,
            preflight = preflight,
        )
    }

    private data class Fixture(
        val baseline: WorldEquationPack,
        val candidate: WorldEquationPackCandidate,
        val preflight: WorldEquationPackStructuralEvidence,
    )
}
