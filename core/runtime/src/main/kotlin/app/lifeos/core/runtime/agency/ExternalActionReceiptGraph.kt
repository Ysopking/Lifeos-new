package app.lifeos.core.runtime.agency

import app.lifeos.core.runtime.policy.OwnerPolicyAssessment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

@JvmInline
value class ExternalActionGraphId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(SHA_256_REGEX))
    }

    companion object {
        const val PREFIX = "external-action-graph:"
    }
}

@JvmInline
value class ExternalActionNodeId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(SHA_256_REGEX))
    }

    companion object {
        const val PREFIX = "external-action-node:"
    }
}

@JvmInline
value class ExternalActionEdgeId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(SHA_256_REGEX))
    }

    companion object {
        const val PREFIX = "external-action-edge:"
    }
}

@JvmInline
value class ExternalActionGraphRevisionId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(SHA_256_REGEX))
    }

    companion object {
        const val PREFIX = "external-action-revision:"
    }
}

enum class ExternalActionNodeKind {
    REQUEST,
    CAPABILITY_PLAN,
    OWNER_POLICY_DECISION,
    EFFECT_RECEIPT,
    OBSERVATION,
    OUTCOME,
}

sealed interface ExternalActionGraphNode {
    val id: ExternalActionNodeId
    val kind: ExternalActionNodeKind

    fun fingerprint(): String
}

data class ExternalActionRequestNode(
    val requestFingerprint: String,
    val resourceIdentity: String,
    override val id: ExternalActionNodeId = nodeId(
        ExternalActionNodeKind.REQUEST,
        requestFingerprint,
        resourceIdentity,
    ),
) : ExternalActionGraphNode {
    init {
        require(requestFingerprint.matches(SHA_256_REGEX))
        requireBoundedIdentity(resourceIdentity, "request resource")
        require(id == nodeId(ExternalActionNodeKind.REQUEST, requestFingerprint, resourceIdentity))
    }

    override val kind: ExternalActionNodeKind = ExternalActionNodeKind.REQUEST

    override fun fingerprint(): String = externalActionFingerprint(
        "external-action-request-node/v1",
        requestFingerprint,
        resourceIdentity,
    )
}

data class CapabilityPlanNode(
    val dispatchPlanFingerprint: String,
    override val id: ExternalActionNodeId = nodeId(
        ExternalActionNodeKind.CAPABILITY_PLAN,
        dispatchPlanFingerprint,
    ),
) : ExternalActionGraphNode {
    init {
        require(dispatchPlanFingerprint.matches(SHA_256_REGEX))
        require(id == nodeId(ExternalActionNodeKind.CAPABILITY_PLAN, dispatchPlanFingerprint))
    }

    override val kind: ExternalActionNodeKind = ExternalActionNodeKind.CAPABILITY_PLAN

    override fun fingerprint(): String = externalActionFingerprint(
        "external-action-capability-plan-node/v1",
        dispatchPlanFingerprint,
    )
}

data class OwnerPolicyDecisionNode(
    val decisionId: String,
    val policyRevision: Long,
    val allowed: Boolean,
    override val id: ExternalActionNodeId = nodeId(
        ExternalActionNodeKind.OWNER_POLICY_DECISION,
        decisionId,
        policyRevision.toString(),
        allowed.toString(),
    ),
) : ExternalActionGraphNode {
    init {
        require(decisionId.startsWith("owner-policy-decision:"))
        require(decisionId.removePrefix("owner-policy-decision:").matches(SHA_256_REGEX))
        require(policyRevision >= 0L)
        require(id == nodeId(ExternalActionNodeKind.OWNER_POLICY_DECISION, decisionId, policyRevision.toString(), allowed.toString()))
    }

    override val kind: ExternalActionNodeKind = ExternalActionNodeKind.OWNER_POLICY_DECISION

    override fun fingerprint(): String = externalActionFingerprint(
        "external-action-owner-policy-node/v1",
        decisionId,
        policyRevision.toString(),
        allowed.toString(),
    )

    companion object {
        fun from(assessment: OwnerPolicyAssessment): OwnerPolicyDecisionNode =
            OwnerPolicyDecisionNode(
                decisionId = assessment.decisionId.value,
                policyRevision = assessment.policyRevision,
                allowed = assessment.allowed,
            )
    }
}

