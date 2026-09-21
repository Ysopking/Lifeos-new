package app.lifeos.core.runtime.reasoning

import app.lifeos.core.reasoning.*
import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldFieldState
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.language.Ambiguity
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
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
import kotlin.test.assertTrue

class ReasoningCounterfactualSimulationTest {
    private val at = Instant.parse("2026-09-21T19:00:00Z")
    private val domainId = StableFieldIds.domain("counterfactual-reasoning-test")

    @Test
    fun `every complete reasoning state is simulated once in counterfactual namespace`() = runTest {
        val search = search()
        val simulator = ReasoningCounterfactualSimulator(validExecutor())
        val inputs = search.completeStates.mapIndexed { index, state ->
            ReasoningCounterfactualInput(
                stateFingerprint = state.fingerprint,
                request = request(index),
            )
        }.reversed()

        val batch = simulator.simulateAll(
            search = search,
            baseProductiveSnapshotId = "productive-head-1",
            baseEquationVersion = "equation-v1",
            inputs = inputs,
        )

        assertEquals(search.completeStates.size, batch.simulations.size)
        assertEquals(
            batch.simulations.map { it.stateFingerprint }.sorted(),
            batch.simulations.map { it.stateFingerprint },
        )
        assertTrue(batch.simulations.all {
            it.scenario.namespace == WorldFormulaSnapshotNamespace.COUNTERFACTUAL &&
                it.snapshot.namespace == WorldFormulaSnapshotNamespace.COUNTERFACTUAL
        })
        assertTrue(batch.simulations.all { !it.scenario.productiveCommitAllowed })
        assertTrue(batch.simulations.all { !it.snapshot.productiveCommitAllowed })
    }

    @Test
    fun `missing complete state request fails closed`() = runTest {
        val search = search()
        val onlyFirst = search.completeStates.first()

        assertFailsWith<IllegalArgumentException> {
            ReasoningCounterfactualSimulator(validExecutor()).simulateAll(
                search = search,
                baseProductiveSnapshotId = "productive-head-1",
                baseEquationVersion = "equation-v1",
                inputs = listOf(
                    ReasoningCounterfactualInput(
                        onlyFirst.fingerprint,
                        request(0),
                    )
                ),
            )
        }
    }

