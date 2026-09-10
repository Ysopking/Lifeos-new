package app.lifeos.core.runtime.context

import app.lifeos.core.field.CompetitionGroup
import app.lifeos.core.field.DomainContext
import app.lifeos.core.field.FieldContext
import app.lifeos.core.field.FieldContextScope
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldEnergySnapshot
import app.lifeos.core.field.FieldGraph
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.FieldNode
import app.lifeos.core.field.FieldNodeKind
import app.lifeos.core.field.FieldState
import app.lifeos.core.field.ForcePolarity
import app.lifeos.core.field.HypothesisScope
import app.lifeos.core.field.PhotonContextReference
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalContext
import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextDomainFieldTest {
    private val at = Instant.parse("2026-09-10T17:00:00Z")
    private val domain: FieldDomainId = StableFieldIds.domain("context-domain-test")

    @Test
    fun `context support biases supported hypothesis and repels weaker competitor`() {
        val alpha = node("alpha")
        val beta = node("beta")
        val alphaHypothesis = hypothesis("alpha", alpha)
        val betaHypothesis = hypothesis("beta", beta)
        val graph = FieldGraph(
            domainId = domain,
            nodes = listOf(alpha, beta),
            competitionGroups = listOf(
                CompetitionGroup("choice", setOf(alpha.id, beta.id))
            ),
        )
        val field = ContextDomainField(domain, listOf(alphaHypothesis, betaHypothesis))

        val evaluation = field.evaluate(
            state(alpha, beta, alphaHypothesis, betaHypothesis),
            graph,
            context(reference("alpha", FieldContextScope.CURRENT_GOAL)),
        )

        assertTrue(evaluation.hypothesisBias.getValue(alphaHypothesis.id) > 0.0)
        assertTrue(evaluation.hypothesisBias.getValue(betaHypothesis.id) < 0.0)
        val repulsion = evaluation.forces.single()
        assertEquals(alpha.id, repulsion.sourceNodeId)
        assertEquals(beta.id, repulsion.targetNodeId)
        assertEquals(ForcePolarity.REPULSION, repulsion.polarity)
    }

    @Test
    fun `equal context support does not manufacture a winner`() {
        val alpha = node("alpha")
        val beta = node("beta")
        val alphaHypothesis = hypothesis("alpha", alpha)
        val betaHypothesis = hypothesis("beta", beta)
        val graph = FieldGraph(
            domainId = domain,
            nodes = listOf(alpha, beta),
            competitionGroups = listOf(
                CompetitionGroup("choice", setOf(alpha.id, beta.id))
            ),
        )
        val field = ContextDomainField(domain, listOf(alphaHypothesis, betaHypothesis))
        val context = context(
            reference("alpha", FieldContextScope.CURRENT_GOAL),
            reference("beta", FieldContextScope.CURRENT_GOAL, id = "ctx-beta"),
        )

        val evaluation = field.evaluate(
            state(alpha, beta, alphaHypothesis, betaHypothesis),
            graph,
            context,
        )

        assertEquals(
            evaluation.hypothesisBias.getValue(alphaHypothesis.id),
            evaluation.hypothesisBias.getValue(betaHypothesis.id),
        )
        assertTrue(evaluation.forces.none { it.polarity == ForcePolarity.REPULSION })
    }

    @Test
    fun `shared context reference attracts related non competing nodes symmetrically`() {
        val left = node("alpha.left")
        val right = node("alpha.right")
        val leftHypothesis = hypothesis("alpha.left", left)
        val rightHypothesis = hypothesis("alpha.right", right)
        val graph = FieldGraph(domainId = domain, nodes = listOf(left, right))
        val field = ContextDomainField(domain, listOf(leftHypothesis, rightHypothesis))

        val evaluation = field.evaluate(
            state(left, right, leftHypothesis, rightHypothesis),
            graph,
            context(reference("alpha", FieldContextScope.CURRENT_CONVERSATION)),
        )

        val attractions = evaluation.forces.filter { it.polarity == ForcePolarity.ATTRACTION }
        assertEquals(2, attractions.size)
        assertTrue(attractions.any { it.sourceNodeId == left.id && it.targetNodeId == right.id })
        assertTrue(attractions.any { it.sourceNodeId == right.id && it.targetNodeId == left.id })
        assertEquals(attractions[0].magnitude, attractions[1].magnitude)
    }

    @Test
    fun `no relevant context produces no field influence`() {
        val alpha = node("alpha")
        val hypothesis = hypothesis("alpha", alpha)
        val field = ContextDomainField(domain, listOf(hypothesis))
        val emptyContext = FieldContext(
            temporal = TemporalContext(at),
            domain = DomainContext(domain),
        )

        val evaluation = field.evaluate(
            FieldState.initial(
                domainId = domain,
                inputFingerprint = "empty",
                energy = FieldEnergySnapshot(
                    nodeEnergy = mapOf(alpha.id to 0.5),
                    hypothesisEnergy = mapOf(hypothesis.id to 0.5),
                ),
            ),
            FieldGraph(domain, listOf(alpha)),
            emptyContext,
        )

        assertTrue(evaluation.forces.isEmpty())
        assertTrue(evaluation.hypothesisBias.isEmpty())
        assertTrue(evaluation.conflicts.isEmpty())
    }

    private fun node(key: String) = FieldNode.create(
        domainId = domain,
        kind = FieldNodeKind.HYPOTHESIS,
        semanticKey = key,
    )

    private fun hypothesis(key: String, node: FieldNode) = FieldHypothesis.create(
        domainId = domain,
        semanticKey = key,
        scope = HypothesisScope.MESSAGE,
        nodeIds = setOf(node.id),
        explanation = "test hypothesis $key",
    )

    private fun state(
        first: FieldNode,
        second: FieldNode,
        firstHypothesis: FieldHypothesis,
        secondHypothesis: FieldHypothesis,
    ) = FieldState.initial(
        domainId = domain,
        inputFingerprint = "test-input",
        energy = FieldEnergySnapshot(
            nodeEnergy = mapOf(first.id to 0.5, second.id to 0.5),
            hypothesisEnergy = mapOf(firstHypothesis.id to 0.5, secondHypothesis.id to 0.5),
        ),
    )

    private fun context(vararg references: PhotonContextReference): FieldContext = FieldContext(
        temporal = TemporalContext(at),
        domain = DomainContext(domain),
        photonReferences = references.toList(),
        activeScopes = setOf(
            FieldContextScope.CURRENT_TASK,
            FieldContextScope.CURRENT_CONVERSATION,
            FieldContextScope.CURRENT_PROJECT,
            FieldContextScope.CURRENT_GOAL,
        ),
    )

    private fun reference(
        term: String,
        scope: FieldContextScope,
        id: String = "ctx-alpha",
    ) = PhotonContextReference(
        photonId = PhotonId(id),
        revision = 1,
        scopes = setOf(scope),
        semanticTerms = setOf(term),
        confidence = 0.9,
        observedAt = at,
    )
}
