package app.lifeos.core.runtime.informationasset

import app.lifeos.core.field.FieldContext
import app.lifeos.core.field.FieldContextScope
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds

data class DomainContextPolicy(
    val domainId: FieldDomainId,
    val allowedDirectScopes: Set<FieldContextScope>,
    val allowGlobalMemory: Boolean = false,
) {
    init {
        require(allowedDirectScopes.isNotEmpty()) { "Domain context policy requires direct scopes" }
        require(allowGlobalMemory || FieldContextScope.GLOBAL_MEMORY !in allowedDirectScopes) {
            "GLOBAL_MEMORY requires explicit policy opt-in"
        }
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "domain-context-policy/v1",
        domainId.value,
        allowGlobalMemory.toString(),
        *allowedDirectScopes.map { it.name }.sorted().toTypedArray(),
    )

    fun validate(context: FieldContext): List<DomainContextViolation> {
        val violations = mutableListOf<DomainContextViolation>()
        if (context.domain.domainId != domainId) {
            violations += DomainContextViolation.DOMAIN_MISMATCH
        }
        if (context.activeScopes.any { it !in allowedDirectScopes }) {
            violations += DomainContextViolation.FORBIDDEN_ACTIVE_SCOPE
        }
        if (!allowGlobalMemory && (
                FieldContextScope.GLOBAL_MEMORY in context.activeScopes ||
                    context.photonReferences.any { FieldContextScope.GLOBAL_MEMORY in it.scopes }
                )
        ) {
            violations += DomainContextViolation.GLOBAL_MEMORY_REQUIRES_EXPLICIT_BRIDGE
        }
        if (context.photonReferences.any { reference ->
                reference.scopes.any { it !in allowedDirectScopes }
            }
        ) {
            violations += DomainContextViolation.FORBIDDEN_REFERENCE_SCOPE
        }
        return violations.distinct()
    }

    fun sanitizeReferences(context: FieldContext): FieldContext {
        require(context.domain.domainId == domainId) { "Cannot sanitize a different domain" }
        require(context.activeScopes.all(allowedDirectScopes::contains)) {
            "Active context contains scopes forbidden by domain policy"
        }
        return context.copy(
            photonReferences = context.photonReferences.filter { reference ->
                reference.scopes.isNotEmpty() && reference.scopes.all(allowedDirectScopes::contains)
            },
        )
    }
}

enum class DomainContextViolation {
    DOMAIN_MISMATCH,
    FORBIDDEN_ACTIVE_SCOPE,
    FORBIDDEN_REFERENCE_SCOPE,
    GLOBAL_MEMORY_REQUIRES_EXPLICIT_BRIDGE,
}

object StandardDomainContextPolicies {
    private val common = setOf(
        FieldContextScope.CURRENT_TASK,
        FieldContextScope.CURRENT_CONVERSATION,
        FieldContextScope.CURRENT_PROJECT,
        FieldContextScope.CURRENT_GOAL,
        FieldContextScope.DOCUMENT_CONTEXT,
    )

    val GENERAL = DomainContextPolicy(
        domainId = StandardInformationDomains.GENERAL,
        allowedDirectScopes = common,
    )

    val ORGANIZATION = DomainContextPolicy(
        domainId = StandardInformationDomains.ORGANIZATION,
        allowedDirectScopes = common + FieldContextScope.ORGANIZATION_CONTEXT,
    )

    val LEGAL = DomainContextPolicy(
        domainId = StandardInformationDomains.LEGAL,
        allowedDirectScopes = common + FieldContextScope.LEGAL_CONTEXT,
    )

    val FINANCE = DomainContextPolicy(
        domainId = StandardInformationDomains.FINANCE,
        allowedDirectScopes = common + FieldContextScope.FINANCIAL_CONTEXT,
    )

    val SCIENCE = DomainContextPolicy(
        domainId = StandardInformationDomains.SCIENCE,
        allowedDirectScopes = common + FieldContextScope.SCIENTIFIC_CONTEXT,
    )

    val CODE = DomainContextPolicy(
        domainId = StandardInformationDomains.CODE,
        allowedDirectScopes = common,
    )

    val PROJECT = DomainContextPolicy(
        domainId = StandardInformationDomains.PROJECT,
        allowedDirectScopes = common,
    )

    val ALL: Map<FieldDomainId, DomainContextPolicy> = listOf(
        GENERAL,
        ORGANIZATION,
        LEGAL,
        FINANCE,
        SCIENCE,
        CODE,
        PROJECT,
    ).associateBy { it.domainId }

    fun forDomain(domainId: FieldDomainId): DomainContextPolicy? = ALL[domainId]

    fun requireFor(domainId: FieldDomainId): DomainContextPolicy =
        requireNotNull(forDomain(domainId)) { "No standard context policy for ${domainId.value}" }
}
