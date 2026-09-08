package app.lifeos.core.field

data class ConvergenceConfig(
    val maxIterations: Int = 12,
    val requiredStableRounds: Int = 2,
    val epsilon: Double = 0.0005,
    val minConvergence: Double = 0.55,
    val minWinnerMargin: Double = 0.08,
    val damping: Double = 0.55,
) {
    init {
        require(maxIterations in 1..100) { "maxIterations must be in 1..100" }
        require(requiredStableRounds in 1..maxIterations) { "requiredStableRounds must fit maxIterations" }
        require(epsilon.isFinite() && epsilon >= 0.0) { "epsilon must be finite and non-negative" }
        require(minConvergence in 0.0..1.0) { "minConvergence must be in 0..1" }
        require(minWinnerMargin in 0.0..1.0) { "minWinnerMargin must be in 0..1" }
        require(damping in 0.0..1.0 && damping > 0.0) { "damping must be in (0,1]" }
    }
}

enum class ConvergenceStatus { CONVERGED, UNRESOLVED, MAX_ITERATIONS }

data class FieldConvergenceRequest(
    val domainId: FieldDomainId,
    val graph: FieldGraph,
    val evidence: List<FieldEvidence>,
    val hypotheses: List<FieldHypothesis>,
    val context: FieldContext,
    val domainFields: List<DomainField> = emptyList(),
) {
    init {
        require(graph.domainId == domainId) { "Graph domain does not match request" }
        require(context.domain.domainId == domainId) { "Context domain does not match request" }
        require(evidence.all { it.domainId == domainId }) { "Evidence domain does not match request" }
        require(hypotheses.isNotEmpty()) { "Convergence requires at least one hypothesis" }
        require(hypotheses.all { it.domainId == domainId }) { "Hypothesis domain does not match request" }
        require(evidence.map { it.id }.distinct().size == evidence.size) { "Evidence ids must be unique" }
        require(hypotheses.map { it.id }.distinct().size == hypotheses.size) { "Hypothesis ids must be unique" }
        val nodeIds = graph.nodes.mapTo(mutableSetOf()) { it.id }
        val evidenceIds = evidence.mapTo(mutableSetOf()) { it.id }
        require(hypotheses.all { hypothesis -> hypothesis.nodeIds.all(nodeIds::contains) }) {
            "Hypotheses must reference graph nodes"
        }
        require(hypotheses.all { hypothesis -> hypothesis.evidenceLinks.all { it.evidenceId in evidenceIds } }) {
            "Hypothesis evidence links must reference request evidence"
        }
        require(domainFields.all { it.descriptor.domainId == domainId }) {
            "Domain fields must match request domain"
        }
    }
}

data class FieldConvergenceResult(
    val status: ConvergenceStatus,
    val state: FieldState,
    val hypotheses: List<FieldHypothesis>,
    val lastForces: List<FieldForce>,
    val conflicts: List<FieldConflict>,
    val iterations: Int,
    val trace: FieldTrace,
    val snapshot: FieldSnapshot,
) {
    init {
        require(iterations == state.iteration.index)
        require(trace.runId == state.runId)
        require(snapshot.runId == state.runId)
        require(snapshot.traceFingerprint == trace.fingerprint())
    }

    val winner: FieldHypothesis?
        get() = hypotheses.firstOrNull { it.state == HypothesisState.CONVERGED }
}

/**
 * Shared deterministic convergence engine. It updates only interpretation energy; evidence,
 * source Photon revisions and graph structure remain immutable throughout a run.
 */
