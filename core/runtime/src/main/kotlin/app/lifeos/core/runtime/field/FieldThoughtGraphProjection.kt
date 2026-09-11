package app.lifeos.core.runtime.field

import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.FieldConvergenceRequest
import app.lifeos.core.field.FieldConvergenceResult
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.field.FieldSnapshotRepository
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.thought.DurableThoughtGraph
import app.lifeos.core.runtime.thought.ThoughtGraphDelta
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaCodec
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeKind
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeVersion
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import app.lifeos.core.runtime.thought.ThoughtGraphNodeVersion
import app.lifeos.core.runtime.thought.ThoughtGraphProvenance
import app.lifeos.core.runtime.thought.ThoughtGraphSourceKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@JvmInline
value class FieldThoughtGraphProjectionId(val value: String) {
    init { require(value.isNotBlank()) { "Field thought-graph projection id must not be blank" } }
}

data class FieldThoughtGraphProjectionEnvelope(
    val id: FieldThoughtGraphProjectionId,
    val snapshotId: FieldSnapshotId,
    val snapshotFingerprint: String,
    val delta: ThoughtGraphDelta,
) {
    init {
        require(snapshotFingerprint.isNotBlank()) { "Projection snapshot fingerprint must not be blank" }
        require(delta.sourceKey == sourceKey(snapshotId)) {
            "Projection delta must be bound to its field snapshot"
        }
        require(id == createId(snapshotId, snapshotFingerprint, delta)) {
            "Projection id must match exact snapshot/delta content"
        }
    }

    companion object {
        fun create(
            snapshotId: FieldSnapshotId,
            snapshotFingerprint: String,
            delta: ThoughtGraphDelta,
        ): FieldThoughtGraphProjectionEnvelope = FieldThoughtGraphProjectionEnvelope(
            id = createId(snapshotId, snapshotFingerprint, delta),
            snapshotId = snapshotId,
            snapshotFingerprint = snapshotFingerprint,
            delta = delta,
        )

        fun sourceKey(snapshotId: FieldSnapshotId): String = "field-snapshot:${snapshotId.value}"

        private fun createId(
            snapshotId: FieldSnapshotId,
            snapshotFingerprint: String,
            delta: ThoughtGraphDelta,
        ): FieldThoughtGraphProjectionId = FieldThoughtGraphProjectionId(
            "field-thought-graph-projection:" + StableFieldIds.fingerprint(
                "field-thought-graph-projection/v1",
                snapshotId.value,
                snapshotFingerprint,
                delta.id.value,
            )
        )
    }
}

data class FieldThoughtGraphProjectionLoadReport(
    val envelopes: List<FieldThoughtGraphProjectionEnvelope>,
    val unreadableEntries: List<String>,
) {
    init {
        require(envelopes == envelopes.distinctBy { it.id }.sortedBy { it.id.value }) {
            "Projection envelopes must be unique and deterministically ordered"
        }
        require(unreadableEntries == unreadableEntries.distinct().sorted()) {
            "Unreadable projection entries must be unique and deterministically ordered"
        }
    }
}

sealed interface FieldThoughtGraphProjectionWriteResult {
    val envelope: FieldThoughtGraphProjectionEnvelope

    data class Stored(
        override val envelope: FieldThoughtGraphProjectionEnvelope,
    ) : FieldThoughtGraphProjectionWriteResult

    data class Duplicate(
        override val envelope: FieldThoughtGraphProjectionEnvelope,
    ) : FieldThoughtGraphProjectionWriteResult
}

interface FieldThoughtGraphProjectionOutboxRepository {
    suspend fun save(envelope: FieldThoughtGraphProjectionEnvelope): FieldThoughtGraphProjectionWriteResult
    suspend fun load(id: FieldThoughtGraphProjectionId): FieldThoughtGraphProjectionEnvelope?
    suspend fun loadReport(): FieldThoughtGraphProjectionLoadReport
}

