package app.lifeos.next

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.runtime.life.PerceptionModality
import app.lifeos.next.kernel.GoalResumeExecutionResult
import app.lifeos.next.kernel.ImageGenerationResult
import app.lifeos.next.kernel.LifeOsKernel
import app.lifeos.next.kernel.LocalCommunicationExecutionResult
import app.lifeos.next.kernel.LocalDeepSearchExecutionResult
import app.lifeos.next.kernel.LocalImageTransformExecutionResult
import app.lifeos.next.kernel.LocalKnowledgeExecutionResult
import app.lifeos.next.kernel.LocalScheduleExecutionResult
import app.lifeos.next.kernel.MultimodalPerceptionRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ChatSubmissionController(
    private val kernel: LifeOsKernel,
    private val multimodalPerception: MultimodalPerceptionRuntime,
    private val state: MutableStateFlow<LifeOsState>,
    private val scope: CoroutineScope,
) {
    fun submitDraft() {
        val current = state.value
        if (
            current.loading ||
            current.loadFailed ||
            current.saving ||
            current.voicePhase != VoiceCapturePhase.IDLE ||
            current.draft.isBlank()
        ) return

        val submittedContent = current.draft.trim()
        val voiceRecognitions = current.pendingVoiceRecognitions
        val exactVoiceRecognition =
            voiceRecognitions.singleOrNull()?.takeIf { recognition ->
                "recognition:resolved" in recognition.tags &&
                    recognition.content.trim() == submittedContent
            }
        val recognitionParentIds = voiceRecognitions.map { it.id }.toSet()
        val photon = exactVoiceRecognition?.let { null } ?: Photon(
            content = submittedContent,
            provenance = Provenance(
                source = if (recognitionParentIds.isEmpty()) {
                    "local-chat"
                } else {
                    "local-chat:edited-multimodal"
                },
                actor = "user",
                parentIds = recognitionParentIds,
            ),
            relations = recognitionParentIds.map { parentId ->
                PhotonRelation(parentId, RelationType.DERIVED_FROM)
            }.toSet(),
            tags = buildSet {
                add("chat")
                if (recognitionParentIds.isNotEmpty()) {
                    add("input:speech-edited")
                    add("perception-derived-utterance")
                }
            },
        )
        state.update { it.copy(saving = true, error = null) }

        scope.launch {
            try {
                val result = if (exactVoiceRecognition != null) {
                    multimodalPerception.routeRecognition(
                        recognition = exactVoiceRecognition,
                        modality = PerceptionModality.SPEECH,
                    )
                } else {
                    kernel.persistUserUtterance(requireNotNull(photon))
                }
                val retainDraft =
                    result.localSchedule is LocalScheduleExecutionResult.Blocked ||
                        result.localImageTransform is
                        LocalImageTransformExecutionResult.Blocked
                state.update {
                    it.copy(
                        draft = if (retainDraft) submittedContent else "",
                        pendingVoiceRecognitions =
                            if (retainDraft) {
                                voiceRecognitions
                            } else {
                                emptyList()
                            },
                        lastGoal = result.effectiveGoal ?: it.lastGoal,
                        lastCapabilityGaps =
                            result.effectiveRouting?.blockingGaps.orEmpty(),
                        capabilityRequestStatus = null,
                        capabilityActivationStatus = null,
                        pendingShare =
                            (
                                result.localCommunication as?
                                    LocalCommunicationExecutionResult.Prepared
                                )?.share,
                        shareStatus = null,
                        error = errorMessage(result),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.update {
                    it.copy(
                        error =
                            "Gedanke konnte nicht gespeichert werden. Die Eingabe bleibt im Textfeld."
                    )
                }
            } finally {
                state.update { it.copy(saving = false) }
            }
        }
    }

    private fun errorMessage(
        result: app.lifeos.next.kernel.LanguageSubmissionResult,
    ): String? = when {
        result.languageFailure != null ->
            "Gedanke wurde gespeichert, aber das lokale Sprachverständnis ist fehlgeschlagen."

        !result.source.processingQueued ->
            "Gedanke wurde gespeichert, konnte aber nicht zur Verarbeitung eingereiht werden."

        result.goal?.processingQueued != true ->
            "Gedanke wurde verstanden und gespeichert, das abgeleitete Ziel konnte aber nicht zur Verarbeitung eingereiht werden."

        result.goalResume is GoalResumeExecutionResult.Blocked ->
            "Das vorherige Ziel konnte nicht sicher fortgesetzt werden: ${result.goalResume.message}"

        result.goalResume is GoalResumeExecutionResult.Failed ->
            "Das vorherige Ziel konnte nicht fortgesetzt werden: ${result.goalResume.message}"

        result.goalResume is GoalResumeExecutionResult.Resumed &&
            !result.goalResume.resumedGoal.processingQueued ->
            "Das vorherige Ziel wurde wiederaufgenommen und gespeichert, konnte aber nicht dauerhaft zur Verarbeitung eingereiht werden."

        result.localKnowledge is LocalKnowledgeExecutionResult.Failed ->
            "Die lokale Wissensaktion ist fehlgeschlagen: ${result.localKnowledge.message}"

        result.localKnowledge is LocalKnowledgeExecutionResult.Produced &&
            !result.localKnowledge.output.processingQueued ->
            "Die lokale Wissensantwort wurde gespeichert, konnte aber nicht dauerhaft zur Verarbeitung eingereiht werden."

        result.localDeepSearch is LocalDeepSearchExecutionResult.Failed ->
            "Die lokale DeepSearch-Suche ist fehlgeschlagen: ${result.localDeepSearch.message}"

        result.localDeepSearch is LocalDeepSearchExecutionResult.Produced &&
            !result.localDeepSearch.output.processingQueued ->
            "Das lokale DeepSearch-Ergebnis wurde gespeichert, konnte aber nicht dauerhaft zur Verarbeitung eingereiht werden."

        result.localSchedule is LocalScheduleExecutionResult.Blocked ->
            when (result.localSchedule.reason) {
                "notification-permission-required" ->
                    "Für lokale Erinnerungen muss die Benachrichtigungsberechtigung erteilt werden."

                "reminder-time-missing" ->
                    "Für die Erinnerung fehlt eine Uhrzeit. Der Entwurf bleibt zum Ergänzen erhalten."

                else ->
                    "Die lokale Erinnerung konnte nicht geplant werden: ${result.localSchedule.reason}"
            }

        result.localSchedule is LocalScheduleExecutionResult.Failed ->
            "Die lokale Erinnerung ist fehlgeschlagen: ${result.localSchedule.message}"

        result.localSchedule is LocalScheduleExecutionResult.Scheduled &&
            !result.localSchedule.output.processingQueued ->
            "Die Erinnerung wurde lokal geplant, ihr Photon konnte aber nicht dauerhaft zur Verarbeitung eingereiht werden."

        result.localImageTransform is
            LocalImageTransformExecutionResult.Blocked ->
            when (result.localImageTransform.reason) {
                "image-transform-operation-unsupported" ->
                    "Unterstützte lokale Bildänderungen sind heller, dunkler, wärmer, kühler, schärfer oder Graustufen."

                "image-transform-reference-unresolved",
                "goal-is-not-action-ready" ->
                    "Welches lokale Bild bearbeitet werden soll, ist nicht eindeutig. Der Entwurf bleibt zum Ergänzen erhalten."

                "image-transform-source-missing" ->
                    "Es ist kein lokales Bild zum Bearbeiten vorhanden."

                "image-transform-pixel-budget-exceeded" ->
                    "Das Bild ist für die lokale Bearbeitung zu groß."

                else ->
                    "Die lokale Bildbearbeitung ist blockiert: ${result.localImageTransform.reason}"
            }

        result.localImageTransform is
            LocalImageTransformExecutionResult.Failed ->
            "Die lokale Bildbearbeitung ist fehlgeschlagen: ${result.localImageTransform.message}"

        result.localImageTransform is
            LocalImageTransformExecutionResult.Transformed &&
            result.localImageTransform.ownerReviewCandidateId == null &&
            !result.localImageTransform.output.processingQueued ->
            "Das bearbeitete Bild wurde lokal gespeichert, konnte aber nicht dauerhaft zur Verarbeitung eingereiht werden."

        result.localCommunication is LocalCommunicationExecutionResult.Blocked ->
            "Es gibt kein eindeutig teilbares lokales Ergebnis."

        result.localCommunication is LocalCommunicationExecutionResult.Failed ->
            "Das lokale Teilen konnte nicht vorbereitet werden: ${result.localCommunication.message}"

        result.imageGeneration is ImageGenerationResult.Blocked ->
            "Das Bildziel wurde verstanden, kann mit den lokalen Fähigkeiten aber noch nicht vollständig ausgeführt werden."

        result.imageGeneration is ImageGenerationResult.Failed ->
            "Das Bild konnte lokal nicht erzeugt werden: ${result.imageGeneration.message}"

        else -> null
    }
}