data class ExternalEffectReceiptRef(
    val actionId: String,
    val idempotencyKey: String,
    val state: ExternalEffectState,
    val recordedAt: Instant,
    val externalReference: String?,
    val observationFingerprint: String?,
    val challengeId: String?,
    val challengeResolutionFingerprint: String?,
    val detailFingerprint: String?,
    val fingerprint: String,
) {
    init {
        requireBoundedIdentity(actionId, "effect action id")
        requireBoundedIdentity(idempotencyKey, "effect idempotency key")
        require(externalReference == null || externalReference.length <= MAX_GRAPH_TEXT_CHARS)
        require(observationFingerprint == null || observationFingerprint.matches(SHA_256_REGEX))
        require(challengeId == null || challengeId.length <= MAX_GRAPH_TEXT_CHARS)
        require(challengeResolutionFingerprint == null ||
            challengeResolutionFingerprint.matches(SHA_256_REGEX))
        require(detailFingerprint == null || detailFingerprint.matches(SHA_256_REGEX))
        require(
            fingerprint == receiptFingerprint(
                actionId = actionId,
                idempotencyKey = idempotencyKey,
                state = state,
                recordedAt = recordedAt,
                externalReference = externalReference,
                observationFingerprint = observationFingerprint,
                challengeId = challengeId,
                challengeResolutionFingerprint = challengeResolutionFingerprint,
                detailFingerprint = detailFingerprint,
            )
        )
    }

    companion object {
        fun from(receipt: EffectReceipt): ExternalEffectReceiptRef {
            val detailFingerprint = receipt.detail?.let {
                externalActionFingerprint("external-effect-detail/v1", it)
            }
            return ExternalEffectReceiptRef(
                actionId = receipt.actionId,
                idempotencyKey = receipt.idempotencyKey,
                state = receipt.state,
                recordedAt = receipt.recordedAt,
                externalReference = receipt.externalReference,
                observationFingerprint = receipt.observationFingerprint,
                challengeId = receipt.challengeId,
                challengeResolutionFingerprint = receipt.challengeResolutionFingerprint,
                detailFingerprint = detailFingerprint,
                fingerprint = receiptFingerprint(
                    actionId = receipt.actionId,
                    idempotencyKey = receipt.idempotencyKey,
                    state = receipt.state,
                    recordedAt = receipt.recordedAt,
                    externalReference = receipt.externalReference,
                    observationFingerprint = receipt.observationFingerprint,
                    challengeId = receipt.challengeId,
                    challengeResolutionFingerprint = receipt.challengeResolutionFingerprint,
                    detailFingerprint = detailFingerprint,
                ),
            )
        }
    }
}

data class ExternalEffectReceiptNode(
    val receipt: ExternalEffectReceiptRef,
    override val id: ExternalActionNodeId = nodeId(
        ExternalActionNodeKind.EFFECT_RECEIPT,
        receipt.fingerprint,
    ),
) : ExternalActionGraphNode {
    init {
        require(id == nodeId(ExternalActionNodeKind.EFFECT_RECEIPT, receipt.fingerprint))
    }

    override val kind: ExternalActionNodeKind = ExternalActionNodeKind.EFFECT_RECEIPT

    override fun fingerprint(): String = externalActionFingerprint(
        "external-action-effect-receipt-node/v1",
        receipt.fingerprint,
    )
}

data class ExternalObservationNode(
    val observationFingerprint: String,
    val resourceIdentity: String,
    val observedAt: Instant,
    val observationRevision: String? = null,
    val fieldFingerprints: Map<String, String> = emptyMap(),
    override val id: ExternalActionNodeId = nodeId(
        ExternalActionNodeKind.OBSERVATION,
        observationFingerprint,
        resourceIdentity,
        observedAt.toString(),
        observationRevision.orEmpty(),
        canonicalFieldFingerprint(fieldFingerprints),
    ),
) : ExternalActionGraphNode {
    init {
        require(observationFingerprint.matches(SHA_256_REGEX))
        requireBoundedIdentity(resourceIdentity, "observation resource")
        require(observationRevision == null || observationRevision.length <= MAX_GRAPH_TEXT_CHARS)
        validateFieldFingerprints(fieldFingerprints)
        require(
            id == nodeId(
                ExternalActionNodeKind.OBSERVATION,
                observationFingerprint,
                resourceIdentity,
                observedAt.toString(),
                observationRevision.orEmpty(),
                canonicalFieldFingerprint(fieldFingerprints),
            )
        )
    }

    override val kind: ExternalActionNodeKind = ExternalActionNodeKind.OBSERVATION

    override fun fingerprint(): String = externalActionFingerprint(
        "external-action-observation-node/v1",
        observationFingerprint,
        resourceIdentity,
        observedAt.toString(),
        observationRevision.orEmpty(),
        canonicalFieldFingerprint(fieldFingerprints),
    )
}

enum class ExternalActionOutcomeState {
    CONFIRMED,
    CONTRADICTED,
    PARTIAL,
    UNKNOWN,
}