/** Canonical bounded binary codec for one immutable snapshot-bound projection envelope. */
object FieldThoughtGraphProjectionCodec {
    const val MAX_PAYLOAD_BYTES: Int = ThoughtGraphDeltaCodec.MAX_PAYLOAD_BYTES + 1024 * 1024
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 256 * 1024

    fun encode(envelope: FieldThoughtGraphProjectionEnvelope): ByteArray {
        val deltaBytes = ThoughtGraphDeltaCodec.encode(envelope.delta)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(VERSION)
            writeString(out, envelope.id.value)
            writeString(out, envelope.snapshotId.value)
            writeString(out, envelope.snapshotFingerprint)
            out.writeInt(deltaBytes.size)
            out.write(deltaBytes)
        }
        return output.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_PAYLOAD_BYTES) {
                "Field thought-graph projection payload too large"
            }
        }
    }

    fun decode(payload: ByteArray): FieldThoughtGraphProjectionEnvelope {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
            "Invalid field thought-graph projection payload size"
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported field thought-graph projection version" }
            val id = FieldThoughtGraphProjectionId(readString(input))
            val snapshotId = FieldSnapshotId(readString(input))
            val snapshotFingerprint = readString(input)
            val deltaLength = input.readInt()
            require(deltaLength in 1..ThoughtGraphDeltaCodec.MAX_PAYLOAD_BYTES && deltaLength == input.available()) {
                "Invalid field thought-graph projection delta length"
            }
            val delta = ThoughtGraphDeltaCodec.decode(ByteArray(deltaLength).also(input::readFully))
            FieldThoughtGraphProjectionEnvelope(
                id = id,
                snapshotId = snapshotId,
                snapshotFingerprint = snapshotFingerprint,
                delta = delta,
            )
        }
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Field thought-graph projection string too large" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available()) {
            "Invalid field thought-graph projection string length"
        }
        return ByteArray(length).also(input::readFully).toString(StandardCharsets.UTF_8)
    }
}