class FieldConvergenceEngine(
    private val forceCalculator: FieldForceCalculator = FieldForceCalculator(),
    private val config: ConvergenceConfig = ConvergenceConfig(),
    private val registry: DomainFieldRegistry = DomainFieldRegistry.EMPTY,
) {
    fun converge(request: FieldConvergenceRequest): FieldConvergenceResult {
        val evidenceById = request.evidence.associateBy { it.id }
        val fields = registry.resolve(request.domainId, request.domainFields)
        val fieldSetFingerprint = DomainFieldRegistry.fingerprintOf(fields)
        val seedData = seedNodeEnergy(request, evidenceById)
        val seedHypothesisBreakdowns = request.hypotheses.sortedBy { it.id.value }.map { hypothesis ->
            forceCalculator.hypothesisForce(
                hypothesis = hypothesis,
                evidenceById = evidenceById,
                nodeEnergy = seedData.energy,
                context = request.context,
            )
        }
        val seedHypothesisEnergy = seedHypothesisBreakdowns.associate { it.hypothesisId to it.total }
        val inputFingerprint = request.physicsFingerprint(
            config = config,
            forceCalculatorFingerprint = forceCalculator.fingerprint(),
            resolvedFields = fields,
        )
        val initialEnergy = FieldEnergySnapshot(seedData.energy, seedHypothesisEnergy)
        val seedTrace = FieldSeedTrace(
            nodeEvidenceForces = seedData.traces,
            hypothesisForces = seedHypothesisBreakdowns,
            initialEnergyFingerprint = initialEnergy.fingerprint(),
        )
        var state = FieldState.initial(request.domainId, inputFingerprint, initialEnergy)
        var lastForces: List<FieldForce> = emptyList()
        val iterationTraces = mutableListOf<FieldIterationTrace>()
        val collectedConflicts = linkedMapOf<String, FieldConflict>()
        request.graph.conflicts.sortedBy { it.key }.forEach { collectedConflicts[it.key] = it }

        repeat(config.maxIterations) {
            val beforeState = state
            val domainEvaluationTraces = fields.map { field ->
                DomainFieldEvaluationTrace(
                    descriptor = field.descriptor,
                    evaluation = field.evaluate(state, request.graph, request.context),
                )
            }
            val domainEvaluations = domainEvaluationTraces.map { it.evaluation }
            domainEvaluations.flatMap { it.conflicts }.sortedBy { it.key }.forEach { conflict ->
                collectedConflicts[conflict.key] = conflict
            }
            val relationForces = request.graph.stableRelations().map { relation ->
                forceCalculator.relationForce(relation, state.energy.nodeEnergy[relation.source] ?: 0.0)
            }
            val domainForces = domainEvaluations.flatMap { it.forces }
                .sortedWith(compareBy<FieldForce> { it.targetNodeId.value }.thenBy { it.sourceNodeId.value }.thenBy { it.reason })
            lastForces = relationForces + domainForces

            val nextNodes = updateNodes(request.graph, seedData.energy, state.energy.nodeEnergy, lastForces)
            val domainBias = mergeBias(domainEvaluations)
            val nextHypotheses = updateHypotheses(
                hypotheses = request.hypotheses,
                evidenceById = evidenceById,
                nodeEnergy = nextNodes,
                previousEnergy = state.energy.hypothesisEnergy,
                context = request.context,
                domainBias = domainBias,
            )
            state = state.next(FieldEnergySnapshot(nextNodes, nextHypotheses), config.epsilon)
            iterationTraces += FieldIterationTrace(
                iteration = state.iteration,
                beforeEnergyFingerprint = beforeState.energy.fingerprint(),
                afterEnergy = state.energy,
                relationForces = relationForces,
                domainEvaluations = domainEvaluationTraces,
                mergedHypothesisBias = domainBias,
            )
            if (state.iteration.stableRounds >= config.requiredStableRounds) {
                return finalize(
                    request = request,
                    evidenceById = evidenceById,
                    state = state,
                    lastForces = lastForces,
                    conflicts = collectedConflicts.values.toList(),
                    stable = true,
                    inputFingerprint = inputFingerprint,
                    fieldSetFingerprint = fieldSetFingerprint,
                    seedTrace = seedTrace,
                    iterationTraces = iterationTraces,
                )
            }
        }

        return finalize(
            request = request,
            evidenceById = evidenceById,
            state = state,
            lastForces = lastForces,
            conflicts = collectedConflicts.values.toList(),
            stable = false,
            inputFingerprint = inputFingerprint,
            fieldSetFingerprint = fieldSetFingerprint,
            seedTrace = seedTrace,
            iterationTraces = iterationTraces,
        )
    }

    private data class SeedNodeData(
        val energy: Map<FieldNodeId, Double>,
        val traces: List<NodeEvidenceForceTrace>,
    )

    private fun seedNodeEnergy(
        request: FieldConvergenceRequest,
        evidenceById: Map<EvidenceId, FieldEvidence>,
    ): SeedNodeData {
        val traces = mutableListOf<NodeEvidenceForceTrace>()
        val energy = request.graph.stableNodes().associate { node ->
            val evidenceScores = node.evidenceIds.sortedBy { it.value }.mapNotNull { id ->
                evidenceById[id]?.let { evidence ->
                    val breakdown = forceCalculator.evidenceForce(evidence, request.context, node.semanticMass)
                    traces += NodeEvidenceForceTrace(node.id, breakdown)
                    breakdown.composite
                }
            }
            val evidenceEnergy = if (evidenceScores.isEmpty()) 0.0 else evidenceScores.average()
            val normalizedBase = node.baseEnergy / (1.0 + node.baseEnergy)
            node.id to maxOf(evidenceEnergy, normalizedBase).coerceIn(0.0, 1.0)
        }
        return SeedNodeData(energy = energy, traces = traces.toList())
    }

    private fun updateNodes(
        graph: FieldGraph,
        seed: Map<FieldNodeId, Double>,
        previous: Map<FieldNodeId, Double>,
        forces: List<FieldForce>,
    ): Map<FieldNodeId, Double> {
        val grouped = forces.groupBy { it.targetNodeId }
        val raw = graph.stableNodes().associate { node ->
            val incoming = grouped[node.id].orEmpty()
            val attraction = incoming.filter { it.polarity == ForcePolarity.ATTRACTION }.sumOf { it.magnitude }
            val repulsion = incoming.filter { it.polarity == ForcePolarity.REPULSION }.sumOf { it.magnitude }
            val desired = ((seed[node.id] ?: 0.0) + attraction * 0.35 - repulsion * 0.35).coerceIn(0.0, 1.0)
            val old = previous[node.id] ?: 0.0
            node.id to blend(old, desired)
        }.toMutableMap()

        graph.competitionGroups.sortedBy { it.key }.forEach { group ->
            val snapshot = group.nodeIds.associateWith { raw[it] ?: 0.0 }
            group.nodeIds.sortedBy { it.value }.forEach { id ->
                val competitor = snapshot.filterKeys { it != id }.values.maxOrNull() ?: 0.0
                raw[id] = ((raw[id] ?: 0.0) - competitor * 0.12).coerceIn(0.0, 1.0)
            }
        }
        return raw.toSortedMap(compareBy { it.value })
    }

    private fun updateHypotheses(
        hypotheses: List<FieldHypothesis>,
        evidenceById: Map<EvidenceId, FieldEvidence>,
        nodeEnergy: Map<FieldNodeId, Double>,
        previousEnergy: Map<HypothesisId, Double>,
        context: FieldContext,
        domainBias: Map<HypothesisId, Double>,
    ): Map<HypothesisId, Double> {
        val previous = previousEnergy.toMap()
        return hypotheses.sortedBy { it.id.value }.associate { hypothesis ->
            val breakdown = forceCalculator.hypothesisForce(hypothesis, evidenceById, nodeEnergy, context)
            val conflictPenalty = hypothesis.conflicts.sumOf { conflict ->
                (previous[conflict.competingHypothesisId] ?: 0.0) * conflict.strength * 0.25
            }
            val desired = (breakdown.total + (domainBias[hypothesis.id] ?: 0.0) * 0.25 - conflictPenalty)
                .coerceIn(0.0, 1.0)
            val old = previous[hypothesis.id] ?: 0.0
            hypothesis.id to blend(old, desired)
        }
    }

    private fun mergeBias(evaluations: List<DomainFieldEvaluation>): Map<HypothesisId, Double> {
        val sums = mutableMapOf<HypothesisId, Double>()
        evaluations.forEach { evaluation ->
            evaluation.hypothesisBias.entries.sortedBy { it.key.value }.forEach { (id, bias) ->
                sums[id] = ((sums[id] ?: 0.0) + bias).coerceIn(-1.0, 1.0)
            }
        }
        return sums.toSortedMap(compareBy { it.value })
    }

    private fun finalize(
        request: FieldConvergenceRequest,
        evidenceById: Map<EvidenceId, FieldEvidence>,
        state: FieldState,
        lastForces: List<FieldForce>,
        conflicts: List<FieldConflict>,
        stable: Boolean,
        inputFingerprint: String,
        fieldSetFingerprint: String,
        seedTrace: FieldSeedTrace,
        iterationTraces: List<FieldIterationTrace>,
    ): FieldConvergenceResult {
        val ranked = request.hypotheses
            .map { it to (state.energy.hypothesisEnergy[it.id] ?: 0.0) }
            .sortedWith(compareByDescending<Pair<FieldHypothesis, Double>> { it.second }.thenBy { it.first.id.value })
        val top = ranked.first()
        val secondEnergy = ranked.getOrNull(1)?.second
        val winnerStrong = top.second >= config.minConvergence
        val winnerSeparated = secondEnergy == null || top.second - secondEnergy >= config.minWinnerMargin
        val status = when {
            stable && winnerStrong && winnerSeparated -> ConvergenceStatus.CONVERGED
            stable -> ConvergenceStatus.UNRESOLVED
            else -> ConvergenceStatus.MAX_ITERATIONS
        }

        val finalHypotheses = ranked.mapIndexed { index, (hypothesis, energy) ->
            val breakdown = forceCalculator.hypothesisForce(hypothesis, evidenceById, state.energy.nodeEnergy, request.context)
            val finalState = when {
                status == ConvergenceStatus.CONVERGED && index == 0 -> HypothesisState.CONVERGED
                status == ConvergenceStatus.CONVERGED && energy < config.minConvergence -> HypothesisState.REJECTED
                status == ConvergenceStatus.UNRESOLVED && index <= 1 -> HypothesisState.UNRESOLVED
                status == ConvergenceStatus.MAX_ITERATIONS && index <= 1 -> HypothesisState.COMPETING
                energy >= config.minConvergence -> HypothesisState.SUPPORTED
                else -> HypothesisState.WEAK
            }
            hypothesis.copy(
                state = finalState,
                score = HypothesisScore(
                    evidence = breakdown.support,
                    support = breakdown.nodeCoherence,
                    contradiction = breakdown.contradiction,
                    context = breakdown.context,
                    temporal = 0.0,
                    authority = 0.0,
                    total = energy,
                ),
            )
        }
        val trace = FieldTrace(
            runId = state.runId,
            domainId = request.domainId,
            inputFingerprint = inputFingerprint,
            fieldSetFingerprint = fieldSetFingerprint,
            seed = seedTrace,
            graphConflicts = request.graph.conflicts.sortedBy { it.key },
            iterations = iterationTraces.toList(),
            status = status,
            finalEnergyFingerprint = state.energy.fingerprint(),
        )
        val snapshot = FieldSnapshot.create(
            status = status,
            state = state,
            hypotheses = finalHypotheses,
            inputFingerprint = inputFingerprint,
            fieldSetFingerprint = fieldSetFingerprint,
            traceFingerprint = trace.fingerprint(),
        )
        return FieldConvergenceResult(
            status = status,
            state = state,
            hypotheses = finalHypotheses,
            lastForces = lastForces,
            conflicts = conflicts.sortedBy { it.key },
            iterations = state.iteration.index,
            trace = trace,
            snapshot = snapshot,
        )
    }

    private fun blend(previous: Double, desired: Double): Double =
        (previous * (1.0 - config.damping) + desired * config.damping).coerceIn(0.0, 1.0)
}
