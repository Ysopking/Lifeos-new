package app.lifeos.core.runtime.informationasset

import app.lifeos.core.runtime.informationasset.conversation.ConversationInformationAssetFactory
import app.lifeos.core.runtime.informationasset.conversation.ConversationSemanticKeys
import app.lifeos.core.runtime.informationasset.document.DocumentInformationAssetFactory
import app.lifeos.core.runtime.informationasset.document.DocumentSemanticKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationDocumentInformationAssetTest {
    @Test
    fun conversationFactoryUsesCommunicationDomainAndMinimalCoreKeys() {
        val request = ConversationInformationAssetFactory.request(
            namespace = "conversation",
            stableKey = "thread-42",
            title = "Thread 42",
        )

        assertEquals(InformationAssetKind.CONVERSATION, request.kind)
        assertEquals(StandardInformationDomains.COMMUNICATION, request.primaryDomainId)
        assertEquals(ConversationSemanticKeys.CORE, request.requiredSemanticKeys)
        assertTrue(ConversationSemanticKeys.DECISION !in request.requiredSemanticKeys)
    }

    @Test
    fun documentFactoryRequiresIdentityAndVersionWithoutInventingProjectMembership() {
        val request = DocumentInformationAssetFactory.request(
            namespace = "document",
            stableKey = "doc-42",
            title = "Document 42",
        )

        assertEquals(InformationAssetKind.DOCUMENT, request.kind)
        assertEquals(StandardInformationDomains.DOCUMENT, request.primaryDomainId)
        assertEquals(DocumentSemanticKeys.CORE, request.requiredSemanticKeys)
        assertTrue(DocumentSemanticKeys.PROJECT_REFERENCE !in request.requiredSemanticKeys)
    }

    @Test
    fun standardDomainsResolveCommunicationDocumentAndEventAliases() {
        assertEquals(StandardInformationDomains.COMMUNICATION, StandardInformationDomains.byKey("conversation"))
        assertEquals(StandardInformationDomains.DOCUMENT, StandardInformationDomains.byKey("file"))
        assertEquals(StandardInformationDomains.EVENT, StandardInformationDomains.byKey("calendar"))
        assertTrue(StandardInformationDomains.ALL.containsAll(
            setOf(
                StandardInformationDomains.COMMUNICATION,
                StandardInformationDomains.DOCUMENT,
                StandardInformationDomains.EVENT,
            )
        ))
    }
}