/** Deterministic projection of committed field semantics into one content-addressed graph delta. */
class FieldThoughtGraphProjector {
    fun project(
        photon: Photon,
        request: FieldConvergenceRequest,
        result: FieldConvergenceResult,
    ): FieldThoughtGraphProjectionEnvelope {
        require(request.domainId == result.snapshot.domainId) { "Projection request/result domain mismatch" }
        val snapshot = result.snapshot
        val observedAt = request.evidence.maxOfOrNull { it.observedAt } ?: photon.provenance.createdAt
        val photonNode = photonNode(photon, request.evidence)
        val evidenceNodes = request.evidence.sortedBy { it.id.value }.associate { evidence ->
            evidence.id to evidenceNode(evidence)
        }
        val hypothesisNodes = result.hypotheses.sortedBy { it.id.value }.associate { hypothesis ->
            hypothesis.id to hypothesisNode(photon, request.evidence, hypothesis, snapshot.id.value, observedAt)
        }

        val edges = buildList {
            request.evidence.sortedBy { it.id.value }.forEach { evidence ->
                val evidenceNode = evidenceNodes.getValue(evidence.id)
                add(
                    ThoughtGraphEdgeVersion.create(
                        sourceNodeId = evidenceNode.id,
                        targetNodeId = photonNode.id,
                        kind = ThoughtGraphEdgeKind.DERIVED_FROM,
                        semanticKey = "evidence-source:${evidence.id.value}",
                        confidence = evidence.reliability.score,
                        authority = evidence.authority.defaultWeight,
                        validity = evidence.validity,
                        provenance = evidenceNode.provenance,
                        explanation = "Field evidence ${evidence.id.value} is derived from source Photon ${photon.id.value}",
                    )
                )
            }
            result.hypotheses.sortedBy { it.id.value }.forEach hypothesisLoop@ { hypothesis ->
                val hypothesisNode = hypothesisNodes.getValue(hypothesis.id)
                hypothesis.evidenceLinks
                    .sortedWith(compareBy({ it.evidenceId.value }, { it.relation.name }))
                    .forEach evidenceLoop@ { link ->
                        val evidenceNode = evidenceNodes[link.evidenceId] ?: return@evidenceLoop
                        val evidence = request.evidence.first { it.id == link.evidenceId }
                        val hypothesisDerivedFromEvidence = link.relation == EvidenceRelationType.DERIVED_FROM
                        val sourceNodeId = if (hypothesisDerivedFromEvidence) hypothesisNode.id else evidenceNode.id
                        val targetNodeId = if (hypothesisDerivedFromEvidence) evidenceNode.id else hypothesisNode.id
                        val explanation = if (hypothesisDerivedFromEvidence) {
                            "Hypothesis ${hypothesis.id.value} is derived from evidence ${link.evidenceId.value}"
                        } else {
                            "Evidence ${link.evidenceId.value} ${link.relation.name.lowercase()} hypothesis ${hypothesis.id.value}"
                        }
                        add(
                            ThoughtGraphEdgeVersion.create(
                                sourceNodeId = sourceNodeId,
                                targetNodeId = targetNodeId,
                                kind = link.relation.toThoughtGraphEdgeKind(),
                                semanticKey = "hypothesis-evidence:${hypothesis.id.value}:${link.evidenceId.value}:${link.relation.name}",
                                confidence = link.weight,
                                authority = evidence.authority.defaultWeight,
                                validity = evidence.validity,
                                provenance = hypothesisNode.provenance,
                                explanation = explanation,
                            )
                        )
                    }
                hypothesis.conflicts.sortedBy { it.competingHypothesisId.value }.forEach conflictLoop@ { conflict ->
                    val target = hypothesisNodes[conflict.competingHypothesisId] ?: return@conflictLoop
                    add(
                        ThoughtGraphEdgeVersion.create(
                            sourceNodeId = hypothesisNode.id,
                            targetNodeId = target.id,
                            kind = ThoughtGraphEdgeKind.COMPETES_WITH,
                            semanticKey = "hypothesis-conflict:${hypothesis.id.value}:${conflict.competingHypothesisId.value}",
                            confidence = conflict.strength,
                            authority = minOf(hypothesisNode.authority, target.authority),
                            validity = TemporalValidity.UNBOUNDED,
                            provenance = hypothesisNode.provenance,
                            explanation = conflict.reason,
                        )
                    )
                }
            }
        }

        val delta = ThoughtGraphDelta.create(
            sourceKey = FieldThoughtGraphProjectionEnvelope.sourceKey(snapshot.id),
            sourceRevision = photon.revision,
            nodeVersions = listOf(photonNode) + evidenceNodes.values + hypothesisNodes.values,
            edgeVersions = edges,
            observedAt = observedAt,
        )
        return FieldThoughtGraphProjectionEnvelope.create(
            snapshotId = snapshot.id,
            snapshotFingerprint = snapshot.contentFingerprint(),
            delta = delta,
        )
    }

    private fun photonNode(photon: Photon, evidence: List<FieldEvidence>): ThoughtGraphNodeVersion {
        val matchingAuthority = evidence
            .filter { it.sourcePhotonId == photon.id && it.sourceRevision == photon.revision }
            .maxOfOrNull { it.authority.defaultWeight } ?: 0.0
        val provenance = ThoughtGraphProvenance(
            sourceKind = ThoughtGraphSourceKind.PHOTON,
            sourceId = photon.id.value,
            sourceRevision = photon.revision,
            sourceFingerprint = runtimePhotonFingerprint(photon),
            origin = photon.provenance.source,
            actor = photon.provenance.actor,
            createdAt = photon.provenance.createdAt,
        )
        return ThoughtGraphNodeVersion.create(
            kind = ThoughtGraphNodeKind.PHOTON,
            semanticKey = "photon",
            summary = photon.content.take(MAX_SUMMARY_CHARS),
            confidence = photon.confidence,
            authority = matchingAuthority,
            validity = TemporalValidity.at(photon.provenance.createdAt),
            provenance = provenance,
            attributes = mapOf(
                "mimeType" to photon.mimeType,
                "phase" to photon.phase.name,
                "revision" to photon.revision.toString(),
            ),
        )
    }

