package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.runtime.world.WorldEquationActivationAuthority
import app.lifeos.core.runtime.world.WorldEquationHead

sealed interface WorldEquationAutoEvolutionResult {
    data class Started(
        val record: WorldEquationEvidenceRecord,
    ) : WorldEquationAutoEvolutionResult

    data class Observed(
        val observation: WorldEquationShadowObservation,
        val record: WorldEquationEvidenceRecord,
    ) : WorldEquationAutoEvolutionResult

    data class Promoted(
        val observation: WorldEquationShadowObservation?,
        val record: WorldEquationEvidenceRecord,
        val head: WorldEquationHead,
    ) : WorldEquationAutoEvolutionResult

    data class Blocked(
        val reason: String,
    ) : WorldEquationAutoEvolutionResult {
        init {
            require(reason.isNotBlank())
        }
    }
}

/**
 * Evidence-bound parameter-only WorldEquation evolution loop.
 *
 * Candidate specs may be persisted before activation, but productive head mutation is possible only
 * after the durable evidence record reaches PROMOTABLE and the admission gate recomputes every hard
 * gate immediately before WorldEquationActivationAuthority CAS.
 */
class WorldEquationAutoEvolutionCoordinator(
    private val evidence: WorldEquationEvidenceRepository,
    private val evidenceCoordinator: WorldEquationEvidenceCoordinator,
    private val shadow: WorldEquationShadowRunner,
    private val admissionGate: WorldEquationEvolutionAdmissionGate,
    private val authority: WorldEquationActivationAuthority,
) {
    suspend fun start(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
        protocol: WorldEquationEvaluationProtocol,
    ): WorldEquationAutoEvolutionResult {
        val head = authority.activeHead()
        if (head.activeEquationVersion != baseline.version) {
            return WorldEquationAutoEvolutionResult.Blocked(
                "world-equation-baseline-is-not-active"
            )
        }
        authority.registerCandidate(candidate)
        val record = evidenceCoordinator.beginShadow(candidate, baseline, protocol)
        return WorldEquationAutoEvolutionResult.Started(record)
    }

    suspend fun observe(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
        case: WorldEquationShadowCase,
    ): WorldEquationAutoEvolutionResult {
        val headBefore = authority.activeHead()
        if (headBefore.activeEquationVersion != baseline.version) {
            return WorldEquationAutoEvolutionResult.Blocked(
                "world-equation-baseline-changed-during-shadow"
            )
        }
        val observation = shadow.evaluate(baseline, candidate, case)
        val record = evidenceCoordinator.recordObservation(
            candidate = candidate,
            baseline = baseline,
            observation = observation,
        )
        if (record.state != WorldEquationLifecycleState.PROMOTABLE) {
            return WorldEquationAutoEvolutionResult.Observed(observation, record)
        }
        return promote(candidate, baseline, observation)
    }

    suspend fun promoteIfEligible(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
    ): WorldEquationAutoEvolutionResult {
        val record = requireNotNull(evidence.load(candidate.fingerprint())) {
            "WorldEquation evidence is missing"
        }
        if (record.state != WorldEquationLifecycleState.PROMOTABLE) {
            return WorldEquationAutoEvolutionResult.Blocked(
                "world-equation-evidence-not-promotable:" + record.state.name.lowercase()
            )
        }
        return promote(candidate, baseline, observation = null)
    }

    suspend fun reconcile(
        candidate: WorldEquationSpec,
    ): WorldEquationEvidenceRecord? {
        val record = evidence.load(candidate.fingerprint()) ?: return null
        val head = authority.activeHead()
        return when {
            head.activeEquationVersion == candidate.version &&
                record.state == WorldEquationLifecycleState.PROMOTABLE ->
                evidenceCoordinator.markActive(candidate, head.fingerprint)
            head.activeEquationVersion == candidate.version &&
                record.state == WorldEquationLifecycleState.ACTIVE -> {
                require(record.activationHeadFingerprint == head.fingerprint) {
                    "ACTIVE WorldEquation evidence points to another activation head"
                }
                record
            }
            else -> record
        }
    }

    private suspend fun promote(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
        observation: WorldEquationShadowObservation?,
    ): WorldEquationAutoEvolutionResult {
        val expectedHead = authority.activeHead()
        if (expectedHead.activeEquationVersion != baseline.version) {
            return WorldEquationAutoEvolutionResult.Blocked(
                "world-equation-baseline-changed-before-promotion"
            )
        }
        val admission = admissionGate.admit(candidate, baseline)
        val promotedHead = authority.promote(
            candidate = candidate,
            admission = admission,
            expectedHeadFingerprint = expectedHead.fingerprint,
        )
        val activeRecord = evidenceCoordinator.markActive(
            candidate = candidate,
            activationHeadFingerprint = promotedHead.fingerprint,
        )
        return WorldEquationAutoEvolutionResult.Promoted(
            observation = observation,
            record = activeRecord,
            head = promotedHead,
        )
    }
}