data class OutcomeNode(
    val state: ExternalActionOutcomeState,
    val basisObservationId: ExternalActionNodeId?,
    val reasonCode: String,
    override val id: ExternalActionNodeId = nodeId(
        ExternalActionNodeKind.OUTCOME,
        state.name,
        basisObservationId?.value.orEmpty(),
        reasonCode,
    ),
) : ExternalActionGraphNode {
    init {
        require(reasonCode.isNotBlank() && reasonCode.length <= MAX_GRAPH_TEXT_CHARS)
        require(reasonCode.none { it == '\u0000' || it == '\n' || it == '\r' })
        require(id == nodeId(
            ExternalActionNodeKind.OUTCOME,
            state.name,
            basisObservationId?.value.orEmpty(),
            reasonCode,
        ))
    }

    override val kind: ExternalActionNodeKind = ExternalActionNodeKind.OUTCOME

    override fun fingerprint(): String = externalActionFingerprint(
        "external-action-outcome-node/v1",
        state.name,
        basisObservationId?.value.orEmpty(),
        reasonCode,
    )
}

enum class ExternalActionEdgeType {
    REQUEST_PLANNED_AS,
    PLAN_AUTHORIZED_BY,
    PLAN_EXPOSED_AS,
    RECEIPT_OBSERVED_BY,
    OBSERVATION_CLASSIFIED_AS_OUTCOME,
}

data class ExternalActionGraphEdge(
    val type: ExternalActionEdgeType,
    val from: ExternalActionNodeId,
    val to: ExternalActionNodeId,
    val id: ExternalActionEdgeId = edgeId(type, from, to),
) {
    init {
        require(from != to)
        require(id == edgeId(type, from, to))
    }

    fun fingerprint(): String = externalActionFingerprint(
        "external-action-edge/v1",
        type.name,
        from.value,
        to.value,
    )
}

data class ExternalActionGraphRevision(
    val graphId: ExternalActionGraphId,
    val revision: Long,
    val predecessorRevisionId: ExternalActionGraphRevisionId?,
    val nodesAdded: List<ExternalActionGraphNode>,
    val edgesAdded: List<ExternalActionGraphEdge>,
    val revisionId: ExternalActionGraphRevisionId,
) {
    init {
        require(revision > 0L)
        require((revision == 1L) == (predecessorRevisionId == null))
        require(nodesAdded.isNotEmpty() || edgesAdded.isNotEmpty())
        require(nodesAdded.size <= MAX_NODES_PER_REVISION)
        require(edgesAdded.size <= MAX_EDGES_PER_REVISION)
        require(nodesAdded.map { it.id }.distinct().size == nodesAdded.size)
        require(edgesAdded.map { it.id }.distinct().size == edgesAdded.size)
        require(
            revisionId == expectedRevisionId(
                graphId = graphId,
                revision = revision,
                predecessorRevisionId = predecessorRevisionId,
                nodesAdded = nodesAdded,
                edgesAdded = edgesAdded,
            )
        )
    }

    companion object {
        fun create(
            graphId: ExternalActionGraphId,
            revision: Long,
            predecessorRevisionId: ExternalActionGraphRevisionId?,
            nodesAdded: List<ExternalActionGraphNode>,
            edgesAdded: List<ExternalActionGraphEdge>,
        ): ExternalActionGraphRevision =
            ExternalActionGraphRevision(
                graphId = graphId,
                revision = revision,
                predecessorRevisionId = predecessorRevisionId,
                nodesAdded = nodesAdded.sortedBy { it.id.value },
                edgesAdded = edgesAdded.sortedBy { it.id.value },
                revisionId = expectedRevisionId(
                    graphId = graphId,
                    revision = revision,
                    predecessorRevisionId = predecessorRevisionId,
                    nodesAdded = nodesAdded.sortedBy { it.id.value },
                    edgesAdded = edgesAdded.sortedBy { it.id.value },
                ),
            )
    }
}

data class ExternalActionGraphLoadReport(
    val revisions: List<ExternalActionGraphRevision>,
    val unreadableEntries: List<String> = emptyList(),
) {
    init {
        require(unreadableEntries.none { it.isBlank() })
    }
}

sealed interface ExternalActionGraphWriteResult {
    data class Stored(val revision: ExternalActionGraphRevision) : ExternalActionGraphWriteResult
    data class Duplicate(val revision: ExternalActionGraphRevision) : ExternalActionGraphWriteResult
}

interface ExternalActionReceiptGraphRepository {
    suspend fun append(revision: ExternalActionGraphRevision): ExternalActionGraphWriteResult
    suspend fun load(graphId: ExternalActionGraphId): List<ExternalActionGraphRevision>
    suspend fun loadReport(): ExternalActionGraphLoadReport
}

