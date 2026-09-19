package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.world.WorldEquationSpec

class WorldEquationEvidenceCoordinator(
    private val repository: WorldEquationEvidenceRepository,
    private val evaluator: WorldEquationPromotionEvaluator,
) {
    suspend fun register(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
        protocol: WorldEquationEvaluationProtocol,
    ): WorldEquationEvidenceRecord {
        val existing = repository.load(candidate.fingerprint())
        if (existing != null) {
            requireMatches(existing, candidate, baseline, protocol)
            return existing
        }
        val initial = WorldEquationEvidenceRecord.create(
            revision = 1L,
            state = WorldEquationLifecycleState.CONJECTURE,
            evidence = WorldEquationEvidenceSet.empty(
                candidate = candidate,
                baseline = baseline,
                protocol = protocol,
                policyFingerprint = evaluator.policy.fingerprint(),
            ),
        )
        if (repository.compareAndSet(candidate.fingerprint(), null, initial)) {
            return initial
        }
        val raced = requireNotNull(repository.load(candidate.fingerprint()))
        requireMatches(raced, candidate, baseline, protocol)
        return raced
    }

    suspend fun beginShadow(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
        protocol: WorldEquationEvaluationProtocol,
    ): WorldEquationEvidenceRecord {
        val current = register(candidate, baseline, protocol)
        if (current.state == WorldEquationLifecycleState.SHADOW ||
            current.state == WorldEquationLifecycleState.SUPPORTED ||
            current.state == WorldEquationLifecycleState.PROMOTABLE
        ) {
            return current
        }
        require(current.state == WorldEquationLifecycleState.CONJECTURE) {
            "WorldEquation candidate cannot enter SHADOW from " + current.state
        }
        return save(current, current.transition(WorldEquationLifecycleState.SHADOW))
    }

    suspend fun recordObservation(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
        observation: WorldEquationShadowObservation,
    ): WorldEquationEvidenceRecord {
        val current = requireNotNull(repository.load(candidate.fingerprint())) {
            "WorldEquation evidence must be registered before observation"
        }
        requireSpecs(current, candidate, baseline)
        val appended = current.append(observation)
        val saved = save(current, appended)
        return reevaluate(candidate, baseline, saved)
    }

    suspend fun reevaluate(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
    ): WorldEquationEvidenceRecord {
        val current = requireNotNull(repository.load(candidate.fingerprint())) {
            "WorldEquation evidence is missing"
        }
        requireSpecs(current, candidate, baseline)
        return reevaluate(candidate, baseline, current)
    }

    suspend fun markActive(
        candidate: WorldEquationSpec,
        activationHeadFingerprint: String,
    ): WorldEquationEvidenceRecord =
        markActive(candidate.fingerprint(), activationHeadFingerprint)

    suspend fun markActive(
        candidateEquationFingerprint: String,
        activationHeadFingerprint: String,
    ): WorldEquationEvidenceRecord {
        val current = requireNotNull(repository.load(candidateEquationFingerprint))
        require(current.state == WorldEquationLifecycleState.PROMOTABLE) {
            "Only PROMOTABLE WorldEquation evidence may become ACTIVE"
        }
        return save(
            current,
            current.transition(
                nextState = WorldEquationLifecycleState.ACTIVE,
                activationHeadFingerprint = activationHeadFingerprint,
            ),
        )
    }

    suspend fun recordPostActivationSafetyObservation(
        candidateEquationFingerprint: String,
        observation: WorldEquationPostActivationSafetyObservation,
    ): WorldEquationEvidenceRecord {
        val current = requireNotNull(repository.load(candidateEquationFingerprint)) {
            "WorldEquation evidence is missing"
        }
        require(current.state == WorldEquationLifecycleState.ACTIVE)
        current.postActivationSafetyObservations
            .firstOrNull { it.id == observation.id }
            ?.let { return current }
        return save(
            current,
            current.appendPostActivationSafetyObservation(observation),
        )
    }

    suspend fun quarantine(
        candidateEquationFingerprint: String,
        verdictId: String,
    ): WorldEquationEvidenceRecord {
        val current = requireNotNull(repository.load(candidateEquationFingerprint))
        require(current.state == WorldEquationLifecycleState.ACTIVE)
        return save(
            current,
            current.transition(
                nextState = WorldEquationLifecycleState.QUARANTINED,
                rollbackDecisionId = verdictId,
            ),
        )
    }

    suspend fun markRolledBack(
        candidateEquationFingerprint: String,
        rollbackDecisionId: String,
    ): WorldEquationEvidenceRecord {
        val current = requireNotNull(repository.load(candidateEquationFingerprint))
        require(current.state == WorldEquationLifecycleState.QUARANTINED)
        require(current.rollbackDecisionId == rollbackDecisionId) {
            "WorldEquation rollback decision does not match quarantined evidence"
        }
        return save(
            current,
            current.transition(
                nextState = WorldEquationLifecycleState.ROLLED_BACK,
                rollbackDecisionId = rollbackDecisionId,
            ),
        )
    }

    private suspend fun reevaluate(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
        current: WorldEquationEvidenceRecord,
    ): WorldEquationEvidenceRecord {
        if (current.state !in setOf(
                WorldEquationLifecycleState.SHADOW,
                WorldEquationLifecycleState.SUPPORTED,
            )
        ) {
            return current
        }
        val verdict = evaluator.evaluate(candidate, baseline, current.evidence)
        val next = when (verdict.decision) {
            WorldEquationPromotionDecision.INSUFFICIENT_EVIDENCE ->
                current.recordVerdict(verdict.id)
            WorldEquationPromotionDecision.SUPPORTED ->
                if (current.state == WorldEquationLifecycleState.SUPPORTED) {
                    current.recordVerdict(verdict.id)
                } else {
                    current.transition(
                        WorldEquationLifecycleState.SUPPORTED,
                        verdictId = verdict.id,
                    )
                }
            WorldEquationPromotionDecision.PROMOTABLE ->
                current.transition(
                    WorldEquationLifecycleState.PROMOTABLE,
                    verdictId = verdict.id,
                )
            WorldEquationPromotionDecision.REJECTED ->
                current.transition(
                    WorldEquationLifecycleState.REJECTED,
                    verdictId = verdict.id,
                )
            WorldEquationPromotionDecision.QUARANTINED ->
                error("Pre-activation evaluator cannot quarantine a WorldEquation candidate")
        }
        return save(current, next)
    }

    private suspend fun save(
        current: WorldEquationEvidenceRecord,
        next: WorldEquationEvidenceRecord,
    ): WorldEquationEvidenceRecord {
        check(
            repository.compareAndSet(
                candidateEquationFingerprint = current.candidateEquationFingerprint,
                expectedRevision = current.revision,
                next = next,
            )
        ) {
            "Concurrent WorldEquation evidence update"
        }
        return next
    }

    private fun requireMatches(
        record: WorldEquationEvidenceRecord,
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
        protocol: WorldEquationEvaluationProtocol,
    ) {
        requireSpecs(record, candidate, baseline)
        require(record.evidence.protocol.fingerprint() == protocol.fingerprint()) {
            "WorldEquation evaluation protocol is already frozen"
        }
        require(record.evidence.policyFingerprint == evaluator.policy.fingerprint()) {
            "WorldEquation promotion policy is already frozen"
        }
    }

    private fun requireSpecs(
        record: WorldEquationEvidenceRecord,
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
    ) {
        require(record.evidence.candidateEquationFingerprint == candidate.fingerprint())
        require(record.evidence.candidatePhysicsFingerprint == candidate.physicsFingerprint())
        require(record.evidence.baselineEquationFingerprint == baseline.fingerprint())
        require(record.evidence.baselinePhysicsFingerprint == baseline.physicsFingerprint())
        require(record.evidence.equationSchemaFingerprint == candidate.schemaFingerprint())
    }
}
