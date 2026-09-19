package app.lifeos.core.runtime.informationasset.project

import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.InformationAssetRevision
import app.lifeos.core.runtime.informationasset.InformationAssetValidationCode
import app.lifeos.core.runtime.informationasset.InformationAssetValidationProfile
import app.lifeos.core.runtime.informationasset.InformationAssetValidationSeverity
import app.lifeos.core.runtime.informationasset.InformationAssetValidationViolation
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.StandardInformationDomains
import java.time.Instant

enum class ProjectStatus {
    PLANNED,
    ACTIVE,
    BLOCKED,
    PAUSED,
    COMPLETED,
    CANCELLED,
}

data class ProjectMilestone(
    val key: String,
    val title: String,
    val dueAt: Instant? = null,
    val completedAt: Instant? = null,
) {
    init {
        require(key.isNotBlank()) { "Project milestone key must not be blank" }
        require(title.isNotBlank()) { "Project milestone title must not be blank" }
        require(completedAt == null || dueAt == null || completedAt >= Instant.EPOCH) {
            "Project milestone completion time must be valid"
        }
    }
}

data class ProjectRisk(
    val key: String,
    val description: String,
    val probability: Double,
    val impact: Double,
    val mitigation: String,
) {
    init {
        require(key.isNotBlank()) { "Project risk key must not be blank" }
        require(description.isNotBlank()) { "Project risk description must not be blank" }
        require(probability.isFinite() && probability in 0.0..1.0) {
            "Project risk probability must be normalized"
        }
        require(impact.isFinite() && impact in 0.0..1.0) {
            "Project risk impact must be normalized"
        }
        require(mitigation.isNotBlank()) { "Project risk mitigation must not be blank" }
    }
}

object ProjectSemanticKeys {
    const val OBJECTIVE = "project.objective"
    const val SCOPE = "project.scope"
    const val STATUS = "project.status"
    const val MILESTONE = "project.milestone"
    const val RISK = "project.risk"
    const val NEXT_ACTION = "project.next-action"
    const val DECISION = "project.decision"
    const val REQUIREMENT = "project.requirement"
    const val DOCUMENT = "project.document"
    const val CONVERSATION = "project.conversation"
    const val REPOSITORY = "project.repository"
    const val ISSUE = "project.issue"
    const val PARTICIPANT = "project.participant"
    const val EVENT = "project.event"
    const val ARTIFACT = "project.artifact"
    const val OUTPUT = "project.output"
    const val BLOCKER = "project.blocker"

    val CORE: Set<String> = linkedSetOf(
        OBJECTIVE,
        SCOPE,
        STATUS,
        MILESTONE,
        RISK,
        NEXT_ACTION,
    )
}

object ProjectInformationAssetFactory {
    fun request(
        namespace: String,
        stableKey: String,
        title: String,
    ): InformationAssetRequest = InformationAssetRequest.create(
        namespace = namespace,
        stableKey = stableKey,
        kind = InformationAssetKind.PROJECT,
        title = title,
        primaryDomainId = StandardInformationDomains.PROJECT,
        requiredSemanticKeys = ProjectSemanticKeys.CORE,
    )
}

class ProjectInformationAssetValidationProfile : InformationAssetValidationProfile {
    override val id: String = "lifeos.project.v1"

    override fun validate(
        revision: InformationAssetRevision,
        evaluatedAt: Instant,
    ): List<InformationAssetValidationViolation> {
        if (revision.request.kind != InformationAssetKind.PROJECT) return emptyList()

        val evidenceById = revision.evidenceBindings.associateBy { it.id }
        return revision.claims
            .filter {
                it.semanticKey in EVIDENCE_CRITICAL_KEYS &&
                    it.state != InformationClaimState.ASSUMPTION &&
                    it.state != InformationClaimState.REJECTED
            }
            .flatMap { claim ->
                val projectEvidence = claim.evidenceBindingIds
                    .mapNotNull(evidenceById::get)
                    .filter { it.domainId == StandardInformationDomains.PROJECT }
                buildList {
                    if (projectEvidence.isEmpty()) {
                        add(requirement(
                            path = "claims[${claim.id.value}].evidence",
                            message = "Project status, milestone, or next-action claim requires project-domain evidence",
                        ))
                    } else if (projectEvidence.none { it.authority in ACCEPTED_AUTHORITIES }) {
                        add(requirement(
                            path = "claims[${claim.id.value}].authority",
                            message = "Project operational claim cannot rely only on UNVERIFIED evidence",
                        ))
                    }
                }
            }
    }

    private fun requirement(
        path: String,
        message: String,
    ): InformationAssetValidationViolation = InformationAssetValidationViolation(
        code = InformationAssetValidationCode.DOMAIN_PROFILE_REQUIREMENT,
        severity = InformationAssetValidationSeverity.ERROR,
        path = path,
        message = message,
    )

    companion object {
        val EVIDENCE_CRITICAL_KEYS: Set<String> = setOf(
            ProjectSemanticKeys.STATUS,
            ProjectSemanticKeys.MILESTONE,
            ProjectSemanticKeys.NEXT_ACTION,
            ProjectSemanticKeys.BLOCKER,
        )

        val ACCEPTED_AUTHORITIES: Set<SourceAuthority> = setOf(
            SourceAuthority.USER_PROVIDED,
            SourceAuthority.DOCUMENTED,
            SourceAuthority.PRIMARY_SOURCE,
            SourceAuthority.OFFICIAL,
            SourceAuthority.AUTHORITATIVE,
        )
    }
}
