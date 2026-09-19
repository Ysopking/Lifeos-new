package app.lifeos.core.runtime.informationasset.conversation

import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.StandardInformationDomains

object ConversationSemanticKeys {
    const val TOPIC = "conversation.topic"
    const val PARTICIPANTS = "conversation.participants"
    const val DECISION = "conversation.decision"
    const val QUESTION = "conversation.question"
    const val ANSWER = "conversation.answer"
    const val ACTION = "conversation.action"
    const val ATTACHMENT = "conversation.attachment"
    const val PROJECT_REFERENCE = "conversation.project-reference"

    val CORE: Set<String> = linkedSetOf(
        TOPIC,
        PARTICIPANTS,
    )

    val ALL: Set<String> = linkedSetOf(
        TOPIC,
        PARTICIPANTS,
        DECISION,
        QUESTION,
        ANSWER,
        ACTION,
        ATTACHMENT,
        PROJECT_REFERENCE,
    )
}

object ConversationInformationAssetFactory {
    fun request(
        namespace: String,
        stableKey: String,
        title: String,
    ): InformationAssetRequest = InformationAssetRequest.create(
        namespace = namespace,
        stableKey = stableKey,
        kind = InformationAssetKind.CONVERSATION,
        title = title,
        primaryDomainId = StandardInformationDomains.COMMUNICATION,
        requiredSemanticKeys = ConversationSemanticKeys.CORE,
    )
}
