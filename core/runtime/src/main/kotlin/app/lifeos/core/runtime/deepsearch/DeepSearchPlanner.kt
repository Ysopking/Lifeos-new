package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.ProviderState
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

data class DeepSearchSourceAuthorization(
    val allowed: Boolean,
    val reason: String,
) {
    init { require(reason.isNotBlank()) }
}

interface DeepSearchCapabilityGate {
    suspend fun authorize(source: DeepSearchSourceDescriptor): DeepSearchSourceAuthorization
}

/** Default gate: local deterministic sources only. External/network access is denied by default. */
object LocalOnlyDeepSearchCapabilityGate : DeepSearchCapabilityGate {
    override suspend fun authorize(source: DeepSearchSourceDescriptor): DeepSearchSourceAuthorization =
        if (source.kind == DeepSearchSourceKind.LOCAL) {
            DeepSearchSourceAuthorization(true, "local-source")
        } else {
            DeepSearchSourceAuthorization(false, "external-source-requires-explicit-capability-and-permission")
        }
}

interface DeepSearchPermissionGate {
    suspend fun permissionFor(source: DeepSearchSourceDescriptor): DeepSearchPermissionState
}

object DescriptorDeepSearchPermissionGate : DeepSearchPermissionGate {
    override suspend fun permissionFor(source: DeepSearchSourceDescriptor): DeepSearchPermissionState =
        source.permissionState
}

/**
 * Explicit external-source admission path. Capability availability never implies permission:
 * both a usable provider and GRANTED permission are required.
 */
class CapabilityRegistryDeepSearchGate(
    private val registry: CapabilityRegistry,
    private val permissionGate: DeepSearchPermissionGate = DescriptorDeepSearchPermissionGate,
) : DeepSearchCapabilityGate {
    override suspend fun authorize(source: DeepSearchSourceDescriptor): DeepSearchSourceAuthorization {
        if (source.kind == DeepSearchSourceKind.LOCAL) {
            return DeepSearchSourceAuthorization(true, "local-source")
        }
        val capabilityId = source.capabilityId
            ?: return DeepSearchSourceAuthorization(false, "external-source-missing-capability")
        val permission = permissionGate.permissionFor(source)
        if (permission != DeepSearchPermissionState.GRANTED) {
            return DeepSearchSourceAuthorization(
                false,
                "external-source-permission-${permission.name.lowercase()}",
            )
        }
        val providers = registry.providersFor(capabilityId, includeUnavailable = true)
        val usable = providers.any { provider ->
            provider.state == ProviderState.ACTIVE || provider.state == ProviderState.DEGRADED
        }
        return if (usable) {
            DeepSearchSourceAuthorization(true, "capability-and-permission-granted")
        } else {
            DeepSearchSourceAuthorization(false, "external-source-capability-unavailable")
        }
    }
}

/**
 * Bounded deterministic search planner.
 *
 * The planner owns branch/evidence/hypothesis identities, ranking, dedupe and budget accounting.
 * Sources can only return drafts. No source may activate tools, mutate source Photons or extend the
 * search beyond configured depth/breadth/work/time bounds through this contract.
 */