data class ExternalActionReceiptGraph(
    val graphId: ExternalActionGraphId,
    val revisions: List<ExternalActionGraphRevision>,
    val nodes: Map<ExternalActionNodeId, ExternalActionGraphNode>,
    val edges: List<ExternalActionGraphEdge>,
) {
    init {
        require(revisions.isNotEmpty())
    }

    companion object {
        fun replay(
            revisions: List<ExternalActionGraphRevision>,
        ): ExternalActionReceiptGraph {
            require(revisions.isNotEmpty())
            val ordered = revisions.sortedBy { it.revision }
            val graphId = ordered.first().graphId
            val nodes = linkedMapOf<ExternalActionNodeId, ExternalActionGraphNode>()
            val edges = mutableListOf<ExternalActionGraphEdge>()

            ordered.forEachIndexed { index, revision ->
                require(revision.graphId == graphId) {
                    "External action graph id changed during replay"
                }
                require(revision.revision == index.toLong() + 1L) {
                    "External action graph revisions must be contiguous"
                }
                val expectedPredecessor = if (index == 0) null else ordered[index - 1].revisionId
                require(revision.predecessorRevisionId == expectedPredecessor) {
                    "External action graph predecessor mismatch"
                }

                revision.nodesAdded.forEach { node ->
                    val previous = nodes.putIfAbsent(node.id, node)
                    require(previous == null || previous == node) {
                        "External action graph node identity collision"
                    }
                }
                revision.edgesAdded.forEach { edge ->
                    require(nodes.containsKey(edge.from) && nodes.containsKey(edge.to)) {
                        "External action graph edge references unknown node"
                    }
                    validateEdgeTypes(edge, nodes)
                    require(edges.none { it.id == edge.id } || edges.any { it == edge }) {
                        "External action graph edge identity collision"
                    }
                    if (edges.none { it.id == edge.id }) edges += edge
                }
            }

            requireRootChain(nodes, edges)
            return ExternalActionReceiptGraph(
                graphId = graphId,
                revisions = ordered,
                nodes = nodes.toMap(),
                edges = edges.sortedBy { it.id.value },
            )
        }

        fun initialRevision(
            requestFingerprint: String,
            resourceIdentity: String,
            dispatchPlanFingerprint: String,
            policyAssessment: OwnerPolicyAssessment,
            receipt: EffectReceipt,
        ): ExternalActionGraphRevision {
            require(policyAssessment.allowed) {
                "Initial external action graph requires the policy decision that exposed the action"
            }
            val request = ExternalActionRequestNode(requestFingerprint, resourceIdentity)
            val plan = CapabilityPlanNode(dispatchPlanFingerprint)
            val policy = OwnerPolicyDecisionNode.from(policyAssessment)
            val effect = ExternalEffectReceiptNode(ExternalEffectReceiptRef.from(receipt))
            val graphId = ExternalActionGraphId(
                ExternalActionGraphId.PREFIX + externalActionFingerprint(
                    "external-action-graph/v1",
                    request.fingerprint(),
                    plan.fingerprint(),
                    policy.fingerprint(),
                    effect.fingerprint(),
                )
            )
            return ExternalActionGraphRevision.create(
                graphId = graphId,
                revision = 1L,
                predecessorRevisionId = null,
                nodesAdded = listOf(request, plan, policy, effect),
                edgesAdded = listOf(
                    ExternalActionGraphEdge(
                        ExternalActionEdgeType.REQUEST_PLANNED_AS,
                        request.id,
                        plan.id,
                    ),
                    ExternalActionGraphEdge(
                        ExternalActionEdgeType.PLAN_AUTHORIZED_BY,
                        plan.id,
                        policy.id,
                    ),
                    ExternalActionGraphEdge(
                        ExternalActionEdgeType.PLAN_EXPOSED_AS,
                        plan.id,
                        effect.id,
                    ),
                ),
            )
        }
    }
}

data class ExternalActionObservationExpectation(
    val resourceIdentity: String,
    val exposedAt: Instant,
    val horizon: Duration,
    val expectedFieldFingerprints: Map<String, String> = emptyMap(),
) {
    init {
        requireBoundedIdentity(resourceIdentity, "expected resource")
        require(!horizon.isNegative && !horizon.isZero)
        require(horizon <= MAX_RECONCILIATION_HORIZON)
        validateFieldFingerprints(expectedFieldFingerprints)
    }
}

data class ExternalActionReconciliation(
    val state: ExternalActionOutcomeState,
    val observationId: ExternalActionNodeId?,
    val reasonCode: String,
)

