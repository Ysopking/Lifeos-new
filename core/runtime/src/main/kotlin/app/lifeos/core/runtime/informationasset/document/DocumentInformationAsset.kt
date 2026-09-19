package app.lifeos.core.runtime.informationasset.document

import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.StandardInformationDomains

object DocumentSemanticKeys {
    const val TITLE = "document.title"
    const val IDENTITY = "document.identity"
    const val VERSION = "document.version"
    const val SUBJECT = "document.subject"
    const val AUTHOR = "document.author"
    const val SECTION = "document.section"
    const val CLAIM = "document.claim"
    const val REFERENCE = "document.reference"
    const val PROJECT_REFERENCE = "document.project-reference"

    val CORE: Set<String> = linkedSetOf(
        TITLE,
        IDENTITY,
        VERSION,
    )

    val ALL: Set<String> = linkedSetOf(
        TITLE,
        IDENTITY,
        VERSION,
        SUBJECT,
        AUTHOR,
        SECTION,
        CLAIM,
        REFERENCE,
        PROJECT_REFERENCE,
    )
}

object DocumentInformationAssetFactory {
    fun request(
        namespace: String,
        stableKey: String,
        title: String,
    ): InformationAssetRequest = InformationAssetRequest.create(
        namespace = namespace,
        stableKey = stableKey,
        kind = InformationAssetKind.DOCUMENT,
        title = title,
        primaryDomainId = StandardInformationDomains.DOCUMENT,
        requiredSemanticKeys = DocumentSemanticKeys.CORE,
    )
}