class DeepSearchPlanner(
    private val evaluator: DeepSearchEvaluator = DeepSearchEvaluator(),
    private val capabilityGate: DeepSearchCapabilityGate = LocalOnlyDeepSearchCapabilityGate,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun search(
        request: DeepSearchRequest,
        sources: List<DeepSearchSource>,
    ): DeepSearchResult {
        val sourceIds = sources.map { it.descriptor.sourceId }
        require(sourceIds.distinct().size == sourceIds.size) {
            "DeepSearch source ids must be unique"
        }

        val trace = mutableListOf<DeepSearchTraceEvent>()
        val evidenceById = linkedMapOf<DeepSearchEvidenceId, DeepSearchEvidence>()
        val blocked = sortedSetOf<String>()
        val failed = sortedSetOf<String>()
        var workUnits = 0
        var workExhausted = false
        var timeExhausted = false
        val startedAt = now()

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

        val root = rootBranch(request)
        emit(
            type = DeepSearchTraceType.ROOT_CREATED,
            branch = root,
            hypothesisId = root.hypothesis.id,
            detail = "deterministic-query-root",
        )

        val authorized = mutableListOf<DeepSearchSource>()
        for (source in sources.sortedBy { it.descriptor.sourceId }) {
            if (timeExceeded(request, startedAt)) {
                timeExhausted = true
                emit(DeepSearchTraceType.TIME_LIMIT_REACHED, detail = "source-authorization-time-budget")
                break
            }
            val authorization = try {
                capabilityGate.authorize(source.descriptor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                blocked += source.descriptor.sourceId
                emit(
                    type = DeepSearchTraceType.SOURCE_BLOCKED,
                    sourceId = source.descriptor.sourceId,
                    detail = "authorization-failed:${stableError(error)}",
                )
                continue
            }
            if (authorization.allowed) {
                authorized += source
                emit(
                    type = DeepSearchTraceType.SOURCE_AUTHORIZED,
                    sourceId = source.descriptor.sourceId,
                    detail = authorization.reason,
                )
            } else {
                blocked += source.descriptor.sourceId
                emit(
                    type = DeepSearchTraceType.SOURCE_BLOCKED,
                    sourceId = source.descriptor.sourceId,
                    detail = authorization.reason,
                )
            }
        }

        if (sources.isEmpty() || (authorized.isEmpty() && blocked.isEmpty() && !timeExhausted)) {
            emit(DeepSearchTraceType.UNRESOLVED, detail = "no-usable-source")
            return result(
                request = request,
                status = DeepSearchStatus.NO_USABLE_SOURCE,
                frontier = DeepSearchFrontier(request),
                evidence = evidenceById,
                trace = trace,
                workUnits = workUnits,
                blocked = blocked,
                failed = failed,
            )
        }
        if (authorized.isEmpty()) {
            val status = if (timeExhausted) {
                DeepSearchStatus.TIME_BUDGET_EXHAUSTED
            } else {
                DeepSearchStatus.PERMISSION_BLOCKED
            }
            emit(
                if (timeExhausted) DeepSearchTraceType.TIME_LIMIT_REACHED else DeepSearchTraceType.UNRESOLVED,
                detail = if (timeExhausted) "authorization-time-budget-exhausted" else "all-sources-blocked",
            )
            return result(
                request, status, DeepSearchFrontier(request), evidenceById, trace,
                workUnits, blocked, failed,
            )
        }

        val frontier = DeepSearchFrontier(request)
        var current: DeepSearchBranch? = root

        searchLoop@ while (current != null) {
            if (timeExceeded(request, startedAt)) {
                timeExhausted = true
                emit(DeepSearchTraceType.TIME_LIMIT_REACHED, current, detail = "planner-time-budget")
                break
            }
            if (current.depth >= request.budget.maxDepth) {
                emit(
                    DeepSearchTraceType.DEPTH_LIMIT_REACHED,
                    current,
                    hypothesisId = current.hypothesis.id,
                    detail = "max-depth:${request.budget.maxDepth}",
                )
                current = frontier.poll()
                continue
            }

            for (source in authorized) {
                val cost = source.workUnits(request, current)
                if (workUnits + cost > request.budget.maxWorkUnits) {
                    workExhausted = true
                    emit(
                        DeepSearchTraceType.WORK_LIMIT_REACHED,
                        current,
                        source.descriptor.sourceId,
                        detail = "work:$workUnits+${cost}>${request.budget.maxWorkUnits}",
                    )
                    break@searchLoop
                }
                if (timeExceeded(request, startedAt)) {
                    timeExhausted = true
                    emit(
                        DeepSearchTraceType.TIME_LIMIT_REACHED,
                        current,
                        source.descriptor.sourceId,
                        detail = "before-source-expansion",
                    )
                    break@searchLoop
                }

                workUnits += cost
                val drafts = try {
                    val remaining = remainingMillis(request, startedAt)
                    withTimeout(remaining) {
                        source.expand(request, current)
                    }
                } catch (timeout: TimeoutCancellationException) {
                    timeExhausted = true
                    emit(
                        DeepSearchTraceType.TIME_LIMIT_REACHED,
                        current,
                        source.descriptor.sourceId,
                        detail = "source-expansion-time-budget",
                    )
                    break@searchLoop
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failed += source.descriptor.sourceId
                    emit(
                        DeepSearchTraceType.SOURCE_FAILED,
                        current,
                        source.descriptor.sourceId,
                        detail = stableError(error),
                    )
                    continue
                }
                emit(
                    DeepSearchTraceType.BRANCH_EXPANDED,
                    current,
                    source.descriptor.sourceId,
                    detail = "findings:${drafts.size}:work:$workUnits",
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
                    candidate.evidence.forEach { evidence -> evidenceById.putIfAbsent(evidence.id, evidence) }
                    when (val offered = frontier.offer(candidate.branch)) {
                        is DeepSearchFrontierOffer -> when (offered.status) {
                            DeepSearchFrontierOfferStatus.ACCEPTED,
                            DeepSearchFrontierOfferStatus.DUPLICATE_REPLACED,
                            DeepSearchFrontierOfferStatus.BREADTH_REPLACED,
                            -> emit(
                                DeepSearchTraceType.CANDIDATE_ACCEPTED,
                                candidate.branch,
                                source.descriptor.sourceId,
                                candidate.branch.hypothesis.id,
                                candidate.evidence.mapTo(sortedSetOf(compareBy { it.value })) { it.id },
                                detail = offered.status.name.lowercase(),
                            )

                            DeepSearchFrontierOfferStatus.DUPLICATE_REJECTED -> emit(
                                DeepSearchTraceType.CANDIDATE_DUPLICATE,
                                candidate.branch,
                                source.descriptor.sourceId,
                                candidate.branch.hypothesis.id,
                                candidate.evidence.mapTo(sortedSetOf(compareBy { it.value })) { it.id },
                                detail = "duplicate-kept:${offered.replacedBranch?.id?.value.orEmpty()}",
                            )

                            DeepSearchFrontierOfferStatus.BREADTH_REJECTED -> emit(
                                DeepSearchTraceType.CANDIDATE_BREADTH_REJECTED,
                                candidate.branch,
                                source.descriptor.sourceId,
                                candidate.branch.hypothesis.id,
                                candidate.evidence.mapTo(sortedSetOf(compareBy { it.value })) { it.id },
                                detail = "breadth:${request.budget.maxBreadth}",
                            )
                        }
                    }
                }
            }

            val resolution = evaluator.resolve(request, frontier.admittedBranches())
            if (resolution.resolved) {
                emit(
                    DeepSearchTraceType.RESOLVED,
                    resolution.best,
                    hypothesisId = resolution.best?.hypothesis?.id,
                    detail = "score:${resolution.best?.score?.total}:margin:${resolution.winnerMargin}",
                )
                return result(
                    request,
                    DeepSearchStatus.RESOLVED,
                    frontier,
                    evidenceById,
                    trace,
                    workUnits,
                    blocked,
                    failed,
                    resolution,
                )
            }
            current = frontier.poll()
        }

        val finalResolution = evaluator.resolve(request, frontier.admittedBranches())
        val status = when {
            finalResolution.resolved -> DeepSearchStatus.RESOLVED
            timeExhausted -> DeepSearchStatus.TIME_BUDGET_EXHAUSTED
            workExhausted -> DeepSearchStatus.WORK_BUDGET_EXHAUSTED
            else -> DeepSearchStatus.UNRESOLVED
        }
        emit(
            if (status == DeepSearchStatus.RESOLVED) DeepSearchTraceType.RESOLVED else DeepSearchTraceType.UNRESOLVED,
            finalResolution.best,
            hypothesisId = finalResolution.best?.hypothesis?.id,
            detail = "terminal:${status.name.lowercase()}:margin:${finalResolution.winnerMargin}",
        )
        return result(
            request,
            status,
            frontier,
            evidenceById,
            trace,
            workUnits,
            blocked,
            failed,
            finalResolution,
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

    private fun timeExceeded(request: DeepSearchRequest, startedAt: Instant): Boolean =
        elapsed(startedAt).compareTo(request.budget.maxElapsed) >= 0

    private fun remainingMillis(request: DeepSearchRequest, startedAt: Instant): Long {
        val remaining = request.budget.maxElapsed.minus(elapsed(startedAt))
        return remaining.toMillis().coerceAtLeast(1L)
    }

    private fun elapsed(startedAt: Instant): Duration {
        val value = Duration.between(startedAt, now())
        return if (value.isNegative) Duration.ZERO else value
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
}