object ExternalActionObservationReconciler {
    fun reconcile(
        expectation: ExternalActionObservationExpectation,
        observation: ExternalObservationNode?,
    ): ExternalActionReconciliation {
        if (observation == null) {
            return ExternalActionReconciliation(
                ExternalActionOutcomeState.UNKNOWN,
                null,
                "no-observation",
            )
        }
        require(observation.resourceIdentity == expectation.resourceIdentity) {
            "External observation resource does not match action resource"
        }
        require(!observation.observedAt.isBefore(expectation.exposedAt)) {
            "External observation predates action exposure"
        }
        require(!observation.observedAt.isAfter(expectation.exposedAt.plus(expectation.horizon))) {
            "External observation is outside reconciliation horizon"
        }

        if (expectation.expectedFieldFingerprints.isEmpty()) {
            return ExternalActionReconciliation(
                ExternalActionOutcomeState.UNKNOWN,
                observation.id,
                "no-comparable-fields",
            )
        }

        var matched = 0
        var mismatched = 0
        var missing = 0
        expectation.expectedFieldFingerprints.forEach { (field, expected) ->
            val observed = observation.fieldFingerprints[field]
            when {
                observed == null -> missing += 1
                observed == expected -> matched += 1
                else -> mismatched += 1
            }
        }

        val state = when {
            mismatched == 0 && missing == 0 ->
                ExternalActionOutcomeState.CONFIRMED
            matched == 0 && missing == 0 && mismatched > 0 ->
                ExternalActionOutcomeState.CONTRADICTED
            else ->
                ExternalActionOutcomeState.PARTIAL
        }
        val reason = when (state) {
            ExternalActionOutcomeState.CONFIRMED -> "all-expected-fields-match"
            ExternalActionOutcomeState.CONTRADICTED -> "all-comparable-fields-contradict"
            ExternalActionOutcomeState.PARTIAL -> "mixed-or-incomplete-observation"
            ExternalActionOutcomeState.UNKNOWN -> "unknown"
        }
        return ExternalActionReconciliation(state, observation.id, reason)
    }

    fun reconcileCandidates(
        expectation: ExternalActionObservationExpectation,
        observations: List<ExternalObservationNode>,
    ): ExternalActionReconciliation {
        val compatible = observations.filter {
            it.resourceIdentity == expectation.resourceIdentity &&
                !it.observedAt.isBefore(expectation.exposedAt) &&
                !it.observedAt.isAfter(expectation.exposedAt.plus(expectation.horizon))
        }
        return when (compatible.size) {
            0 -> ExternalActionReconciliation(
                ExternalActionOutcomeState.UNKNOWN,
                null,
                "no-compatible-observation",
            )
            1 -> reconcile(expectation, compatible.single())
            else -> ExternalActionReconciliation(
                ExternalActionOutcomeState.UNKNOWN,
                null,
                "ambiguous-multiple-observations",
            )
        }
    }
}

object ExternalActionReceiptGraphCodec {
    const val CODEC_VERSION = 1
    const val MAX_PAYLOAD_BYTES = 512 * 1024