    private fun evidenceNode(evidence: FieldEvidence): ThoughtGraphNodeVersion {
        val provenance = ThoughtGraphProvenance(
            sourceKind = ThoughtGraphSourceKind.EVIDENCE,
            sourceId = evidence.id.value,
            sourceRevision = evidence.sourceRevision,
            sourceFingerprint = evidence.sourceFingerprint,
            origin = "field:${evidence.domainId.value}",
            actor = "field-convergence",
            createdAt = evidence.observedAt,
        )
        return ThoughtGraphNodeVersion.create(
            kind = ThoughtGraphNodeKind.EVIDENCE,
            semanticKey = evidence.semanticKey,
            summary = evidence.explanation.take(MAX_SUMMARY_CHARS),
            confidence = evidence.confidence,
            authority = evidence.authority.defaultWeight,
            validity = evidence.validity,
            provenance = provenance,
            attributes = mapOf(
                "domainId" to evidence.domainId.value,
                "evidenceKind" to evidence.kind.name,
                "payloadFingerprint" to evidence.payload.stableFingerprint(),
                "reliability" to java.lang.Double.toHexString(evidence.reliability.score),
            ),
        )
    }

    private fun hypothesisNode(
        photon: Photon,
        evidence: List<FieldEvidence>,
        hypothesis: FieldHypothesis,
        snapshotId: String,
        observedAt: Instant,
    ): ThoughtGraphNodeVersion {
        val linkedEvidence = hypothesis.evidenceLinks.mapNotNull { link ->
            evidence.firstOrNull { it.id == link.evidenceId }?.let { link to it }
        }
        val authority = if (linkedEvidence.isEmpty()) 0.0 else linkedEvidence
            .sumOf { (link, item) -> link.weight * item.authority.defaultWeight }
            .div(linkedEvidence.sumOf { it.first.weight }.takeIf { it > 0.0 } ?: 1.0)
            .coerceIn(0.0, 1.0)
        val provenance = ThoughtGraphProvenance(
            sourceKind = ThoughtGraphSourceKind.HYPOTHESIS,
            sourceId = hypothesis.id.value,
            sourceRevision = photon.revision,
            sourceFingerprint = hypothesisFingerprint(hypothesis),
            origin = "field:${hypothesis.domainId.value}",
            actor = "field-convergence",
            createdAt = observedAt,
        )
        return ThoughtGraphNodeVersion.create(
            kind = ThoughtGraphNodeKind.HYPOTHESIS,
            semanticKey = hypothesis.semanticKey,
            summary = hypothesis.explanation.take(MAX_SUMMARY_CHARS),
            confidence = hypothesis.score.total,
            authority = authority,
            validity = TemporalValidity.UNBOUNDED,
            provenance = provenance,
            attributes = mapOf(
                "domainId" to hypothesis.domainId.value,
                "scope" to hypothesis.scope.name,
                "state" to hypothesis.state.name,
                "snapshotId" to snapshotId,
            ),
        )
    }

    private fun hypothesisFingerprint(hypothesis: FieldHypothesis): String = StableFieldIds.fingerprint(
        "field-hypothesis/final/v1",
        hypothesis.id.value,
        hypothesis.domainId.value,
        hypothesis.semanticKey,
        hypothesis.scope.name,
        hypothesis.state.name,
        hypothesis.explanation,
        java.lang.Double.toHexString(hypothesis.score.evidence),
        java.lang.Double.toHexString(hypothesis.score.support),
        java.lang.Double.toHexString(hypothesis.score.contradiction),
        java.lang.Double.toHexString(hypothesis.score.context),
        java.lang.Double.toHexString(hypothesis.score.temporal),
        java.lang.Double.toHexString(hypothesis.score.authority),
        java.lang.Double.toHexString(hypothesis.score.total),
        *buildList {
            hypothesis.nodeIds.sortedBy { it.value }.forEach { add("node:${it.value}") }
            hypothesis.evidenceLinks
                .sortedWith(compareBy({ it.evidenceId.value }, { it.relation.name }))
                .forEach { add("evidence:${it.evidenceId.value}:${it.relation.name}:${java.lang.Double.toHexString(it.weight)}") }
            hypothesis.conflicts.sortedBy { it.competingHypothesisId.value }.forEach {
                add("conflict:${it.competingHypothesisId.value}:${java.lang.Double.toHexString(it.strength)}:${it.reason}")
            }
        }.toTypedArray(),
    )

