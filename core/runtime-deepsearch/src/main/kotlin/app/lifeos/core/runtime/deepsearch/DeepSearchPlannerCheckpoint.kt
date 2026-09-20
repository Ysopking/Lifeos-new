package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.field.EvidenceId
import app.lifeos.core.field.HypothesisId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Duration

data class DeepSearchPlannerCheckpoint(
    val request: DeepSearchRequest,
    val frontier: DeepSearchFrontierSnapshot,
    val evidence: List<DeepSearchEvidence>,
    val trace: List<DeepSearchTraceEvent>,
    val workUnitsUsed: Int,
    val elapsedMillisUsed: Long,
    val blockedSourceIds: Set<String>,
    val failedSourceIds: Set<String>,
    val rootExpanded: Boolean,
) {
    init {
        require(workUnitsUsed in 0..request.budget.maxWorkUnits)
        require(elapsedMillisUsed >= 0L)
        require(frontier.admittedBranches.all { it.requestId == request.id })
        require(evidence.all { it.requestId == request.id })
        require(evidence.map { it.id }.distinct().size == evidence.size)
        require(trace.map { it.sequence } == trace.indices.toList())
        require(blockedSourceIds.none { it.isBlank() })
        require(failedSourceIds.none { it.isBlank() })
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "deep-search-planner-checkpoint/v2",
        request.id.value,
        workUnitsUsed.toString(),
        elapsedMillisUsed.toString(),
        rootExpanded.toString(),
        *frontier.admittedBranches.map(::branchFingerprint).sorted().toTypedArray(),
        *frontier.queuedBranchIds.map { "queued:${it.value}" }.sorted().toTypedArray(),
        *frontier.expandedBranchIds.map { "expanded:${it.value}" }.sorted().toTypedArray(),
        *evidence.map(::evidenceFingerprint).sorted().toTypedArray(),
        *trace.map(::traceFingerprint).toTypedArray(),
        *blockedSourceIds.sorted().map { "blocked:$it" }.toTypedArray(),
        *failedSourceIds.sorted().map { "failed:$it" }.toTypedArray(),
    )

    private fun branchFingerprint(branch: DeepSearchBranch): String = StableFieldIds.fingerprint(
        "deep-search-checkpoint-branch/v1",
        branch.id.value,
        branch.requestId.value,
        branch.parentId?.value.orEmpty(),
        branch.sourceId,
        branch.depth.toString(),
        branch.hypothesis.id.value,
        branch.hypothesis.statement,
        java.lang.Double.toHexString(branch.hypothesis.confidence),
        branch.hypothesis.fieldHypothesisId?.value.orEmpty(),
        *branch.hypothesis.semanticTerms.sorted().toTypedArray(),
        *branch.hypothesis.evidenceIds.map { it.value }.sorted().toTypedArray(),
        java.lang.Double.toHexString(branch.score.relevance),
        java.lang.Double.toHexString(branch.score.evidenceStrength),
        java.lang.Double.toHexString(branch.score.sourceReliability),
        java.lang.Double.toHexString(branch.score.novelty),
        java.lang.Double.toHexString(branch.score.depthCost),
        java.lang.Double.toHexString(branch.score.contradictionPenalty),
        java.lang.Double.toHexString(branch.score.total),
    )

    /** Legacy semantic fingerprint retained so codec v1 checkpoints remain verifiable. */
    private fun evidenceFingerprint(value: DeepSearchEvidence): String = StableFieldIds.fingerprint(
        "deep-search-checkpoint-evidence/v1",
        value.id.value,
        value.requestId.value,
        value.branchId.value,
        value.sourceId,
        value.statement,
        java.lang.Double.toHexString(value.confidence),
        value.sourcePhotonId?.value.orEmpty(),
        value.fieldEvidenceId?.value.orEmpty(),
        value.contradiction.toString(),
    )

    private fun traceFingerprint(value: DeepSearchTraceEvent): String = StableFieldIds.fingerprint(
        "deep-search-checkpoint-trace/v1",
        value.sequence.toString(),
        value.type.name,
        value.branchId?.value.orEmpty(),
        value.sourceId.orEmpty(),
        value.hypothesisId?.value.orEmpty(),
        value.detail,
        *value.evidenceIds.map { it.value }.sorted().toTypedArray(),
    )
}