    fun encode(revision: ExternalActionGraphRevision): ByteArray =
        ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(CODEC_VERSION)
                writeString(data, revision.graphId.value)
                data.writeLong(revision.revision)
                writeNullable(data, revision.predecessorRevisionId?.value)
                data.writeInt(revision.nodesAdded.size)
                revision.nodesAdded.sortedBy { it.id.value }.forEach { writeNode(data, it) }
                data.writeInt(revision.edgesAdded.size)
                revision.edgesAdded.sortedBy { it.id.value }.forEach { writeEdge(data, it) }
                writeString(data, revision.revisionId.value)
            }
            output.toByteArray()
        }.also {
            require(it.size in 1..MAX_PAYLOAD_BYTES) {
                "External action graph payload exceeds bound"
            }
        }

    fun decode(bytes: ByteArray): ExternalActionGraphRevision {
        require(bytes.size in 1..MAX_PAYLOAD_BYTES)
        val data = DataInputStream(ByteArrayInputStream(bytes))
        require(data.readInt() == MAGIC) { "Invalid external action graph payload magic" }
        require(data.readInt() == CODEC_VERSION) {
            "Unsupported external action graph codec version"
        }
        val graphId = ExternalActionGraphId(readString(data))
        val revision = data.readLong()
        val predecessor = readNullable(data)?.let(::ExternalActionGraphRevisionId)
        val nodeCount = data.readInt()
        require(nodeCount in 0..MAX_NODES_PER_REVISION)
        val nodes = List(nodeCount) { readNode(data) }
        val edgeCount = data.readInt()
        require(edgeCount in 0..MAX_EDGES_PER_REVISION)
        val edges = List(edgeCount) { readEdge(data) }
        val revisionId = ExternalActionGraphRevisionId(readString(data))
        require(data.available() == 0) { "Trailing external action graph payload bytes" }
        return ExternalActionGraphRevision(
            graphId = graphId,
            revision = revision,
            predecessorRevisionId = predecessor,
            nodesAdded = nodes,
            edgesAdded = edges,
            revisionId = revisionId,
        )
    }

    private fun writeNode(
        data: DataOutputStream,
        node: ExternalActionGraphNode,
    ) {
        data.writeInt(node.kind.ordinal)
        when (node) {
            is ExternalActionRequestNode -> {
                writeString(data, node.requestFingerprint)
                writeString(data, node.resourceIdentity)
            }
            is CapabilityPlanNode -> {
                writeString(data, node.dispatchPlanFingerprint)
            }
            is OwnerPolicyDecisionNode -> {
                writeString(data, node.decisionId)
                data.writeLong(node.policyRevision)
                data.writeBoolean(node.allowed)
            }
            is ExternalEffectReceiptNode -> {
                writeReceiptRef(data, node.receipt)
            }
            is ExternalObservationNode -> {
                writeString(data, node.observationFingerprint)
                writeString(data, node.resourceIdentity)
                writeInstant(data, node.observedAt)
                writeNullable(data, node.observationRevision)
                writeFieldMap(data, node.fieldFingerprints)
            }
            is OutcomeNode -> {
                data.writeInt(node.state.ordinal)
                writeNullable(data, node.basisObservationId?.value)
                writeString(data, node.reasonCode)
            }
        }
        writeString(data, node.id.value)
    }

    private fun readNode(data: DataInputStream): ExternalActionGraphNode {
        val kinds = ExternalActionNodeKind.entries
        val ordinal = data.readInt()
        require(ordinal in kinds.indices)
        return when (kinds[ordinal]) {
            ExternalActionNodeKind.REQUEST -> {
                val request = readString(data)
                val resource = readString(data)
                val id = ExternalActionNodeId(readString(data))
                ExternalActionRequestNode(request, resource, id)
            }
            ExternalActionNodeKind.CAPABILITY_PLAN -> {
                val fingerprint = readString(data)
                val id = ExternalActionNodeId(readString(data))
                CapabilityPlanNode(fingerprint, id)
            }
            ExternalActionNodeKind.OWNER_POLICY_DECISION -> {
                val decision = readString(data)
                val revision = data.readLong()
                val allowed = data.readBoolean()
                val id = ExternalActionNodeId(readString(data))
                OwnerPolicyDecisionNode(decision, revision, allowed, id)
            }
            ExternalActionNodeKind.EFFECT_RECEIPT -> {
                val receipt = readReceiptRef(data)
                val id = ExternalActionNodeId(readString(data))
                ExternalEffectReceiptNode(receipt, id)
            }
            ExternalActionNodeKind.OBSERVATION -> {
                val fingerprint = readString(data)
                val resource = readString(data)
                val observedAt = readInstant(data)
                val observationRevision = readNullable(data)
                val fields = readFieldMap(data)
                val id = ExternalActionNodeId(readString(data))
                ExternalObservationNode(
                    observationFingerprint = fingerprint,
                    resourceIdentity = resource,
                    observedAt = observedAt,
                    observationRevision = observationRevision,
                    fieldFingerprints = fields,
                    id = id,
                )
            }
            ExternalActionNodeKind.OUTCOME -> {
                val states = ExternalActionOutcomeState.entries
                val stateOrdinal = data.readInt()
                require(stateOrdinal in states.indices)
                val state = states[stateOrdinal]
                val basis = readNullable(data)?.let(::ExternalActionNodeId)
                val reason = readString(data)
                val id = ExternalActionNodeId(readString(data))
                OutcomeNode(state, basis, reason, id)
            }
        }
    }

    private fun writeEdge(
        data: DataOutputStream,
        edge: ExternalActionGraphEdge,
    ) {
        data.writeInt(edge.type.ordinal)
        writeString(data, edge.from.value)
        writeString(data, edge.to.value)
        writeString(data, edge.id.value)
    }

    private fun readEdge(data: DataInputStream): ExternalActionGraphEdge {
        val types = ExternalActionEdgeType.entries
        val ordinal = data.readInt()
        require(ordinal in types.indices)
        return ExternalActionGraphEdge(
            type = types[ordinal],
            from = ExternalActionNodeId(readString(data)),
            to = ExternalActionNodeId(readString(data)),
            id = ExternalActionEdgeId(readString(data)),
        )
    }

    private fun writeReceiptRef(
        data: DataOutputStream,
        receipt: ExternalEffectReceiptRef,
    ) {
        writeString(data, receipt.actionId)
        writeString(data, receipt.idempotencyKey)
        data.writeInt(receipt.state.ordinal)
        writeInstant(data, receipt.recordedAt)
        writeNullable(data, receipt.externalReference)
        writeNullable(data, receipt.observationFingerprint)
        writeNullable(data, receipt.challengeId)
        writeNullable(data, receipt.challengeResolutionFingerprint)
        writeNullable(data, receipt.detailFingerprint)
        writeString(data, receipt.fingerprint)
    }

    private fun readReceiptRef(data: DataInputStream): ExternalEffectReceiptRef {
        val states = ExternalEffectState.entries
        val actionId = readString(data)
        val idempotencyKey = readString(data)
        val stateOrdinal = data.readInt()
        require(stateOrdinal in states.indices)
        return ExternalEffectReceiptRef(
            actionId = actionId,
            idempotencyKey = idempotencyKey,
            state = states[stateOrdinal],
            recordedAt = readInstant(data),
            externalReference = readNullable(data),
            observationFingerprint = readNullable(data),
            challengeId = readNullable(data),
            challengeResolutionFingerprint = readNullable(data),
            detailFingerprint = readNullable(data),
            fingerprint = readString(data),
        )
    }

    private fun writeFieldMap(
        data: DataOutputStream,
        fields: Map<String, String>,
    ) {
        validateFieldFingerprints(fields)
        data.writeInt(fields.size)
        fields.toSortedMap().forEach { (key, value) ->
            writeString(data, key)
            writeString(data, value)
        }
    }

    private fun readFieldMap(data: DataInputStream): Map<String, String> {
        val count = data.readInt()
        require(count in 0..MAX_OBSERVATION_FIELDS)
        return buildMap {
            repeat(count) {
                val key = readString(data)
                val value = readString(data)
                require(put(key, value) == null) { "Duplicate observation field key" }
            }
        }.also(::validateFieldFingerprints)
    }

    private fun writeInstant(data: DataOutputStream, value: Instant) {
        data.writeLong(value.epochSecond)
        data.writeInt(value.nano)
    }

    private fun readInstant(data: DataInputStream): Instant {
        val seconds = data.readLong()
        val nanos = data.readInt()
        require(nanos in 0..999_999_999)
        return Instant.ofEpochSecond(seconds, nanos.toLong())
    }

    private fun writeNullable(data: DataOutputStream, value: String?) {
        data.writeBoolean(value != null)
        value?.let { writeString(data, it) }
    }

    private fun readNullable(data: DataInputStream): String? =
        if (data.readBoolean()) readString(data) else null

    private fun writeString(data: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_GRAPH_TEXT_BYTES)
        data.writeInt(bytes.size)
        data.write(bytes)
    }

    private fun readString(data: DataInputStream): String {
        val size = data.readInt()
        require(size in 0..MAX_GRAPH_TEXT_BYTES && size <= data.available())
        return ByteArray(size).also(data::readFully).toString(Charsets.UTF_8)
    }

    private const val MAGIC = 0x45414731 // EAG1
}