    private fun EvidenceRelationType.toThoughtGraphEdgeKind(): ThoughtGraphEdgeKind = when (this) {
        EvidenceRelationType.SUPPORTS -> ThoughtGraphEdgeKind.SUPPORTS
        EvidenceRelationType.CONTRADICTS -> ThoughtGraphEdgeKind.CONTRADICTS
        EvidenceRelationType.DERIVED_FROM -> ThoughtGraphEdgeKind.DERIVED_FROM
        EvidenceRelationType.REFINES,
        EvidenceRelationType.DUPLICATES,
        -> ThoughtGraphEdgeKind.REFERENCES
    }

    private companion object {
        const val MAX_SUMMARY_CHARS = 4096
    }
}

data class FieldThoughtGraphProjectionReconcileReport(
    val projected: Int,
    val alreadyApplied: Int,
    val deferredWithoutSnapshot: Int,
    val remainingBeyondBatch: Int,
)

/**
 * Crash-recovery bridge from immutable projection envelopes to the durable graph.
 * Only an exact committed FieldSnapshot authorizes graph materialization.
 */
class FieldThoughtGraphProjectionCoordinator(
    private val outbox: FieldThoughtGraphProjectionOutboxRepository,
    private val snapshots: FieldSnapshotRepository,
    private val graph: DurableThoughtGraph,
    private val projector: FieldThoughtGraphProjector = FieldThoughtGraphProjector(),
    private val maxBatchSize: Int = 100,
) {
    private val mutex = Mutex()

    init { require(maxBatchSize in 1..10_000) { "Projection batch size must be in 1..10000" } }

    suspend fun prepare(
        photon: Photon,
        request: FieldConvergenceRequest,
        result: FieldConvergenceResult,
    ): FieldThoughtGraphProjectionEnvelope = mutex.withLock {
        val proposed = projector.project(photon, request, result)
        when (val write = outbox.save(proposed)) {
            is FieldThoughtGraphProjectionWriteResult.Stored -> write.envelope
            is FieldThoughtGraphProjectionWriteResult.Duplicate -> write.envelope
        }
    }

    suspend fun materialize(envelope: FieldThoughtGraphProjectionEnvelope): Boolean = mutex.withLock {
        materializeIfCommitted(envelope)
    }

    suspend fun reconcile(): FieldThoughtGraphProjectionReconcileReport = mutex.withLock {
        val load = outbox.loadReport()
        require(load.unreadableEntries.isEmpty()) {
            "Field thought-graph projection outbox contains unreadable entries: ${load.unreadableEntries.joinToString()}"
        }
        val applied = graph.state.value.appliedDeltaIds.toHashSet()
        val pending = load.envelopes.filter { it.delta.id !in applied }
        var projected = 0
        var deferred = 0
        pending.take(maxBatchSize).forEach { envelope ->
            if (materializeIfCommitted(envelope)) projected++ else deferred++
        }
        FieldThoughtGraphProjectionReconcileReport(
            projected = projected,
            alreadyApplied = load.envelopes.size - pending.size,
            deferredWithoutSnapshot = deferred,
            remainingBeyondBatch = (pending.size - maxBatchSize).coerceAtLeast(0),
        )
    }

    private suspend fun materializeIfCommitted(envelope: FieldThoughtGraphProjectionEnvelope): Boolean {
        if (envelope.delta.id in graph.state.value.appliedDeltaIds) return true
        val snapshot = snapshots.load(envelope.snapshotId) ?: return false
        check(snapshot.contentFingerprint() == envelope.snapshotFingerprint) {
            "Field snapshot/projection fingerprint mismatch for ${envelope.snapshotId.value}"
        }
        graph.append(envelope.delta, envelope.delta.observedAt)
        return true
    }
}
