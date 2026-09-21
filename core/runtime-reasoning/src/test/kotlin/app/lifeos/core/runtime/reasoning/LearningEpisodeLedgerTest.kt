package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldFieldState
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.language.Ambiguity
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.reasoning.ProblemFactInput
import app.lifeos.core.reasoning.ProblemHypothesisAlternative
import app.lifeos.core.reasoning.ProblemHypothesisQuestion
import app.lifeos.core.reasoning.ProblemHypothesisSeedBuilder
import app.lifeos.core.reasoning.ProblemStateGraph
import app.lifeos.core.reasoning.ProblemStateGraphBuilder
import app.lifeos.core.reasoning.ReasoningSearchEngine
import app.lifeos.core.runtime.learning.OutcomeSignal
import app.lifeos.core.runtime.level7.CausalDiscriminationRequest
import app.lifeos.core.runtime.level7.CausalEvidenceKind
import app.lifeos.core.runtime.level7.CausalObservation
import app.lifeos.core.runtime.level7.CounterfactualWorldFormulaInput
import app.lifeos.core.runtime.level7.CounterfactualWorldSnapshot
import app.lifeos.core.runtime.world.WorldFormulaInputSnapshot
import app.lifeos.core.runtime.world.WorldFormulaRequest
import app.lifeos.core.runtime.world.WorldFormulaSnapshot
import app.lifeos.core.runtime.world.WorldFormulaSnapshotNamespace
import app.lifeos.core.runtime.world.WorldFormulaStatus
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LearningEpisodeLedgerTest {
    private val at = Instant.parse("2026-09-21T20:30:00Z")
    private val domainId = StableFieldIds.domain("learning-episode-test")

    @Test
    fun `factory binds the exact B366 through B373 lineage without promotion authority`() = runTest {
        val fixture = fixture(withCausalCredit = true)
        val episode = LearningEpisodeFactory().create(
            problem = fixture.problem,
            hypothesisSeed = fixture.seed,
            search = fixture.search,
            counterfactualBatch = fixture.counterfactual,
            experimentPlan = fixture.plan,
            expectationModel = fixture.expectation,
            predictionErrorReport = fixture.error,
            causalCreditReport = fixture.credit,
            createdAt = at.plusSeconds(60),
        )

        assertEquals(1L, episode.cycleRevision)
        assertEquals(null, episode.predecessorId)
        assertEquals(LearningEpisodeStatus.VERIFIED_WITH_CAUSAL_CREDIT, episode.status)
        assertEquals(fixture.problem.id, episode.problemGraphId)
        assertEquals(fixture.seed.fingerprint, episode.hypothesisSeedFingerprint)
        assertEquals(fixture.search.fingerprint, episode.reasoningSearchFingerprint)
        assertEquals(fixture.counterfactual.fingerprint, episode.counterfactualBatchFingerprint)
        assertEquals(fixture.plan.fingerprint, episode.experimentPlanFingerprint)
        assertEquals(fixture.expectation.fingerprint, episode.expectationModelFingerprint)
        assertEquals(fixture.error.fingerprint, episode.predictionErrorReportFingerprint)
        assertEquals(fixture.credit?.fingerprint, episode.causalCreditReportFingerprint)
        assertFalse(episode.learningAuthority)
        assertFalse(episode.promotionAllowed)
    }

    @Test
    fun `later observation is an immutable contiguous revision not an in place update`() = runTest {
        val fixture = fixture(withCausalCredit = false)
        val factory = LearningEpisodeFactory()
        val first = factory.create(
            fixture.problem,
            fixture.seed,
            fixture.search,
            fixture.counterfactual,
            fixture.plan,
            fixture.expectation,
            fixture.error,
            createdAt = at.plusSeconds(60),
        )
        val second = factory.create(
            fixture.problem,
            fixture.seed,
            fixture.search,
            fixture.counterfactual,
            fixture.plan,
            fixture.expectation,
            fixture.error,
            createdAt = at.plusSeconds(120),
            previous = first,
        )

        assertEquals(1L, first.cycleRevision)
        assertEquals(2L, second.cycleRevision)
        assertEquals(first.id, second.predecessorId)
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.contentFingerprint(), second.contentFingerprint())
    }

    @Test
    fun `factory rejects cross lineage substitution`() = runTest {
        val first = fixture(withCausalCredit = false, sourceCycleId = "cycle-a")
        val second = fixture(withCausalCredit = false, sourceCycleId = "cycle-b")

        assertFailsWith<IllegalArgumentException> {
            LearningEpisodeFactory().create(
                problem = first.problem,
                hypothesisSeed = first.seed,
                search = first.search,
                counterfactualBatch = first.counterfactual,
                experimentPlan = second.plan,
                expectationModel = second.expectation,
                predictionErrorReport = second.error,
                createdAt = at.plusSeconds(60),
            )
        }
    }

    @Test
    fun `codec round trip preserves exact episode identity and rejects trailing bytes`() = runTest {
        val fixture = fixture(withCausalCredit = true)
        val episode = LearningEpisodeFactory().create(
            fixture.problem,
            fixture.seed,
            fixture.search,
            fixture.counterfactual,
            fixture.plan,
            fixture.expectation,
            fixture.error,
            fixture.credit,
            at.plusSeconds(60),
        )

        val encoded = LearningEpisodeCodec.encode(episode)
        assertEquals(episode, LearningEpisodeCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException> {
            LearningEpisodeCodec.decode(encoded + byteArrayOf(1))
        }
    }

    @Test
    fun `ledger persists before RAM and duplicate append is idempotent`() = runTest {
        val fixture = fixture(withCausalCredit = false)
        val episode = LearningEpisodeFactory().create(
            fixture.problem,
            fixture.seed,
            fixture.search,
            fixture.counterfactual,
            fixture.plan,
            fixture.expectation,
            fixture.error,
            createdAt = at.plusSeconds(60),
        )
        val repository = RecordingRepository()
        val ledger = DurableLearningEpisodeLedger(repository)

        val first = ledger.append(episode)
        assertFalse(first.replayed)
        assertEquals(listOf("save:" + episode.id.value), repository.operations)
        assertEquals(episode, ledger.state.latestFor(episode.sourceCycleId))

        val duplicate = ledger.append(episode)
        assertTrue(duplicate.replayed)
        assertEquals(2, repository.operations.size)
        assertEquals(1L, ledger.state.revision)
    }

    @Test
    fun `reducer rejects forked revisions for one source cycle`() = runTest {
        val fixture = fixture(withCausalCredit = false)
        val factory = LearningEpisodeFactory()
        val first = factory.create(
            fixture.problem,
            fixture.seed,
            fixture.search,
            fixture.counterfactual,
            fixture.plan,
            fixture.expectation,
            fixture.error,
            createdAt = at.plusSeconds(60),
        )
        val second = factory.create(
            fixture.problem,
            fixture.seed,
            fixture.search,
            fixture.counterfactual,
            fixture.plan,
            fixture.expectation,
            fixture.error,
            createdAt = at.plusSeconds(120),
            previous = first,
        )
        val competingSecond = factory.create(
            fixture.problem,
            fixture.seed,
            fixture.search,
            fixture.counterfactual,
            fixture.plan,
            fixture.expectation,
            fixture.error,
            createdAt = at.plusSeconds(121),
            previous = first,
        )
        val reducer = LearningEpisodeReducer()
        val state1 = reducer.apply(LearningEpisodeState(), first).state
        val state2 = reducer.apply(state1, second).state

        assertFailsWith<IllegalArgumentException> {
            reducer.apply(state2, competingSecond)
        }
    }

    @Test
    fun `rehydration is order independent and fails closed on corruption`() = runTest {
        val firstFixture = fixture(withCausalCredit = false, sourceCycleId = "cycle-a")
        val secondFixture = fixture(withCausalCredit = false, sourceCycleId = "cycle-b")
        val factory = LearningEpisodeFactory()
        val first = factory.create(
            firstFixture.problem,
            firstFixture.seed,
            firstFixture.search,
            firstFixture.counterfactual,
            firstFixture.plan,
            firstFixture.expectation,
            firstFixture.error,
            createdAt = at.plusSeconds(60),
        )
        val second = factory.create(
            secondFixture.problem,
            secondFixture.seed,
            secondFixture.search,
            secondFixture.counterfactual,
            secondFixture.plan,
            secondFixture.expectation,
            secondFixture.error,
            createdAt = at.plusSeconds(61),
        )

        val repository = RecordingRepository(
            initial = listOf(second, first),
        )
        val ledger = DurableLearningEpisodeLedger(repository)
        val restored = ledger.rehydrate()

        assertEquals(2, restored.restoredEpisodes)
        assertEquals(
            listOf(first.id, second.id).sortedBy { it.value },
            restored.state.episodes.map { it.id },
        )

        val corrupt = DurableLearningEpisodeLedger(
            RecordingRepository(
                initial = listOf(first),
                unreadable = listOf("bad-segment"),
            )
        )
        assertFailsWith<IllegalArgumentException> {
            corrupt.rehydrate()
        }
    }

    private suspend fun fixture(
        withCausalCredit: Boolean,
        sourceCycleId: String = "cycle-b374",
    ): Fixture {
        val evidence = FieldEvidence.create(
            domainId = domainId,
            sourcePhotonId = PhotonId("evidence-" + sourceCycleId),
            sourceRevision = 1L,
            kind = EvidenceKind.DOCUMENT_FACT,
            semanticKey = "fixture.fact",
            confidence = 0.60,
            reliability = EvidenceReliability(0.8, "test"),
            authority = SourceAuthority.USER_PROVIDED,
            observedAt = at,
            payload = EvidencePayload.text("fact-" + sourceCycleId),
            explanation = "learning episode fixture",
        )
        val problem = ProblemStateGraphBuilder().build(
            goal = GoalFrame(
                intent = IntentType.QUERY,
                objective = "Resolve the learning episode fixture.",
                entities = emptyList(),
                references = emptyList(),
                constraints = emptyList(),
                ambiguities = listOf(
                    Ambiguity(
                        code = "cause",
                        message = "Cause unresolved",
                        alternatives = listOf("a", "b"),
                        severity = 1.0,
                    )
                ),
                confidence = 0.95,
                language = LanguageCode.EN,
            ),
            sourcePhoton = Photon(
                id = PhotonId("problem-" + sourceCycleId),
                revision = 1L,
                content = "Resolve the learning episode fixture.",
                provenance = Provenance(
                    source = "learning-episode-test",
                    actor = "owner",
                    createdAt = at,
                ),
            ),
            facts = listOf(
                ProblemFactInput(
                    semanticKey = evidence.semanticKey,
                    statement = "Known fixture fact",
                    confidence = 0.60,
                    evidence = listOf(evidence),
                )
            ),
        )
        val unknown = problem.unknowns.single()
        val fact = problem.facts.single()
        val seed = ProblemHypothesisSeedBuilder().build(
            problem = problem,
            domainId = domainId,
            evidence = listOf(evidence),
            questions = listOf(
                ProblemHypothesisQuestion(
                    unknownNodeId = unknown.id,
                    alternatives = listOf(
                        ProblemHypothesisAlternative(
                            semanticKey = "a",
                            claim = "Alternative A",
                            supportingFactNodeIds = setOf(fact.id),
                        ),
                        ProblemHypothesisAlternative(
                            semanticKey = "b",
                            claim = "Alternative B",
                            contradictingFactNodeIds = setOf(fact.id),
                        ),
                    ),
                )
            ),
        )
        val search = ReasoningSearchEngine().search(seed)
        val counterfactual = ReasoningCounterfactualSimulator(
            CounterfactualSimulationExecutor { input -> counterfactualSnapshot(input) }
        ).simulateAll(
            search = search,
            baseProductiveSnapshotId = "productive-" + sourceCycleId,
            baseEquationVersion = "equation-v1",
            inputs = search.completeStates.mapIndexed { index, state ->
                ReasoningCounterfactualInput(
                    stateFingerprint = state.fingerprint,
                    request = worldRequest(sourceCycleId, index),
                )
            },
        )
        val plan = ExperimentPlanner(
            ExperimentPlannerConfig(
                maxExperiments = 1,
                maxTotalResourceCost = 10.0,
            )
        ).plan(
            sourceCycleId = sourceCycleId,
            reasoningSearchFingerprint = search.fingerprint,
            counterfactualBatchFingerprint = counterfactual.fingerprint,
            budgetFingerprint = "budget-" + sourceCycleId,
            requests = listOf(
                CausalDiscriminationRequest(
                    candidateIds = setOf("candidate-a", "candidate-b"),
                    interventionVariableId = "variable-" + sourceCycleId,
                    expectedInformationGain = 1.0,
                    rationale = "distinguish fixture alternatives",
                )
            ),
        )
        val expectation = OutcomeExpectationModelBuilder().build(
            plan,
            plan.items.map { item ->
                OutcomeExpectationInput(
                    evidenceActionId = item.evidenceAction.id,
                    expected = ExpectedOutcomeSignal(
                        completion = ExpectedOutcomeBand(0.6, 0.7, 0.8),
                        correctness = ExpectedOutcomeBand(0.6, 0.7, 0.8),
                    ),
                    confidence = 0.8,
                    rationale = "fixture expectation",
                )
            },
        )
        val observedByAction = expectation.entries.associate { entry ->
            entry.evidenceActionId to ObservedOutcomeInput(
                evidenceActionId = entry.evidenceActionId,
                observationRef = "observation:" + entry.evidenceActionId,
                observationFingerprint = "observation-fingerprint:" + entry.evidenceActionId,
                signal = OutcomeSignal(
                    completion = 0.75,
                    correctness = 0.72,
                ),
                confidence = 0.9,
                verified = true,
            )
        }
        val error = PredictionErrorEngine().compare(
            expectation,
            observedByAction.values.toList(),
        )
        val credit = if (withCausalCredit) {
            val creditInputs = plan.items.map { item ->
                val request = item.proposal.request
                val observed = observedByAction.getValue(item.evidenceAction.id)
                CausalCreditObservationInput(
                    evidenceActionId = item.evidenceAction.id,
                    observation = CausalObservation(
                        id = "causal:" + item.evidenceAction.id,
                        sourceSnapshotId = "before:" + item.evidenceAction.id,
                        targetSnapshotId = "after:" + item.evidenceAction.id,
                        sourceTarget = WorldTargetRef(
                            WorldNodeKind.EXPERIMENT,
                            request.interventionVariableId,
                        ),
                        targetTarget = WorldTargetRef(
                            WorldNodeKind.OUTCOME,
                            item.evidenceAction.id,
                        ),
                        sourceDimension = WorldSignalDimension.INFORMATION_GAIN,
                        targetDimension = WorldSignalDimension.OUTCOME_ALIGNMENT,
                        signedEffect = 0.2,
                        confidence = 0.9,
                        evidenceKind = CausalEvidenceKind.CONTROLLED_INTERVENTION,
                        provenanceFingerprint = observed.fingerprint(),
                    ),
                    candidateRelations = request.candidateIds.sorted().mapIndexed { index, id ->
                        id to if (index == 0) {
                            CausalCreditRelation.SUPPORTS
                        } else {
                            CausalCreditRelation.CONTRADICTS
                        }
                    }.toMap(),
                )
            }
            CausalCreditAssignmentEngine().assign(
                plan,
                expectation,
                error,
                creditInputs,
            )
        } else {
            null
        }
        return Fixture(
            problem = problem,
            seed = seed,
            search = search,
            counterfactual = counterfactual,
            plan = plan,
            expectation = expectation,
            error = error,
            credit = credit,
        )
    }

    private fun worldRequest(
        sourceCycleId: String,
        index: Int,
    ): WorldFormulaRequest = WorldFormulaRequest(
        inputs = listOf(
            WorldFormulaInputSnapshot(
                target = WorldTargetRef(
                    WorldNodeKind.THOUGHT,
                    sourceCycleId + "-state-" + index,
                ),
                vector = WorldFieldVector.EMPTY,
                sourceSnapshotFingerprint = "source-" + sourceCycleId + "-" + index,
            )
        ),
        interactions = emptyList(),
        equationVersion = "equation-v1",
        observedAt = at.plusSeconds(index.toLong()),
    )

    private fun counterfactualSnapshot(
        input: CounterfactualWorldFormulaInput,
    ): CounterfactualWorldSnapshot {
        val graphFingerprint = StableFieldIds.fingerprint("graph", input.request.id)
        val equationFingerprint = StableFieldIds.fingerprint("equation", input.baseEquationVersion)
        val finalState = WorldFieldState(
            graphFingerprint = graphFingerprint,
            equationFingerprint = equationFingerprint,
            generation = 0,
            vectors = emptyMap(),
        )
        val snapshot = WorldFormulaSnapshot.create(
            runId = "run:" + StableFieldIds.fingerprint(input.request.id),
            requestId = input.request.id,
            equationVersion = input.baseEquationVersion,
            equationFingerprint = equationFingerprint,
            graphFingerprint = graphFingerprint,
            configFingerprint = input.request.config.fingerprint(),
            status = WorldFormulaStatus.CONVERGED,
            finalState = finalState,
            iterations = emptyList(),
            conflicts = emptyList(),
            anomalies = emptyList(),
            inputSnapshotFingerprints = input.request.inputs
                .mapTo(linkedSetOf()) { it.sourceSnapshotFingerprint },
        )
        return CounterfactualWorldSnapshot(
            id = "counterfactual:" + StableFieldIds.fingerprint(
                input.baseProductiveSnapshotId,
                input.interventionFingerprint,
                snapshot.id,
            ),
            namespace = WorldFormulaSnapshotNamespace.COUNTERFACTUAL,
            baseProductiveSnapshotId = input.baseProductiveSnapshotId,
            interventionFingerprint = input.interventionFingerprint,
            snapshot = snapshot,
        )
    }

    private class RecordingRepository(
        initial: List<LearningEpisode> = emptyList(),
        private val unreadable: List<String> = emptyList(),
    ) : LearningEpisodeRepository {
        private val episodes = initial.associateByTo(linkedMapOf()) { it.id }
        val operations = mutableListOf<String>()

        override suspend fun save(episode: LearningEpisode): LearningEpisodeWriteResult {
            operations += "save:" + episode.id.value
            val existing = episodes[episode.id]
            if (existing != null) {
                require(existing == episode)
                return LearningEpisodeWriteResult.Duplicate(existing)
            }
            episodes[episode.id] = episode
            return LearningEpisodeWriteResult.Stored(episode)
        }

        override suspend fun load(id: LearningEpisodeId): LearningEpisode? = episodes[id]

        override suspend fun loadReport(): LearningEpisodeLoadReport =
            LearningEpisodeLoadReport(
                episodes = episodes.values.toList(),
                unreadableEntries = unreadable,
            )
    }

    private data class Fixture(
        val problem: ProblemStateGraph,
        val seed: app.lifeos.core.reasoning.ProblemHypothesisSeed,
        val search: app.lifeos.core.reasoning.ReasoningSearchResult,
        val counterfactual: ReasoningCounterfactualBatch,
        val plan: ExperimentPlan,
        val expectation: OutcomeExpectationModel,
        val error: PredictionErrorReport,
        val credit: CausalCreditAssignmentReport?,
    )
}
