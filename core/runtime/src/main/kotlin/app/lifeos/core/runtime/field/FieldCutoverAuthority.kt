package app.lifeos.core.runtime.field

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds
import java.time.Instant

enum class FieldCutoverMode {
    SHADOW,
    ELIGIBLE,
    AUTHORITATIVE,
}

data class FieldCutoverState(
    val domainId: FieldDomainId,
    val generation: Long,
    val revision: Long,
    val mode: FieldCutoverMode,
    val evidenceFingerprint: String?,
    val replayCaseCount: Int,
    val updatedAt: Instant,
    val authoritativeSince: Instant? = null,
    val provenance: String,
) {
    init {
        require(generation > 0L)
        require(revision > 0L)
        require(evidenceFingerprint == null || evidenceFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(replayCaseCount >= 0)
        require(provenance.isNotBlank())
        require(mode != FieldCutoverMode.AUTHORITATIVE || authoritativeSince != null)
        require(mode == FieldCutoverMode.SHADOW || evidenceFingerprint != null) {
            "Eligible/authoritative cutover state requires durable evidence identity"
        }
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "field-cutover-state/v1",
        domainId.value,
        generation.toString(),
        revision.toString(),
        mode.name,
        evidenceFingerprint.orEmpty(),
        replayCaseCount.toString(),
        updatedAt.toString(),
        authoritativeSince?.toString().orEmpty(),
        provenance,
    )

    companion object {
        fun initial(
            domainId: FieldDomainId,
            at: Instant,
            provenance: String = "initial-shadow",
        ): FieldCutoverState = FieldCutoverState(
            domainId = domainId,
            generation = 1L,
            revision = 1L,
            mode = FieldCutoverMode.SHADOW,
            evidenceFingerprint = null,
            replayCaseCount = 0,
            updatedAt = at,
            provenance = provenance,
        )
    }
}

interface FieldCutoverStateRepository {
    suspend fun load(domainId: FieldDomainId): FieldCutoverState?
    suspend fun compareAndSet(
        expectedRevision: Long?,
        state: FieldCutoverState,
    ): Boolean
}

data class FieldCutoverAssessment(
    val report: FieldShadowValidationReport,
    val evidenceFingerprint: String,
    val state: FieldCutoverState,
)

fun interface FieldCutoverEligibilityPolicy {
    fun canBecomeAuthoritative(domainId: FieldDomainId): Boolean

    companion object {
        val DEFAULT: FieldCutoverEligibilityPolicy = FieldCutoverEligibilityPolicy { domainId ->
            domainId != DefaultPhotonFieldRequestFactory.DOMAIN_ID
        }
    }
}

class FieldCutoverAuthority(
    private val ledger: FieldShadowValidationLedger,
    private val states: FieldCutoverStateRepository,
    private val validator: FieldShadowValidator,
    private val eligibility: FieldCutoverEligibilityPolicy = FieldCutoverEligibilityPolicy.DEFAULT,
) {
    suspend fun assess(
        domainId: FieldDomainId,
        at: Instant,
        provenance: String,
    ): FieldCutoverAssessment {
        require(provenance.isNotBlank())
        val evidence = ledger.forDomain(domainId)
        val report = validator.report(domainId, evidence)
        val fingerprint = evidenceFingerprint(domainId, report, evidence)
        val current = states.load(domainId)
        val targetMode = when {
            report.recommendation == FieldCutoverRecommendation.READY_FOR_SELECTED_DOMAIN &&
                eligibility.canBecomeAuthoritative(domainId) -> FieldCutoverMode.ELIGIBLE
            else -> FieldCutoverMode.SHADOW
        }

        val next = when {
            current == null -> FieldCutoverState(
                domainId = domainId,
                generation = 1L,
                revision = 1L,
                mode = targetMode,
                evidenceFingerprint = fingerprint.takeIf { targetMode != FieldCutoverMode.SHADOW },
                replayCaseCount = report.replayCaseCount,
                updatedAt = at,
                provenance = provenance,
            )
            current.mode == FieldCutoverMode.AUTHORITATIVE &&
                targetMode == FieldCutoverMode.ELIGIBLE &&
                current.evidenceFingerprint == fingerprint -> current
            else -> current.copy(
                generation = if (
                    current.mode == FieldCutoverMode.AUTHORITATIVE &&
                    targetMode == FieldCutoverMode.SHADOW
                ) current.generation + 1L else current.generation,
                revision = current.revision + 1L,
                mode = targetMode,
                evidenceFingerprint = fingerprint.takeIf { targetMode != FieldCutoverMode.SHADOW },
                replayCaseCount = report.replayCaseCount,
                updatedAt = at,
                authoritativeSince = null,
                provenance = provenance,
            )
        }

        val persisted = if (current == next) {
            current
        } else {
            check(states.compareAndSet(current?.revision, next)) {
                "Field cutover state changed concurrently for " + domainId.value
            }
            next
        }
        return FieldCutoverAssessment(report, fingerprint, persisted)
    }

    suspend fun activate(
        domainId: FieldDomainId,
        expectedEvidenceFingerprint: String,
        at: Instant,
        provenance: String,
    ): FieldCutoverState {
        require(expectedEvidenceFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(provenance.isNotBlank())
        require(eligibility.canBecomeAuthoritative(domainId)) {
            "Field domain is not permitted to become authoritative"
        }
        val current = requireNotNull(states.load(domainId)) {
            "Field cutover state missing for " + domainId.value
        }
        require(current.mode == FieldCutoverMode.ELIGIBLE) {
            "Field domain is not eligible for authoritative cutover"
        }
        require(current.evidenceFingerprint == expectedEvidenceFingerprint) {
            "Field cutover evidence changed before activation"
        }
        val next = current.copy(
            generation = current.generation + 1L,
            revision = current.revision + 1L,
            mode = FieldCutoverMode.AUTHORITATIVE,
            updatedAt = at,
            authoritativeSince = at,
            provenance = provenance,
        )
        check(states.compareAndSet(current.revision, next)) {
            "Field cutover state changed concurrently before activation"
        }
        return next
    }

    suspend fun state(
        domainId: FieldDomainId,
        at: Instant,
    ): FieldCutoverState = states.load(domainId)
        ?: FieldCutoverState.initial(domainId, at)

    private fun evidenceFingerprint(
        domainId: FieldDomainId,
        report: FieldShadowValidationReport,
        evidence: List<FieldShadowValidationEvidence>,
    ): String = StableFieldIds.fingerprint(
        "field-cutover-evidence/v1",
        domainId.value,
        report.recommendation.name,
        report.replayCaseCount.toString(),
        report.equivalentCaseCount.toString(),
        report.sourceMutationRegressions.toString(),
        report.taskOwnershipRegressions.toString(),
        report.unresolvedCollapsedRegressions.toString(),
        report.semanticDivergences.toString(),
        *report.nondeterministicReplayCases.sorted().map { "nondeterministic:" + it }.toTypedArray(),
        *report.blockerClasses.map { it.name }.sorted().map { "blocker:" + it }.toTypedArray(),
        *evidence
            .filter { it.domainId == domainId }
            .sortedBy { it.id }
            .map { "evidence:" + it.id + ":" + it.outcomeFingerprint() }
            .toTypedArray(),
    )
}
