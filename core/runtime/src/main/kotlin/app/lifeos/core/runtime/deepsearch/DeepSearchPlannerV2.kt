package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.field.StableFieldIds
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * V12 restart-safe DeepSearch planner.
 *
 * The legacy planner remains available as a stable fallback. V2 adds durable checkpoints at the
 * search loop boundaries and, crucially, persists a work reservation before each source expansion.
 * A crash may cause a read-only source expansion to be retried, but cannot multiply its work charge.
 * Source scope is frozen at mission start; sources initially blocked cannot become newly authorized
 * during resume, while initially-authorized sources are permission/capability rechecked each run.
 */
class DeepSearchPlannerV2(
    private val evaluator: DeepSearchEvaluator = DeepSearchEvaluator(),
    private val capabilityGate: DeepSearchCapabilityGate = LocalOnlyDeepSearchCapabilityGate,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun search(
        request: DeepSearchRequest,
        sources: List<DeepSearchSource>,
        resume: DeepSearchPlannerCheckpoint? = null,
        checkpointSink: DeepSearchCheckpointSink? = null,
    ): DeepSearchResult {
        val sortedSources = sources.sortedBy { it.descriptor.sourceId }
        val sourceIds = sortedSources.map { it.descriptor.sourceId }
        require(sourceIds.distinct().size == sourceIds.size) {
            "DeepSearch source ids must be unique"
        }
        if (resume != null) {
            require(resume.request == request) { "DeepSearch resume request changed" }
        }

        val trace = resume?.trace?.toMutableList() ?: mutableListOf()
        val evidenceById = linkedMapOf<DeepSearchEvidenceId, DeepSearchEvidence>().apply {
            resume?.evidence?.forEach { put(it.id, it) }
        }
        val blocked = (resume?.blockedSourceIds ?: emptySet()).toSortedSet()
        val failed = (resume?.failedSourceIds ?: emptySet()).toSortedSet()
        var workUnits = resume?.workUnitsUsed ?: 0
        var rootExpanded = resume?.rootExpanded ?: false
        val baseElapsedMillis = resume?.elapsedMillisUsed ?: 0L
        val segmentStartedAt = now()
        val frontier = DeepSearchFrontier(request, resume?.frontier)
        val root = rootBranch(request)

        fun emit(
            type: DeepSearchTraceType,
            branch: DeepSearchBranch? = null,
            sourceId: String? = null,
            hypothesisId: DeepSearchHypothesisId? = null,
            evidenceIds: Set<DeepSearchEvidenceId> = emptySet(),
            detail: String,
        ) {
            trace += DeepSearchTraceEvent(
                sequence = trace.size,
                type = type,
                branchId = branch?.id,
                sourceId = sourceId,
                hypothesisId = hypothesisId,
                evidenceIds = evidenceIds,
                detail = detail,
            )
        }

        fun elapsedMillisUsed(): Long {
            val segment = Duration.between(segmentStartedAt, now())
            val nonNegative = if (segment.isNegative) Duration.ZERO else segment
            return safeAdd(baseElapsedMillis, nonNegative.toMillis())
        }

        fun timeExceeded(): Boolean = elapsedMillisUsed() >= maxElapsedMillis(request)

        fun remainingMillis(): Long =
            (maxElapsedMillis(request) - elapsedMillisUsed()).coerceAtLeast(1L)

        suspend fun persistCheckpoint() {
            checkpointSink?.persist(
                DeepSearchPlannerCheckpoint(
                    request = request,
                    frontier = frontier.snapshot(),
                    evidence = evidenceById.values.sortedBy { it.id.value },
                    trace = trace.toList(),
                    workUnitsUsed = workUnits,
                    elapsedMillisUsed = elapsedMillisUsed(),
                    blockedSourceIds = blocked.toSortedSet(),
                    failedSourceIds = failed.toSortedSet(),
                    rootExpanded = rootExpanded,
                )
            )
        }

        if (resume == null) {
            emit(
                type = DeepSearchTraceType.ROOT_CREATED,
                branch = root,
                hypothesisId = root.hypothesis.id,
                detail = "deterministic-query-root-v2",
            )
        } else {
            require(trace.any {
                it.type == DeepSearchTraceType.ROOT_CREATED && it.branchId == root.id
            }) { "DeepSearch checkpoint is missing deterministic root lineage" }
        }

        val authorized = authorizeSources(
            sources = sortedSources,
            resume = resume,
            trace = trace,
            blocked = blocked,
            timeExceeded = ::timeExceeded,
            emit = ::emit,
        )
        persistCheckpoint()

        if (sortedSources.isEmpty()) {
            emit(DeepSearchTraceType.UNRESOLVED, detail = "no-usable-source")
            persistCheckpoint()
            return result(
                request = request,
                status = DeepSearchStatus.NO_USABLE_SOURCE,
                frontier = frontier,
                evidence = evidenceById,
                trace = trace,
                workUnits = workUnits,
                blocked = blocked,
                failed = failed,
            )
        }
        if (authorized.isEmpty()) {
            val status = if (timeExceeded()) {
                DeepSearchStatus.TIME_BUDGET_EXHAUSTED
            } else {
                DeepSearchStatus.PERMISSION_BLOCKED
            }
            emit(
                if (status == DeepSearchStatus.TIME_BUDGET_EXHAUSTED) {
                    DeepSearchTraceType.TIME_LIMIT_REACHED
                } else {
                    DeepSearchTraceType.UNRESOLVED
                },
                detail = if (status == DeepSearchStatus.TIME_BUDGET_EXHAUSTED) {
                    "authorization-time-budget-exhausted"
                } else {
                    "all-mission-sources-blocked"
                },
            )
            persistCheckpoint()
            return result(
                request, status, frontier, evidenceById, trace,
                workUnits, blocked, failed,
            )
        }

        var workExhausted = false
        var timeExhausted = false
        var current: DeepSearchBranch? = when {
            !rootExpanded -> root
            else -> unfinishedExpandedBranch(frontier, authorized, trace)
                ?: frontier.poll()
        }

        searchLoop@ while (current != null) {
            if (timeExceeded()) {
                timeExhausted = true
                emit(DeepSearchTraceType.TIME_LIMIT_REACHED, current, detail = "planner-v2-time-budget")
                persistCheckpoint()
                break
            }
            if (current.depth >= request.budget.maxDepth) {
                emit(
                    DeepSearchTraceType.DEPTH_LIMIT_REACHED,
                    current,
                    hypothesisId = current.hypothesis.id,
                    detail = "max-depth:${request.budget.maxDepth}",
                )
                if (current.id == root.id) rootExpanded = true
                persistCheckpoint()
                val resolution = evaluator.resolve(request, frontier.admittedBranches())
                if (resolution.resolved) {
                    emitResolved(resolution, emit = ::emit)
                    persistCheckpoint()
                    return result(
                        request, DeepSearchStatus.RESOLVED, frontier, evidenceById, trace,
                        workUnits, blocked, failed, resolution,
                    )
                }
                current = unfinishedExpandedBranch(frontier, authorized, trace)
                    ?: frontier.poll()
                continue
            }

            for (source in authorized) {
                val sourceId = source.descriptor.sourceId
                if (expansionFinished(trace, current.id, sourceId)) continue

                val alreadyReserved = expansionReservationOutstanding(trace, current.id, sourceId)
                if (!alreadyReserved) {
                    val cost = source.descriptor.workUnitsPerExpansion
                    if (workUnits + cost > request.budget.maxWorkUnits) {
                        workExhausted = true
                        emit(
                            DeepSearchTraceType.WORK_LIMIT_REACHED,
                            current,
                            sourceId,
                            detail = "work:$workUnits+$cost>${request.budget.maxWorkUnits}",
                        )
                        persistCheckpoint()
                        break@searchLoop
                    }
                    if (timeExceeded()) {
                        timeExhausted = true
                        emit(
                            DeepSearchTraceType.TIME_LIMIT_REACHED,
                            current,
                            sourceId,
                            detail = "before-v2-source-expansion",
                        )
                        persistCheckpoint()
                        break@searchLoop
                    }
                    workUnits += cost
                    emit(
                        DeepSearchTraceType.EXPANSION_RESERVED,
                        current,
                        sourceId,
                        hypothesisId = current.hypothesis.id,
                        detail = "reserved-work:$cost:total:$workUnits",
                    )
                    persistCheckpoint()
                }

                val drafts = try {
                    withTimeout(remainingMillis()) {
                        source.expand(request, current)
                    }
                } catch (timeout: TimeoutCancellationException) {
                    timeExhausted = true
                    emit(
                        DeepSearchTraceType.TIME_LIMIT_REACHED,
                        current,
                        sourceId,
                        detail = "source-expansion-time-budget-v2",
                    )
                    persistCheckpoint()
                    break@searchLoop
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failed += sourceId
                    emit(
                        DeepSearchTraceType.SOURCE_FAILED,
                        current,
                        sourceId,
                        detail = stableError(error),
                    )
                    persistCheckpoint()
                    continue
                }

                emit(
                    DeepSearchTraceType.BRANCH_EXPANDED,
                    current,
                    sourceId,
                    detail = "findings:${drafts.size}:work:$workUnits:v2",
                )
                val orderedDrafts = drafts.sortedWith(findingOrder())
                    .take(request.budget.maxBreadth * 2)
                for ((index, draft) in orderedDrafts.withIndex()) {
                    val candidate = materialize(
                        request = request,
                        parent = current,
                        source = source.descriptor,
                        draft = draft,
                        ordinal = index,
                        seenSignatures = frontier.signatures(),
                    )
                    candidate.evidence.forEach { evidence ->
                        evidenceById.putIfAbsent(evidence.id, evidence)
                    }
                    when (val offered = frontier.offer(candidate.branch)) {
                        is DeepSearchFrontierOffer -> when (offered.status) {
                            DeepSearchFrontierOfferStatus.ACCEPTED,
                            DeepSearchFrontierOfferStatus.DUPLICATE_REPLACED,
                            DeepSearchFrontierOfferStatus.BREADTH_REPLACED,
                            -> emit(
                                DeepSearchTraceType.CANDIDATE_ACCEPTED,
                                candidate.branch,
                                sourceId,
                                candidate.branch.hypothesis.id,
                                candidate.evidence.mapTo(sortedSetOf(compareBy { it.value })) { it.id },
                                detail = "v2:${offered.status.name.lowercase()}",
                            )

                            DeepSearchFrontierOfferStatus.DUPLICATE_REJECTED -> emit(
                                DeepSearchTraceType.CANDIDATE_DUPLICATE,
                                candidate.branch,
                                sourceId,
                                candidate.branch.hypothesis.id,
                                candidate.evidence.mapTo(sortedSetOf(compareBy { it.value })) { it.id },
                                detail = "duplicate-kept:${offered.replacedBranch?.id?.value.orEmpty()}",
                            )

                            DeepSearchFrontierOfferStatus.BREADTH_REJECTED -> emit(
                                DeepSearchTraceType.CANDIDATE_BREADTH_REJECTED,
                                candidate.branch,
                                sourceId,
                                candidate.branch.hypothesis.id,
                                candidate.evidence.mapTo(sortedSetOf(compareBy { it.value })) { it.id },
                                detail = "breadth:${request.budget.maxBreadth}",
                            )
                        }
                    }
                }
                persistCheckpoint()
            }

            if (current.id == root.id) {
                rootExpanded = true
                persistCheckpoint()
            }

            val resolution = evaluator.resolve(request, frontier.admittedBranches())
            if (resolution.resolved) {
                emitResolved(resolution, emit = ::emit)
                persistCheckpoint()
                return result(
                    request, DeepSearchStatus.RESOLVED, frontier, evidenceById, trace,
                    workUnits, blocked, failed, resolution,
                )
            }

            current = unfinishedExpandedBranch(frontier, authorized, trace)
                ?: frontier.poll()
        }

        val finalResolution = evaluator.resolve(request, frontier.admittedBranches())
        val status = when {
            finalResolution.resolved -> DeepSearchStatus.RESOLVED
            timeExhausted || timeExceeded() -> DeepSearchStatus.TIME_BUDGET_EXHAUSTED
            workExhausted -> DeepSearchStatus.WORK_BUDGET_EXHAUSTED
            else -> DeepSearchStatus.UNRESOLVED
        }
        emit(
            if (status == DeepSearchStatus.RESOLVED) {
                DeepSearchTraceType.RESOLVED
            } else {
                DeepSearchTraceType.UNRESOLVED
            },
            finalResolution.best,
            hypothesisId = finalResolution.best?.hypothesis?.id,
            detail = "terminal-v2:${status.name.lowercase()}:margin:${finalResolution.winnerMargin}",
        )
        persistCheckpoint()
        return result(
            request, status, frontier, evidenceById, trace,
            workUnits, blocked, failed, finalResolution,
        )
    }

    private suspend fun authorizeSources(
        sources: List<DeepSearchSource>,
        resume: DeepSearchPlannerCheckpoint?,
        trace: List<DeepSearchTraceEvent>,
        blocked: MutableSet<String>,
        timeExceeded: () -> Boolean,
        emit: (
            DeepSearchTraceType,
            DeepSearchBranch?,
            String?,
            DeepSearchHypothesisId?,
            Set<DeepSearchEvidenceId>,
            String,
        ) -> Unit,
    ): List<DeepSearchSource> {
        if (resume == null) {
            val authorized = mutableListOf<DeepSearchSource>()
            for (source in sources) {
                if (timeExceeded()) break
                val decision = authorize(source)
                if (decision.allowed) {
                    authorized += source
                    emit(
                        DeepSearchTraceType.SOURCE_AUTHORIZED,
                        null,
                        source.descriptor.sourceId,
                        null,
                        emptySet(),
                        decision.reason,
                    )
                } else {
                    blocked += source.descriptor.sourceId
                    emit(
                        DeepSearchTraceType.SOURCE_BLOCKED,
                        null,
                        source.descriptor.sourceId,
                        null,
                        emptySet(),
                        decision.reason,
                    )
                }
            }
            return authorized
        }

        val initialDecisionBySource = linkedMapOf<String, DeepSearchTraceType>()
        trace.forEach { event ->
            if (event.type == DeepSearchTraceType.SOURCE_AUTHORIZED ||
                event.type == DeepSearchTraceType.SOURCE_BLOCKED
            ) {
                val sourceId = event.sourceId ?: return@forEach
                initialDecisionBySource.putIfAbsent(sourceId, event.type)
            }
        }
        val currentIds = sources.map { it.descriptor.sourceId }.toSet()
        require(initialDecisionBySource.keys == currentIds) {
            "DeepSearch source scope changed during resume"
        }

        val authorized = mutableListOf<DeepSearchSource>()
        for (source in sources) {
            val sourceId = source.descriptor.sourceId
            if (initialDecisionBySource[sourceId] != DeepSearchTraceType.SOURCE_AUTHORIZED) {
                continue
            }
            if (timeExceeded()) break
            val decision = authorize(source)
            if (decision.allowed) {
                authorized += source
            } else {
                blocked += sourceId
                emit(
                    DeepSearchTraceType.SOURCE_BLOCKED,
                    null,
                    sourceId,
                    null,
                    emptySet(),
                    "resume-recheck:${decision.reason}",
                )
            }
        }
        return authorized
    }

    private suspend fun authorize(source: DeepSearchSource): DeepSearchSourceAuthorization = try {
        capabilityGate.authorize(source.descriptor)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        DeepSearchSourceAuthorization(false, "authorization-failed:${stableError(error)}")
    }

    private fun unfinishedExpandedBranch(
        frontier: DeepSearchFrontier,
        authorized: List<DeepSearchSource>,
        trace: List<DeepSearchTraceEvent>,
    ): DeepSearchBranch? {
        val snapshot = frontier.snapshot()
        val authorizedIds = authorized.map { it.descriptor.sourceId }
        val unfinished = snapshot.admittedBranches.filter { branch ->
            branch.id in snapshot.expandedBranchIds && authorizedIds.any { sourceId ->
                !expansionFinished(trace, branch.id, sourceId)
            }
        }
        require(unfinished.size <= 1) {
            "DeepSearch checkpoint contains multiple partially expanded branches"
        }
        return unfinished.singleOrNull()
    }

    private fun expansionFinished(
        trace: List<DeepSearchTraceEvent>,
        branchId: DeepSearchBranchId,
        sourceId: String,
    ): Boolean {
        val terminalSequence = trace.asSequence()
            .filter { it.branchId == branchId && it.sourceId == sourceId }
            .filter {
                it.type == DeepSearchTraceType.BRANCH_EXPANDED ||
                    it.type == DeepSearchTraceType.SOURCE_FAILED
            }
            .maxOfOrNull { it.sequence }
            ?: return false
        val reservedSequence = trace.asSequence()
            .filter {
                it.type == DeepSearchTraceType.EXPANSION_RESERVED &&
                    it.branchId == branchId &&
                    it.sourceId == sourceId
            }
            .maxOfOrNull { it.sequence }
            ?: return terminalSequence >= 0
        return terminalSequence > reservedSequence
    }

    private fun expansionReservationOutstanding(
        trace: List<DeepSearchTraceEvent>,
        branchId: DeepSearchBranchId,
        sourceId: String,
    ): Boolean {
        val reservedSequence = trace.asSequence()
            .filter {
                it.type == DeepSearchTraceType.EXPANSION_RESERVED &&
                    it.branchId == branchId &&
                    it.sourceId == sourceId
            }
            .maxOfOrNull { it.sequence }
            ?: return false
        val terminalSequence = trace.asSequence()
            .filter { it.branchId == branchId && it.sourceId == sourceId }
            .filter {
                it.type == DeepSearchTraceType.BRANCH_EXPANDED ||
                    it.type == DeepSearchTraceType.SOURCE_FAILED
            }
            .maxOfOrNull { it.sequence }
        return terminalSequence == null || terminalSequence < reservedSequence
    }

    private fun emitResolved(
        resolution: DeepSearchResolution,
        emit: (
            DeepSearchTraceType,
            DeepSearchBranch?,
            String?,
            DeepSearchHypothesisId?,
            Set<DeepSearchEvidenceId>,
            String,
        ) -> Unit,
    ) {
        emit(
            DeepSearchTraceType.RESOLVED,
            resolution.best,
            null,
            resolution.best?.hypothesis?.id,
            emptySet(),
            "v2-score:${resolution.best?.score?.total}:margin:${resolution.winnerMargin}",
        )
    }

    private fun result(
        request: DeepSearchRequest,
        status: DeepSearchStatus,
        frontier: DeepSearchFrontier,
        evidence: Map<DeepSearchEvidenceId, DeepSearchEvidence>,
        trace: List<DeepSearchTraceEvent>,
        workUnits: Int,
        blocked: Set<String>,
        failed: Set<String>,
        resolution: DeepSearchResolution = evaluator.resolve(request, frontier.admittedBranches()),
    ): DeepSearchResult {
        val admittedEvidenceIds = frontier.admittedBranches()
            .flatMapTo(mutableSetOf()) { it.hypothesis.evidenceIds }
        return DeepSearchResult(
            requestId = request.id,
            status = status,
            best = resolution.best,
            alternatives = resolution.alternatives,
            evidence = evidence.values
                .filter { it.id in admittedEvidenceIds }
                .sortedBy { it.id.value },
            trace = trace.toList(),
            workUnitsUsed = workUnits,
            blockedSourceIds = blocked.toSortedSet(),
            failedSourceIds = failed.toSortedSet(),
        )
    }

    private data class MaterializedFinding(
        val branch: DeepSearchBranch,
        val evidence: List<DeepSearchEvidence>,
    )

    private fun materialize(
        request: DeepSearchRequest,
        parent: DeepSearchBranch,
        source: DeepSearchSourceDescriptor,
        draft: DeepSearchFindingDraft,
        ordinal: Int,
        seenSignatures: Set<String>,
    ): MaterializedFinding {
        val draftKey = draftKey(draft)
        val candidateSeed = StableFieldIds.fingerprint(
            "deep-search-candidate/v1",
            request.id.value,
            parent.id.value,
            source.sourceId,
            (parent.depth + 1).toString(),
            ordinal.toString(),
            draftKey,
        )
        val branchId = DeepSearchBranchId(candidateSeed)
        val evidence = draft.evidence
            .sortedWith(evidenceDraftOrder())
            .mapIndexed { index, evidenceDraft ->
                DeepSearchEvidence(
                    id = DeepSearchEvidenceId(
                        StableFieldIds.fingerprint(
                            "deep-search-evidence/v2",
                            candidateSeed,
                            index.toString(),
                            evidenceDraftKey(evidenceDraft),
                        )
                    ),
                    requestId = request.id,
                    branchId = branchId,
                    sourceId = source.sourceId,
                    statement = evidenceDraft.statement,
                    confidence = evidenceDraft.confidence,
                    sourcePhotonId = evidenceDraft.sourcePhotonId,
                    fieldEvidenceId = evidenceDraft.fieldEvidenceId,
                    contradiction = evidenceDraft.contradiction,
                    sourcePhotonRevision = evidenceDraft.sourcePhotonRevision,
                )
            }
        val hypothesis = DeepSearchHypothesis(
            id = DeepSearchHypothesisId(
                StableFieldIds.fingerprint(
                    "deep-search-hypothesis/v1",
                    request.id.value,
                    draftKey,
                    *evidence.map { it.id.value }.sorted().toTypedArray(),
                )
            ),
            requestId = request.id,
            statement = draft.statement,
            semanticTerms = (draft.semanticTerms + tokenizeSearchText(draft.statement)).toSortedSet(),
            confidence = draft.confidence,
            evidenceIds = evidence.mapTo(sortedSetOf(compareBy { it.value })) { it.id },
            fieldHypothesisId = draft.fieldHypothesisId,
        )
        val score = evaluator.score(
            request = request,
            finding = draft,
            source = source,
            depth = parent.depth + 1,
            previouslySeenSignatures = seenSignatures,
        )
        return MaterializedFinding(
            branch = DeepSearchBranch(
                id = branchId,
                requestId = request.id,
                parentId = parent.id,
                sourceId = source.sourceId,
                depth = parent.depth + 1,
                hypothesis = hypothesis,
                score = score,
            ),
            evidence = evidence,
        )
    }

    private fun rootBranch(request: DeepSearchRequest): DeepSearchBranch {
        val branchId = DeepSearchBranchId(
            StableFieldIds.fingerprint("deep-search-root/v1", request.id.value)
        )
        val evidenceId = DeepSearchEvidenceId(
            StableFieldIds.fingerprint("deep-search-root-evidence/v1", request.id.value)
        )
        val hypothesis = DeepSearchHypothesis(
            id = DeepSearchHypothesisId(
                StableFieldIds.fingerprint("deep-search-root-hypothesis/v1", request.id.value)
            ),
            requestId = request.id,
            statement = request.query,
            semanticTerms = (request.queryTerms + request.contextTerms.map(::normalizeSearchText)).toSortedSet(),
            confidence = 1.0,
            evidenceIds = setOf(evidenceId),
        )
        return DeepSearchBranch(
            id = branchId,
            requestId = request.id,
            parentId = null,
            sourceId = "deepsearch-root",
            depth = 0,
            hypothesis = hypothesis,
            score = DeepSearchScore(
                relevance = 1.0,
                evidenceStrength = 1.0,
                sourceReliability = 1.0,
                novelty = 1.0,
                depthCost = 0.0,
                contradictionPenalty = 0.0,
                total = 1.0,
            ),
        )
    }

    private fun findingOrder(): Comparator<DeepSearchFindingDraft> =
        compareBy<DeepSearchFindingDraft> { normalizeSearchText(it.statement) }
            .thenByDescending { it.confidence }
            .thenBy { draftKey(it) }

    private fun evidenceDraftOrder(): Comparator<DeepSearchEvidenceDraft> =
        compareBy<DeepSearchEvidenceDraft> { normalizeSearchText(it.statement) }
            .thenBy { it.contradiction }
            .thenByDescending { it.confidence }
            .thenBy { it.sourcePhotonId?.value.orEmpty() }
            .thenBy { it.sourcePhotonRevision ?: Long.MIN_VALUE }
            .thenBy { it.fieldEvidenceId?.value.orEmpty() }

    private fun draftKey(draft: DeepSearchFindingDraft): String = StableFieldIds.fingerprint(
        "deep-search-finding-draft/v2",
        normalizeSearchText(draft.statement),
        java.lang.Double.toHexString(draft.confidence),
        draft.fieldHypothesisId?.value.orEmpty(),
        *draft.semanticTerms.map(::normalizeSearchText).filter { it.isNotBlank() }.sorted().toTypedArray(),
        *draft.evidence.map(::evidenceDraftKey).sorted().toTypedArray(),
    )

    private fun evidenceDraftKey(draft: DeepSearchEvidenceDraft): String = StableFieldIds.fingerprint(
        "deep-search-evidence-draft/v2",
        normalizeSearchText(draft.statement),
        java.lang.Double.toHexString(draft.confidence),
        draft.sourcePhotonId?.value.orEmpty(),
        draft.sourcePhotonRevision?.toString().orEmpty(),
        draft.fieldEvidenceId?.value.orEmpty(),
        draft.contradiction.toString(),
    )

    private fun stableError(error: Exception): String =
        "${error::class.simpleName ?: "Exception"}:${error.message.orEmpty().take(160)}"

    private fun maxElapsedMillis(request: DeepSearchRequest): Long =
        request.budget.maxElapsed.toMillis().coerceAtLeast(1L)

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
