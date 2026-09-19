package app.lifeos.core.runtime.informationasset

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds

object StandardInformationDomains {
    val GENERAL: FieldDomainId = StableFieldIds.domain("information.general")
    val ORGANIZATION: FieldDomainId = StableFieldIds.domain("information.organization")
    val LEGAL: FieldDomainId = StableFieldIds.domain("information.legal")
    val FINANCE: FieldDomainId = StableFieldIds.domain("information.finance")
    val SCIENCE: FieldDomainId = StableFieldIds.domain("information.science")
    val CODE: FieldDomainId = StableFieldIds.domain("information.code")
    val PROJECT: FieldDomainId = StableFieldIds.domain("information.project")
    val COMMUNICATION: FieldDomainId = StableFieldIds.domain("information.communication")
    val DOCUMENT: FieldDomainId = StableFieldIds.domain("information.document")
    val EVENT: FieldDomainId = StableFieldIds.domain("information.event")

    val ALL: Set<FieldDomainId> = linkedSetOf(
        GENERAL,
        ORGANIZATION,
        LEGAL,
        FINANCE,
        SCIENCE,
        CODE,
        PROJECT,
        COMMUNICATION,
        DOCUMENT,
        EVENT,
    )

    fun isStandard(domainId: FieldDomainId): Boolean = domainId in ALL

    fun byKey(key: String): FieldDomainId? = when (key.trim().lowercase()) {
        "general" -> GENERAL
        "organization", "organisation", "company" -> ORGANIZATION
        "legal", "law" -> LEGAL
        "finance", "financial" -> FINANCE
        "science", "scientific", "research" -> SCIENCE
        "code", "coding" -> CODE
        "project" -> PROJECT
        "communication", "conversation", "message", "email" -> COMMUNICATION
        "document", "file" -> DOCUMENT
        "event", "calendar" -> EVENT
        else -> null
    }
}