private fun validateEdgeTypes(
    edge: ExternalActionGraphEdge,
    nodes: Map<ExternalActionNodeId, ExternalActionGraphNode>,
) {
    val from = requireNotNull(nodes[edge.from]).kind
    val to = requireNotNull(nodes[edge.to]).kind
    val expected = when (edge.type) {
        ExternalActionEdgeType.REQUEST_PLANNED_AS ->
            ExternalActionNodeKind.REQUEST to ExternalActionNodeKind.CAPABILITY_PLAN
        ExternalActionEdgeType.PLAN_AUTHORIZED_BY ->
            ExternalActionNodeKind.CAPABILITY_PLAN to ExternalActionNodeKind.OWNER_POLICY_DECISION
        ExternalActionEdgeType.PLAN_EXPOSED_AS ->
            ExternalActionNodeKind.CAPABILITY_PLAN to ExternalActionNodeKind.EFFECT_RECEIPT
        ExternalActionEdgeType.RECEIPT_OBSERVED_BY ->
            ExternalActionNodeKind.EFFECT_RECEIPT to ExternalActionNodeKind.OBSERVATION
        ExternalActionEdgeType.OBSERVATION_CLASSIFIED_AS_OUTCOME ->
            ExternalActionNodeKind.OBSERVATION to ExternalActionNodeKind.OUTCOME
    }
    require(from == expected.first && to == expected.second) {
        "External action graph edge node-kind mismatch"
    }
}