    @Test
    fun `duplicate state request and wrong equation fail closed`() = runTest {
        val search = search()
        val first = search.completeStates.first()

        assertFailsWith<IllegalArgumentException> {
            ReasoningCounterfactualSimulator(validExecutor()).simulateAll(
                search = search,
                baseProductiveSnapshotId = "productive-head-1",
                baseEquationVersion = "equation-v1",
                inputs = listOf(
                    ReasoningCounterfactualInput(first.fingerprint, request(0)),
                    ReasoningCounterfactualInput(first.fingerprint, request(1)),
                ),
            )
        }

        val wrongInputs = search.completeStates.mapIndexed { index, state ->
            ReasoningCounterfactualInput(
                state.fingerprint,
                request(index, equationVersion = "equation-wrong"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ReasoningCounterfactualSimulator(validExecutor()).simulateAll(
                search = search,
                baseProductiveSnapshotId = "productive-head-1",
                baseEquationVersion = "equation-v1",
                inputs = wrongInputs,
            )
        }
    }

    @Test
    fun `executor cannot rewrite intervention identity or productive base`() = runTest {
        val search = search()
        val inputs = search.completeStates.mapIndexed { index, state ->
            ReasoningCounterfactualInput(state.fingerprint, request(index))
        }
        val malicious = CounterfactualSimulationExecutor { input ->
            snapshotFor(
                input = input,
                baseProductiveSnapshotId = "different-base",
                interventionFingerprint = "different-intervention",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            ReasoningCounterfactualSimulator(malicious).simulateAll(
                search = search,
                baseProductiveSnapshotId = "productive-head-1",
                baseEquationVersion = "equation-v1",
                inputs = inputs,
            )
        }
    }

    @Test
    fun `same complete states and requests produce stable batch identity`() = runTest {
        val search = search()
        val inputs = search.completeStates.mapIndexed { index, state ->
            ReasoningCounterfactualInput(state.fingerprint, request(index))
        }
        val simulator = ReasoningCounterfactualSimulator(validExecutor())

        val first = simulator.simulateAll(
            search,
            "productive-head-1",
            "equation-v1",
            inputs,
        )
        val second = simulator.simulateAll(
            search,
            "productive-head-1",
            "equation-v1",
            inputs.reversed(),
        )

        assertEquals(first, second)
        assertFalse(first.fingerprint.isBlank())
    }

    private fun validExecutor(): CounterfactualSimulationExecutor =
        CounterfactualSimulationExecutor { input -> snapshotFor(input) }

    private fun snapshotFor(
        input: app.lifeos.core.runtime.level7.CounterfactualWorldFormulaInput,
        baseProductiveSnapshotId: String = input.baseProductiveSnapshotId,
        interventionFingerprint: String = input.interventionFingerprint,
    ): CounterfactualWorldSnapshot {
        val graphFingerprint = StableFieldIds.fingerprint("graph", input.request.id)
        val equationFingerprint = StableFieldIds.fingerprint(
            "equation",
            input.baseEquationVersion,
        )
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
            id = "counterfactual-test:" + StableFieldIds.fingerprint(
                baseProductiveSnapshotId,
                interventionFingerprint,
                snapshot.id,
            ),
            namespace = WorldFormulaSnapshotNamespace.COUNTERFACTUAL,
            baseProductiveSnapshotId = baseProductiveSnapshotId,
            interventionFingerprint = interventionFingerprint,
            snapshot = snapshot,
        )
    }

    private fun request(
        index: Int,
        equationVersion: String = "equation-v1",
    ): WorldFormulaRequest = WorldFormulaRequest(
        inputs = listOf(
            WorldFormulaInputSnapshot(
                target = WorldTargetRef(
                    WorldNodeKind.THOUGHT,
                    "reasoning-state-" + index,
                ),
                vector = WorldFieldVector.EMPTY,
                sourceSnapshotFingerprint = "source-state-" + index,
            )
        ),
        interactions = emptyList(),
        equationVersion = equationVersion,
        observedAt = at.plusSeconds(index.toLong()),
    )

    private fun search(): ReasoningSearchResult {
        val evidence = FieldEvidence.create(
            domainId = domainId,
            sourcePhotonId = PhotonId("evidence-source"),
            sourceRevision = 1L,
            kind = EvidenceKind.DOCUMENT_FACT,
            semanticKey = "fixture.fact",
            confidence = 0.60,
            reliability = EvidenceReliability(0.8, "test"),
            authority = SourceAuthority.USER_PROVIDED,
            observedAt = at,
            payload = EvidencePayload.text("fact"),
            explanation = "test evidence",
        )
        val problem = ProblemStateGraphBuilder().build(
            goal = GoalFrame(
                intent = IntentType.QUERY,
                objective = "Resolve the counterfactual question.",
                entities = emptyList(),
                references = emptyList(),
                constraints = emptyList(),
                ambiguities = listOf(
                    Ambiguity(
                        code = "counterfactual-unknown",
                        message = "Counterfactual cause unresolved",
                        alternatives = listOf("a", "b"),
                        severity = 1.0,
                    )
                ),
                confidence = 0.95,
                language = LanguageCode.EN,
            ),
            sourcePhoton = Photon(
                id = PhotonId("counterfactual-source"),
                revision = 2L,
                content = "Resolve the counterfactual question.",
                provenance = Provenance(
                    source = "counterfactual-test",
                    actor = "owner",
                    createdAt = at,
                ),
            ),
            facts = listOf(
                ProblemFactInput(
                    semanticKey = evidence.semanticKey,
                    statement = "Known fact",
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
        return ReasoningSearchEngine().search(seed)
    }
}