fun interface DeepSearchCheckpointSink {
    suspend fun persist(checkpoint: DeepSearchPlannerCheckpoint)
}

object DeepSearchPlannerCheckpointCodec {
    private const val MAGIC = 0x44534332 // DSC2
    private const val VERSION = 2
    private const val LEGACY_VERSION = 1
    private const val MAX_STRING_BYTES = 256 * 1024
    private const val MAX_ITEMS = 16_384
    const val MAX_PAYLOAD_BYTES = 32 * 1024 * 1024

    fun encode(checkpoint: DeepSearchPlannerCheckpoint): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            write(out, persistedFingerprint(checkpoint))
            writeRequest(out, checkpoint.request)
            writeFrontier(out, checkpoint.frontier)
            writeListSize(out, checkpoint.evidence.size)
            checkpoint.evidence.sortedBy { it.id.value }.forEach { writeEvidence(out, it) }
            writeListSize(out, checkpoint.trace.size)
            checkpoint.trace.forEach { writeTrace(out, it) }
            out.writeInt(checkpoint.workUnitsUsed)
            out.writeLong(checkpoint.elapsedMillisUsed)
            writeStringSet(out, checkpoint.blockedSourceIds)
            writeStringSet(out, checkpoint.failedSourceIds)
            out.writeBoolean(checkpoint.rootExpanded)
        }
        return bytes.toByteArray().also { require(it.size <= MAX_PAYLOAD_BYTES) }
    }

    fun decode(bytes: ByteArray): DeepSearchPlannerCheckpoint {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid DeepSearch checkpoint magic" }
        val version = input.readInt()
        require(version == LEGACY_VERSION || version == VERSION) {
            "Unsupported DeepSearch checkpoint version"
        }
        val fingerprint = read(input)
        val request = readRequest(input)
        val frontier = readFrontier(input, request)
        val evidenceCount = readListSize(input)
        val evidence = List(evidenceCount) { readEvidence(input, request.id, version) }
        val traceCount = readListSize(input)
        val trace = List(traceCount) { readTrace(input) }
        val checkpoint = DeepSearchPlannerCheckpoint(
            request = request,
            frontier = frontier,
            evidence = evidence,
            trace = trace,
            workUnitsUsed = input.readInt(),
            elapsedMillisUsed = input.readLong(),
            blockedSourceIds = readStringSet(input),
            failedSourceIds = readStringSet(input),
            rootExpanded = input.readBoolean(),
        )
        require(input.available() == 0) { "Trailing DeepSearch checkpoint bytes" }
        val expectedFingerprint = if (version == LEGACY_VERSION) {
            checkpoint.fingerprint()
        } else {
            persistedFingerprint(checkpoint)
        }
        require(fingerprint == expectedFingerprint) { "DeepSearch checkpoint fingerprint mismatch" }
        return checkpoint
    }

    private fun persistedFingerprint(checkpoint: DeepSearchPlannerCheckpoint): String =
        StableFieldIds.fingerprint(
            "deep-search-checkpoint-codec/v2",
            checkpoint.fingerprint(),
            *checkpoint.evidence
                .sortedBy { it.id.value }
                .map { evidence ->
                    listOf(
                        evidence.id.value,
                        evidence.sourcePhotonId?.value.orEmpty(),
                        evidence.sourcePhotonRevision?.toString().orEmpty(),
                    ).joinToString(":")
                }
                .toTypedArray(),
        )

    private fun writeRequest(out: DataOutputStream, request: DeepSearchRequest) {
        write(out, request.query)
        writeStringSet(out, request.contextTerms)
        out.writeInt(request.budget.maxDepth)
        out.writeInt(request.budget.maxBreadth)
        out.writeInt(request.budget.maxWorkUnits)
        out.writeLong(request.budget.maxElapsed.seconds)
        out.writeInt(request.budget.maxElapsed.nano)
        out.writeDouble(request.minimumResolutionScore)
        out.writeDouble(request.minimumWinnerMargin)
        write(out, request.id.value)
    }

    private fun readRequest(input: DataInputStream): DeepSearchRequest {
        val query = read(input)
        val contextTerms = readStringSet(input)
        val budget = DeepSearchBudget(
            maxDepth = input.readInt(),
            maxBreadth = input.readInt(),
            maxWorkUnits = input.readInt(),
            maxElapsed = Duration.ofSeconds(input.readLong(), input.readInt().toLong()),
        )
        val request = DeepSearchRequest(
            query = query,
            contextTerms = contextTerms,
            budget = budget,
            minimumResolutionScore = input.readDouble(),
            minimumWinnerMargin = input.readDouble(),
        )
        require(read(input) == request.id.value) { "DeepSearch checkpoint request fingerprint mismatch" }
        return request
    }

    private fun writeFrontier(out: DataOutputStream, frontier: DeepSearchFrontierSnapshot) {
        writeListSize(out, frontier.admittedBranches.size)
        frontier.admittedBranches.sortedBy { it.id.value }.forEach { writeBranch(out, it) }
        writeIdSet(out, frontier.queuedBranchIds.map { it.value }.toSet())
        writeIdSet(out, frontier.expandedBranchIds.map { it.value }.toSet())
    }

    private fun readFrontier(input: DataInputStream, request: DeepSearchRequest): DeepSearchFrontierSnapshot {
        val branches = List(readListSize(input)) { readBranch(input, request.id) }
        return DeepSearchFrontierSnapshot(
            admittedBranches = branches,
            queuedBranchIds = readIdSet(input).mapTo(linkedSetOf(), ::DeepSearchBranchId),
            expandedBranchIds = readIdSet(input).mapTo(linkedSetOf(), ::DeepSearchBranchId),
        )
    }

    private fun writeBranch(out: DataOutputStream, branch: DeepSearchBranch) {
        write(out, branch.id.value)
        writeNullable(out, branch.parentId?.value)
        write(out, branch.sourceId)
        out.writeInt(branch.depth)
        write(out, branch.hypothesis.id.value)
        write(out, branch.hypothesis.statement)
        writeStringSet(out, branch.hypothesis.semanticTerms)
        out.writeDouble(branch.hypothesis.confidence)
        writeIdSet(out, branch.hypothesis.evidenceIds.map { it.value }.toSet())
        writeNullable(out, branch.hypothesis.fieldHypothesisId?.value)
        out.writeDouble(branch.score.relevance)
        out.writeDouble(branch.score.evidenceStrength)
        out.writeDouble(branch.score.sourceReliability)
        out.writeDouble(branch.score.novelty)
        out.writeDouble(branch.score.depthCost)
        out.writeDouble(branch.score.contradictionPenalty)
        out.writeDouble(branch.score.total)
    }

    private fun readBranch(input: DataInputStream, requestId: DeepSearchRequestId): DeepSearchBranch {
        val branchId = DeepSearchBranchId(read(input))
        val parentId = readNullable(input)?.let(::DeepSearchBranchId)
        val sourceId = read(input)
        val depth = input.readInt()
        val hypothesis = DeepSearchHypothesis(
            id = DeepSearchHypothesisId(read(input)),
            requestId = requestId,
            statement = read(input),
            semanticTerms = readStringSet(input),
            confidence = input.readDouble(),
            evidenceIds = readIdSet(input).mapTo(linkedSetOf(), ::DeepSearchEvidenceId),
            fieldHypothesisId = readNullable(input)?.let(::HypothesisId),
        )
        val score = DeepSearchScore(
            relevance = input.readDouble(),
            evidenceStrength = input.readDouble(),
            sourceReliability = input.readDouble(),
            novelty = input.readDouble(),
            depthCost = input.readDouble(),
            contradictionPenalty = input.readDouble(),
            total = input.readDouble(),
        )
        return DeepSearchBranch(
            id = branchId,
            requestId = requestId,
            parentId = parentId,
            sourceId = sourceId,
            depth = depth,
            hypothesis = hypothesis,
            score = score,
        )
    }

    private fun writeEvidence(out: DataOutputStream, value: DeepSearchEvidence) {
        write(out, value.id.value)
        write(out, value.branchId.value)
        write(out, value.sourceId)
        write(out, value.statement)
        out.writeDouble(value.confidence)
        writeNullable(out, value.sourcePhotonId?.value)
        writeNullableLong(out, value.sourcePhotonRevision)
        writeNullable(out, value.fieldEvidenceId?.value)
        out.writeBoolean(value.contradiction)
    }

    private fun readEvidence(
        input: DataInputStream,
        requestId: DeepSearchRequestId,
        version: Int,
    ): DeepSearchEvidence {
        val id = DeepSearchEvidenceId(read(input))
        val branchId = DeepSearchBranchId(read(input))
        val sourceId = read(input)
        val statement = read(input)
        val confidence = input.readDouble()
        val sourcePhotonId = readNullable(input)?.let(::PhotonId)
        val sourcePhotonRevision = if (version >= VERSION) readNullableLong(input) else null
        val fieldEvidenceId = readNullable(input)?.let(::EvidenceId)
        val contradiction = input.readBoolean()
        return DeepSearchEvidence(
            id = id,
            requestId = requestId,
            branchId = branchId,
            sourceId = sourceId,
            statement = statement,
            confidence = confidence,
            sourcePhotonId = sourcePhotonId,
            fieldEvidenceId = fieldEvidenceId,
            contradiction = contradiction,
            sourcePhotonRevision = sourcePhotonRevision,
        )
    }

    private fun writeTrace(out: DataOutputStream, value: DeepSearchTraceEvent) {
        out.writeInt(value.sequence)
        write(out, value.type.name)
        writeNullable(out, value.branchId?.value)
        writeNullable(out, value.sourceId)
        writeNullable(out, value.hypothesisId?.value)
        writeIdSet(out, value.evidenceIds.map { it.value }.toSet())
        write(out, value.detail)
    }

    private fun readTrace(input: DataInputStream) = DeepSearchTraceEvent(
        sequence = input.readInt(),
        type = enumValueOf(read(input)),
        branchId = readNullable(input)?.let(::DeepSearchBranchId),
        sourceId = readNullable(input),
        hypothesisId = readNullable(input)?.let(::DeepSearchHypothesisId),
        evidenceIds = readIdSet(input).mapTo(linkedSetOf(), ::DeepSearchEvidenceId),
        detail = read(input),
    )

    private fun writeStringSet(out: DataOutputStream, values: Set<String>) {
        writeListSize(out, values.size)
        values.sorted().forEach { write(out, it) }
    }

    private fun readStringSet(input: DataInputStream): Set<String> {
        val count = readListSize(input)
        val values = List(count) { read(input) }.toSet()
        require(values.size == count) { "Duplicate DeepSearch checkpoint string" }
        return values.toSortedSet()
    }

    private fun writeIdSet(out: DataOutputStream, values: Set<String>) = writeStringSet(out, values)
    private fun readIdSet(input: DataInputStream): Set<String> = readStringSet(input)

    private fun writeNullable(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) write(out, value)
    }

    private fun readNullable(input: DataInputStream): String? = if (input.readBoolean()) read(input) else null

    private fun writeNullableLong(out: DataOutputStream, value: Long?) {
        out.writeBoolean(value != null)
        if (value != null) out.writeLong(value)
    }

    private fun readNullableLong(input: DataInputStream): Long? =
        if (input.readBoolean()) input.readLong().also { require(it > 0L) } else null

    private fun writeListSize(out: DataOutputStream, size: Int) {
        require(size in 0..MAX_ITEMS)
        out.writeInt(size)
    }

    private fun readListSize(input: DataInputStream): Int = input.readInt().also {
        require(it in 0..MAX_ITEMS)
    }

    private fun write(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun read(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available())
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }
}