private fun requireRootChain(
    nodes: Map<ExternalActionNodeId, ExternalActionGraphNode>,
    edges: List<ExternalActionGraphEdge>,
) {
    val requests = nodes.values.filter { it.kind == ExternalActionNodeKind.REQUEST }
    val plans = nodes.values.filter { it.kind == ExternalActionNodeKind.CAPABILITY_PLAN }
    val policies = nodes.values.filter { it.kind == ExternalActionNodeKind.OWNER_POLICY_DECISION }
    val receipts = nodes.values.filter { it.kind == ExternalActionNodeKind.EFFECT_RECEIPT }
    require(requests.size == 1 && plans.size == 1 && policies.size == 1 && receipts.size == 1) {
        "External action graph must contain exactly one root request/plan/policy/receipt chain"
    }
    require(edges.any {
        it.type == ExternalActionEdgeType.REQUEST_PLANNED_AS &&
            it.from == requests.single().id && it.to == plans.single().id
    })
    require(edges.any {
        it.type == ExternalActionEdgeType.PLAN_AUTHORIZED_BY &&
            it.from == plans.single().id && it.to == policies.single().id
    })
    require(edges.any {
        it.type == ExternalActionEdgeType.PLAN_EXPOSED_AS &&
            it.from == plans.single().id && it.to == receipts.single().id
    })
}

private fun nodeId(
    kind: ExternalActionNodeKind,
    vararg parts: String,
): ExternalActionNodeId =
    ExternalActionNodeId(
        ExternalActionNodeId.PREFIX + externalActionFingerprint(
            "external-action-node-id/v1",
            kind.name,
            *parts,
        )
    )

private fun edgeId(
    type: ExternalActionEdgeType,
    from: ExternalActionNodeId,
    to: ExternalActionNodeId,
): ExternalActionEdgeId =
    ExternalActionEdgeId(
        ExternalActionEdgeId.PREFIX + externalActionFingerprint(
            "external-action-edge-id/v1",
            type.name,
            from.value,
            to.value,
        )
    )

private fun expectedRevisionId(
    graphId: ExternalActionGraphId,
    revision: Long,
    predecessorRevisionId: ExternalActionGraphRevisionId?,
    nodesAdded: List<ExternalActionGraphNode>,
    edgesAdded: List<ExternalActionGraphEdge>,
): ExternalActionGraphRevisionId =
    ExternalActionGraphRevisionId(
        ExternalActionGraphRevisionId.PREFIX + externalActionFingerprint(
            "external-action-graph-revision/v1",
            graphId.value,
            revision.toString(),
            predecessorRevisionId?.value.orEmpty(),
            nodesAdded.sortedBy { it.id.value }.joinToString("\u001f") {
                it.id.value + ":" + it.fingerprint()
            },
            edgesAdded.sortedBy { it.id.value }.joinToString("\u001f") {
                it.id.value + ":" + it.fingerprint()
            },
        )
    )

private fun receiptFingerprint(
    actionId: String,
    idempotencyKey: String,
    state: ExternalEffectState,
    recordedAt: Instant,
    externalReference: String?,
    observationFingerprint: String?,
    challengeId: String?,
    challengeResolutionFingerprint: String?,
    detailFingerprint: String?,
): String = externalActionFingerprint(
    "external-effect-receipt-ref/v1",
    actionId,
    idempotencyKey,
    state.name,
    recordedAt.toString(),
    externalReference.orEmpty(),
    observationFingerprint.orEmpty(),
    challengeId.orEmpty(),
    challengeResolutionFingerprint.orEmpty(),
    detailFingerprint.orEmpty(),
)

private fun validateFieldFingerprints(fields: Map<String, String>) {
    require(fields.size <= MAX_OBSERVATION_FIELDS)
    fields.forEach { (key, value) ->
        require(key.isNotBlank() && key.length <= MAX_FIELD_KEY_CHARS)
        require(key.none(Char::isISOControl))
        require(value.matches(SHA_256_REGEX))
    }
}

private fun canonicalFieldFingerprint(fields: Map<String, String>): String {
    validateFieldFingerprints(fields)
    return externalActionFingerprint(
        "external-action-observation-fields/v1",
        fields.toSortedMap().entries.joinToString("\u001f") { (key, value) -> "$key=$value" },
    )
}

private fun requireBoundedIdentity(
    value: String,
    name: String,
) {
    require(value.isNotBlank() && value.length <= MAX_GRAPH_TEXT_CHARS) {
        "$name must be bounded and nonblank"
    }
    require(value.none { it == '\u0000' || it == '\n' || it == '\r' }) {
        "$name must be single-line"
    }
}

private fun externalActionFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(domain, *parts).forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private val SHA_256_REGEX = Regex("[0-9a-f]{64}")
private val MAX_RECONCILIATION_HORIZON: Duration = Duration.ofDays(30)

private const val MAX_GRAPH_TEXT_CHARS = 16_384
private const val MAX_GRAPH_TEXT_BYTES = 64 * 1024
private const val MAX_FIELD_KEY_CHARS = 256
private const val MAX_OBSERVATION_FIELDS = 256
private const val MAX_NODES_PER_REVISION = 512
private const val MAX_EDGES_PER_REVISION = 1024
