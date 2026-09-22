package app.lifeos.core.runtime.agency

import app.lifeos.core.runtime.policy.OwnerPolicyAssessment
import app.lifeos.core.runtime.policy.OwnerPolicyDecisionId
import app.lifeos.core.runtime.policy.OwnerPolicyEvaluationMode
import app.lifeos.core.runtime.policy.OwnerPolicyGrantId
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExternalActionReceiptGraphTest {
    @Test
    fun initial_graph_binds_exact_request_plan_policy_and_canonical_effect_receipt() {
        val receipt = receipt(detail = "private-detail-value")
        val initial = ExternalActionReceiptGraph.initialRevision(
            requestFingerprint = REQUEST,
            resourceIdentity = RESOURCE,
            dispatchPlanFingerprint = PLAN,
            policyAssessment = assessment(),
            receipt = receipt,
        )

        val graph = ExternalActionReceiptGraph.replay(listOf(initial))
        val receiptNode = graph.nodes.values
            .filterIsInstance<ExternalEffectReceiptNode>()
            .single()

        assertEquals(receipt.actionId, receiptNode.receipt.actionId)
        assertEquals(receipt.idempotencyKey, receiptNode.receipt.idempotencyKey)
        assertEquals(receipt.state, receiptNode.receipt.state)
        assertEquals(receipt.recordedAt, receiptNode.receipt.recordedAt)
        assertTrue(receiptNode.receipt.detailFingerprint?.matches(Regex("[0-9a-f]{64}")) == true)
        assertEquals(3, graph.edges.size)
        assertEquals(
            setOf(
                ExternalActionEdgeType.REQUEST_PLANNED_AS,
                ExternalActionEdgeType.PLAN_AUTHORIZED_BY,
                ExternalActionEdgeType.PLAN_EXPOSED_AS,
            ),
            graph.edges.map { it.type }.toSet(),
        )
    }

    @Test
    fun codec_round_trip_does_not_serialize_raw_effect_detail() {
        val initial = ExternalActionReceiptGraph.initialRevision(
            requestFingerprint = REQUEST,
            resourceIdentity = RESOURCE,
            dispatchPlanFingerprint = PLAN,
            policyAssessment = assessment(),
            receipt = receipt(detail = "raw-secret-detail-must-not-appear"),
        )

        val bytes = ExternalActionReceiptGraphCodec.encode(initial)
        val decoded = ExternalActionReceiptGraphCodec.decode(bytes)

        assertEquals(initial, decoded)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("raw-secret-detail-must-not-appear"))
    }

    @Test
    fun observation_and_outcome_extend_exact_receipt_chain() {
        val initial = initial()
        val receiptNode = initial.nodesAdded
            .filterIsInstance<ExternalEffectReceiptNode>()
            .single()
        val observation = observation(
            resource = RESOURCE,
            fields = mapOf("state" to FIELD_A),
        )
        val second = ExternalActionGraphRevision.create(
            graphId = initial.graphId,
            revision = 2L,
            predecessorRevisionId = initial.revisionId,
            nodesAdded = listOf(observation),
            edgesAdded = listOf(
                ExternalActionGraphEdge(
                    ExternalActionEdgeType.RECEIPT_OBSERVED_BY,
                    receiptNode.id,
                    observation.id,
                )
            ),
        )
        val outcome = OutcomeNode(
            state = ExternalActionOutcomeState.CONFIRMED,
            basisObservationId = observation.id,
            reasonCode = "verified-observation",
        )
        val third = ExternalActionGraphRevision.create(
            graphId = initial.graphId,
            revision = 3L,
            predecessorRevisionId = second.revisionId,
            nodesAdded = listOf(outcome),
            edgesAdded = listOf(
                ExternalActionGraphEdge(
                    ExternalActionEdgeType.OBSERVATION_CLASSIFIED_AS_OUTCOME,
                    observation.id,
                    outcome.id,
                )
            ),
        )

        val graph = ExternalActionReceiptGraph.replay(listOf(initial, second, third))

        assertEquals(6, graph.nodes.size)
        assertEquals(5, graph.edges.size)
        assertTrue(graph.nodes.containsKey(observation.id))
        assertTrue(graph.nodes.containsKey(outcome.id))
    }

    @Test
    fun substitution_edge_with_wrong_source_kind_is_rejected() {
        val initial = initial()
        val requestNode = initial.nodesAdded
            .filterIsInstance<ExternalActionRequestNode>()
            .single()
        val observation = observation(RESOURCE)
        val second = ExternalActionGraphRevision.create(
            graphId = initial.graphId,
            revision = 2L,
            predecessorRevisionId = initial.revisionId,
            nodesAdded = listOf(observation),
            edgesAdded = listOf(
                ExternalActionGraphEdge(
                    ExternalActionEdgeType.RECEIPT_OBSERVED_BY,
                    requestNode.id,
                    observation.id,
                )
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            ExternalActionReceiptGraph.replay(listOf(initial, second))
        }
    }

    @Test
    fun non_contiguous_or_wrong_predecessor_revision_fails_closed() {
        val initial = initial()
        val observation = observation(RESOURCE)

        assertFailsWith<IllegalArgumentException> {
            ExternalActionGraphRevision.create(
                graphId = initial.graphId,
                revision = 2L,
                predecessorRevisionId = null,
                nodesAdded = listOf(observation),
                edgesAdded = emptyList(),
            )
        }

        val wrongPredecessor = ExternalActionGraphRevisionId(
            ExternalActionGraphRevisionId.PREFIX + "f".repeat(64)
        )
        val second = ExternalActionGraphRevision.create(
            graphId = initial.graphId,
            revision = 2L,
            predecessorRevisionId = wrongPredecessor,
            nodesAdded = listOf(observation),
            edgesAdded = emptyList(),
        )
        assertFailsWith<IllegalArgumentException> {
            ExternalActionReceiptGraph.replay(listOf(initial, second))
        }
    }

    @Test
    fun reconciliation_keeps_confirmed_contradicted_partial_and_unknown_distinct() {
        val expectation = ExternalActionObservationExpectation(
            resourceIdentity = RESOURCE,
            exposedAt = NOW,
            horizon = Duration.ofMinutes(10),
            expectedFieldFingerprints = mapOf(
                "state" to FIELD_A,
                "version" to FIELD_B,
            ),
        )

        val confirmed = ExternalActionObservationReconciler.reconcile(
            expectation,
            observation(
                RESOURCE,
                fields = mapOf("state" to FIELD_A, "version" to FIELD_B),
            ),
        )
        val contradicted = ExternalActionObservationReconciler.reconcile(
            expectation,
            observation(
                RESOURCE,
                fields = mapOf("state" to FIELD_C, "version" to FIELD_D),
            ),
        )
        val partial = ExternalActionObservationReconciler.reconcile(
            expectation,
            observation(
                RESOURCE,
                fields = mapOf("state" to FIELD_A),
            ),
        )
        val unknown = ExternalActionObservationReconciler.reconcile(expectation, null)

        assertEquals(ExternalActionOutcomeState.CONFIRMED, confirmed.state)
        assertEquals(ExternalActionOutcomeState.CONTRADICTED, contradicted.state)
        assertEquals(ExternalActionOutcomeState.PARTIAL, partial.state)
        assertEquals(ExternalActionOutcomeState.UNKNOWN, unknown.state)
    }

    @Test
    fun wrong_resource_is_rejected_and_ambiguous_candidates_stay_unknown() {
        val expectation = ExternalActionObservationExpectation(
            resourceIdentity = RESOURCE,
            exposedAt = NOW,
            horizon = Duration.ofMinutes(10),
            expectedFieldFingerprints = mapOf("state" to FIELD_A),
        )

        assertFailsWith<IllegalArgumentException> {
            ExternalActionObservationReconciler.reconcile(
                expectation,
                observation("android-file://other/path"),
            )
        }

        val ambiguous = ExternalActionObservationReconciler.reconcileCandidates(
            expectation,
            listOf(
                observation(RESOURCE, suffix = "1"),
                observation(RESOURCE, suffix = "2"),
            ),
        )
        assertEquals(ExternalActionOutcomeState.UNKNOWN, ambiguous.state)
        assertEquals("ambiguous-multiple-observations", ambiguous.reasonCode)
    }

    @Test
    fun exact_effect_receipt_changes_graph_identity() {
        val first = initial()
        val second = ExternalActionReceiptGraph.initialRevision(
            requestFingerprint = REQUEST,
            resourceIdentity = RESOURCE,
            dispatchPlanFingerprint = PLAN,
            policyAssessment = assessment(),
            receipt = receipt().copy(externalReference = "different-ref"),
        )

        assertNotEquals(first.graphId, second.graphId)
    }

    private fun initial(): ExternalActionGraphRevision =
        ExternalActionReceiptGraph.initialRevision(
            requestFingerprint = REQUEST,
            resourceIdentity = RESOURCE,
            dispatchPlanFingerprint = PLAN,
            policyAssessment = assessment(),
            receipt = receipt(),
        )

    private fun receipt(
        detail: String? = null,
    ): EffectReceipt = EffectReceipt(
        actionId = "action-1",
        idempotencyKey = "idem-1",
        state = ExternalEffectState.UNKNOWN_OUTCOME,
        recordedAt = NOW,
        externalReference = "external-ref",
        detail = detail,
    )

    private fun assessment(): OwnerPolicyAssessment =
        OwnerPolicyAssessment(
            decisionId = OwnerPolicyDecisionId(
                OwnerPolicyDecisionId.PREFIX + "d".repeat(64)
            ),
            policyRevision = 7L,
            mode = OwnerPolicyEvaluationMode.LIVE,
            requestFingerprint = "e".repeat(64),
            allowed = true,
            grantId = OwnerPolicyGrantId(
                OwnerPolicyGrantId.PREFIX + "c".repeat(64)
            ),
        )

    private fun observation(
        resource: String,
        fields: Map<String, String> = mapOf("state" to FIELD_A),
        suffix: String = "0",
    ): ExternalObservationNode =
        ExternalObservationNode(
            observationFingerprint =
                suffix.padEnd(64, 'a').take(64),
            resourceIdentity = resource,
            observedAt = NOW.plusSeconds(60),
            observationRevision = "rev-$suffix",
            fieldFingerprints = fields,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
        const val RESOURCE = "android-file://shared-primary/Documents/a.txt"
        const val REQUEST =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PLAN =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val FIELD_A =
            "1111111111111111111111111111111111111111111111111111111111111111"
        const val FIELD_B =
            "2222222222222222222222222222222222222222222222222222222222222222"
        const val FIELD_C =
            "3333333333333333333333333333333333333333333333333333333333333333"
        const val FIELD_D =
            "4444444444444444444444444444444444444444444444444444444444444444"
    }
}
