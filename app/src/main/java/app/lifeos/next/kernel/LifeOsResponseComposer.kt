package app.lifeos.next.kernel

import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.language.LanguageResponseAct
import app.lifeos.core.language.LanguageResponseFact
import app.lifeos.core.language.LanguageResponseGenerationEngine
import app.lifeos.core.language.LanguageResponseTarget
import app.lifeos.core.runtime.goal.LocalKnowledgeGoalKind

object LifeOsResponseComposer {
    private val responseGeneration = LanguageResponseGenerationEngine()

    fun compose(result: LanguageSubmissionResult): String {
        (result.localKnowledge as? LocalKnowledgeExecutionResult.Produced)?.let { produced ->
            return when (produced.kind) {
                LocalKnowledgeGoalKind.QUERY_ANSWER -> generated(
                    result = result,
                    act = LanguageResponseAct.EVIDENCE,
                    statement = produced.output.photon.content,
                    confidence = produced.output.photon.confidence,
                    semanticTags = setOf("EVIDENCE"),
                )
                LocalKnowledgeGoalKind.MEMORY_STORED -> generated(
                    result = result,
                    act = LanguageResponseAct.REPORT_SUCCESS,
                    statement = when (language(result)) {
                        LanguageCode.EN -> "I stored this in the local LIFEOS memory: ${produced.output.photon.content}"
                        else -> "Ich habe das im lokalen LIFEOS-Gedächtnis gespeichert: ${produced.output.photon.content}"
                    },
                    confidence = produced.output.photon.confidence,
                    semanticTags = setOf("MEMORY"),
                )
            }
        }
        (result.localDeepSearch as? LocalDeepSearchExecutionResult.Produced)?.let { produced ->
            return generated(
                result = result,
                act = LanguageResponseAct.EVIDENCE,
                statement = produced.output.photon.content,
                confidence = produced.output.photon.confidence,
                semanticTags = setOf("EVIDENCE", "SEARCH"),
            )
        }
        when (val conversation = result.localConversation) {
            is LocalConversationExecutionResult.Produced -> {
                val hasEvidence = conversation.evidencePhotonIds.isNotEmpty()
                return generated(
                    result = result,
                    act = if (hasEvidence) LanguageResponseAct.EVIDENCE else LanguageResponseAct.ASSERT,
                    statement = conversation.photon.content,
                    confidence = conversation.photon.confidence,
                    semanticTags = if (hasEvidence) {
                        setOf("CONVERSATION", "EVIDENCE")
                    } else {
                        setOf("CONVERSATION")
                    },
                )
            }
            is LocalConversationExecutionResult.Failed ->
                return generated(
                    result,
                    LanguageResponseAct.REPORT_FAILURE,
                    statement(
                        result,
                        "Die Gesprächsantwort konnte nicht dauerhaft abgeschlossen werden: ${conversation.message}",
                        "The conversation response could not be durably completed: ${conversation.message}",
                    ),
                    semanticTags = setOf("CONVERSATION"),
                )
            null -> Unit
        }
        when (val scheduled = result.localSchedule) {
            is LocalScheduleExecutionResult.Scheduled ->
                return generated(
                    result,
                    LanguageResponseAct.REPORT_SUCCESS,
                    statement(result, "Die geplante Aktion wurde lokal erstellt und als LIFEOS-Photon gespeichert.", "The scheduled action was created locally and stored as a LIFEOS photon."),
                    semanticTags = setOf("SCHEDULE"),
                )
            is LocalScheduleExecutionResult.Blocked ->
                return generated(
                    result,
                    LanguageResponseAct.REPORT_BLOCKED,
                    statement(result, "Die geplante Aktion konnte nicht ausgeführt werden: ${scheduled.reason}", "The scheduled action could not be executed: ${scheduled.reason}"),
                    semanticTags = setOf("SCHEDULE"),
                )
            is LocalScheduleExecutionResult.Failed ->
                return generated(
                    result,
                    LanguageResponseAct.REPORT_FAILURE,
                    statement(result, "Die Planung ist fehlgeschlagen: ${scheduled.message}", "Scheduling failed: ${scheduled.message}"),
                    semanticTags = setOf("SCHEDULE"),
                )
            null -> Unit
        }
        when (val transformed = result.localImageTransform) {
            is LocalImageTransformExecutionResult.Transformed -> {
                val fact = if (transformed.ownerReviewCandidateId != null) {
                    statement(
                        result,
                        "Das Bild wurde lokal verarbeitet. Das Ergebnis wartet in Assets auf deine Freigabe und wird erst danach als LIFEOS-Photon veröffentlicht.",
                        "The image was processed locally. The result is waiting in Assets for your approval and will only then be published as a LIFEOS photon.",
                    )
                } else {
                    statement(
                        result,
                        "Das Bild wurde lokal verarbeitet und das Ergebnis wieder als LIFEOS-Photon gespeichert.",
                        "The image was processed locally and the result was stored again as a LIFEOS photon.",
                    )
                }
                return generated(result, LanguageResponseAct.REPORT_SUCCESS, fact, semanticTags = setOf("IMAGE", "TRANSFORM"))
            }
            is LocalImageTransformExecutionResult.Blocked ->
                return generated(
                    result,
                    LanguageResponseAct.REPORT_BLOCKED,
                    statement(result, "Die Bildverarbeitung konnte nicht ausgeführt werden: ${transformed.reason}", "Image processing could not be executed: ${transformed.reason}"),
                    semanticTags = setOf("IMAGE", "TRANSFORM"),
                )
            is LocalImageTransformExecutionResult.Failed ->
                return generated(
                    result,
                    LanguageResponseAct.REPORT_FAILURE,
                    statement(result, "Die Bildverarbeitung ist fehlgeschlagen: ${transformed.message}", "Image processing failed: ${transformed.message}"),
                    semanticTags = setOf("IMAGE", "TRANSFORM"),
                )
            null -> Unit
        }
        when (val communication = result.localCommunication) {
            is LocalCommunicationExecutionResult.Prepared ->
                return generated(
                    result,
                    LanguageResponseAct.REPORT_SUCCESS,
                    statement(result, "Die Freigabe wurde vorbereitet. Die eigentliche Übergabe bleibt unter deiner Kontrolle.", "Sharing was prepared. The actual handoff remains under your control."),
                    semanticTags = setOf("COMMUNICATION"),
                )
            is LocalCommunicationExecutionResult.Blocked ->
                return generated(
                    result,
                    LanguageResponseAct.REPORT_BLOCKED,
                    statement(result, "Die Freigabe konnte nicht vorbereitet werden: ${communication.reason}", "Sharing could not be prepared: ${communication.reason}"),
                    semanticTags = setOf("COMMUNICATION"),
                )
            is LocalCommunicationExecutionResult.Failed ->
                return generated(
                    result,
                    LanguageResponseAct.REPORT_FAILURE,
                    statement(result, "Die Freigabevorbereitung ist fehlgeschlagen: ${communication.message}", "Share preparation failed: ${communication.message}"),
                    semanticTags = setOf("COMMUNICATION"),
                )
            null -> Unit
        }
        when (val resume = result.goalResume) {
            is GoalResumeExecutionResult.Resumed ->
                if (result.localKnowledge == null && result.localDeepSearch == null &&
                    result.localSchedule == null && result.localImageTransform == null &&
                    result.localCommunication == null && result.localConversation == null &&
                    result.imageGeneration == null
                ) {
                    return generated(
                        result,
                        LanguageResponseAct.REPORT_SUCCESS,
                        statement(result, "Das bestehende Ziel wurde wieder aufgenommen und der nächste LIFEOS-Schritt aktiviert.", "The existing goal was resumed and the next LIFEOS step was activated."),
                        semanticTags = setOf("GOAL", "CONTINUE"),
                    )
                }
            is GoalResumeExecutionResult.Blocked ->
                return generated(
                    result,
                    LanguageResponseAct.REPORT_BLOCKED,
                    statement(result, "Das Ziel konnte nicht fortgesetzt werden: ${resume.message}", "The goal could not be continued: ${resume.message}"),
                    semanticTags = setOf("GOAL", "CONTINUE"),
                )
            is GoalResumeExecutionResult.Failed ->
                return generated(
                    result,
                    LanguageResponseAct.REPORT_FAILURE,
                    statement(result, "Das Wiederaufnehmen des Ziels ist fehlgeschlagen: ${resume.message}", "Resuming the goal failed: ${resume.message}"),
                    semanticTags = setOf("GOAL", "CONTINUE"),
                )
            null -> Unit
        }
        when (val image = result.imageGeneration) {
            is ImageGenerationResult.Generated -> {
                val fact = if (image.value.ownerReviewCandidateId != null) {
                    statement(
                        result,
                        "Das Bild wurde lokal erzeugt. Es wartet in Assets auf deine Freigabe und wird erst danach als LIFEOS-Photon veröffentlicht.",
                        "The image was generated locally. It is waiting in Assets for your approval and will only then be published as a LIFEOS photon.",
                    )
                } else {
                    statement(
                        result,
                        "Das Bild wurde lokal erzeugt und als LIFEOS-Photon gespeichert.",
                        "The image was generated locally and stored as a LIFEOS photon.",
                    )
                }
                return generated(result, LanguageResponseAct.REPORT_SUCCESS, fact, semanticTags = setOf("IMAGE", "CREATE"))
            }
            is ImageGenerationResult.Blocked ->
                return generated(
                    result,
                    LanguageResponseAct.REPORT_BLOCKED,
                    statement(result, "Das Bild konnte nicht erzeugt werden: ${image.reasons.joinToString("; ")}", "The image could not be generated: ${image.reasons.joinToString("; ")}"),
                    semanticTags = setOf("IMAGE", "CREATE"),
                )
            is ImageGenerationResult.Failed ->
                return generated(
                    result,
                    LanguageResponseAct.REPORT_FAILURE,
                    statement(result, "Die Bilderzeugung ist fehlgeschlagen: ${image.message}", "Image generation failed: ${image.message}"),
                    semanticTags = setOf("IMAGE", "CREATE"),
                )
            null -> Unit
        }
        result.languageFailure?.let { failure ->
            return generated(
                result,
                LanguageResponseAct.REPORT_FAILURE,
                statement(result, "Deine Nachricht wurde gespeichert, konnte aber nicht vollständig verarbeitet werden: $failure", "Your message was stored but could not be processed completely: $failure"),
                semanticTags = setOf("LANGUAGE"),
            )
        }
        val gaps = result.effectiveRouting?.blockingGaps.orEmpty()
        if (gaps.isNotEmpty()) {
            val missing = gaps
                .map { it.requirement.capabilityId.value }
                .distinct()
                .joinToString(", ")
            return generated(
                result,
                LanguageResponseAct.REPORT_BLOCKED,
                statement(
                    result,
                    "Das Ziel wurde verstanden, aber der produktive Provider für $missing fehlt noch. Genesis wählt den kleinsten sicheren Erweiterungspfad; Handoff und ToolWorkshop-/BuildStudio-/Evolution-Status bleiben im Systemstrom nachvollziehbar.",
                    "The goal was understood, but the productive provider for $missing is still missing. Genesis selects the smallest safe expansion path; handoff and ToolWorkshop/BuildStudio/Evolution status remain traceable in the system stream.",
                ),
                semanticTags = setOf("CAPABILITY", "GENESIS"),
            )
        }
        val goal = result.effectiveGoal
        if (goal?.intent == IntentType.CONVERSATION) {
            // Defensive fallback only. Normal productive conversation has already been planned,
            // persisted and surfaced through LocalConversationExecutionResult above.
            return generated(
                result,
                LanguageResponseAct.ASSERT,
                statement(
                    result,
                    "Der Gesprächsbeitrag wurde verstanden, aber es liegt kein abgeschlossenes Conversation-Outcome vor.",
                    "The conversation input was understood, but no completed conversation outcome is available.",
                ),
                confidence = goal.confidence,
                semanticTags = setOf("CONVERSATION"),
            )
        }
        return if (goal != null) {
            generated(
                result,
                LanguageResponseAct.ASSERT,
                statement(
                    result,
                    "Die Nachricht wurde verarbeitet und als ${goal.intent.name.lowercase()}-Ziel in LIFEOS übernommen.",
                    "The message was processed and adopted as a ${goal.intent.name.lowercase()} goal in LIFEOS.",
                ),
                confidence = goal.confidence,
                semanticTags = setOf("GOAL"),
            )
        } else {
            generated(
                result,
                LanguageResponseAct.ASSERT,
                statement(result, "Die Nachricht wurde verarbeitet und im LIFEOS-Gedächtnis verankert.", "The message was processed and anchored in LIFEOS memory."),
                semanticTags = setOf("MEMORY"),
            )
        }
    }

    private fun generated(
        result: LanguageSubmissionResult,
        act: LanguageResponseAct,
        statement: String,
        confidence: Double = result.effectiveGoal?.confidence ?: 1.0,
        semanticTags: Set<String> = emptySet(),
    ): String = runCatching {
        responseGeneration.generate(
            LanguageResponseTarget(
                act = act,
                language = language(result),
                facts = listOf(
                    LanguageResponseFact(
                        statement = statement,
                        semanticTags = semanticTags,
                        confidence = confidence.coerceIn(0.0, 1.0),
                    )
                ),
            )
        ).text
    }.getOrElse { statement }

    private fun statement(result: LanguageSubmissionResult, de: String, en: String): String =
        if (language(result) == LanguageCode.EN) en else de

    private fun language(result: LanguageSubmissionResult): LanguageCode =
        result.effectiveGoal?.language
            ?: result.understanding?.goal?.language
            ?: LanguageCode.DE
}
